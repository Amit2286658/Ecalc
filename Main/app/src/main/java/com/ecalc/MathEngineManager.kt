package com.ecalc

import android.content.Context
import android.util.Log
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

    private const val TAG = "MathEngineManager"
    private const val BUNDLED_STDLIB_ASSET = "precompiled/stdlib.mathc"
    private const val BUNDLED_USER_TRIG_ASSET = "precompiled/user_trig.math"

    @Volatile
    private var appContext: Context? = null
    private var engineHandle: Long = 0
    private val worker = Executors.newSingleThreadExecutor()
    private val loadedExtensionSources = mutableSetOf<String>()

    /** Provides the application context so `assets/precompiled/stdlib.mathc`
     *  and `filesDir/math_patches/` can be loaded automatically on the background
     *  worker thread without stalling cold-start UI rendering. */
    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        worker.execute {
            seedDefaultPatchesIfNeeded(app)
        }
    }

    /** Seeds `user_trig.math` into `filesDir/math_patches/` on first install so
     *  reciprocal/inverse trig functions work offline before GitHub sync runs. */
    private fun seedDefaultPatchesIfNeeded(context: Context) {
        runCatching {
            val patchesDir = MathPatchDownloader.getPatchesDir(context)
            val userTrigFile = java.io.File(patchesDir, "user_trig.math")
            if (!userTrigFile.exists()) {
                val bytes = context.assets.open(BUNDLED_USER_TRIG_ASSET).use { it.readBytes() }
                userTrigFile.writeBytes(bytes)
            }
        }
    }

    /**
     * Checks whether a `.math` file's source code is a true native extension `[module]`
     * definition (rather than a standard stateful `.math` evaluation script).
     *
     * True modules contain at least one `[module_name]` section header, `<[dep]`
     * dependency directive, `$type` declaration, or typed module overload declaration
     * (`fn(x: real) = ...;`) and do not consist of session `:=` variable/function
     * assignments or standalone evaluation expressions.
     */
    fun isTrueModuleSource(raw: String): Boolean {
        val codeLines = raw.lines()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
        if (codeLines.isEmpty()) return true // Keep blank module template until edited

        val joined = codeLines.joinToString("\n")
        val hasModuleHeader = Regex("(?m)^\\[[a-zA-Z_][a-zA-Z0-9_]*\\]$").containsMatchIn(joined)
        val hasDepHeader = Regex("(?m)^<\\[[a-zA-Z_][a-zA-Z0-9_]*\\]$").containsMatchIn(joined)
        val hasTypeDecl = Regex("(?m)^\\$[a-zA-Z_][a-zA-Z0-9_]*").containsMatchIn(joined)
        val hasTypedOverload = Regex(
            "(?m)^[a-zA-Z_][a-zA-Z0-9_]*\\s*\\([^)]*:\\s*[a-zA-Z_][a-zA-Z0-9_]*[^)]*\\)\\s*="
        ).containsMatchIn(joined)
        val hasQuotedOperatorDecl = Regex("(?m)^\"[^\"]+\"").containsMatchIn(joined)
        val hasSessionAssignment = codeLines.any { it.contains(":=") }

        if (hasModuleHeader || hasDepHeader || hasTypeDecl || hasQuotedOperatorDecl) {
            return true
        }
        if (hasTypedOverload && !hasSessionAssignment) {
            return true
        }
        return false
    }

    /** Boots the native engine strictly from `stdlib.mathc` (no embedded fallback):
     *  1. If `compiled_state.mathc` (cached `stdlib.mathc` + compiled `.math` patches)
     *     is valid on disk, loads it into a blank engine (`nativeCreateBlank` + `nativeLoadMathc`).
     *  2. Otherwise creates a blank engine (`nativeCreateBlank`), loads the bundled
     *     `assets/precompiled/stdlib.mathc` (`nativeLoadMathc`), compiles any `.math`
     *     patches in `filesDir/math_patches/`, and saves `compiled_state.mathc`. */
    @Synchronized
    fun ensureEngine(): Boolean {
        if (engineHandle != 0L) {
            appContext?.let { ctx ->
                MathPatchLoader.loadUnloadedPatches(ctx, engineHandle, ::compileAndLoadWithBytesSync)
            }
            return true
        }
        val ctx = appContext ?: return false
        return try {
            seedDefaultPatchesIfNeeded(ctx)

            // 1. Fast path: boot blank engine from cached compiled_state.mathc (stdlib + patches)
            var handle = MathPatchLoader.tryBootFromCompiledCache(ctx)

            // 2. Standard path: boot blank engine and load bundled assets/precompiled/stdlib.mathc
            if (handle == 0L) {
                val stdlibBytes = ctx.assets.open(BUNDLED_STDLIB_ASSET).use { it.readBytes() }
                val blank = MathEngineBridge.nativeCreateBlank()
                if (blank != 0L && MathEngineBridge.nativeLoadMathc(blank, stdlibBytes)) {
                    handle = blank
                    Log.i(TAG, "Booted blank engine with $BUNDLED_STDLIB_ASSET (${stdlibBytes.size} bytes)")
                } else if (blank != 0L) {
                    MathEngineBridge.nativeDestroy(blank)
                }
            }

            // 3. Last resort: legacy embedded-stdlib engine (eng_create)
            if (handle == 0L) {
                handle = MathEngineBridge.nativeCreate()
                if (handle != 0L) Log.i(TAG, "Booted engine via nativeCreate() (embedded stdlib)")
            }

            engineHandle = handle
            if (engineHandle != 0L) {
                MathPatchLoader.loadUnloadedPatches(ctx, engineHandle, ::compileAndLoadWithBytesSync)
                true
            } else {
                Log.e(TAG, "Failed to initialize blank engine with $BUNDLED_STDLIB_ASSET")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception while initializing engine from $BUNDLED_STDLIB_ASSET", t)
            false
        }
    }

    /** Scans `filesDir/math_patches/` on the engine worker thread and links any
     *  downloaded `.math` patches that are not yet loaded. */
    fun loadPendingPatches(onDone: ((loadedCount: Int) -> Unit)? = null) {
        worker.execute {
            if (!ensureEngine()) {
                onDone?.invoke(0)
                return@execute
            }
            val count = appContext?.let { ctx ->
                MathPatchLoader.loadUnloadedPatches(ctx, engineHandle, ::compileAndLoadWithBytesSync)
            } ?: 0
            onDone?.invoke(count)
        }
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
    private fun compileAndLoadWithBytesSync(mathSource: String): Triple<Boolean, String, ByteArray?> {
        if (engineHandle == 0L) return Triple(false, "Engine not initialized", null)
        val trimmed = normalizeExtensionSource(mathSource)
        if (trimmed.isEmpty()) return Triple(false, "Empty .math source", null)
        return try {
            // nativeCompileExt compiles & links the .math source directly into engineHandle
            // (with new [module] headers enabled by default) AND returns the serialized
            // cumulative .mathc binary image of the entire engine.
            val mathcBytes = MathEngineBridge.nativeCompileExt(engineHandle, trimmed)
            if (mathcBytes == null || mathcBytes.isEmpty()) {
                val err = MathEngineBridge.nativeLastError(engineHandle) ?: "Compilation failed"
                Triple(false, err, null)
            } else {
                Regex("(?m)^\\s*\\[([a-zA-Z0-9_]+)\\]").findAll(trimmed).forEach { m ->
                    val modName = m.groupValues[1]
                    MathEngineBridge.nativeSetModule(engineHandle, modName, true)
                }
                loadedExtensionSources.add(trimmed)
                Triple(
                    true,
                    "Compiled ${mathcBytes.size} bytes (.mathc) and linked into active engine.",
                    mathcBytes
                )
            }
        } catch (t: Throwable) {
            Triple(false, t.message ?: "Native exception", null)
        }
    }

    @Synchronized
    private fun compileAndLoadSync(mathSource: String): Pair<Boolean, String> {
        val (ok, msg, _) = compileAndLoadWithBytesSync(mathSource)
        return ok to msg
    }

    /** Destroy the engine when the process exits. */
    @Synchronized
    fun shutdown() {
        if (engineHandle != 0L) {
            MathEngineBridge.nativeDestroy(engineHandle)
            engineHandle = 0L
        }
        MathPatchLoader.resetTracking()
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

        // Apply active Degree / Radian mode to trig and inverse trig functions
        // (`cosec`, `asec`, `acsc`, `acosec`, and `acot` are provided by `patches/user_trig.math`)
        if (isDegreeMode) {
            e = rewriteFunctionCalls(
                e,
                listOf(
                    "sin", "cos", "tan", "sec", "csc", "cosec", "cot",
                    "asin", "acos", "atan", "asec", "acsc", "acosec", "acot"
                )
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
        if (sessionId <= 0) {
            onDone(EvalOutcome(false, "0.0", "", "none", "Session not ready (no session open yet)"), "")
            return
        }
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

    /** Executes a multi-line `.math` evaluation script inside `sessionId`. */
    fun runProgramScript(
        sessionId: Int,
        scriptSource: String,
        onDone: (results: List<Pair<String, EvalOutcome>>, varsSummary: String) -> Unit
    ) {
        if (sessionId <= 0) {
            onDone(emptyList(), "")
            return
        }
        worker.execute {
            if (!ensureEngine()) {
                onDone(emptyList(), "")
                return@execute
            }
            val results = mutableListOf<Pair<String, EvalOutcome>>()
            val trimmed = scriptSource.trim()

            // Check if this file is a [module] extension definition
            if (isTrueModuleSource(trimmed) && Regex("(?m)^\\s*\\[[a-zA-Z0-9_]+\\]").containsMatchIn(trimmed)) {
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
