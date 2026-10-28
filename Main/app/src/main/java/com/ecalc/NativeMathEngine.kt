package com.ecalc

/**
 * Placeholder engine contract for future C11 Math Engine (MEP) integration via JNI/NDK.
 * As requested, the backend is decoupled for now until ready to be hooked up.
 */
object NativeMathEngine {
    // Once CMakeLists.txt and ecalc_jni.c are linked in build.gradle.kts:
    // init { System.loadLibrary("ecalc_mep") }

    fun isReady(): Boolean = false

    fun evalExpression(expr: String): String {
        return "Backend not hooked up yet"
    }

    fun lastError(): String = ""
}
