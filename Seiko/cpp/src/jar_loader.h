#pragma once
#include <jni.h>
#include <jvmti.h>
#include <cstdint>

// vtable/inline hook zincirini çözer (E9 rel32 ve FF 25 rip-relative)
template<typename T>
inline T unhook(T fn) {
    uint8_t* p = reinterpret_cast<uint8_t*>(fn);
    for (int i = 0; i < 5; i++) {
        if (p[0] == 0xE9) {
            int32_t rel = *reinterpret_cast<int32_t*>(p + 1);
            p = p + 5 + rel;
            continue;
        }
        if (p[0] == 0xFF && p[1] == 0x25) {
            int32_t rel = *reinterpret_cast<int32_t*>(p + 2);
            uint8_t** target = reinterpret_cast<uint8_t**>(p + 6 + rel);
            p = *target;
            continue;
        }
        break;
    }
    return reinterpret_cast<T>(p);
}

namespace JarLoader {
    bool LoadJar(JNIEnv*, jvmtiEnv*, const char*);
    bool IsHudReady();
    bool RenderHud(JNIEnv*);
}
