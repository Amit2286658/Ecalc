package com.ecalc

import android.content.Context
import android.os.Build
import android.util.Log
import com.mathengine.MathEngineBridge
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Resolves and loads any `<[dep]` modules from [projectFilesByModName] in topological
 * dependency order on [projectState]'s isolated engine in **declaration-only dependency mode**
 * (binding types, dimensions, functions, operators, and `pub`/`priv` variables/constants while
 * skipping standalone scratch expressions and the dependency's own `main()`).
 */
@Synchronized
internal fun MathEngineManager.ensureProjectDependenciesSync(
    projectState: ProjectRuntimeState,
    targetModName: String,
    targetSource: String,
    projectFilesByModName: Map<String, String>,
    visited: MutableSet<String>
): String? {
    val handle = projectState.engineHandle
    if (handle == 0L) return "Project engine not initialized"
    visited.add(targetModName)
    val deps = extractModuleDependencies(targetSource)
    for (dep in deps) {
        if (dep == targetModName) {
            return "Circular module dependency involving '[$dep]'"
        }
        val depSource = projectFilesByModName[dep]
        if (depSource != null) {
            if (dep !in visited) {
                val depErr = ensureProjectDependenciesSync(
                    projectState,
                    dep,
                    depSource,
                    projectFilesByModName,
                    visited
                )
                if (depErr != null) return depErr
                val prevLoaded = projectState.studioModuleSourcesByName[dep]
                if (prevLoaded != depSource) {
                    MathEngineBridge.nativeModuleReset(handle, dep)
                    val declStmts = splitModuleStatements(depSource).filter { stmt ->
                        !stmt.trim().startsWith("@entry") && isDeclarationOrBindingStatement(stmt)
                    }
                    val declOnlySrc = declStmts.joinToString("\n")
                    if (declOnlySrc.isNotEmpty()) {
                        val wire = evalInModuleWithoutAutoMain(handle, dep, declOnlySrc)
                        val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                        if (!parsed.ok) {
                            return "Error in dependency '[$dep]': ${parsed.error}"
                        }
                        MathEngineBridge.nativeSetModule(handle, dep, true)
                        projectState.studioModuleSourcesByName[dep] = depSource
                    }
                } else {
                    MathEngineBridge.nativeSetModule(handle, dep, true)
                }
            }
        } else {
            val active = MathEngineBridge.nativeIsModuleActive(handle, dep)
            if (!active && !MathEngineBridge.nativeSetModule(handle, dep, true)) {
                return "Unresolved module dependency '<[$dep]' required by '[$targetModName]' in project '${projectState.projectName}'"
            }
        }
    }
    return null
}

/**
 * Persists the cumulative `.mathc` image (`stdlib.mathc` + global patches + linked modules in [projectState])
 * into `filesDir/math_projects/<projectName>/stdlib.mathc`.
 */
@Synchronized
internal fun MathEngineManager.persistProjectStdlibMathcSync(ctx: Context, projectState: ProjectRuntimeState) {
    val projDir = ensureProjectSetup(ctx, projectState.projectName, allowCalcProject0 = false)
    val stdlibFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
    val baseBytes = runCatching { getUniversalPatchedStdlibBytes(ctx) }.getOrNull() ?: return

    val tempHandle = MathEngineBridge.nativeCreateBlank().takeIf { it != 0L } ?: return
    try {
        if (!MathEngineBridge.nativeLoadMathc(tempHandle, baseBytes)) return
        registerHostExternsOnHandle(tempHandle, projectState.projectName)
        var latestMathc: ByteArray? = null
        for ((modName, rawSrc) in projectState.studioModuleSourcesByName) {
            val declStmts = splitModuleStatements(rawSrc).filter { stmt ->
                !stmt.trim().startsWith("@entry") && isDeclarationOrBindingStatement(stmt)
            }
            val norm = declStmts.joinToString("\n").trim()
            if (norm.isNotEmpty()) {
                val withHeader = if (modHeaderMultilineRegex.containsMatchIn(norm)) {
                    norm
                } else {
                    "[$modName]\n$norm"
                }
                val bytes = runCatching { MathEngineBridge.nativeCompileExt(tempHandle, withHeader) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    latestMathc = bytes
                }
            }
        }
        if (latestMathc != null && latestMathc.isNotEmpty()) {
            runCatching { stdlibFile.writeBytes(latestMathc) }
        }
    } finally {
        runCatching { MathEngineBridge.nativeDestroy(tempHandle) }
    }
}

/**
 * Executes an individual `.math` module file strictly top-to-bottom on its own inside [projectName]'s
 * isolated engine:
 * - Automatically resolves any `<[dep]` sibling modules in declaration-only dependency mode.
 * - Evaluates every top-level declaration, function definition, variable binding, and standalone
 *   expression in the file strictly from top to bottom.
 * - Does NOT auto-invoke `main()` or `@entry(fn)` (project-level entry functions are run via
 *   [runProject] from the Project Run option).
 */
fun MathEngineManager.runModuleFile(
    fileName: String?,
    mathSource: String,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    resetBeforeRun: Boolean = true,
    projectName: String = activeProjectName,
    onDone: (modName: String, results: List<Pair<String, EvalOutcome>>, varsSummary: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
    worker.execute {
        val fallbackMod = deriveModuleName(fileName, mathSource)
        try {
            val ctx = appContext
            if (ctx == null) {
                onDone(
                    fallbackMod,
                    listOf("(init)" to EvalOutcome(false, "0.0", "void", "none", "App context not initialized")),
                    ""
                )
                return@execute
            }

            synchronized(this) {
                activeProjectName = safeProj
                var projHandle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (projHandle == 0L) {
                    onDone(
                        fallbackMod,
                        listOf("(init)" to EvalOutcome(false, "0.0", "void", "none", "Project '$safeProj' engine init failed")),
                        ""
                    )
                    return@execute
                }

                val projState = projectStates.getOrPut(safeProj) { ProjectRuntimeState(safeProj, projHandle) }
                registerHostExternsOnHandle(projHandle, safeProj)
                val primaryMod = fallbackMod

                val hasTypeDecl = anyTypeOrStructDeclRegex.containsMatchIn(mathSource)
                if (resetBeforeRun && hasTypeDecl && projState.studioModuleSourcesByName.containsKey(primaryMod)) {
                    projState.studioModuleSourcesByName.remove(primaryMod)
                    projHandle = rebuildProjectEngineFromBaselineSync(ctx, safeProj, allowCalcProject0 = false)
                }

                val projectByMod = LinkedHashMap<String, String>()
                for ((fName, fSrc) in projectSourcesByFileName) {
                    val mName = deriveModuleName(fName, fSrc)
                    projectByMod[mName] = fSrc
                }
                projectByMod[primaryMod] = mathSource

                val depErr = ensureProjectDependenciesSync(
                    projState,
                    primaryMod,
                    mathSource,
                    projectByMod,
                    mutableSetOf()
                )
                if (depErr != null) {
                    onDone(
                        primaryMod,
                        listOf("<[dep]" to EvalOutcome(false, "0.0", "void", "none", depErr)),
                        formatModuleVariablesOnHandleSync(projHandle, primaryMod)
                    )
                    return@execute
                }

                if (resetBeforeRun) {
                    runCatching { MathEngineBridge.nativeModuleReset(projHandle, primaryMod) }
                }

                val statementsWithLines = splitModuleStatementsWithLines(mathSource)
                val results = mutableListOf<Pair<String, EvalOutcome>>()
                var curMod = primaryMod
                var allOk = true

                for ((stmtLine, stmt) in statementsWithLines) {
                    val trimmedStmt = stmt.trim()
                    val hdrMatch = modHeaderSingleLineRegex.matchEntire(trimmedStmt)
                    if (hdrMatch != null) {
                        curMod = hdrMatch.groupValues[1]
                        if (resetBeforeRun && curMod != primaryMod) {
                            runCatching { MathEngineBridge.nativeModuleReset(projHandle, curMod) }
                        }
                        MathEngineBridge.nativeSetModule(projHandle, curMod, true)
                        continue
                    }

                    val entryMatch = explicitEntryRegex.matchEntire(trimmedStmt)
                    if (entryMatch != null) {
                        results.add(trimmedStmt to EvalOutcome(true, "void", "void", "none", null, sourceLine = stmtLine))
                        continue
                    }

                    runCatching { MathEngineBridge.nativeClearOutput(projHandle) }
                    val wire = try {
                        evalInModuleWithoutAutoMain(projHandle, curMod, stmt)
                    } catch (t: Throwable) {
                        "ERR|${t.message ?: "Native exception"}"
                    }
                    val stdout = runCatching { MathEngineBridge.nativeGetOutput(projHandle).orEmpty() }.getOrDefault("")

                    val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                    val outcome = if (parsed.ok) {
                        EvalOutcome(true, parsed.value, parsed.type, parsed.unit, null, stdout = stdout, sourceLine = stmtLine)
                    } else {
                        allOk = false
                        EvalOutcome(false, "0.0", "void", "none", parsed.error, stdout = stdout, sourceLine = stmtLine)
                    }
                    results.add(stmt to outcome)
                    if (!parsed.ok) break
                }

                MathEngineBridge.nativeSetModule(projHandle, curMod, true)
                if (allOk) {
                    projState.studioModuleSourcesByName[primaryMod] = mathSource
                    persistProjectStdlibMathcSync(ctx, projState)
                }
                val varsSummary = formatModuleVariablesOnHandleSync(projHandle, curMod)
                onDone(curMod, results, varsSummary)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "runModuleFile failed for '$fileName'", t)
            onDone(
                fallbackMod,
                listOf("(error)" to EvalOutcome(false, "0.0", "void", "none", t.message ?: "Module execution failed")),
                ""
            )
        }
    }
}

/**
 * Runs the project-level `main()` or `@entry(fn)` function located anywhere in any `.math` file
 * of [projectName].
 *
 * Behavior:
 * - Scans all `.math` files in the project using [findProjectEntryPoint].
 * - If no `main()` or `@entry(fn)` function exists in any file of the project, the project itself
 *   cannot run and [onDone] is invoked with `entryPoint = null` and a descriptive error outcome.
 * - Otherwise links all sibling modules in declaration-only mode, evaluates the entry module's
 *   declarations/bindings, and invokes `${entryPoint.entryFn}()` in `${entryPoint.modName}`.
 */
fun MathEngineManager.runProject(
    projectName: String = activeProjectName,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    preferredActiveFile: String? = null,
    onDone: (entryPoint: ProjectEntryPoint?, results: List<Pair<String, EvalOutcome>>, varsSummary: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null) {
                onDone(
                    null,
                    listOf("(init)" to EvalOutcome(false, "0.0", "void", "none", "App context not initialized")),
                    ""
                )
                return@execute
            }

            synchronized(this) {
                activeProjectName = safeProj
                val mergedSources = LinkedHashMap<String, String>()
                val projDir = ensureProjectSetup(ctx, safeProj, allowCalcProject0 = false)
                projDir.listFiles { f ->
                    f.isFile && f.name.endsWith(".math", ignoreCase = true) && !f.name.startsWith(".")
                }?.sortedBy { it.name.lowercase() }?.forEach { file ->
                    runCatching { mergedSources[file.name] = file.readText() }
                }
                for ((fName, fSrc) in projectSourcesByFileName) {
                    if (fName.endsWith(".math", ignoreCase = true) || !fName.contains('.')) {
                        mergedSources[fName] = fSrc
                    }
                }

                val entryPoint = findProjectEntryPoint(mergedSources, preferredActiveFile = preferredActiveFile)
                if (entryPoint == null) {
                    onDone(
                        null,
                        listOf(
                            "(project)" to EvalOutcome(
                                false,
                                "0.0",
                                "void",
                                "none",
                                "Project '$safeProj' cannot run: no main() or @entry function found in any file. Individual module files can still run top-to-bottom on their own."
                            )
                        ),
                        ""
                    )
                    return@execute
                }

                var projHandle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (projHandle == 0L) {
                    onDone(
                        entryPoint,
                        listOf("(init)" to EvalOutcome(false, "0.0", "void", "none", "Project '$safeProj' engine init failed")),
                        ""
                    )
                    return@execute
                }

                val projState = projectStates.getOrPut(safeProj) { ProjectRuntimeState(safeProj, projHandle) }
                registerHostExternsOnHandle(projHandle, safeProj)

                val entrySource = mergedSources[entryPoint.fileName] ?: ""
                val projectByMod = LinkedHashMap<String, String>()
                for ((fName, fSrc) in mergedSources) {
                    val mName = deriveModuleName(fName, fSrc)
                    projectByMod[mName] = fSrc
                }

                // If any project module redeclares a custom type, rebuild cleanly from baseline
                val anyRedeclaredType = projectByMod.any { (mName, mSrc) ->
                    anyTypeOrStructDeclRegex.containsMatchIn(mSrc) && projState.studioModuleSourcesByName.containsKey(mName)
                }
                if (anyRedeclaredType) {
                    projState.studioModuleSourcesByName.clear()
                    projHandle = rebuildProjectEngineFromBaselineSync(ctx, safeProj, allowCalcProject0 = false)
                }

                // Load all sibling modules in declaration-only mode first (respecting dependencies)
                for ((modName, modSrc) in projectByMod) {
                    if (modName == entryPoint.modName) continue
                    val depErr = ensureProjectDependenciesSync(
                        projState,
                        modName,
                        modSrc,
                        projectByMod,
                        mutableSetOf()
                    )
                    if (depErr != null) {
                        onDone(
                            entryPoint,
                            listOf("<[dep]" to EvalOutcome(false, "0.0", "void", "none", depErr)),
                            ""
                        )
                        return@execute
                    }
                    if (projState.studioModuleSourcesByName[modName] != modSrc) {
                        MathEngineBridge.nativeModuleReset(projHandle, modName)
                        val declStmts = splitModuleStatements(modSrc).filter { stmt ->
                            !stmt.trim().startsWith("@entry") && isDeclarationOrBindingStatement(stmt)
                        }
                        val declOnlySrc = declStmts.joinToString("\n")
                        if (declOnlySrc.isNotEmpty()) {
                            val wire = evalInModuleWithoutAutoMain(projHandle, modName, declOnlySrc)
                            val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                            if (!parsed.ok) {
                                onDone(
                                    entryPoint,
                                    listOf("[$modName]" to EvalOutcome(false, "0.0", "void", "none", "Error in module '[$modName]': ${parsed.error}")),
                                    ""
                                )
                                return@execute
                            }
                            MathEngineBridge.nativeSetModule(projHandle, modName, true)
                            projState.studioModuleSourcesByName[modName] = modSrc
                        }
                    } else {
                        MathEngineBridge.nativeSetModule(projHandle, modName, true)
                    }
                }

                // Ensure dependencies of the entry module itself
                val entryDepErr = ensureProjectDependenciesSync(
                    projState,
                    entryPoint.modName,
                    entrySource,
                    projectByMod,
                    mutableSetOf()
                )
                if (entryDepErr != null) {
                    onDone(
                        entryPoint,
                        listOf("<[dep]" to EvalOutcome(false, "0.0", "void", "none", entryDepErr)),
                        formatModuleVariablesOnHandleSync(projHandle, entryPoint.modName)
                    )
                    return@execute
                }

                runCatching { MathEngineBridge.nativeModuleReset(projHandle, entryPoint.modName) }

                // Evaluate all declarations and bindings in the entry file so functions/constants/vars are ready
                val statementsWithLines = splitModuleStatementsWithLines(entrySource)
                val results = mutableListOf<Pair<String, EvalOutcome>>()
                var curMod = deriveModuleName(entryPoint.fileName, entrySource)
                var allOk = true

                for ((stmtLine, stmt) in statementsWithLines) {
                    val trimmedStmt = stmt.trim()
                    val hdrMatch = modHeaderSingleLineRegex.matchEntire(trimmedStmt)
                    if (hdrMatch != null) {
                        curMod = hdrMatch.groupValues[1]
                        MathEngineBridge.nativeSetModule(projHandle, curMod, true)
                        continue
                    }
                    if (explicitEntryRegex.matches(trimmedStmt)) {
                        continue
                    }
                    // During project run, load all declarations/bindings in the entry file before invoking entryFn()
                    if (!isDeclarationOrBindingStatement(trimmedStmt)) {
                        continue
                    }

                    runCatching { MathEngineBridge.nativeClearOutput(projHandle) }
                    val wire = try {
                        evalInModuleWithoutAutoMain(projHandle, curMod, stmt)
                    } catch (t: Throwable) {
                        "ERR|${t.message ?: "Native exception"}"
                    }
                    val stdout = runCatching { MathEngineBridge.nativeGetOutput(projHandle).orEmpty() }.getOrDefault("")
                    val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                    val outcome = if (parsed.ok) {
                        EvalOutcome(true, parsed.value, parsed.type, parsed.unit, null, stdout = stdout, sourceLine = stmtLine)
                    } else {
                        allOk = false
                        EvalOutcome(false, "0.0", "void", "none", parsed.error, stdout = stdout, sourceLine = stmtLine)
                    }
                    results.add(stmt to outcome)
                    if (!parsed.ok) break
                }

                if (allOk) {
                    runCatching { MathEngineBridge.nativeClearOutput(projHandle) }
                    val entryCall = "${entryPoint.entryFn}()"
                    val wire = try {
                        evalInModuleWithoutAutoMain(projHandle, entryPoint.modName, entryCall)
                    } catch (t: Throwable) {
                        "ERR|${t.message ?: "Native exception"}"
                    }
                    val stdout = runCatching { MathEngineBridge.nativeGetOutput(projHandle).orEmpty() }.getOrDefault("")
                    val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                    val entryOutcome = if (parsed.ok) {
                        EvalOutcome(true, parsed.value, parsed.type, parsed.unit, null, stdout = stdout, sourceLine = entryPoint.sourceLine)
                    } else {
                        allOk = false
                        EvalOutcome(false, "0.0", "void", "none", parsed.error, stdout = stdout, sourceLine = entryPoint.sourceLine)
                    }
                    val label = "▶ ${entryPoint.fileName} [${entryPoint.modName}] :: $entryCall"
                    results.add(label to entryOutcome)
                }

                MathEngineBridge.nativeSetModule(projHandle, entryPoint.modName, true)
                if (allOk) {
                    projState.studioModuleSourcesByName[entryPoint.modName] = entrySource
                    persistProjectStdlibMathcSync(ctx, projState)
                }
                val varsSummary = formatModuleVariablesOnHandleSync(projHandle, entryPoint.modName)
                onDone(entryPoint, results, varsSummary)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "runProject failed for '$safeProj'", t)
            onDone(
                null,
                listOf("(error)" to EvalOutcome(false, "0.0", "void", "none", t.message ?: "Project execution failed")),
                ""
            )
        }
    }
}

/** Resets all runtime variables, constants, and overloads in [modName] inside [projectName]. */
fun MathEngineManager.resetModule(
    modName: String,
    projectName: String = activeProjectName,
    onDone: (ok: Boolean) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone(false)
                return@execute
            }
            val ok = synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (handle == 0L) {
                    false
                } else {
                    val st = projectStates[safeProj]
                    st?.studioModuleSourcesByName?.remove(modName)
                    val r = runCatching {
                        MathEngineBridge.nativeModuleReset(handle, modName)
                    }.getOrDefault(false)
                    if (st != null) {
                        persistProjectStdlibMathcSync(ctx, st)
                    }
                    r
                }
            }
            onDone(ok)
        } catch (t: Throwable) {
            Log.e(TAG, "resetModule failed for '$modName'", t)
            onDone(false)
        }
    }
}

/** Inspects all variables and constants currently bound in [modName] inside [projectName]. */
fun MathEngineManager.inspectModuleVariables(
    modName: String,
    projectName: String = activeProjectName,
    onDone: (summary: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone("")
                return@execute
            }
            synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                onDone(formatModuleVariablesOnHandleSync(handle, modName))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "inspectModuleVariables failed", t)
            onDone("")
        }
    }
}

/** Exports [modName]'s current variables and constants in [projectName] as reproducible `.math` module source. */
fun MathEngineManager.exportModuleSource(
    modName: String,
    projectName: String = activeProjectName,
    onDone: (source: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone("")
                return@execute
            }
            val src = synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (handle == 0L) ""
                else runCatching {
                    MathEngineBridge.nativeModuleExportSource(handle, modName) ?: ""
                }.getOrDefault("")
            }
            onDone(src)
        } catch (t: Throwable) {
            Log.e(TAG, "exportModuleSource failed", t)
            onDone("")
        }
    }
}

internal fun MathEngineManager.splitModuleStatementsWithLines(rawSource: String): List<Pair<Int, String>> {
    val cleaned = rawSource.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
    val rawLines = cleaned.lines()
    val codeLinesWithNum = mutableListOf<Pair<Int, String>>()

    for ((idx, rawLine) in rawLines.withIndex()) {
        var codePart = stripCommentOutsideQuotes(rawLine)
        val trimmed = codePart.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed.startsWith("@dim") ||
                            trimmed.startsWith("@entry") ||
            typeDeclRegex.containsMatchIn(trimmed) ||
            syntaxBindingRegex.containsMatchIn(trimmed)
        ) {
            while (codePart.endsWith(";")) {
                codePart = codePart.dropLast(1).trimEnd()
            }
        }
        if (codePart.isNotBlank()) {
            codeLinesWithNum.add((idx + 1) to codePart)
        }
    }

    val merged = mutableListOf<Pair<Int, String>>()
    var parenDepth = 0
    var braceDepth = 0
    var brackDepth = 0

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

    for ((lineNum, line) in codeLinesWithNum) {
        val stripped = line.trim()
        if (merged.isNotEmpty() && parenDepth == 0 && braceDepth == 0 && brackDepth == 0) {
            val (prevLineNum, prevText) = merged.last()
            val prev = prevText.trimEnd()
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
                                            !prevStripped.startsWith("@entry")
                val curContinues = contStartRegex.containsMatchIn(stripped)
                if (prevContinues || curContinues) {
                    merged[merged.lastIndex] = prevLineNum to "$prev $stripped"
                    updateDepths(stripped)
                    continue
                }
            }
        }
        merged.add(lineNum to line)
        updateDepths(stripped)
    }

    val statements = mutableListOf<Pair<Int, String>>()
    val currentStmt = StringBuilder()
    var currentStartLine = 1
    var bDepth = 0
    var pDepth = 0
    var kDepth = 0

    for ((lineNum, rawLine) in merged) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (currentStmt.isEmpty()) {
            currentStartLine = lineNum
        } else {
            currentStmt.append("\n")
        }
        currentStmt.append(line)

        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '\\' && i + 1 < line.length && inQuotes) {
                i += 2
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (!inQuotes) {
                when (ch) {
                    '{' -> bDepth++
                    '}' -> if (bDepth > 0) bDepth--
                    '(' -> pDepth++
                    ')' -> if (pDepth > 0) pDepth--
                    '[' -> kDepth++
                    ']' -> if (kDepth > 0) kDepth--
                }
            }
            i++
        }
        if (bDepth == 0 && pDepth == 0 && kDepth == 0) {
            val norm = normalizeExtensionSource(currentStmt.toString())
            var built = norm.trim()
            while (built.endsWith(";")) {
                built = built.dropLast(1).trimEnd()
            }
            if (built.isNotEmpty()) {
                statements.add(currentStartLine to built)
            }
            currentStmt.setLength(0)
        }
    }
    if (currentStmt.isNotEmpty()) {
        val norm = normalizeExtensionSource(currentStmt.toString())
        var built = norm.trim()
        while (built.endsWith(";")) {
            built = built.dropLast(1).trimEnd()
        }
        if (built.isNotEmpty()) {
            statements.add(currentStartLine to built)
        }
    }
    return statements
}

/**
 * Ensures that [modName]'s declarations (and any `<[dep]` dependencies) are linked on [handle]
 * when the user opens the 2D Plotter or Bytecode Disassembler before running the module manually.
 */
@Synchronized
internal fun MathEngineManager.ensureModuleDeclarationsLinkedSync(
    projState: ProjectRuntimeState,
    modName: String,
    moduleSource: String?,
    projectSourcesByFileName: Map<String, String>
) {
    val handle = projState.engineHandle
    if (handle == 0L || moduleSource.isNullOrBlank()) return
    if (projState.studioModuleSourcesByName[modName] == moduleSource) {
        MathEngineBridge.nativeSetModule(handle, modName, true)
        return
    }
    val projectByMod = LinkedHashMap<String, String>()
    for ((fName, fSrc) in projectSourcesByFileName) {
        projectByMod[deriveModuleName(fName, fSrc)] = fSrc
    }
    projectByMod[modName] = moduleSource
    ensureProjectDependenciesSync(projState, modName, moduleSource, projectByMod, mutableSetOf())

    val declStmts = splitModuleStatements(moduleSource).filter { stmt ->
        !stmt.trim().startsWith("@entry") && isDeclarationOrBindingStatement(stmt)
    }
    val declOnlySrc = declStmts.joinToString("\n")
    if (declOnlySrc.isNotEmpty()) {
        runCatching { evalInModuleWithoutAutoMain(handle, modName, declOnlySrc) }
        runCatching { MathEngineBridge.nativeSetModule(handle, modName, true) }
        projState.studioModuleSourcesByName[modName] = moduleSource
    }
}
