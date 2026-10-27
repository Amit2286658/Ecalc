package com.ecalc

import com.mathengine.MathEngineBridge
import java.util.concurrent.Executors

/**
 * App-side wrapper for the CMath engine (Cmath_engine_project).
 *
 * One persistent engine handle lives for the whole app lifetime. Supports:
 * - Stateless single-shot evaluation (`eng_eval`) for the calculator keypad
 * - Stateful multi-session evaluation (`eng_session_open`, `eng_session_eval`,
 *   `eng_session_reset`, `eng_session_close`, MSP1 snapshot export/restore)
 * - Native `.math` extension compilation & `.mathc` runtime linking
 *   (`user_compile_ext` + `user_load_mathc`)
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
    private val loadedExtensionSources = mutableSetOf<String>()

    /** Default `.math` extension module compiled and linked into the engine at boot
     *  so `asec`, `acosec`, `acot`, and `nthroot` exist as native `.math` functions
     *  in both calculator and session modes. */
    private val BUILTIN_USER_TRIG_EXT =
        "[user_trig]\n" +
        "<[trig]\n\n" +
        "asec(x: real) = acos(1 / x);\n" +
        "acosec(x: real) = asin(1 / x);\n" +
        "acot(x: real) = atan(1 / x);\n" +
        "nthroot(x: real, n: real) = root(x, n);\n"

    /** Boots the native engine (idempotent, thread-safe) and links the default
     *  `[user_trig]` `.math` extension. */
    @Synchronized
    fun ensureEngine(): Boolean {
        if (engineHandle != 0L) return true
        return try {
            engineHandle = MathEngineBridge.nativeCreate()
            if (engineHandle != 0L) {
                compileAndLoadSync(BUILTIN_USER_TRIG_EXT)
                true
            } else {
                false
            }
        } catch (_: Throwable) { false }
    }

    private fun normalizeExtensionSource(raw: String): String {
        val sb = StringBuilder()
        var parenDepth = 0
        var braceDepth = 0
        var brackDepth = 0
        for (rawLine in raw.lines()) {
            val commentIdx = rawLine.indexOf('#')
            val codePart = (if (commentIdx >= 0) rawLine.substring(0, commentIdx) else rawLine).trimEnd()
            val commentPart = if (commentIdx >= 0) rawLine.substring(commentIdx) else ""
            val trimmedCode = codePart.trim()
            if (trimmedCode.isEmpty()) {
                sb.append(rawLine).append("\n")
                continue
            }
            for (ch in trimmedCode) {
                when (ch) {
                    '(' -> parenDepth++
                    ')' -> if (parenDepth > 0) parenDepth--
                    '{' -> braceDepth++
                    '}' -> if (braceDepth > 0) braceDepth--
                    '[' -> brackDepth++
                    ']' -> if (brackDepth > 0) brackDepth--
                }
            }
            val isHeaderOrDep = (trimmedCode.startsWith("[") && trimmedCode.endsWith("]")) ||
                                (trimmedCode.startsWith("<[") && trimmedCode.endsWith("]"))
            val needsSemicolon = !isHeaderOrDep &&
                parenDepth == 0 && braceDepth == 0 && brackDepth == 0 &&
                !trimmedCode.endsWith(";") && !trimmedCode.endsWith("{") && !trimmedCode.endsWith(",")
            sb.append(codePart)
            if (needsSemicolon) sb.append(";")
            if (commentPart.isNotEmpty()) sb.append(" ").append(commentPart)
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    @Synchronized
    private fun compileAndLoadSync(mathSource: String): Pair<Boolean, String> {
        if (engineHandle == 0L) return false to "Engine not initialized"
        val trimmed = normalizeExtensionSource(mathSource)
        if (trimmed.isEmpty()) return false to "Empty .math source"
        return try {
            val mathcBytes = MathEngineBridge.nativeCompileExt(engineHandle, trimmed)
            if (mathcBytes == null || mathcBytes.isEmpty()) {
                val err = MathEngineBridge.nativeLastError(engineHandle) ?: "Compilation failed"
                false to err
            } else {
                val loaded = MathEngineBridge.nativeLoadMathc(engineHandle, mathcBytes)
                if (loaded) {
                    // Activate any [module_name] headers declared in the source
                    Regex("(?m)^\\s*\\[([a-zA-Z0-9_]+)\\]").findAll(trimmed).forEach { m ->
                        val modName = m.groupValues[1]
                        MathEngineBridge.nativeSetModule(engineHandle, modName, true)
                    }
                    loadedExtensionSources.add(trimmed)
                    true to "Compiled ${mathcBytes.size} bytes (.mathc) and linked into active engine."
                } else {
                    val err = MathEngineBridge.nativeLastError(engineHandle) ?: "Failed to link .mathc"
                    false to err
                }
            }
        } catch (t: Throwable) {
            false to (t.message ?: "Native exception")
        }
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

    private fun findMatchingParen(s: String, openIdx: Int): Int {
        var depth = 0
        for (i in openIdx until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return s.length - 1
    }

    private fun rewriteFunctionCalls(
        expr: String,
        fnNames: List<String>,
        replacer: (fn: String, inner: String) -> String
    ): String {
        val pattern = Regex("\\b(" + fnNames.joinToString("|") + ")\\(")
        var cur = expr
        var searchFrom = 0
        while (true) {
            val match = pattern.find(cur, searchFrom) ?: break
            val fn = match.groupValues[1]
            val openIdx = match.range.last
            val closeIdx = findMatchingParen(cur, openIdx)
            val inner = cur.substring(openIdx + 1, closeIdx)
            val innerTransformed = rewriteFunctionCalls(inner, fnNames, replacer)
            val replacement = replacer(fn, innerTransformed)
            cur = cur.substring(0, match.range.first) + replacement + cur.substring(closeIdx + 1)
            searchFrom = match.range.first + replacement.length
        }
        return cur
    }

    private fun replaceInfixNthRoot(expr: String): String {
        var cur = expr
        while (cur.contains("√(")) {
            val idx = cur.indexOf("√(")
            if (idx == 0) {
                cur = "__SQRT__(" + cur.substring(2)
                continue
            }
            val endN = idx
            var startN = endN - 1
            if (cur[startN] == ')') {
                var depth = 0
                while (startN >= 0) {
                    if (cur[startN] == ')') depth++
                    else if (cur[startN] == '(') {
                        depth--
                        if (depth == 0) break
                    }
                    startN--
                }
                if (startN < 0) startN = 0
            } else {
                while (startN >= 0 && (cur[startN].isDigit() || cur[startN] == '.')) {
                    startN--
                }
                startN++
            }
            if (startN == endN) {
                cur = cur.substring(0, idx) + "__SQRT__(" + cur.substring(idx + 2)
                continue
            }
            val nStr = cur.substring(startN, endN)
            val openP = idx + 1
            val closeP = findMatchingParen(cur, openP)
            val xStr = cur.substring(openP + 1, closeP)
            val replacement = "root($xStr,$nStr)"
            cur = cur.substring(0, startN) + replacement + cur.substring(closeP + 1)
        }
        return cur.replace("__SQRT__(", "√(")
    }

    /** Translates the calculator's display expression into engine dialect. */
    fun toEngineSyntax(
        displayExpr: String,
        isDegreeMode: Boolean = true,
        lastAnswer: String = "0.0"
    ): String {
        var e = displayExpr
            .replace(" x ", " * ").replace("( x ", "( * ").replace("^ x ", "^ * ")
            .replace("÷", "/").replace("×", "*")
            .replace("EE", "E")
            .replace("ⁿ√(", "root(")
            .replace("log₂(", "log2(")

        e = Regex("\\bans\\b").replace(e, "($lastAnswer)")

        // Balance unmatched opening parens first so every function call has a closing ')'
        var depth = 0
        for (ch in e) {
            when (ch) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
        }
        if (depth > 0) e += ")".repeat(depth)

        // Support infix n√(x) -> root(x, n)
        e = replaceInfixNthRoot(e)

        // Normalize cosec / acosec aliases
        e = Regex("\\bacosec\\(").replace(e, "acsc(")
        e = Regex("\\bcosec\\(").replace(e, "csc(")

        // Expand inverse reciprocal trig functions:
        // asec(x) -> acos(1/(x)), acsc(x) -> asin(1/(x)), acot(x) -> atan(1/(x))
        val invRecipMap = mapOf("asec" to "acos", "acsc" to "asin", "acot" to "atan")
        e = rewriteFunctionCalls(e, listOf("asec", "acsc", "acot")) { fn, inner ->
            val baseFn = invRecipMap[fn] ?: fn
            "$baseFn(1/($inner))"
        }

        // Apply active Degree / Radian mode to trig and inverse trig functions
        if (isDegreeMode) {
            e = rewriteFunctionCalls(
                e,
                listOf("sin", "cos", "tan", "sec", "csc", "cot", "asin", "acos", "atan")
            ) { fn, inner ->
                if (fn.startsWith("a")) {
                    "rad2deg($fn($inner))"
                } else {
                    "$fn(deg2rad($inner))"
                }
            }
        }

        // Standard calculator log(x) is base-10 log10(x)
        e = Regex("\\blog\\(").replace(e, "log10(")

        return e.trim()
    }

    /** Stateless single-shot evaluation via eng_eval. */
    fun evaluate(
        displayExpr: String,
        isDegreeMode: Boolean = true,
        lastAnswer: String = "0.0",
        onDone: (EvalOutcome) -> Unit
    ) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(EvalOutcome(false, "0.0", "", "none", "Engine init failed"))
                return@execute
            }
            val engineExpr = toEngineSyntax(displayExpr, isDegreeMode, lastAnswer)
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

    // =========================================================================
    //  Stateful Sessions & .math Extension Compiler API
    // =========================================================================

    fun compileAndLoadExtension(
        mathSource: String,
        onDone: (ok: Boolean, message: String) -> Unit
    ) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(false, "Engine init failed")
                return@execute
            }
            val (ok, msg) = compileAndLoadSync(mathSource)
            onDone(ok, msg)
        }
    }

    fun openSession(onDone: (sessionId: Int) -> Unit) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(-1)
                return@execute
            }
            val sid = try {
                MathEngineBridge.nativeSessionOpen(engineHandle)
            } catch (_: Throwable) { -1 }
            onDone(sid)
        }
    }

    fun resetSession(sessionId: Int, onDone: (ok: Boolean) -> Unit) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(false)
                return@execute
            }
            val ok = try {
                MathEngineBridge.nativeSessionReset(engineHandle, sessionId)
            } catch (_: Throwable) { false }
            onDone(ok)
        }
    }

    fun closeSession(sessionId: Int, onDone: (ok: Boolean) -> Unit) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(false)
                return@execute
            }
            val ok = try {
                MathEngineBridge.nativeSessionClose(engineHandle, sessionId)
            } catch (_: Throwable) { false }
            onDone(ok)
        }
    }

    fun exportSessionSnapshots(sessionId: Int, onDone: (snapshots: String) -> Unit) {
        worker.execute {
            if (!ensureEngine()) {
                onDone("")
                return@execute
            }
            val snaps = try {
                MathEngineBridge.nativeSessionExportSnapshots(engineHandle, sessionId) ?: ""
            } catch (_: Throwable) { "" }
            onDone(snaps)
        }
    }

    fun restoreSession(snapshots: String, onDone: (newSessionId: Int, error: String?) -> Unit) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(-1, "Engine init failed")
                return@execute
            }
            val sid = try {
                MathEngineBridge.nativeSessionRestore(engineHandle, snapshots)
            } catch (_: Throwable) { -1 }
            val err = if (sid <= 0) {
                try { MathEngineBridge.nativeLastError(engineHandle) } catch (_: Throwable) { null }
            } else null
            onDone(sid, err)
        }
    }

    /** Parse the latest MSP1 snapshot for `sessionId` to display bound variables. */
    private fun parseSessionVariablesSync(sessionId: Int): String {
        return try {
            val count = MathEngineBridge.nativeSessionSnapshotCount(engineHandle, sessionId)
            if (count <= 0) return ""
            val latest = MathEngineBridge.nativeSessionSnapshotAt(engineHandle, sessionId, count - 1) ?: return ""
            // Format: MSP1|seq:...|prev:...|hash:...|vars:N|name:type:unit:ltype:lunit=val;...|res:type:unit=val
            val varsMarker = "|vars:"
            val resMarker = "|res:"
            val vStart = latest.indexOf(varsMarker)
            val rStart = latest.indexOf(resMarker)
            if (vStart < 0 || rStart <= vStart) return ""
            val varsSection = latest.substring(vStart + varsMarker.length, rStart)
            val pipeIdx = varsSection.indexOf('|')
            if (pipeIdx < 0) return ""
            val payload = varsSection.substring(pipeIdx + 1)
            if (payload.isBlank()) return ""
            payload.split(";").mapNotNull { entry ->
                val eqIdx = entry.indexOf('=')
                if (eqIdx <= 0) null
                else {
                    val meta = entry.substring(0, eqIdx).split(":")
                    val vVal = entry.substring(eqIdx + 1)
                    val vName = meta.getOrNull(0) ?: "?"
                    val vType = meta.getOrNull(1) ?: "real"
                    val vUnit = meta.getOrNull(2) ?: "none"
                    val unitStr = if (vUnit != "none" && vUnit.isNotEmpty()) " [$vUnit]" else ""
                    "$vName : $vType$unitStr = $vVal"
                }
            }.joinToString("\n")
        } catch (_: Throwable) { "" }
    }

    fun inspectSessionVariables(sessionId: Int, onDone: (summary: String) -> Unit) {
        worker.execute {
            if (!ensureEngine()) {
                onDone("")
                return@execute
            }
            onDone(parseSessionVariablesSync(sessionId))
        }
    }

    fun evalInSession(
        sessionId: Int,
        expr: String,
        isDegreeMode: Boolean = false,
        onDone: (outcome: EvalOutcome, varsSummary: String) -> Unit
    ) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(EvalOutcome(false, "0.0", "", "none", "Engine init failed"), "")
                return@execute
            }
            val wire = try {
                MathEngineBridge.nativeSessionEval(engineHandle, sessionId, expr.trim())
            } catch (_: Throwable) { null }
            val outcome = if (wire == null) {
                EvalOutcome(false, "0.0", "", "none", "Session call failed")
            } else {
                val res = MathEngineBridge.EvalResult.parseWire(wire)
                if (res.ok) EvalOutcome(true, res.value, res.type, res.unit, null)
                else EvalOutcome(false, "0.0", "", "none", res.error)
            }
            val vars = parseSessionVariablesSync(sessionId)
            onDone(outcome, vars)
        }
    }

    /** Executes a multi-line `.math` script inside `sessionId`. If the script
     *  contains a `[module]` header, it first compiles and links the extension
     *  into the engine, and then evaluates any executable statements. */
    fun runProgramScript(
        sessionId: Int,
        scriptSource: String,
        onDone: (results: List<Pair<String, EvalOutcome>>, varsSummary: String) -> Unit
    ) {
        worker.execute {
            if (!ensureEngine()) {
                onDone(emptyList(), "")
                return@execute
            }
            val results = mutableListOf<Pair<String, EvalOutcome>>()
            val trimmed = scriptSource.trim()

            // Check if this file is a [module] extension definition
            val hasModuleHeader = Regex("(?m)^\\s*\\[[a-zA-Z0-9_]+\\]").containsMatchIn(trimmed)
            if (hasModuleHeader) {
                val (ok, msg) = compileAndLoadSync(trimmed)
                results.add(
                    "[Module Extension]" to if (ok) {
                        EvalOutcome(true, msg, "mathc", "none", null)
                    } else {
                        EvalOutcome(false, "0.0", "error", "none", msg)
                    }
                )
            } else {
                // Split into statements by newline (ignoring # comments and blank lines)
                val statements = mutableListOf<String>()
                var currentStmt = StringBuilder()
                var braceDepth = 0
                var parenDepth = 0
                var brackDepth = 0

                for (rawLine in trimmed.lines()) {
                    val line = rawLine.substringBefore("#").trim()
                    if (line.isEmpty()) continue
                    if (currentStmt.isNotEmpty()) currentStmt.append(" ")
                    currentStmt.append(line)
                    for (ch in line) {
                        when (ch) {
                            '{' -> braceDepth++
                            '}' -> if (braceDepth > 0) braceDepth--
                            '(' -> parenDepth++
                            ')' -> if (parenDepth > 0) parenDepth--
                            '[' -> brackDepth++
                            ']' -> if (brackDepth > 0) brackDepth--
                        }
                    }
                    if (braceDepth == 0 && parenDepth == 0 && brackDepth == 0) {
                        statements.add(currentStmt.toString().trim())
                        currentStmt = StringBuilder()
                    }
                }
                if (currentStmt.isNotEmpty()) {
                    statements.add(currentStmt.toString().trim())
                }

                for (stmt in statements) {
                    val wire = try {
                        MathEngineBridge.nativeSessionEval(engineHandle, sessionId, stmt)
                    } catch (_: Throwable) { null }
                    val outcome = if (wire == null) {
                        EvalOutcome(false, "0.0", "", "none", "Session eval failed")
                    } else {
                        val res = MathEngineBridge.EvalResult.parseWire(wire)
                        if (res.ok) EvalOutcome(true, res.value, res.type, res.unit, null)
                        else EvalOutcome(false, "0.0", "", "none", res.error)
                    }
                    results.add(stmt to outcome)
                }
            }

            val vars = parseSessionVariablesSync(sessionId)
            onDone(results, vars)
        }
    }
}
