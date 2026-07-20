#pragma once
#include "../include/jni.h"
#include "../include/jvmti.h"
#include <vector>

class JNIHelper {
public:
    static JavaVM* g_jvm;
    static JNIEnv* g_env;
    static jvmtiEnv* g_jvmti;
    static jobject g_customClassLoader;
    static std::vector<jclass> g_loadedClasses;

    static bool Initialize(JNIEnv* env);
    static jclass FindClassByName(const char* name);

    static jobject GetFieldObject(JNIEnv* env, jobject obj, const char* fieldName);
    static double GetDoubleField(JNIEnv* env, jobject obj, const char* fieldName);
    static float GetFloatField(JNIEnv* env, jobject obj, const char* fieldName);

    // JNI Function Typedefs
    typedef jclass(JNICALL* tFindClass)(JNIEnv*, const char*);
    typedef jmethodID(JNICALL* tGetMethodID)(JNIEnv*, jclass, const char*, const char*);
    typedef jmethodID(JNICALL* tGetStaticMethodID)(JNIEnv*, jclass, const char*, const char*);
    typedef jfieldID(JNICALL* tGetFieldID)(JNIEnv*, jclass, const char*, const char*);
    typedef jfieldID(JNICALL* tGetStaticFieldID)(JNIEnv*, jclass, const char*, const char*);
    typedef jobject(JNICALL* tGetObjectField)(JNIEnv*, jobject, jfieldID);
    typedef jobject(JNICALL* tGetStaticObjectField)(JNIEnv*, jclass, jfieldID);
    typedef jobject(JNICALL* tCallStaticObjectMethod)(JNIEnv*, jclass, jmethodID, ...);
    typedef jobject(JNICALL* tCallObjectMethod)(JNIEnv*, jobject, jmethodID, ...);
    typedef void(JNICALL* tCallVoidMethod)(JNIEnv*, jobject, jmethodID, ...);
    typedef jdouble(JNICALL* tCallDoubleMethod)(JNIEnv*, jobject, jmethodID, ...);
    typedef jstring(JNICALL* tNewStringUTF)(JNIEnv*, const char*);
    typedef const char* (JNICALL* tGetStringUTFChars)(JNIEnv*, jstring, jboolean*);
    typedef void(JNICALL* tReleaseStringUTFChars)(JNIEnv*, jstring, const char*);
    typedef jsize(JNICALL* tGetArrayLength)(JNIEnv*, jarray);
    typedef jobject(JNICALL* tGetObjectArrayElement)(JNIEnv*, jobjectArray, jsize);
    typedef jobject(JNICALL* tNewGlobalRef)(JNIEnv*, jobject);
    typedef void(JNICALL* tDeleteLocalRef)(JNIEnv*, jobject);
    typedef jobject(JNICALL* tNewObject)(JNIEnv*, jclass, jmethodID, ...);

    // Hook pointers
    static tFindClass oFindClass;
    static tGetMethodID oGetMethodID;
    static tGetStaticMethodID oGetStaticMethodID;
    static tGetFieldID oGetFieldID;
    static tGetStaticFieldID oGetStaticFieldID;
    static tGetObjectField oGetObjectField;
    static tGetStaticObjectField oGetStaticObjectField;
    static tCallStaticObjectMethod oCallStaticObjectMethod;
    static tCallObjectMethod oCallObjectMethod;
    static tCallVoidMethod oCallVoidMethod;
    static tCallDoubleMethod oCallDoubleMethod;
    static tNewStringUTF oNewStringUTF;
    static tGetStringUTFChars oGetStringUTFChars;
    static tReleaseStringUTFChars oReleaseStringUTFChars;
    static tGetArrayLength oGetArrayLength;
    static tGetObjectArrayElement oGetObjectArrayElement;
    static tNewGlobalRef oNewGlobalRef;
    static tDeleteLocalRef oDeleteLocalRef;
    static tNewObject oNewObject;

    static void* Hook3Hit(void* func);
    static void* Hook(void* func);
    static bool LoadClassesFromClassLoader(jobject classLoader, const char* packagePrefix);

private:
    static void HookFunctions();
    static bool FindCrClassLoader();
};