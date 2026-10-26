#include <jni.h>
#include <string.h>

// #include "math_engine.h"
// static Engine g_eng;

JNIEXPORT jboolean JNICALL
Java_com_ecalc_app_NativeMathEngine_initEngine(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    // return eng_init(&g_eng) ? JNI_TRUE : JNI_FALSE;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_ecalc_app_NativeMathEngine_evalExpression(JNIEnv *env, jobject thiz, jstring expr) {
    (void)thiz; (void)expr;
    return (*env)->NewStringUTF(env, "0.0");
}

JNIEXPORT jstring JNICALL
Java_com_ecalc_app_NativeMathEngine_lastError(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return (*env)->NewStringUTF(env, "");
}
