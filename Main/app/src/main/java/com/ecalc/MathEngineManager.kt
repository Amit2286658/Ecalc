package com.ecalc

import com.mathengine.MathEngineBridge
import java.util.concurrent.Executors

/**
 * App-side wrapper for the CMath engine (Cmath_engine_project).
 *
 * One persistent engine handle lives for the whole app lifetime. Evaluation
 * uses the stateless **eng_eval** API — no sessions — which is perfect for a
 * calculator workflow where each expression is self-contained.
 */
object MathEngineManager {

    /** Result of an evaluation, mirroring the engine's wire format. */
    data class EvalOutcome(
        val ok: Boolean,
        val value: String,
        val type: String,
        val unit: String,
        val error: String?
    )

    private var engineHandle: Long = 0
    private val worker = Executors.newSingleThreadExecutor()

    /** Boots the native engine (idempotent, thread-safe).
     *  eng_create allocates ~2 MB on the heap — done once at startup. */
    @Synchronized
    fun ensureEngine(): Boolean {
        if (engineHandle != 0L) return true
        return try {
            engineHandle = MathEngineBridge.nativeCreate()
            engineHandle != 0L
        } catch (_: Throwable) { false }
    }

    /** Destroy the engine when the process exits. */
    @Synchronized
    fun shutdown() {
        if (engineHandle != 0L) {
            MathEngineBridge.nativeDestroy(engineHandle)
            engineHandle = 0L
        }
        worker.shutdown()
    }

    /** Translates the calculator's display expression into engine dialect.
     *  Stateless eval via eng_eval — no sessions needed. */
    private fun toEngineSyntax(displayExpr: String): String {
        var e = displayExpr
            .replace(" x ", " * ").replace("( x ", "( * ").replace("^ x ", "^ * ")
            .replace("÷", "/").replace("×", "*")
            .replace("EE", "E")
        // Balance unmatched opening parens
        var depth = 0
        for (ch in e) { when (ch) { '(' -> depth++; ')' -> if (depth > 0) depth-- } }
        if (depth > 0) e += ")".repeat(depth)
        return e.trim()
    }

    /** Stateless single-shot evaluation via eng_eval.
     *  No session — each call compiles and runs independently. */
    fun evaluate(displayExpr: String, onDone: (EvalOutcome) -> Unit) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(EvalOutcome(false, "0.0", "", "none", "Engine init failed"))
                return@execute
            }
            val engineExpr = toEngineSyntax(displayExpr)
            val wire = try {
                MathEngineBridge.nativeEval(engineHandle, engineExpr)
            } catch (t: Throwable) { null }

            if (wire == null) {
                onDone(EvalOutcome(false, "0.0", "", "none", "Engine call failed"))
                return@execute
            }

            val res = MathEngineBridge.EvalResult.parseWire(wire)
            if (res.ok) {
                onDone(EvalOutcome(true, res.value, res.type, res.unit, null))
            } else {
                onDone(EvalOutcome(false, "0.0", "", "none", res.error))
            }
        }
    }
}
