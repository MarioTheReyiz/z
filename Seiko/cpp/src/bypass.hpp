// kullanım 
//             AvamHook::Init();
//             void* loadedClassesDoTarget = (void*)((uintptr_t)hJvm + 0x61590);
//             AvamHook::Hook(loadedClassesDoTarget);

#pragma once
#include <Windows.h>
#include <vector>
#include <iostream>
#include <mutex>
#include <queue>
#include <condition_variable>
#include <thread>
#include <atomic>
#include <cstring>

struct PageGuardEntry {
    void* targetPage;
    void* targetAddress;
    DWORD originalProtection;
    bool isActive;
};

typedef void(__fastcall* DoKlass_t)(void* closure, void* klass);
inline std::atomic<DoKlass_t> g_origDoKlass{ nullptr };
inline void* g_fakeVtable[10] = { nullptr };
inline std::atomic_bool g_vtableReady{ false };
inline std::atomic_int g_testLimitCounter{ 0 };
inline std::atomic_int g_filteredCount{ 0 };
inline std::atomic_int g_passedCount{ 0 };
inline thread_local bool g_allowFullLoadedClasses = false;

void __fastcall FakeDoKlass(void* closure, void* klass);

class ScopedFullLoadedClasses {
public:
    ScopedFullLoadedClasses()
        : previous_(g_allowFullLoadedClasses) {
        g_allowFullLoadedClasses = true;
    }

    ~ScopedFullLoadedClasses() {
        g_allowFullLoadedClasses = previous_;
    }

    ScopedFullLoadedClasses(const ScopedFullLoadedClasses&) = delete;
    ScopedFullLoadedClasses& operator=(const ScopedFullLoadedClasses&) = delete;

private:
    bool previous_;
};

class AvamHook {
private:
    static inline PVOID vehHandle = nullptr;
    static inline std::vector<PageGuardEntry> hooks;
    static inline std::mutex hookMutex;
    static inline thread_local bool inHandler = false;

    // Noise re-arm worker
    static inline std::queue<void*> rearmQueue;
    static inline std::mutex rearmMutex;
    static inline std::condition_variable rearmCV;
    static inline std::thread rearmThread;
    static inline std::atomic_bool shouldExit{ false };

    static void EmulateInsn(CONTEXT* ctx) {
        if (!ctx) return;
        // 0x61590: mov [rsp+0x18], rbp  (48 89 6C 24 18 — 5 byte)
        __try {
            *(uintptr_t*)(ctx->Rsp + 0x18) = ctx->Rbp;
        } __except(1) {}
        ctx->Rip += 5;
    }

    static void SafeCopyVtable(void** dest, void** src, int count) {
        if (!dest || !src || count <= 0) return;
        __try {
            for (int i = 0; i < count; i++) {
                dest[i] = src[i];
            }
        } __except(1) {}
    }

    static bool SafeReadPointer(void* address, void** out) {
        if (!address || !out) return false;

        __try {
            *out = *(void**)address;
            return true;
        } __except (1) {}

        *out = nullptr;
        return false;
    }

    static bool SafeWritePointer(void* address, void* value) {
        if (!address) return false;

        __try {
            *(void**)address = value;
            return true;
        } __except (1) {}

        return false;
    }

    static DWORD GuardedProtection(DWORD protection) {
        DWORD baseProtection = protection & ~PAGE_GUARD;
        return (baseProtection ? baseProtection : PAGE_EXECUTE_READ) | PAGE_GUARD;
    }

    static DWORD QueryPageProtection(void* page) {
        MEMORY_BASIC_INFORMATION mbi = {};
        if (page && VirtualQuery(page, &mbi, sizeof(mbi)) == sizeof(mbi)) {
            return mbi.Protect & ~PAGE_GUARD;
        }
        return PAGE_EXECUTE_READ;
    }

    static bool WhitelistCFG(void* funcPtr) {
        void* pageBase = (void*)((uintptr_t)funcPtr & ~0xFFF);
        uintptr_t offsetInPage = (uintptr_t)funcPtr & 0xFFF;

        typedef BOOL(WINAPI* pSetProcessValidCallTargets)(HANDLE, PVOID, SIZE_T, ULONG, PCFG_CALL_TARGET_INFO);
        pSetProcessValidCallTargets fnSet = (pSetProcessValidCallTargets)GetProcAddress(
            GetModuleHandleA("kernelbase.dll"), "SetProcessValidCallTargets");

        if (!fnSet) {
            std::cout << "[AvamHook]  whitelist: SetProcessValidCallTargets not found" << std::endl;
            return false;
        }

        CFG_CALL_TARGET_INFO cfgInfo;
        cfgInfo.Offset = offsetInPage & ~0xF;
        cfgInfo.Flags = CFG_CALL_TARGET_VALID;

        BOOL ok = fnSet(GetCurrentProcess(), pageBase, 0x1000, 1, &cfgInfo);
        std::cout << "[AvamHook]  whitelist: " << (ok ? "OK" : "FAIL") << std::endl;
        return ok == TRUE;
    }

    static void RearmWorker() {
        while (!shouldExit.load(std::memory_order_acquire)) {
            void* pageToRearm = nullptr;
            {
                std::unique_lock<std::mutex> lock(rearmMutex);
                rearmCV.wait(lock, [] {
                    return !rearmQueue.empty() || shouldExit.load(std::memory_order_acquire);
                });
                if (shouldExit.load(std::memory_order_acquire)) break;
                pageToRearm = rearmQueue.front();
                rearmQueue.pop();
            }

            if (pageToRearm) {
                Sleep(1);

                DWORD protection = PAGE_EXECUTE_READ;
                {
                    std::lock_guard<std::mutex> lock(hookMutex);
                    for (auto& h : hooks) {
                        if (h.targetPage == pageToRearm) {
                            protection = h.originalProtection;
                            break;
                        }
                    }
                }

                DWORD oldProtect;
                if (VirtualProtect(pageToRearm, 0x1000, GuardedProtection(protection), &oldProtect)) {
                    std::lock_guard<std::mutex> lock(hookMutex);
                    for (auto& h : hooks) {
                        if (h.targetPage == pageToRearm) {
                            h.isActive = true;
                            break;
                        }
                    }
                } else {
                    std::cout << "[AvamHook] Guard REARM FAILED page=0x" << std::hex
                              << (uintptr_t)pageToRearm << std::dec
                              << " err=" << GetLastError() << std::endl;
                }
            }
        }
    }

    static LONG WINAPI VehHandler(PEXCEPTION_POINTERS p) {
        if (!p || !p->ExceptionRecord || !p->ContextRecord)
            return EXCEPTION_CONTINUE_SEARCH;

        if (p->ExceptionRecord->ExceptionCode != EXCEPTION_GUARD_PAGE)
            return EXCEPTION_CONTINUE_SEARCH;

        if (inHandler)
            return EXCEPTION_CONTINUE_SEARCH;

        void* faultAddress = (void*)p->ExceptionRecord->ExceptionInformation[1];

        // Önce sayfanın bizim hook'umuz olup olmadığını kontrol et
        {
            std::lock_guard<std::mutex> lock(hookMutex);
            bool isOurPage = false;
            for (auto& h : hooks) {
                if ((uintptr_t)faultAddress >= (uintptr_t)h.targetPage &&
                    (uintptr_t)faultAddress < (uintptr_t)h.targetPage + 0x1000) {
                    isOurPage = true;
                    break;
                }
            }
            if (!isOurPage) return EXCEPTION_CONTINUE_SEARCH;
        }

        inHandler = true;

        {
            std::lock_guard<std::mutex> lock(hookMutex);
            for (auto& h : hooks) {
                if ((uintptr_t)faultAddress >= (uintptr_t)h.targetPage &&
                    (uintptr_t)faultAddress < (uintptr_t)h.targetPage + 0x1000) {

                    if (faultAddress == h.targetAddress) {
                        // --- HEDEF ADRES: Instruction Emulation ---
                        // PAGE_GUARD asla kaldırılmıyor → race condition sıfır
                        CONTEXT* ctx      = p->ContextRecord;
                        uintptr_t rcx     = ctx->Rcx;

                        // retRVA kontrolü yok — sub_70401590 tek bir GetLoadedClasses
                        // wrapper'ından çağrılıyor, tüm JVMTI instance'ları buraya giriyor
                        if (rcx > 0x10000) {
                                void** closurePtr = (void**)rcx;
                                void* rawVtable = nullptr;
                                void** origVtable = SafeReadPointer(closurePtr, &rawVtable) ? (void**)rawVtable : nullptr;

                                if (origVtable && !g_vtableReady.load(std::memory_order_acquire)) {
                                    SafeCopyVtable(g_fakeVtable, origVtable, 10);
                                    DoKlass_t copiedOrig = (DoKlass_t)g_fakeVtable[0];
                                    if (copiedOrig) {
                                        g_origDoKlass.store(copiedOrig, std::memory_order_release);
                                        g_fakeVtable[0] = (void*)&FakeDoKlass;
                                        WhitelistCFG((void*)&FakeDoKlass);
                                        g_vtableReady.store(true, std::memory_order_release);
                                        std::cout << "[AvamHook] vtable swap hazir origDoKlass=0x"
                                                  << std::hex << (uintptr_t)copiedOrig
                                                  << std::dec << std::endl;
                                    } else {
                                        std::cout << "[AvamHook] vtable copy failed: origDoKlass=null" << std::endl;
                                    }
                                }

                                static std::atomic_int triggerCount{ 0 };
                                int currentTrigger = triggerCount.fetch_add(1, std::memory_order_relaxed) + 1;
                                int previousFiltered = g_filteredCount.exchange(0, std::memory_order_acq_rel);
                                int previousPassed = g_passedCount.exchange(0, std::memory_order_acq_rel);

                                // Önceki çağrının özeti (sayaçlar dolu ise)
                                if (currentTrigger > 1 && (previousFiltered > 0 || previousPassed > 0)) {
                                }

                                std::cout << "[AvamHook] GetLoadedClasses cagirildi (#"
                                          << currentTrigger << ") — filtreleme basliyor..." << std::endl;
                                if (g_vtableReady.load(std::memory_order_acquire)) {
                                    if (!SafeWritePointer(closurePtr, g_fakeVtable)) {
                                        std::cout << "[AvamHook] closure vtable write failed" << std::endl;
                                    }
                                }
                        }

                        // Instruction emulation — ayrı fonksiyonda (C2712 fix)
                        EmulateInsn(ctx);

                        // Emülasyon bitti, sayfayı hemen yeniden guard'la
                        // (kernel guard exception'da korumayı otomatik kaldırır)
                        DWORD oldProtect;
                        if (VirtualProtect(h.targetPage, 0x1000, GuardedProtection(h.originalProtection), &oldProtect)) {
                            h.isActive = true;
                        } else {
                            std::cout << "[AvamHook] Target re-arm FAILED err=" << GetLastError() << std::endl;
                        }

                    } else {
                        // --- NOISE: Aynı sayfada hedef dışı erişim ---
                        // Guard'ı kaldır, worker thread 1ms sonra yeniler
                        DWORD oldProtect;
                        VirtualProtect(h.targetPage, 0x1000, h.originalProtection, &oldProtect);
                        h.isActive = false;
                        void* noisePage = h.targetPage; // mutex dışında kullanmak için kopyala
                        // hookMutex scope'u bitmeden rearmMutex almak güvenli ama
                        // notify'ı dışarı taşıyoruz
                        {
                            std::lock_guard<std::mutex> rLock(rearmMutex);
                            rearmQueue.push(noisePage);
                        }
                        rearmCV.notify_one();
                    }
                    break;
                }
            }
        }

        inHandler = false;
        return EXCEPTION_CONTINUE_EXECUTION;
    }

public:
    static bool Init() {
        if (!vehHandle) {
            vehHandle = AddVectoredExceptionHandler(1, VehHandler);
            if (vehHandle) {
                shouldExit.store(false, std::memory_order_release);
                if (!rearmThread.joinable()) {
                    rearmThread = std::thread(RearmWorker);
                }
            }
        }
        return vehHandle != nullptr;
    }

    static void Shutdown() {
        if (vehHandle) {
            shouldExit.store(true, std::memory_order_release);
            rearmCV.notify_one();
            if (rearmThread.joinable()) {
                rearmThread.join();
            }
            RemoveVectoredExceptionHandler(vehHandle);
            vehHandle = nullptr;
        }

        {
            std::lock_guard<std::mutex> lock(hookMutex);
            for (auto& h : hooks) {
                DWORD ignored;
                VirtualProtect(h.targetPage, 0x1000, h.originalProtection, &ignored);
                h.isActive = false;
            }
            hooks.clear();
        }

        {
            std::lock_guard<std::mutex> lock(rearmMutex);
            std::queue<void*> empty;
            rearmQueue.swap(empty);
        }

        g_vtableReady.store(false, std::memory_order_release);
        g_origDoKlass.store(nullptr, std::memory_order_release);
        g_filteredCount.store(0, std::memory_order_release);
        g_passedCount.store(0, std::memory_order_release);
    }

    static bool Hook(void* target) {
        if (!target) return false;

        void* pageBase = (void*)((uintptr_t)target & ~0xFFF);

        {
            std::lock_guard<std::mutex> lock(hookMutex);
            for (auto& h : hooks) {
                if (h.targetAddress == target) {
                    if (!h.isActive) {
                        DWORD oldProtect;
                        if (VirtualProtect(h.targetPage, 0x1000, GuardedProtection(h.originalProtection), &oldProtect)) {
                            h.isActive = true;
                        }
                    }
                    return true;
                }
            }
        }

        DWORD oldProtect;
        DWORD pageProtection = QueryPageProtection(pageBase);
        if (VirtualProtect(pageBase, 0x1000, GuardedProtection(pageProtection), &oldProtect)) {
            std::lock_guard<std::mutex> lock(hookMutex);
            for (auto& h : hooks) {
                if (h.targetAddress == target) {
                    h.isActive = true;
                    return true;
                }
            }
            PageGuardEntry entry;
            entry.targetAddress = target;
            entry.targetPage = pageBase;
            entry.originalProtection = oldProtect;
            entry.isActive = true;
            hooks.push_back(entry);
            return true;
        }

        return false;
    }

    static void RefreshHooks() {
        std::lock_guard<std::mutex> lock(hookMutex);
        for (auto& h : hooks) {
            if (!h.isActive) {
                DWORD oldProtect;
                if (VirtualProtect(h.targetPage, 0x1000, GuardedProtection(h.originalProtection), &oldProtect)) {
                    h.isActive = true;
                }
            }
        }
    }

    static void LogStatus() {
        std::lock_guard<std::mutex> lock(hookMutex);
        std::cout << "[AvamHook] === Hook Status ===" << std::endl;
        for (int i = 0; i < (int)hooks.size(); i++) {
            auto& h = hooks[i];
            MEMORY_BASIC_INFORMATION mbi = {};
            VirtualQuery(h.targetPage, &mbi, sizeof(mbi));
            bool guardActual = (mbi.Protect & PAGE_GUARD) != 0;
        }
    }
};

inline bool IsAdaptiveClass(void* klass) {
    if (!klass) return false;

    __try {
        // Klass::_name = *(klass + 0x10) = Symbol*
        // Symbol::_length = *(unsigned short*)(symbol + 0)
        // Symbol::_body   = symbol + 8
        void* symbol = *(void**)((char*)klass + 0x10);
        if ((uintptr_t)symbol > 0x10000) {
            unsigned short len = *(unsigned short*)((char*)symbol + 0);
            if (len > 0 && len < 512) {
                char* body = (char*)symbol + 8;
                if (len >= 8 && strncmp(body, "me/pewa/", 8) == 0) return true;
            }
        }
    } __except(1) {}
    return false;
}

inline void __fastcall FakeDoKlass(void* closure, void* klass) {
    if (IsAdaptiveClass(klass)) {
        g_filteredCount.fetch_add(1, std::memory_order_relaxed);
        __try {
            for (int offset = 0x10; offset <= 0x40; offset += 8) {
                void* ptr = *(void**)((char*)klass + offset);
                if ((uintptr_t)ptr > 0x10000) {
                    short len = *(short*)((char*)ptr + 8);
                    if (len > 0 && len < 200) {
                        char* body = (char*)ptr + 10;
                        if (strncmp(body, "me/pewa/", 8) == 0) {
                            char nameBuf[256] = {};
                            int copyLen = (len < 255) ? len : 255;
                            memcpy(nameBuf, body, copyLen);
                            std::cout << "sex: " << nameBuf << std::endl;
                            break;
                        }
                    }
                }
            }
        } __except(1) {}
        return;
    }

    if (!g_allowFullLoadedClasses) {
        int passed = g_passedCount.load(std::memory_order_relaxed);
        if (passed >= 500) {
            return;
        }
    }

    g_passedCount.fetch_add(1, std::memory_order_relaxed);
    DoKlass_t origDoKlass = g_origDoKlass.load(std::memory_order_acquire);
    if (origDoKlass) {
        origDoKlass(closure, klass);
    }
}
