package com.ecalc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Synchronous convenience bridge to [MathEngineManager] (which hosts the
 * C11 Math Engine (MEP) and real-time [CurrencyConverter] externs).
 */
object NativeMathEngine {

    @Volatile
    private var lastErr: String = ""

    fun isReady(): Boolean = MathEngineManager.ensureEngine()

    fun evalExpression(expr: String): String {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference("0.0")
        MathEngineManager.evaluate(expr) { outcome ->
            if (outcome.ok) {
                lastErr = ""
                resultRef.set(outcome.value)
            } else {
                lastErr = outcome.error ?: "Evaluation failed"
                resultRef.set("Error: $lastErr")
            }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return resultRef.get()
    }

    fun lastError(): String = lastErr
}
