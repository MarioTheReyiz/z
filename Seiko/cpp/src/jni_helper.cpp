#include "jni_helper.h"
#include <cstdio>
#include <string>
#include <Windows.h>
#include "bypass.hpp"

JavaVM* JNIHelper::g_jvm = nullptr;
JNIEnv* JNIHelper::g_env = nullptr;
jvmtiEnv* JNIHelper::g_jvmti = nullptr;
jobject JNIHelper::g_customClassLoader = nullptr;
std::vector<jclass> JNIHelper::g_loadedClasses;

JNIHelper::tFindClass JNIHelper::oFindClass = nullptr;
JNIHelper::tGetMethodID JNIHelper::oGetMethodID = nullptr;
JNIHelper::tGetStaticMethodID JNIHelper::oGetStaticMethodID = nullptr;
JNIHelper::tGetFieldID JNIHelper::oGetFieldID = nullptr;
JNIHelper::tGetStaticObjectField JNIHelper::oGetStaticObjectField = nullptr;
JNIHelper::tGetObjectField JNIHelper::oGetObjectField = nullptr;
JNIHelper::tCallStaticObjectMethod JNIHelper::oCallStaticObjectMethod = nullptr;
JNIHelper::tCallObjectMethod JNIHelper::oCallObjectMethod = nullptr;
JNIHelper::tCallVoidMethod JNIHelper::oCallVoidMethod = nullptr;
JNIHelper::tCallDoubleMethod JNIHelper::oCallDoubleMethod = nullptr;
JNIHelper::tNewStringUTF JNIHelper::oNewStringUTF = nullptr;
JNIHelper::tGetStringUTFChars JNIHelper::oGetStringUTFChars = nullptr;
JNIHelper::tReleaseStringUTFChars JNIHelper::oReleaseStringUTFChars = nullptr;
JNIHelper::tGetArrayLength JNIHelper::oGetArrayLength = nullptr;
JNIHelper::tGetObjectArrayElement JNIHelper::oGetObjectArrayElement = nullptr;
JNIHelper::tGetStaticFieldID JNIHelper::oGetStaticFieldID = nullptr;
JNIHelper::tNewGlobalRef JNIHelper::oNewGlobalRef = nullptr;
JNIHelper::tDeleteLocalRef JNIHelper::oDeleteLocalRef = nullptr;
JNIHelper::tNewObject JNIHelper::oNewObject = nullptr;

void* JNIHelper::Hook(void* func) {
    uint8_t* f = (uint8_t*)func;
    if (f[0] == 0xE9) return (void*)(f + 5);
    if (f[0] == 0x49 && f[1] == 0xBA) return (void*)*(uint64_t*)(f + 2);
    return func;
}

jobject JNIHelper::GetFieldObject(JNIEnv* env, jobject obj, const char* fieldName) {
    if (!obj) return nullptr;

    auto GetObjectClass_ = (jclass(JNICALL*)(JNIEnv*, jobject))Hook(env->functions->GetObjectClass);
    jclass objClass = GetObjectClass_(env, obj);

    jclass classClass = JNIHelper::oFindClass(env, "java/lang/Class");
    jmethodID getDeclaredField = JNIHelper::oGetMethodID(
        env, classClass, "getDeclaredField",
        "(Ljava/lang/String;)Ljava/lang/reflect/Field;"
    );

    jstring name = JNIHelper::oNewStringUTF(env, fieldName);

    jobject field = JNIHelper::oCallObjectMethod(env, objClass, getDeclaredField, name);
    if (!field) {
        JNIHelper::oDeleteLocalRef(env, name);
        JNIHelper::oDeleteLocalRef(env, objClass);
        return nullptr;
    }

    jclass fieldClass = JNIHelper::oFindClass(env, "java/lang/reflect/Field");

    jmethodID setAccessible = JNIHelper::oGetMethodID(env, fieldClass, "setAccessible", "(Z)V");
    jmethodID getMethod = JNIHelper::oGetMethodID(env, fieldClass, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");

    JNIHelper::oCallVoidMethod(env, field, setAccessible, JNI_TRUE);

    jobject result = JNIHelper::oCallObjectMethod(env, field, getMethod, obj);

    JNIHelper::oDeleteLocalRef(env, name);
    JNIHelper::oDeleteLocalRef(env, field);
    JNIHelper::oDeleteLocalRef(env, objClass);

    return result;
}

double JNIHelper::GetDoubleField(JNIEnv* env, jobject obj, const char* fieldName) {
    jobject valObj = GetFieldObject(env, obj, fieldName);
    if (!valObj) return 0.0;

    jclass doubleClass = oFindClass(env, "java/lang/Double");
    jmethodID doubleValue = oGetMethodID(env, doubleClass, "doubleValue", "()D");

    double val = oCallDoubleMethod(env, valObj, doubleValue);

    oDeleteLocalRef(env, valObj);
    oDeleteLocalRef(env, doubleClass);
    return val;
}

float JNIHelper::GetFloatField(JNIEnv* env, jobject obj, const char* fieldName) {
    jobject valObj = GetFieldObject(env, obj, fieldName);
    if (!valObj) return 0.0f;

    jclass floatClass = oFindClass(env, "java/lang/Float");
    jmethodID floatValue = oGetMethodID(env, floatClass, "floatValue", "()F");

    auto CallFloatMethod_ = (jfloat(JNICALL*)(JNIEnv*, jobject, jmethodID, ...))Hook(env->functions->CallFloatMethod);
    float val = CallFloatMethod_(env, valObj, floatValue);

    oDeleteLocalRef(env, valObj);
    oDeleteLocalRef(env, floatClass);
    return val;
}

void* JNIHelper::Hook3Hit(void* func) {
    uint8_t* f = (uint8_t*)func;

    printf("[HOOK3] Original: %p\n", func);

    // 1. JMP REL32 (E9)
    if (f[0] == 0xE9) {
        int32_t offset1 = *(int32_t*)(f + 1);
        f = f + 5 + offset1;
        printf("[HOOK3] After E9: %p\n", f);

        // 2. JMP [RIP+offset] (FF 25)
        if (f[0] == 0xFF && f[1] == 0x25) {
            int32_t offset2 = *(int32_t*)(f + 2);
            void** ptrAddr = (void**)(f + 6 + offset2);
            f = (uint8_t*)*ptrAddr;
            printf("[HOOK3] After FF 25: %p\n", f);

            // 3. Bir daha E9 var mı?
            if (f[0] == 0xE9) {
                int32_t offset3 = *(int32_t*)(f + 1);
                f = f + 5 + offset3;
                printf("[HOOK3] After final E9: %p\n", f);
            }
        }
        // 2. E9 JMP
        else if (f[0] == 0xE9) {
            int32_t offset2 = *(int32_t*)(f + 1);
            f = f + 5 + offset2;
            printf("[HOOK3] After 2nd E9: %p\n", f);

            // 3. E9 JMP
            if (f[0] == 0xE9) {
                int32_t offset3 = *(int32_t*)(f + 1);
                f = f + 5 + offset3;
                printf("[HOOK3] After 3rd E9: %p\n", f);
            }
        }

        printf("[HOOK3] Final: %p\n", f);
        return (void*)f;
    }

    return func;
}

void JNIHelper::HookFunctions() {
    void** vt = *(void***)g_env;

    oFindClass = (tFindClass)Hook(vt[6]);
    oGetMethodID = (tGetMethodID)Hook(vt[33]);
    oCallObjectMethod = (tCallObjectMethod)Hook(vt[34]);
    oCallVoidMethod = (tCallVoidMethod)Hook(vt[61]);
    oCallDoubleMethod = (tCallDoubleMethod)Hook(vt[37]);
    oGetFieldID = (tGetFieldID)Hook(vt[94]);
    oGetStaticMethodID = (tGetStaticMethodID)Hook(vt[113]);
    oCallStaticObjectMethod = (tCallStaticObjectMethod)Hook(vt[114]);
    oGetStaticFieldID = (tGetStaticFieldID)Hook(vt[144]);
    oGetStaticObjectField = (tGetStaticObjectField)Hook(vt[145]);
    oNewStringUTF = (tNewStringUTF)Hook(vt[167]);

    // getobjectfield için özel hook
    oGetObjectField = (tGetObjectField)Hook3Hit(vt[95]);

    oGetStringUTFChars = (tGetStringUTFChars)Hook(vt[169]);
    oReleaseStringUTFChars = (tReleaseStringUTFChars)Hook(vt[170]);
    oGetArrayLength = (tGetArrayLength)Hook(vt[171]);
    oGetObjectArrayElement = (tGetObjectArrayElement)Hook(vt[173]);
    oNewGlobalRef = (tNewGlobalRef)Hook(vt[21]);
    oDeleteLocalRef = (tDeleteLocalRef)Hook(vt[23]);
    oNewObject = (tNewObject)Hook(vt[28]);
}

bool JNIHelper::FindCrClassLoader() {
    printf("[*] FindCrClassLoader START\n");

    jclass classClass = oFindClass(g_env, "java/lang/Class");
    jmethodID getNameMethod = oGetMethodID(g_env, classClass, "getName", "()Ljava/lang/String;");

    if (g_jvm->GetEnv((void**)&g_jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        printf("[-] Failed to get JVMTI\n");
        return false;
    }

    printf("[+] JVMTI obtained: %p\n", g_jvmti);

    jint classCount = 0;
    jclass* classes = nullptr;
    jvmtiError loadedClassesErr = JVMTI_ERROR_NONE;
    {
        ScopedFullLoadedClasses fullLoadedClassesScope;
        loadedClassesErr = g_jvmti->GetLoadedClasses(&classCount, &classes);
    }
    if (loadedClassesErr != JVMTI_ERROR_NONE) {
        printf("[-] Failed to get loaded classes\n");
        return false;
    }

    printf("[*] Scanning %d loaded classes...\n", classCount);

    int loadedMinecraft = 0;
    int loadedCraftrise = 0;

    for (jint i = 0; i < classCount; i++) {
        jstring name = (jstring)oCallObjectMethod(g_env, classes[i], getNameMethod);
        if (g_env->ExceptionCheck()) { g_env->ExceptionClear(); continue; }
        if (!name) continue;

        const char* nameStr = oGetStringUTFChars(g_env, name, nullptr);
        if (nameStr) {
            std::string n(nameStr);

            if (n.find("$Lambda") == std::string::npos) {
                // Minecraft sınıfları
                if (n.rfind("net/minecraft/", 0) == 0) {
                    g_loadedClasses.push_back((jclass)oNewGlobalRef(g_env, classes[i]));
                    loadedMinecraft++;
                }
                // CraftRise sınıfları
                else if (n.rfind("craftrise.", 0) == 0 || n.rfind("crsecond.", 0) == 0) {
                    g_loadedClasses.push_back((jclass)oNewGlobalRef(g_env, classes[i]));
                    loadedCraftrise++;
                }
            }

            oReleaseStringUTFChars(g_env, name, nameStr);
        }
        oDeleteLocalRef(g_env, name);
    }

    g_jvmti->Deallocate((unsigned char*)classes);

    printf("[+] Loaded %d minecraft classes\n", loadedMinecraft);
    printf("[+] Loaded %d craftrise classes\n", loadedCraftrise);
    printf("[+] Total: %d classes\n", (int)g_loadedClasses.size());

    return !g_loadedClasses.empty();
}

jclass JNIHelper::FindClassByName(const char* targetName) {
    jclass classClass = oFindClass(g_env, "java/lang/Class");
    jmethodID getNameMethod = oGetMethodID(g_env, classClass, "getName", "()Ljava/lang/String;");
    for (jclass cls : g_loadedClasses) {
        jstring name = (jstring)oCallObjectMethod(g_env, cls, getNameMethod);
        if (g_env->ExceptionCheck()) { g_env->ExceptionClear(); continue; }
        if (!name) continue;
        const char* str = oGetStringUTFChars(g_env, name, nullptr);
        bool match = str && strcmp(str, targetName) == 0;
        if (str) oReleaseStringUTFChars(g_env, name, str);
        oDeleteLocalRef(g_env, name);
        if (match) {
            printf("[+] Found class: %s\n", targetName);
            return cls;
        }
    }

    printf("[-] Class not found: %s\n", targetName);
    return nullptr;
}

bool JNIHelper::Initialize(JNIEnv* env) {
    if (!env) return false;

    // Use the provided thread-local JNIEnv (render thread). Store JavaVM only.
    g_env = env; // note: functions that use g_env must be called from same thread
    if (g_env->GetJavaVM(&g_jvm) != JNI_OK) {
        printf("[-] Failed to get JavaVM from provided JNIEnv\n");
        return false;
    }

    HookFunctions();
    return FindCrClassLoader();
}
