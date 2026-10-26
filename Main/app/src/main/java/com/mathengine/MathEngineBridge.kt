package com.mathengine

import java.io.Closeable

/**
 * Thread-Safe Android Kotlin Idiomatic Wrapper for the C11 Math Engine (MEP).
 */
class MathEngineKt : Closeable {
    private var handle: Long = MathEngineBridge.nativeCreate()

    init {
        check(handle != 0L) { "Failed to initialize native MEP Engine" }
    }

    private fun requireOpenHandle(): Long {
        check(handle != 0L) { "MathEngineKt has already been closed" }
        return handle
    }

    @Synchronized
    fun eval(expr: String): MathEngineBridge.EvalResult =
        MathEngineBridge.EvalResult.parseWire(MathEngineBridge.nativeEval(requireOpenHandle(), expr))

    @Synchronized
    fun openSession(): Int = MathEngineBridge.nativeSessionOpen(requireOpenHandle())

    @Synchronized
    fun closeSession(sessionId: Int): Boolean =
        MathEngineBridge.nativeSessionClose(requireOpenHandle(), sessionId)

    @Synchronized
    fun resetSession(sessionId: Int): Boolean =
        MathEngineBridge.nativeSessionReset(requireOpenHandle(), sessionId)

    @Synchronized
    fun evalSession(sessionId: Int, expr: String): MathEngineBridge.EvalResult =
        MathEngineBridge.EvalResult.parseWire(
            MathEngineBridge.nativeSessionEval(requireOpenHandle(), sessionId, expr)
        )

    @Synchronized
    fun exportSnapshots(sessionId: Int): String? =
        MathEngineBridge.nativeSessionExportSnapshots(requireOpenHandle(), sessionId)

    @Synchronized
    fun restoreSession(snapshots: String): Int =
        MathEngineBridge.nativeSessionRestore(requireOpenHandle(), snapshots)

    @Synchronized
    fun compileExt(mathSource: String): ByteArray? =
        MathEngineBridge.nativeCompileExt(requireOpenHandle(), mathSource)

    @Synchronized
    fun loadMathc(bytes: ByteArray): Boolean =
        MathEngineBridge.nativeLoadMathc(requireOpenHandle(), bytes)

    @Synchronized
    fun setModule(name: String, enabled: Boolean): Boolean =
        MathEngineBridge.nativeSetModule(requireOpenHandle(), name, enabled)

    @Synchronized
    fun simplify(expr: String): String? =
        MathEngineBridge.nativeSymSimplify(requireOpenHandle(), expr)

    @Synchronized
    fun diff(varName: String, expr: String): String? =
        MathEngineBridge.nativeSymDiff(requireOpenHandle(), varName, expr)

    @Synchronized
    fun integ(varName: String, expr: String): String? =
        MathEngineBridge.nativeSymInteg(requireOpenHandle(), varName, expr)

    @Synchronized
    fun solve(varName: String, eq: String): String? =
        MathEngineBridge.nativeSymSolve(requireOpenHandle(), varName, eq)

    @Synchronized
    fun compactScratch(): Int =
        MathEngineBridge.nativeCompactScratch(requireOpenHandle())

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            MathEngineBridge.nativeDestroy(handle)
            handle = 0L
        }
    }
}
