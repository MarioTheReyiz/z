#define _CRT_SECURE_NO_WARNINGS
#define NOMINMAX

#include <Windows.h>
#include "spotify_control.h"
#include <gl/GL.h>
#include <gl/GLU.h>
#include <cstdio>
#include <iostream>
#include <thread>
#include <chrono>
#include <fstream>
#include <DbgHelp.h>
#include "jni_helper.h"
#include "jar_loader.h"
#include "bypass.hpp"
#include "MinHook.h"

#pragma comment(lib, "opengl32.lib")
#pragma comment(lib, "glu32.lib")
#pragma comment(lib, "DbgHelp.lib")

JavaVM* g_jvm = nullptr;
JNIEnv* g_env = nullptr;
jvmtiEnv* g_jvmti = nullptr;

static bool isClientInitialized = false;

static void WriteStackTrace(uintptr_t rsp, FILE* f) {
    uintptr_t* stack = (uintptr_t*)rsp;
    HMODULE hJvm  = GetModuleHandleA("jvm.dll");
    HMODULE hSelf = GetModuleHandleA("Pewa.dll");
    for (int i = 0; i < 20; i++) {
        __try {
            uintptr_t addr = stack[i];
            if (addr > 0x10000) {
                fprintf(f, "  [%2d] 0x%016llx", i, (unsigned long long)addr);
                if (hJvm  && addr >= (uintptr_t)hJvm  && addr < (uintptr_t)hJvm  + 0x800000)
                    fprintf(f, "  (jvm.dll+0x%llx)",  (unsigned long long)(addr - (uintptr_t)hJvm));
                else if (hSelf && addr >= (uintptr_t)hSelf && addr < (uintptr_t)hSelf + 0x100000)
                    fprintf(f, "  (Pewa.dll+0x%llx)", (unsigned long long)(addr - (uintptr_t)hSelf));
                else {
                    // Hangi modülde olduğunu bul
                    HMODULE hMod = nullptr;
                    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                           GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                           (LPCSTR)addr, &hMod) && hMod) {
                        char modName[MAX_PATH] = {};
                        GetModuleFileNameA(hMod, modName, MAX_PATH);
                        // Sadece dosya adını al
                        const char* slash = strrchr(modName, '\\');
                        fprintf(f, "  (%s+0x%llx)", slash ? slash + 1 : modName,
                                (unsigned long long)(addr - (uintptr_t)hMod));
                    }
                }
                fprintf(f, "\n");
            }
        } __except(1) { break; }
    }
}

void WriteCrashLog(EXCEPTION_POINTERS* pExceptionInfo) {
    FILE* f = nullptr;
    fopen_s(&f, "C:\\pewa\\crash.log", "a");
    if (!f) return;

    SYSTEMTIME st;
    GetLocalTime(&st);

    fprintf(f, "\n");
    fprintf(f, "========================================\n");
    fprintf(f, "  CRASH REPORT  %04d-%02d-%02d %02d:%02d:%02d\n",
        st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond);
    fprintf(f, "========================================\n");

    if (!pExceptionInfo) {
        fprintf(f, "[No exception info]\n");
        fclose(f);
        return;
    }

    EXCEPTION_RECORD* er  = pExceptionInfo->ExceptionRecord;
    CONTEXT*          ctx = pExceptionInfo->ContextRecord;
    void* crashAddr = er->ExceptionAddress;

    // --- Exception info ---
    fprintf(f, "\n[EXCEPTION]\n");
    fprintf(f, "  Code    : 0x%08lx\n", er->ExceptionCode);
    fprintf(f, "  Address : 0x%016llx\n", (unsigned long long)crashAddr);
    fprintf(f, "  Flags   : 0x%08lx\n", er->ExceptionFlags);
    if (er->ExceptionCode == EXCEPTION_ACCESS_VIOLATION && er->NumberParameters >= 2) {
        fprintf(f, "  AV Type : %s\n", er->ExceptionInformation[0] == 1 ? "WRITE" : "READ");
        fprintf(f, "  AV Addr : 0x%016llx\n", (unsigned long long)er->ExceptionInformation[1]);
    }

    // Crash adresinin modülünü bul
    {
        HMODULE hMod = nullptr;
        if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                               GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                               (LPCSTR)crashAddr, &hMod) && hMod) {
            char modName[MAX_PATH] = {};
            GetModuleFileNameA(hMod, modName, MAX_PATH);
            const char* slash = strrchr(modName, '\\');
            fprintf(f, "  Module  : %s+0x%llx\n",
                    slash ? slash + 1 : modName,
                    (unsigned long long)((uintptr_t)crashAddr - (uintptr_t)hMod));
        }
    }

    // --- Registers ---
    fprintf(f, "\n[REGISTERS]\n");
    fprintf(f, "  RAX=%016llx  RBX=%016llx  RCX=%016llx\n",
        (unsigned long long)ctx->Rax, (unsigned long long)ctx->Rbx, (unsigned long long)ctx->Rcx);
    fprintf(f, "  RDX=%016llx  RSI=%016llx  RDI=%016llx\n",
        (unsigned long long)ctx->Rdx, (unsigned long long)ctx->Rsi, (unsigned long long)ctx->Rdi);
    fprintf(f, "  R8 =%016llx  R9 =%016llx  R10=%016llx\n",
        (unsigned long long)ctx->R8,  (unsigned long long)ctx->R9,  (unsigned long long)ctx->R10);
    fprintf(f, "  R11=%016llx  R12=%016llx  R13=%016llx\n",
        (unsigned long long)ctx->R11, (unsigned long long)ctx->R12, (unsigned long long)ctx->R13);
    fprintf(f, "  R14=%016llx  R15=%016llx\n",
        (unsigned long long)ctx->R14, (unsigned long long)ctx->R15);
    fprintf(f, "  RSP=%016llx  RBP=%016llx  RIP=%016llx\n",
        (unsigned long long)ctx->Rsp, (unsigned long long)ctx->Rbp, (unsigned long long)ctx->Rip);
    fprintf(f, "  EFL=%08lx\n", ctx->EFlags);

    // --- Bytes at RIP ---
    fprintf(f, "\n[BYTES AT RIP]\n  ");
    __try {
        uint8_t* rip = (uint8_t*)ctx->Rip;
        if (rip && (uintptr_t)rip > 0x1000) {
            for (int i = 0; i < 16; i++)
                fprintf(f, "%02x ", rip[i]);
            fprintf(f, "\n");
        }
    } __except(1) { fprintf(f, "[unreadable]\n"); }

    // --- Stack trace (raw RSP walk) ---
    fprintf(f, "\n[STACK TRACE]\n");
    WriteStackTrace(ctx->Rsp, f);

    // --- StackWalk64 (proper call stack) ---
    fprintf(f, "\n[CALL STACK (StackWalk64)]\n");
    __try {
        HANDLE hProcess = GetCurrentProcess();
        HANDLE hThread  = GetCurrentThread();
        SymInitialize(hProcess, NULL, TRUE);

        STACKFRAME64 sf = {};
        sf.AddrPC.Offset    = ctx->Rip;
        sf.AddrPC.Mode      = AddrModeFlat;
        sf.AddrFrame.Offset = ctx->Rbp;
        sf.AddrFrame.Mode   = AddrModeFlat;
        sf.AddrStack.Offset = ctx->Rsp;
        sf.AddrStack.Mode   = AddrModeFlat;

        CONTEXT ctxCopy = *ctx;

        for (int frame = 0; frame < 32; frame++) {
            if (!StackWalk64(IMAGE_FILE_MACHINE_AMD64, hProcess, hThread,
                             &sf, &ctxCopy, NULL,
                             SymFunctionTableAccess64, SymGetModuleBase64, NULL))
                break;
            if (sf.AddrPC.Offset == 0) break;

            uintptr_t pc = (uintptr_t)sf.AddrPC.Offset;
            fprintf(f, "  [%2d] 0x%016llx", frame, (unsigned long long)pc);

            // Modül adı
            HMODULE hMod = nullptr;
            if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                   GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                                   (LPCSTR)pc, &hMod) && hMod) {
                char modName[MAX_PATH] = {};
                GetModuleFileNameA(hMod, modName, MAX_PATH);
                const char* slash = strrchr(modName, '\\');
                fprintf(f, "  %s+0x%llx",
                        slash ? slash + 1 : modName,
                        (unsigned long long)(pc - (uintptr_t)hMod));
            }

            // Sembol adı
            char symBuf[sizeof(SYMBOL_INFO) + MAX_PATH] = {};
            SYMBOL_INFO* sym = (SYMBOL_INFO*)symBuf;
            sym->SizeOfStruct = sizeof(SYMBOL_INFO);
            sym->MaxNameLen   = MAX_PATH;
            DWORD64 symDisp = 0;
            if (SymFromAddr(hProcess, pc, &symDisp, sym))
                fprintf(f, "  %s+0x%llx", sym->Name, (unsigned long long)symDisp);

            fprintf(f, "\n");
        }

        SymCleanup(hProcess);
    } __except(1) { fprintf(f, "  [StackWalk64 failed]\n"); }

    // --- State ---
    fprintf(f, "\n[STATE]\n");
    fprintf(f, "  ClientInit : %s\n", isClientInitialized ? "Yes" : "No");
    fprintf(f, "  JVM        : %p\n", g_jvm);
    fprintf(f, "  JNIEnv     : %p\n", g_env);
    fprintf(f, "  JVMTI      : %p\n", g_jvmti);

    fprintf(f, "\n========================================\n\n");
    fclose(f);
    printf("[-] Crash log -> C:\\pewa\\crash.log\n");
}

LONG WINAPI ExceptionHandler(EXCEPTION_POINTERS* pExceptionInfo) {
    printf("[-] EXCEPTION CAUGHT!\n");
    WriteCrashLog(pExceptionInfo);
    return EXCEPTION_CONTINUE_SEARCH;
}

// OpenGL Typedefs
namespace GLHooks {
    typedef void(__stdcall* wglSwapBuffers_t)(HDC);
    wglSwapBuffers_t original_wglSwapBuffers = nullptr;
}

void InitializeLogging() {
    AllocConsole();
    
    // UTF-8 encoding ayarla
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
    
    FILE* pCout;
    freopen_s(&pCout, "CONOUT$", "w", stdout);
    freopen_s(&pCout, "CONOUT$", "w", stderr);
    
    SetConsoleTitleA("Pewa Loader - Debug Console");
}


jclass g_spotifyWidgetClass = nullptr;
jfieldID g_nativeTrackField = nullptr;
jfieldID g_nativeArtistField = nullptr;
jfieldID g_nativePlayingField = nullptr;
jfieldID g_nativeAvailableField = nullptr;
jfieldID g_nativeCommandField = nullptr;
jfieldID g_nativePositionField = nullptr;
jfieldID g_nativeDurationField = nullptr;
bool g_spotifyFieldsInitialized = false;
DWORD g_lastSpotifyUpdate = 0;

// g_jarLoaded — JarLoader::IsHudReady() ile sync tutulur
static bool g_jarLoaded = false;




// GetLoadedClasses'ı biz de çağırıp kaç döndüğünü test et
// Bypass çalışıyorsa 500 dönmeli, çalışmıyorsa gerçek sayı döner
static void DumpAllLoadedClasses() {
    // JNIHelper'ın JVMTI'sini kullan — main.cpp'deki farklı instance bypass'ı tetiklemiyor
    jvmtiEnv* jvmti = JNIHelper::g_jvmti;
    if (!jvmti) {
        printf("[DUMP] JNIHelper::g_jvmti null, atlanıyor\n");
        return;
    }

    printf("[DUMP] Kullanılan JVMTI: %p\n", jvmti);

    auto GetLoadedClasses_ = unhook((jvmtiError(JNICALL*)(jvmtiEnv*, jint*, jclass**))
        jvmti->functions->GetLoadedClasses);
    auto GetClassSignature_ = unhook((jvmtiError(JNICALL*)(jvmtiEnv*, jclass, char**, char**))
        jvmti->functions->GetClassSignature);
    auto Deallocate_ = unhook((jvmtiError(JNICALL*)(jvmtiEnv*, unsigned char*))
        jvmti->functions->Deallocate);

    jint classCount = 0;
    jclass* classes = nullptr;

    // Bypass PAGE_GUARD'ı bu çağrıyı da yakalayacak — 500 dönerse bypass çalışıyor demek
    jvmtiError err = JVMTI_ERROR_NONE;
    {
        ScopedFullLoadedClasses fullLoadedClassesScope;
        err = GetLoadedClasses_(jvmti, &classCount, &classes);
    }
    if (err != JVMTI_ERROR_NONE) {
        printf("[DUMP] GetLoadedClasses hata: %d\n", (int)err);
        return;
    }

    printf("[DUMP] *** GetLoadedClasses bize %d döndürdü ***\n", (int)classCount);
    printf("[DUMP] Bypass çalışıyorsa 500 olmalı, çalışmıyorsa gerçek sayı\n");

    FILE* f = nullptr;
    fopen_s(&f, "C:\\pewa\\classes_dump.txt", "a");
    if (f) {
        fprintf(f, "GetLoadedClasses count: %d\n\n", (int)classCount);
        for (jint i = 0; i < classCount; i++) {
            char* sig = nullptr;
            if (GetClassSignature_(jvmti, classes[i], &sig, nullptr) == JVMTI_ERROR_NONE && sig) {
                fprintf(f, "%s\n", sig);
                if (strstr(sig, "pewa") || strstr(sig, "Pewa"))
                    printf("[DUMP] PEWA GORUNUYOR: %s\n", sig);
                Deallocate_(jvmti, (unsigned char*)sig);
            }
        }
        fclose(f);
        printf("[DUMP] Yazıldı -> C:\\pewa\\classes_dump.txt\n");
    }

    Deallocate_(jvmti, (unsigned char*)classes);
}


void UpdateSpotifyWidget() {
    // g_jarLoaded'ı IsHudReady ile sync tut
    if (!g_jarLoaded) g_jarLoaded = JarLoader::IsHudReady();
    if (!g_env || !g_jarLoaded) return;
    
    // Throttle: Her 100ms'de bir güncelle
    DWORD now = GetTickCount();
    if (now - g_lastSpotifyUpdate < 100) return;
    g_lastSpotifyUpdate = now;
    
    // Field ID'leri bir kez al
    if (!g_spotifyFieldsInitialized) {
        auto FindClass_        = (jclass(JNICALL*)(JNIEnv*, const char*))                    unhook(g_env->functions->FindClass);
        auto GetStaticFieldID_ = (jfieldID(JNICALL*)(JNIEnv*, jclass, const char*, const char*)) unhook(g_env->functions->GetStaticFieldID);
        auto NewGlobalRef_     = (jobject(JNICALL*)(JNIEnv*, jobject))                       unhook(g_env->functions->NewGlobalRef);
        auto DeleteLocalRef_   = (void(JNICALL*)(JNIEnv*, jobject))                          unhook(g_env->functions->DeleteLocalRef);
        auto ExceptionCheck_   = (jboolean(JNICALL*)(JNIEnv*))                               unhook(g_env->functions->ExceptionCheck);
        auto ExceptionClear_   = (void(JNICALL*)(JNIEnv*))                                   unhook(g_env->functions->ExceptionClear);

        jclass widgetClass = FindClass_(g_env, "me/pewa/module/impl/SpotifyWidget");
        if (ExceptionCheck_(g_env)) ExceptionClear_(g_env);
        if (!widgetClass) {
            printf("[-] SpotifyWidget class not found\n");
            return;
        }

        g_spotifyWidgetClass   = (jclass)NewGlobalRef_(g_env, widgetClass);
        DeleteLocalRef_(g_env, widgetClass);

        g_nativeTrackField     = GetStaticFieldID_(g_env, g_spotifyWidgetClass, "nativeTrack",     "Ljava/lang/String;");
        if (ExceptionCheck_(g_env)) ExceptionClear_(g_env);
        g_nativeArtistField    = GetStaticFieldID_(g_env, g_spotifyWidgetClass, "nativeArtist",    "Ljava/lang/String;");
        if (ExceptionCheck_(g_env)) ExceptionClear_(g_env);
        g_nativePlayingField   = GetStaticFieldID_(g_env, g_spotifyWidgetClass, "nativePlaying",   "Z");
        if (ExceptionCheck_(g_env)) ExceptionClear_(g_env);
        g_nativeAvailableField = GetStaticFieldID_(g_env, g_spotifyWidgetClass, "nativeAvailable", "Z");
        if (ExceptionCheck_(g_env)) ExceptionClear_(g_env);
        g_nativeCommandField   = GetStaticFieldID_(g_env, g_spotifyWidgetClass, "nativeCommand",   "I");
        if (ExceptionCheck_(g_env)) ExceptionClear_(g_env);
        g_nativePositionField  = GetStaticFieldID_(g_env, g_spotifyWidgetClass, "nativePosition",  "J");
        if (ExceptionCheck_(g_env)) ExceptionClear_(g_env);
        g_nativeDurationField  = GetStaticFieldID_(g_env, g_spotifyWidgetClass, "nativeDuration",  "J");
        if (ExceptionCheck_(g_env)) ExceptionClear_(g_env);

        if (g_nativeTrackField && g_nativeArtistField && g_nativePlayingField
            && g_nativeAvailableField && g_nativeCommandField) {
            g_spotifyFieldsInitialized = true;
            Spotify_Init();
            printf("[+] SpotifyWidget bridge OK\n");
        } else {
            printf("[-] SpotifyWidget field lookup failed\n");
            return;
        }
    }
    
    // Spotify bilgilerini al
    const char* track = Spotify_GetTrack();
    const char* artist = Spotify_GetArtist();
    bool playing = Spotify_IsPlaying();
    
    // Field'lara yaz (unhook ile)
    auto SetStaticObjectField_ = (void(JNICALL*)(JNIEnv*, jclass, jfieldID, jobject)) unhook(g_env->functions->SetStaticObjectField);
    auto SetStaticBooleanField_ = (void(JNICALL*)(JNIEnv*, jclass, jfieldID, jboolean)) unhook(g_env->functions->SetStaticBooleanField);
    auto NewStringUTF_ = (jstring(JNICALL*)(JNIEnv*, const char*)) unhook(g_env->functions->NewStringUTF);
    auto DeleteLocalRef_ = (void(JNICALL*)(JNIEnv*, jobject)) unhook(g_env->functions->DeleteLocalRef);
    
    // Track string
    if (track && strlen(track) > 0) {
        jstring jTrack = NewStringUTF_(g_env, track);
        if (jTrack) {
            SetStaticObjectField_(g_env, g_spotifyWidgetClass, g_nativeTrackField, jTrack);
            DeleteLocalRef_(g_env, jTrack);
        }
    }
    
    // Artist string
    if (artist && strlen(artist) > 0) {
        jstring jArtist = NewStringUTF_(g_env, artist);
        if (jArtist) {
            SetStaticObjectField_(g_env, g_spotifyWidgetClass, g_nativeArtistField, jArtist);
            DeleteLocalRef_(g_env, jArtist);
        }
    }
    
    // Playing boolean
    SetStaticBooleanField_(g_env, g_spotifyWidgetClass, g_nativePlayingField, playing ? JNI_TRUE : JNI_FALSE);
    
    // Available flag
    SetStaticBooleanField_(g_env, g_spotifyWidgetClass, g_nativeAvailableField, JNI_TRUE);

    // Position & Duration
    long pos = Spotify_GetPosition();
    long dur = Spotify_GetDuration();
    if (g_nativePositionField) {
        auto SetStaticLongField_ = (void(JNICALL*)(JNIEnv*, jclass, jfieldID, jlong)) unhook(g_env->functions->SetStaticLongField);
        SetStaticLongField_(g_env, g_spotifyWidgetClass, g_nativePositionField, (jlong)pos);
    }
    if (g_nativeDurationField) {
        auto SetStaticLongField_ = (void(JNICALL*)(JNIEnv*, jclass, jfieldID, jlong)) unhook(g_env->functions->SetStaticLongField);
        SetStaticLongField_(g_env, g_spotifyWidgetClass, g_nativeDurationField, (jlong)dur);
    }

    // ===================================
    // PROCESS COMMANDS (Java -> DLL)
    // ===================================
    auto GetStaticIntField_ = (jint(JNICALL*)(JNIEnv*, jclass, jfieldID)) unhook(g_env->functions->GetStaticIntField);
    auto SetStaticIntField_ = (void(JNICALL*)(JNIEnv*, jclass, jfieldID, jint)) unhook(g_env->functions->SetStaticIntField);

    jint cmd = GetStaticIntField_(g_env, g_spotifyWidgetClass, g_nativeCommandField);
    if (cmd > 0) {
        std::cout << "[+] komut yakalandi: " << cmd << std::endl;
        
        // Execute command
        if (cmd == 1) Spotify_Previous();
        else if (cmd == 2) Spotify_PlayPause();
        else if (cmd == 3) Spotify_Next();
        else if (cmd == 4) Spotify_VolumeDown();
        else if (cmd == 5) Spotify_VolumeUp();
        else if (cmd >= 100 && cmd <= 200) {
            Spotify_SetVolume(cmd - 100);
        }
        else if (cmd >= 300) {
            Spotify_Seek(cmd - 300);
        }
        
        // Reset command to 0 (ack)
        SetStaticIntField_(g_env, g_spotifyWidgetClass, g_nativeCommandField, 0);
    }
}


static bool AcquireJNIEnv() {
    if (g_env && g_jvm) return true;

    HMODULE hJvm = GetModuleHandleA("jvm.dll");
    if (!hJvm) {
        printf("[-] jvm.dll not found in AcquireJNIEnv\n");
        return false;
    }

    typedef jint(JNICALL* p_GetEnv)(JavaVM*, JNIEnv**, jint);
    p_GetEnv fnGetEnv = (p_GetEnv)((uintptr_t)hJvm + 0x144080);
    fnGetEnv(nullptr, &g_env, JNI_VERSION_1_8);

    if (!g_env) {
        printf("[-] Failed to get JNIEnv via offset\n");
        return false;
    }
    printf("[+] Got JNIEnv (render thread): %p\n", g_env);

    g_env->GetJavaVM(&g_jvm);
    if (!g_jvm) {
        printf("[-] Failed to get JavaVM from JNIEnv\n");
        return false;
    }
    printf("[+] Got JavaVM: %p\n", g_jvm);

    jint res = g_jvm->GetEnv((void**)&g_jvmti, JVMTI_VERSION_1_2);

    if (res != JNI_OK) {
        printf("[-] Failed to get JVMTI: %d\n", res);
        g_jvmti = nullptr;
    } else {
        printf("[+] Got JVMTI: %p\n", g_jvmti);
    }

    return true;
}

static void InitializeClient() {
    if (!isClientInitialized) {
        isClientInitialized = true; // Önce set et, döngüye girmesin
        __try {
            printf("[*] InitializeClient START\n");

            // Render thread'den JNIEnv al
            if (!AcquireJNIEnv()) {
                printf("[-] Failed to acquire JNIEnv, aborting\n");
                return;
            }

            // Bypass sistemini başlat
            printf("\n[*] Initializing Bypass System...\n");
            AvamHook::Init();
            HMODULE hJvm = GetModuleHandleA("jvm.dll");
            if (!hJvm) {
                printf("[-] Failed to get JVM.dll handle\n");
                return;
            }

            void* loadedClassesTarget = (void*)((uintptr_t)hJvm + 0x61590);
            AvamHook::Hook(loadedClassesTarget);
            printf("[+] Bypass system initialized\n");

            // JNIHelper başlat (class listesini temizleyerek)
            JNIHelper::g_loadedClasses.clear();
            printf("[*] Initializing JNIHelper...\n");
            if (!JNIHelper::Initialize(g_env)) {
                printf("[-] JNIHelper::Initialize failed\n");
                return;
            }
            printf("[+] JNIHelper initialized\n");

            // JAR yükle (DISABLED FOR NOW)
            const char* jarPath = "C:\\pewa\\client.jar";
            printf("[*] Loading JAR from: %s\n", jarPath);
            if (JarLoader::LoadJar(g_env, g_jvmti, jarPath)) {
                printf("[+] JAR Loader completed successfully\n");
            } else {
                printf("[-] JAR Loader failed\n");
            }

            // Manuel dump — hook'tan bağımsız, JVMTI ile tüm classları yaz
            printf("[*] Dumping all loaded classes to C:\\pewa\\classes_dump.txt...\n");
            DumpAllLoadedClasses();

            printf("[+] InitializeClient COMPLETE\n");
            printf("[*] System ready\n\n");
        }
        __except (WriteCrashLog(GetExceptionInformation()), EXCEPTION_EXECUTE_HANDLER) {
            printf("[-] Exception in InitializeClient\n");
        }
    }
}

// OpenGL Hooks
void __stdcall Hooked_wglSwapBuffers(HDC hdc) {
    InitializeClient();
    if (g_env && JarLoader::IsHudReady()) {
        JarLoader::RenderHud(g_env);
        UpdateSpotifyWidget();
    }
    GLHooks::original_wglSwapBuffers(hdc);
}

// Main Thread
DWORD WINAPI MainThread(LPVOID) {
    // JVM'nin yüklenmesini bekle
    printf("[*] Waiting for jvm.dll...\n");
    HMODULE hJvm = nullptr;
    while (!hJvm) {
        hJvm = GetModuleHandleA("jvm.dll");
        Sleep(100);
    }
    printf("[+] jvm.dll loaded\n");
    
    // OpenGL32.dll'yi bekle
    printf("[*] Waiting for opengl32.dll...\n");
    HMODULE openglModule = nullptr;
    while (!openglModule) {
        openglModule = GetModuleHandleA("opengl32.dll");
        Sleep(100);
    }
    printf("[+] opengl32.dll loaded\n");
    
    // MinHook Initialize
    printf("[*] Initializing MinHook...\n");
    if (MH_Initialize() != MH_OK) {
        printf("[-] MinHook initialization failed\n");
        return 0;
    }
    printf("[+] MinHook initialized\n");
    
    // wglSwapBuffers hook
    printf("[*] Hooking wglSwapBuffers...\n");
    GLHooks::original_wglSwapBuffers = (GLHooks::wglSwapBuffers_t)GetProcAddress(openglModule, "wglSwapBuffers");
    
    if (!GLHooks::original_wglSwapBuffers) {
        printf("[-] Failed to get wglSwapBuffers\n");
        return 0;
    }
    
    printf("[+] wglSwapBuffers found: %p\n", GLHooks::original_wglSwapBuffers);
    
    if (MH_CreateHook((LPVOID)GLHooks::original_wglSwapBuffers, (LPVOID)Hooked_wglSwapBuffers, (LPVOID*)&GLHooks::original_wglSwapBuffers) != MH_OK) {
        printf("[-] Failed to create wglSwapBuffers hook\n");
        return 0;
    }
    
    if (MH_EnableHook(MH_ALL_HOOKS) != MH_OK) {
        printf("[-] Failed to enable hooks\n");
        return 0;
    }
    
    printf("[+] wglSwapBuffers hooked successfully\n");
    printf("[*] Waiting for game to render...\n");
    
    return 0;
}

// DllMain
BOOL APIENTRY DllMain(HMODULE hModule, DWORD ul_reason_for_call, LPVOID lpReserved) {
    if (ul_reason_for_call == DLL_PROCESS_ATTACH) {
        SetUnhandledExceptionFilter(ExceptionHandler);
        InitializeLogging();
        printf("[*] Pewa DLL attached\n");
        printf("[*] Module: %p\n", hModule);
        printf("[*] Exception handler installed\n");
        DisableThreadLibraryCalls(hModule);
        CreateThread(nullptr, 0, MainThread, nullptr, 0, nullptr);
    }
    else if (ul_reason_for_call == DLL_PROCESS_DETACH) {
        AvamHook::Shutdown();
    }
    return TRUE;
}
