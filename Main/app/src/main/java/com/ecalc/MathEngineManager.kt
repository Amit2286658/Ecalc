package com.ecalc

import android.content.Context
import android.util.Log
import com.mathengine.MathEngineBridge
import java.util.concurrent.Executors

/**
 * App-side manager for the C11 Math Engine (MEP) under the unified,
 * session-free top-to-bottom executable module architecture.
 *
 * One persistent engine handle lives for the whole app lifetime. Supports:
 * - Stateless single-shot evaluation (`eng_eval`, isolated in `[eval]`) for the calculator keypad
 * - Top-to-bottom executable `.math` modules (`eng_mod_eval`, `eng_mod_reset`,
 *   `eng_mod_format_vars`, `eng_mod_export_source`) where every `.math` file is
 *   executable on its own with no entry point required, supporting multi-file
 *   `<[dep]` projects, top-level expressions, and explicit variable privacy
 *   (`pub`/`priv`), 3-tier lexical scoping, and mutability (`:=`/`mut` vs `=`/`const`).
 * - Native `.math` extension compilation & `.mathc` binary caching
 *   (`user_compile_ext` + `user_load_mathc`).
 * - Host-bridge externs (`@currency_convert`, `@fx_rate`, etc.) backed by
 *   [CurrencyConverter] for real-time currency conversion.
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

    @Volatile
    private var appContext: Context? = null
    private var engineHandle: Long = 0
    private val worker = Executors.newSingleThreadExecutor()
    private val studioModuleSourcesByName = linkedMapOf<String, String>()
    private val customExternCallbacks = linkedMapOf<String, MathEngineBridge.ExternCallback>()

    /** Provides the application context so `assets/precompiled/stdlib.mathc`,
     *  remotely fetched patches in `filesDir/math_patches/`, and [CurrencyConverter]
     *  live rates can be initialized without stalling cold-start UI rendering. */
    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        CurrencyConverter.initialize(app)
    }

    /** Registers all built-in host externs ([CurrencyConverter]) and any custom app externs on [handle]. */
    @Synchronized
    private fun registerHostExternsOnHandle(handle: Long) {
        if (handle == 0L) return
        CurrencyConverter.registerWithEngine(handle)
        for ((extName, cb) in customExternCallbacks) {
            runCatching { MathEngineBridge.nativeRegisterExtern(handle, extName, cb) }
        }
    }

    /** Registers a custom host extern callback (`@<name>(...)`) on the engine and persists it across rebuilds. */
    @Synchronized
    fun registerExtern(name: String, callback: MathEngineBridge.ExternCallback): Boolean {
        customExternCallbacks[name] = callback
        if (engineHandle != 0L) {
            return runCatching {
                MathEngineBridge.nativeRegisterExtern(engineHandle, name, callback)
            }.getOrDefault(false)
        }
        return true
    }

    private fun stripCommentOutsideQuotes(line: String): String {
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '\\' && inQuotes && i + 1 < line.length) {
                i += 2
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (ch == '#' && !inQuotes) {
                return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /**
     * Derives the canonical `[module]` name for a `.math` file:
     * - If [rawSource] declares an explicit `[mod_name]` header, returns that name.
     * - Otherwise derives a clean identifier from [fileName] (e.g. `physics_sim.math` -> `physics_sim`).
     */
    fun deriveModuleName(fileName: String?, rawSource: String): String {
        val modRegex = Regex("^\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
        for (rawLine in rawSource.lineSequence()) {
            val trimmed = stripCommentOutsideQuotes(rawLine).trim()
            if (trimmed.isEmpty()) continue
            val m = modRegex.matchEntire(trimmed)
            if (m != null) {
                return m.groupValues[1]
            }
        }
        val stem = (fileName ?: "workspace")
            .removeSuffix(".math")
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .trim('_')
        if (stem.isEmpty()) return "workspace"
        return if (stem[0].isDigit()) "mod_$stem" else stem
    }

    /** Extracts all `<[dep]` module names referenced in [rawSource]. */
    fun extractModuleDependencies(rawSource: String): List<String> {
        val deps = LinkedHashSet<String>()
        val depRegex = Regex("^<\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
        for (rawLine in rawSource.lineSequence()) {
            val trimmed = stripCommentOutsideQuotes(rawLine).trim()
            if (trimmed.isEmpty()) continue
            depRegex.matchEntire(trimmed)?.let { m ->
                deps.add(m.groupValues[1])
            }
        }
        return deps.toList()
    }

    /** Creates a blank engine loaded strictly with `assets/precompiled/stdlib.mathc` and registers host externs. */
    private fun createFreshStdlibEngine(ctx: Context): Long {
        val stdlibBytes = ctx.assets.open(BUNDLED_STDLIB_ASSET).use { it.readBytes() }
        val handle = MathEngineBridge.nativeCreateBlank().takeIf { it != 0L }
            ?: MathEngineBridge.nativeCreate().takeIf { it != 0L }
        if (handle == null) {
            Log.e(TAG, "Engine allocation failed (nativeCreateBlank + nativeCreate both returned 0)")
            return 0L
        }
        if (MathEngineBridge.nativeLoadMathc(handle, stdlibBytes)) {
            registerHostExternsOnHandle(handle)
            Log.i(TAG, "Booted blank engine with $BUNDLED_STDLIB_ASSET (${stdlibBytes.size} bytes)")
            return handle
        }
        MathEngineBridge.nativeDestroy(handle)
        Log.e(TAG, "Failed to load $BUNDLED_STDLIB_ASSET into a blank engine")
        return 0L
    }

    /**
     * Rebuilds the native engine from `stdlib.mathc` + all current `.math` patches in
     * `filesDir/math_patches/` + any active `.math Studio` user modules.
     */
    @Synchronized
    private fun rebuildEnginePreservingStateSync(
        ctx: Context,
        onStatus: ((String) -> Unit)? = null
    ): Boolean {
        val oldHandle = engineHandle
        MathPatchDownloader.invalidateCompiledCache(MathPatchDownloader.getPatchesDir(ctx))
        val newHandle = createFreshStdlibEngine(ctx)
        if (newHandle == 0L) {
            onStatus?.invoke("Engine rebuild failed: could not load $BUNDLED_STDLIB_ASSET")
            return false
        }

        if (oldHandle != 0L) {
            runCatching { MathEngineBridge.nativeDestroy(oldHandle) }
        }
        engineHandle = newHandle
        registerHostExternsOnHandle(engineHandle)
        MathPatchLoader.resetTracking()

        MathPatchLoader.loadUnloadedPatches(
            ctx,
            engineHandle,
            ::compileAndLoadPatchWithBytesSync,
            onStatus
        )

        // Re-evaluate any custom modules active in .math Studio
        for ((modName, studioSrc) in studioModuleSourcesByName) {
            val normalized = normalizeExtensionSource(studioSrc)
            if (normalized.isNotEmpty()) {
                runCatching {
                    MathEngineBridge.nativeModuleEval(engineHandle, modName, normalized)
                }
            }
        }
        return true
    }

    /** Boots the native engine strictly from `stdlib.mathc` (or cached `compiled_state.mathc`). */
    @Synchronized
    fun ensureEngine(onStatus: ((String) -> Unit)? = null): Boolean {
        val ctx = appContext ?: return false
        if (engineHandle != 0L) {
            registerHostExternsOnHandle(engineHandle)
            if (MathPatchLoader.needsEngineRebuild(ctx, engineHandle)) {
                return rebuildEnginePreservingStateSync(ctx, onStatus)
            }
            MathPatchLoader.loadUnloadedPatches(
                ctx,
                engineHandle,
                ::compileAndLoadPatchWithBytesSync,
                onStatus
            )
            return true
        }
        return try {
            val cachedHandle = MathPatchLoader.tryBootFromCompiledCache(ctx, onStatus)
            if (cachedHandle != 0L) {
                engineHandle = cachedHandle
                registerHostExternsOnHandle(engineHandle)
                if (onStatus == null) {
                    MathPatchLoader.loadUnloadedPatches(
                        ctx,
                        engineHandle,
                        ::compileAndLoadPatchWithBytesSync,
                        null
                    )
                }
                return true
            }

            val handle = createFreshStdlibEngine(ctx)
            engineHandle = handle
            if (engineHandle != 0L) {
                registerHostExternsOnHandle(engineHandle)
                MathPatchLoader.loadUnloadedPatches(
                    ctx,
                    engineHandle,
                    ::compileAndLoadPatchWithBytesSync,
                    onStatus
                )
                true
            } else {
                Log.e(TAG, "Failed to initialize blank engine with $BUNDLED_STDLIB_ASSET")
                onStatus?.invoke("Engine init failed: could not load $BUNDLED_STDLIB_ASSET")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception while initializing engine from $BUNDLED_STDLIB_ASSET", t)
            onStatus?.invoke("Engine init exception: ${t.message}")
            false
        }
    }

    /** Scans `filesDir/math_patches/` on the engine worker thread and links any unloaded `.math` patches. */
    fun loadPendingPatches(
        onDone: ((loadedCount: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ) {
        worker.execute {
            val ctx = appContext
            if (ctx == null) {
                onStatus?.invoke("Patch load skipped: app context not initialized")
                onDone?.invoke(0)
                return@execute
            }
            synchronized(this) {
                if (engineHandle == 0L) {
                    val ok = ensureEngine(onStatus = onStatus)
                    if (!ok) {
                        onStatus?.invoke("Patch load skipped: engine not ready")
                    }
                    onDone?.invoke(0)
                    return@execute
                }
                registerHostExternsOnHandle(engineHandle)
                if (MathPatchLoader.needsEngineRebuild(ctx, engineHandle)) {
                    rebuildEnginePreservingStateSync(ctx, onStatus)
                    onDone?.invoke(1)
                    return@execute
                }
                val count = MathPatchLoader.loadUnloadedPatches(
                    ctx,
                    engineHandle,
                    ::compileAndLoadPatchWithBytesSync,
                    onStatus
                )
                onDone?.invoke(count)
            }
        }
    }

    /** Runs the complete startup patch pipeline in deterministic order. */
    fun syncAndLoadPatches(
        context: Context,
        onStatus: ((String) -> Unit)? = null,
        onDone: ((downloadedCount: Int, loadedCount: Int) -> Unit)? = null
    ) {
        val app = context.applicationContext
        appContext = app
        CurrencyConverter.initialize(app)
        Thread {
            val downloaded = MathPatchDownloader.syncMissingPatches(app, onStatus)
            loadPendingPatches(
                onDone = { loaded ->
                    onDone?.invoke(downloaded, loaded)
                },
                onStatus = onStatus
            )
        }.start()
    }

    /**
     * Normalizes `.math` module source code before passing to `nativeCompileExt` or `nativeModuleEval`:
     * - Strips UTF-8 BOM and normalizes CRLF/CR line endings.
     * - Strips `#` comments only outside `"..."` string literals.
     * - Strips trailing `;` on non-expression top-level directives (`@dim`, `@unit_dim`,
     *   `$type`, and `"..." => @...`).
     * - Joins multi-line expression continuations at depth 0 so multi-line definitions stay intact.
     */
    internal fun normalizeExtensionSource(raw: String): String {
        val cleaned = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val codeLines = mutableListOf<String>()
        val syntaxBindingRegex = Regex("^(?:(?:pub|priv)\\s+)*\"[^\"]+\"\\s*=>\\s*@")
        val typeDeclRegex = Regex("^(?:(?:pub|priv)\\s+)*\\$[a-zA-Z_]")

        for (rawLine in cleaned.lines()) {
            var codePart = stripCommentOutsideQuotes(rawLine)
            val trimmed = codePart.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("@dim") ||
                trimmed.startsWith("@unit_dim") ||
                typeDeclRegex.containsMatchIn(trimmed) ||
                syntaxBindingRegex.containsMatchIn(trimmed)
            ) {
                while (codePart.endsWith(";")) {
                    codePart = codePart.dropLast(1).trimEnd()
                }
            }
            if (codePart.isNotBlank()) {
                codeLines.add(codePart)
            }
        }

        val merged = mutableListOf<String>()
        var parenDepth = 0
        var braceDepth = 0
        var brackDepth = 0
        val contEndRegex = Regex("(:=|\\+=|-=|\\*=|/=|%=|\\^=|==|!=|<=|>=|&&|\\|\\||\\|>|=|\\+|-|\\*|/|\\^|\\?|:)$")
        val contStartRegex = Regex("^(\\|>|\\?|:|else\\b|or\\b)")

        fun updateDepths(segment: String) {
            var inQuotes = false
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                if (c == '\\' && inQuotes && i + 1 < segment.length) {
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                } else if (!inQuotes) {
                    when (c) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                        '[' -> brackDepth++
                        ']' -> if (brackDepth > 0) brackDepth--
                    }
                }
                i++
            }
        }

        for (line in codeLines) {
            val stripped = line.trim()
            if (merged.isNotEmpty() && parenDepth == 0 && braceDepth == 0 && brackDepth == 0) {
                val prev = merged.last().trimEnd()
                val prevStripped = prev.trim()
                val isPrevHeaderOrDep =
                    (prevStripped.startsWith("[") && prevStripped.endsWith("]")) ||
                    (prevStripped.startsWith("<[") && prevStripped.endsWith("]"))
                val isCurHeaderOrDep =
                    (stripped.startsWith("[") && stripped.endsWith("]")) ||
                    (stripped.startsWith("<[") && stripped.endsWith("]"))

                if (!isPrevHeaderOrDep && !isCurHeaderOrDep) {
                    val prevContinues = contEndRegex.containsMatchIn(prevStripped) &&
                        !prevStripped.endsWith(";") &&
                        !typeDeclRegex.containsMatchIn(prevStripped) &&
                        !prevStripped.startsWith("@dim") &&
                        !prevStripped.startsWith("@unit_dim")
                    val curContinues = contStartRegex.containsMatchIn(stripped)
                    if (prevContinues || curContinues) {
                        merged[merged.lastIndex] = "$prev $stripped"
                        updateDepths(stripped)
                        continue
                    }
                }
            }
            merged.add(line)
            updateDepths(stripped)
        }

        return merged.joinToString("\n").trim()
    }

    @Synchronized
    private fun compileAndLoadPatchWithBytesSync(mathSource: String): Triple<Boolean, String, ByteArray?> {
        if (engineHandle == 0L) return Triple(false, "Engine not initialized", null)
        registerHostExternsOnHandle(engineHandle)
        val trimmed = normalizeExtensionSource(mathSource)
        if (trimmed.isEmpty()) return Triple(false, "Empty .math source", null)
        return try {
            val mathcBytes = MathEngineBridge.nativeCompileExt(engineHandle, trimmed)
            if (mathcBytes == null || mathcBytes.isEmpty()) {
                val err = MathEngineBridge.nativeLastError(engineHandle) ?: "Compilation failed"
                Triple(false, err, null)
            } else {
                Regex("(?m)^\\s*\\[([a-zA-Z0-9_]+)]").findAll(trimmed).forEach { m ->
                    val modName = m.groupValues[1]
                    MathEngineBridge.nativeSetModule(engineHandle, modName, true)
                }
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

    /**
     * Splits a normalized `.math` module source into balanced top-level declarations,
     * headers, and statements so top-to-bottom execution can log every item's result.
     */
    internal fun splitModuleStatements(rawSource: String): List<String> {
        val normalized = normalizeExtensionSource(rawSource)
        if (normalized.isEmpty()) return emptyList()

        val statements = mutableListOf<String>()
        val currentStmt = StringBuilder()
        var braceDepth = 0
        var parenDepth = 0
        var brackDepth = 0

        for (rawLine in normalized.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (currentStmt.isNotEmpty()) currentStmt.append("\n")
            currentStmt.append(line)

            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (ch == '\\' && inQuotes && i + 1 < line.length) {
                    i += 2
                    continue
                }
                if (ch == '"') {
                    inQuotes = !inQuotes
                } else if (!inQuotes) {
                    when (ch) {
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> brackDepth++
                        ']' -> if (brackDepth > 0) brackDepth--
                    }
                }
                i++
            }
            if (braceDepth == 0 && parenDepth == 0 && brackDepth == 0) {
                var built = currentStmt.toString().trim()
                while (built.endsWith(";")) {
                    built = built.dropLast(1).trimEnd()
                }
                if (built.isNotEmpty()) {
                    statements.add(built)
                }
                currentStmt.setLength(0)
            }
        }
        if (currentStmt.isNotEmpty()) {
            var built = currentStmt.toString().trim()
            while (built.endsWith(";")) {
                built = built.dropLast(1).trimEnd()
            }
            if (built.isNotEmpty()) {
                statements.add(built)
            }
        }
        return statements
    }

    /**
     * Formats the bound variables and constants of [modName] using `nativeModuleFormatVars`:
     *   `<name>|<type>|<unit>|<mutability>|<visibility>|<formatted_value>`
     * into readable lines such as:
     *   `pub mut  v0 : real [speed] = 25`
     *   `priv const g : real = 9.80665`
     */
    @Synchronized
    private fun formatModuleVariablesSync(modName: String): String {
        if (engineHandle == 0L || modName.isBlank()) return ""
        val raw = runCatching {
            MathEngineBridge.nativeModuleFormatVars(engineHandle, modName)
        }.getOrNull() ?: return ""
        if (raw.isBlank()) return ""

        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { record ->
                val parts = record.split("|", limit = 6)
                if (parts.size < 6) null
                else {
                    val vName = parts[0]
                    val vType = parts[1]
                    val vUnit = parts[2]
                    val vMut = parts[3]  // "mut" or "const"
                    val vVis = parts[4]  // "pub" or "priv"
                    val vVal = parts[5]
                    val unitBadge = if (vUnit != "none" && vUnit.isNotEmpty()) " [$vUnit]" else ""
                    val visPad = if (vVis == "priv") "priv" else "pub "
                    val mutPad = if (vMut == "const") "const" else "mut  "
                    "$visPad $mutPad $vName : $vType$unitBadge = $vVal"
                }
            }
            .joinToString("\n")
    }

    /** Destroy the engine when the process exits. */
    @Synchronized
    fun shutdown() {
        if (engineHandle != 0L) {
            MathEngineBridge.nativeDestroy(engineHandle)
            engineHandle = 0L
        }
        studioModuleSourcesByName.clear()
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

        var depth = 0
        for (ch in e) {
            when (ch) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
        }
        if (depth > 0) e += ")".repeat(depth)

        e = replaceInfixNthRoot(e)

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

        e = Regex("\\blog\\(").replace(e, "log10(")

        return e.trim()
    }

    /** Stateless single-shot evaluation via `eng_eval` (isolated in `[eval]`). */
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
    //  Unified Top-to-Bottom Executable Modules & Multi-File Project API
    // =========================================================================

    /**
     * Resolves and executes any `<[dep]` modules from [projectFilesByModName] in
     * topological dependency order before executing [targetModName].
     */
    @Synchronized
    private fun ensureProjectDependenciesSync(
        targetModName: String,
        targetSource: String,
        projectFilesByModName: Map<String, String>,
        visited: MutableSet<String>
    ): String? {
        visited.add(targetModName)
        val deps = extractModuleDependencies(targetSource)
        for (dep in deps) {
            if (dep == targetModName) {
                return "Circular module dependency involving '[$dep]'"
            }
            val depSource = projectFilesByModName[dep]
            if (depSource != null) {
                if (dep !in visited) {
                    val depErr = ensureProjectDependenciesSync(dep, depSource, projectFilesByModName, visited)
                    if (depErr != null) return depErr
                    val prevLoaded = studioModuleSourcesByName[dep]
                    if (prevLoaded != depSource) {
                        MathEngineBridge.nativeModuleReset(engineHandle, dep)
                        val normDep = normalizeExtensionSource(depSource)
                        if (normDep.isNotEmpty()) {
                            val wire = MathEngineBridge.nativeModuleEval(engineHandle, dep, normDep)
                            val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                            if (!parsed.ok) {
                                return "Error in dependency '[$dep]': ${parsed.error}"
                            }
                            MathEngineBridge.nativeSetModule(engineHandle, dep, true)
                            studioModuleSourcesByName[dep] = depSource
                        }
                    }
                }
            } else {
                // Check if dep is a loaded stdlib or patch module
                val active = MathEngineBridge.nativeIsModuleActive(engineHandle, dep)
                if (!active && !MathEngineBridge.nativeSetModule(engineHandle, dep, true)) {
                    return "Unresolved module dependency '<[$dep]' required by '[$targetModName]'"
                }
            }
        }
        return null
    }

    /**
     * Executes a `.math` module file top-to-bottom on its own (Python-style, no entry point),
     * automatically resolving any `<[dep]` sibling modules from [projectSourcesByFileName].
     *
     * Supports interleaved `[module]` headers, `<[dep]` dependencies, `@dim` directives,
     * `$type` structs, `pub`/`priv` functions & operators, `pub`/`priv` + `mut`/`const`
     * variables (`:=` vs `=`), control flow, and standalone expressions.
     */
    fun runModuleFile(
        fileName: String?,
        mathSource: String,
        projectSourcesByFileName: Map<String, String> = emptyMap(),
        resetBeforeRun: Boolean = true,
        onDone: (modName: String, results: List<Pair<String, EvalOutcome>>, varsSummary: String) -> Unit
    ) {
        worker.execute {
            if (!ensureEngine()) {
                val fallbackMod = deriveModuleName(fileName, mathSource)
                onDone(
                    fallbackMod,
                    listOf("(init)" to EvalOutcome(false, "0.0", "void", "none", "Engine init failed")),
                    ""
                )
                return@execute
            }

            synchronized(this) {
                registerHostExternsOnHandle(engineHandle)
                val primaryMod = deriveModuleName(fileName, mathSource)

                // Map all project files by their module name so <[dep] resolves across project files
                val projectByMod = LinkedHashMap<String, String>()
                for ((fName, fSrc) in projectSourcesByFileName) {
                    val mName = deriveModuleName(fName, fSrc)
                    projectByMod[mName] = fSrc
                }
                projectByMod[primaryMod] = mathSource

                val depErr = ensureProjectDependenciesSync(
                    primaryMod,
                    mathSource,
                    projectByMod,
                     mutableSetOf()
                )
                if (depErr != null) {
                    onDone(
                        primaryMod,
                        listOf("<[dep]" to EvalOutcome(false, "0.0", "void", "none", depErr)),
                        formatModuleVariablesSync(primaryMod)
                    )
                    return@execute
                }

                if (resetBeforeRun) {
                    runCatching { MathEngineBridge.nativeModuleReset(engineHandle, primaryMod) }
                }

                val statements = splitModuleStatements(mathSource)
                val results = mutableListOf<Pair<String, EvalOutcome>>()
                var curMod = primaryMod
                val modHeaderRegex = Regex("^\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
                var allOk = true

                for (stmt in statements) {
                    val hdrMatch = modHeaderRegex.matchEntire(stmt.trim())
                    if (hdrMatch != null) {
                        curMod = hdrMatch.groupValues[1]
                        if (resetBeforeRun && curMod != primaryMod) {
                            runCatching { MathEngineBridge.nativeModuleReset(engineHandle, curMod) }
                        }
                        MathEngineBridge.nativeSetModule(engineHandle, curMod, true)
                        continue
                    }

                    val wire = try {
                        MathEngineBridge.nativeModuleEval(engineHandle, curMod, stmt)
                    } catch (t: Throwable) {
                        "ERR|${t.message ?: "Native exception"}"
                    }

                    val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                    val outcome = if (parsed.ok) {
                        EvalOutcome(true, parsed.value, parsed.type, parsed.unit, null)
                    } else {
                        allOk = false
                        EvalOutcome(false, "0.0", "void", "none", parsed.error)
                    }
                    results.add(stmt to outcome)
                    if (!parsed.ok) break
                }

                MathEngineBridge.nativeSetModule(engineHandle, curMod, true)
                if (allOk) {
                    studioModuleSourcesByName[primaryMod] = mathSource
                }
                val varsSummary = formatModuleVariablesSync(curMod)
                onDone(curMod, results, varsSummary)
            }
        }
    }

    /** Resets all runtime variables, constants, and overloads in [modName]. */
    fun resetModule(modName: String, onDone: (ok: Boolean) -> Unit) {
        worker.execute {
            if (!ensureEngine() || modName.isBlank()) {
                onDone(false)
                return@execute
            }
            val ok = synchronized(this) {
                studioModuleSourcesByName.remove(modName)
                runCatching {
                    MathEngineBridge.nativeModuleReset(engineHandle, modName)
                }.getOrDefault(false)
            }
            onDone(ok)
        }
    }

    /** Inspects all variables and constants currently bound in [modName]. */
    fun inspectModuleVariables(modName: String, onDone: (summary: String) -> Unit) {
        worker.execute {
            if (!ensureEngine() || modName.isBlank()) {
                onDone("")
                return@execute
            }
            onDone(formatModuleVariablesSync(modName))
        }
    }

    /** Exports [modName]'s current variables and constants as reproducible `.math` module source. */
    fun exportModuleSource(modName: String, onDone: (source: String) -> Unit) {
        worker.execute {
            if (!ensureEngine() || modName.isBlank()) {
                onDone("")
                return@execute
            }
            val src = synchronized(this) {
                runCatching {
                    MathEngineBridge.nativeModuleExportSource(engineHandle, modName) ?: ""
                }.getOrDefault("")
            }
            onDone(src)
        }
    }
}
