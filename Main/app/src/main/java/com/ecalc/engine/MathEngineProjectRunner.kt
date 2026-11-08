package com.ecalc

import android.content.Context
import android.util.Log
import com.mathengine.MathEngineBridge

/**
 * Unified Compilation, Linking & Execution Pipeline for `.math` Studio Modules and Multi-File Projects.
 *
 * Matches the C11 Math Engine's unified architecture (`eng_flush_user_modules` + `eng_build_and_run`)
 * on the **single recyclable `Engine*` instance** ([MathEngineManager.engineHandle]):
 * - **Single-Unit Standalone Mode (`unit_count == 1`)**: When running an individual `.math` file
 *   that has no sibling project `<[dep]` dependencies, flushes user modules in O(1) time and runs
 *   that single unit through `nativeBuildAndRun(handle, arrayOf(unitSource))`.
 * - **Multi-Unit Project Mode (`unit_count > 1`)**: When running a multi-file project (or a file
 *   that imports sibling project modules via `<[dep]`), flushes user modules in O(1) time and passes
 *   all compilation units to `nativeBuildAndRun(handle, unitSources)`, which runs Kahn's topological
 *   sort in C, initializes library dependencies (`is_dep_unit = true`), and executes the project's
 *   single `@entry(fn)` or `main()` entry point (or unique DAG sink).
 */

/**
 * Prepares a `.math` file as a compilation unit for `nativeBuildAndRun`, ensuring it starts with
 * its canonical `[modName]` header so all declarations and module variables land in `[modName]`.
 */
internal fun MathEngineManager.prepareCompilationUnit(fileName: String?, rawSource: String): Pair<String, String> {
    val modName = deriveModuleName(fileName, rawSource)
    val norm = normalizeExtensionSource(rawSource)
    if (norm.isEmpty()) return modName to "[$modName]"

    val firstLine = norm.lineSequence().firstOrNull()?.trim().orEmpty()
    val hasExplicitTopHeader = modHeaderSingleLineRegex.matches(firstLine)
    val unitSource = if (hasExplicitTopHeader) {
        norm
    } else {
        "[$modName]\n$norm"
    }
    return modName to unitSource
}

/**
 * Collects the transitive closure of sibling `.math` files in [projectFilesByModName] required by
 * [rootModName] via `<[dep]` headers.
 */
internal fun MathEngineManager.collectTransitiveProjectUnits(
    rootFileName: String,
    rootModName: String,
    rootSource: String,
    projectFilesByModName: Map<String, Pair<String, String>>
): List<Pair<String, String>> {
    val visited = LinkedHashSet<String>()
    val ordered = mutableListOf<Pair<String, String>>()

    fun visit(modName: String, fileName: String, src: String) {
        if (!visited.add(modName)) return
        for (dep in extractModuleDependencies(src)) {
            val sibling = projectFilesByModName[dep]
            if (sibling != null && dep != modName) {
                visit(dep, sibling.first, sibling.second)
            }
        }
        ordered.add(fileName to src)
    }

    visit(rootModName, rootFileName, rootSource)
    return ordered
}

/**
 * Executes one or more `.math` compilation units on the single shared `Engine*` instance:
 * 1. Flushes any previously loaded user modules in O(1) time via [flushUserModulesSync].
 * 2. Sets the sandboxed `import_dir` to [projectName]'s directory.
 * 3. Calls `MathEngineBridge.nativeBuildAndRun(handle, unitSourcesArray)` (falling back to
 *    `nativeEval` if an older prebuilt `.so` without `nativeBuildAndRun` is loaded).
 */
@Synchronized
internal fun MathEngineManager.executeUnitsViaEngineSync(
    ctx: Context,
    projectName: String,
    units: List<Pair<String, String>>,
    targetFileName: String,
    targetModName: String,
    targetRawSource: String,
    entryPoint: ProjectEntryPoint? = null
): Pair<List<Pair<String, EvalOutcome>>, String> {
    val safeProj = sanitizeProjectName(projectName)
    ensureProjectSetup(ctx, safeProj)

    val handle = flushUserModulesSync(ctx)
    if (handle == 0L) {
        return listOf(
            "(init)" to EvalOutcome(
                false, "0.0", "void", "none", "Shared engine initialization failed"
            )
        ) to ""
    }

    registerHostExternsOnHandle(handle, safeProj)

    val preparedUnits = ArrayList<String>(units.size)
    val preparedByMod = LinkedHashMap<String, String>()
    for ((fName, rawSrc) in units) {
        val (mName, unitSrc) = prepareCompilationUnit(fName, rawSrc)
        if (unitSrc.isNotBlank()) {
            preparedUnits.add(unitSrc)
            preparedByMod[mName] = rawSrc
        }
    }

    if (preparedUnits.isEmpty()) {
        return emptyList<Pair<String, EvalOutcome>>() to ""
    }

    runCatching { MathEngineBridge.nativeClearOutput(handle) }

    val wire = try {
        MathEngineBridge.nativeBuildAndRun(handle, preparedUnits.toTypedArray())
    } catch (_: UnsatisfiedLinkError) {
        // Fallback if a legacy prebuilt .so without nativeBuildAndRun is loaded
        var lastWire = "OK|void|none|"
        for (uSrc in preparedUnits) {
            val w = MathEngineBridge.nativeEval(handle, uSrc) ?: "ERR|Null nativeEval response"
            lastWire = w
            if (w.startsWith("ERR|")) break
        }
        lastWire
    } catch (t: Throwable) {
        "ERR|${t.message ?: "Native buildAndRun exception"}"
    }

    val stdout = runCatching { MathEngineBridge.nativeGetOutput(handle).orEmpty() }.getOrDefault("")
    val parsed = MathEngineBridge.EvalResult.parseWire(wire)

    // Activate all compiled project modules for variable & CAS/Plot introspection
    for ((mName, rawSrc) in preparedByMod) {
        runCatching { MathEngineBridge.nativeSetModule(handle, mName, true) }
        linkedModuleSourcesByName[mName] = rawSrc
    }
    runCatching { MathEngineBridge.nativeSetModule(handle, targetModName, true) }
    linkedProjectName = safeProj
    isProjectModeActive = true
    refreshLiveAutocompleteCatalogSync(handle)

    val stmtsWithLines = splitModuleStatementsWithLines(targetRawSource)
    val results = mutableListOf<Pair<String, EvalOutcome>>()

    if (parsed.ok) {
        // Record declared items from the target file for a clear, scannable execution log
        for ((lineNum, stmt) in stmtsWithLines) {
            val trimmed = stmt.trim()
            if (modHeaderSingleLineRegex.matches(trimmed) || depHeaderSingleLineRegex.matches(trimmed)) {
                continue
            }
            if (isDeclarationOrBindingStatement(trimmed)) {
                results.add(
                    trimmed to EvalOutcome(
                        ok = true,
                        value = "void",
                        type = "void",
                        unit = "none",
                        error = null,
                        stdout = "",
                        sourceLine = lineNum
                    )
                )
            }
        }

        val lastExprPair = stmtsWithLines.lastOrNull { (_, s) ->
            val t = s.trim()
            !modHeaderSingleLineRegex.matches(t) &&
                !depHeaderSingleLineRegex.matches(t) &&
                !isDeclarationOrBindingStatement(t)
        }
        val summaryLabel = when {
            entryPoint != null -> "▶ ${entryPoint.fileName} [${entryPoint.modName}] :: ${entryPoint.entryFn}()"
            lastExprPair != null -> lastExprPair.second
            else -> "▶ Linked [$targetModName] (${units.size} unit${if (units.size == 1) "" else "s"})"
        }
        val summaryLine = entryPoint?.sourceLine ?: lastExprPair?.first ?: (stmtsWithLines.lastOrNull()?.first ?: 1)

        results.add(
            summaryLabel to EvalOutcome(
                ok = true,
                value = parsed.value,
                type = parsed.type,
                unit = parsed.unit,
                error = null,
                stdout = stdout,
                sourceLine = summaryLine
            )
        )
    } else {
        val errMsg = parsed.error ?: "Compilation or execution failed"
        val extractedLine = errorLineExtractRegex.find(errMsg)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fallbackLine = extractedLine
            ?: entryPoint?.sourceLine
            ?: stmtsWithLines.lastOrNull()?.first
            ?: 1
        val errStmt = stmtsWithLines.firstOrNull { it.first == fallbackLine }?.second
            ?: (if (entryPoint != null) "▶ ${entryPoint.fileName} :: ${entryPoint.entryFn}()" else "[$targetModName]")
        results.add(
            errStmt to EvalOutcome(
                ok = false,
                value = "0.0",
                type = "void",
                unit = "none",
                error = errMsg,
                stdout = stdout,
                sourceLine = fallbackLine
            )
        )
    }

    val varsSummary = formatModuleVariablesOnHandleSync(handle, targetModName)
    return results to varsSummary
}

/**
 * Runs a `.math` file from the Studio Editor (`▶ Run / Link`) through the unified C engine pipeline:
 * - If the file has no `<[dep]` dependencies on sibling project files, runs it in **Single-Unit
 *   Standalone Mode** (`nativeBuildAndRun(handle, arrayOf(unitSource))`).
 * - If the file imports sibling `.math` modules in the project via `<[dep]`, links those sibling
 *   units together with the file in **Multi-Unit Project Mode** (`nativeBuildAndRun(handle, units)`).
 */
fun MathEngineManager.runModuleFile(
    fileName: String?,
    mathSource: String,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    resetBeforeRun: Boolean = true,
    projectName: String = activeProjectName,
    onDone: (modName: String, results: List<Pair<String, EvalOutcome>>, varsSummary: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName)
    worker.execute {
        val primaryMod = deriveModuleName(fileName, mathSource)
        try {
            val ctx = appContext
            if (ctx == null) {
                onDone(
                    primaryMod,
                    listOf("(init)" to EvalOutcome(false, "0.0", "void", "none", "App context not initialized")),
                    ""
                )
                return@execute
            }

            synchronized(this) {
                activeProjectName = safeProj
                val targetFile = fileName ?: "$primaryMod.math"

                val projectByMod = LinkedHashMap<String, Pair<String, String>>()
                for ((fName, fSrc) in projectSourcesByFileName) {
                    val mName = deriveModuleName(fName, fSrc)
                    projectByMod[mName] = fName to fSrc
                }
                projectByMod[primaryMod] = targetFile to mathSource

                val units = collectTransitiveProjectUnits(
                    rootFileName = targetFile,
                    rootModName = primaryMod,
                    rootSource = mathSource,
                    projectFilesByModName = projectByMod
                )

                val (entryFn, isExplicit) = detectModuleEntryPoint(mathSource)
                val fileEntryPoint = if (entryFn != null) {
                    ProjectEntryPoint(
                        fileName = targetFile,
                        modName = primaryMod,
                        entryFn = entryFn,
                        isExplicitDirective = isExplicit,
                        sourceLine = splitModuleStatementsWithLines(mathSource).lastOrNull()?.first ?: 1
                    )
                } else null

                val (results, varsSummary) = executeUnitsViaEngineSync(
                    ctx = ctx,
                    projectName = safeProj,
                    units = units,
                    targetFileName = targetFile,
                    targetModName = primaryMod,
                    targetRawSource = mathSource,
                    entryPoint = fileEntryPoint
                )
                onDone(primaryMod, results, varsSummary)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "runModuleFile failed for '$fileName'", t)
            onDone(
                primaryMod,
                listOf("(error)" to EvalOutcome(false, "0.0", "void", "none", t.message ?: "Module execution failed")),
                ""
            )
        }
    }
}

/**
 * **Path 2 — Projects Mode (`eng_flush_user_modules` + `eng_build_and_run`)**:
 * Flushes previous user modules on the single shared `Engine*` instance, links the project's
 * `.math` modules in topological `<[dep]` order, and executes `@entry(fn)` or `main()`.
 */
fun MathEngineManager.runProject(
    projectName: String = activeProjectName,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    preferredActiveFile: String? = null,
    onDone: (entryPoint: ProjectEntryPoint?, results: List<Pair<String, EvalOutcome>>, varsSummary: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName)
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
                val projDir = ensureProjectSetup(ctx, safeProj)
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
                    // If the project has only 1 .math file, run it directly as a single-unit standalone project
                    if (mergedSources.size == 1) {
                        val (singleFile, singleSrc) = mergedSources.entries.first()
                        val singleMod = deriveModuleName(singleFile, singleSrc)
                        val synthEntry = ProjectEntryPoint(
                            fileName = singleFile,
                            modName = singleMod,
                            entryFn = "script",
                            isExplicitDirective = false,
                            sourceLine = 1
                        )
                        val (results, varsSummary) = executeUnitsViaEngineSync(
                            ctx = ctx,
                            projectName = safeProj,
                            units = listOf(singleFile to singleSrc),
                            targetFileName = singleFile,
                            targetModName = singleMod,
                            targetRawSource = singleSrc,
                            entryPoint = null
                        )
                        onDone(synthEntry, results, varsSummary)
                        return@execute
                    }
                    onDone(
                        null,
                        listOf(
                            "(project)" to EvalOutcome(
                                false,
                                "0.0",
                                "void",
                                "none",
                                "Project '$safeProj' has multiple files but no main() or @entry function. Add pub main() or @entry(fn), or run individual standalone files via '▶ Run / Link'."
                            )
                        ),
                        ""
                    )
                    return@execute
                }

                val entrySource = mergedSources[entryPoint.fileName] ?: ""
                val projectByMod = LinkedHashMap<String, Pair<String, String>>()
                for ((fName, fSrc) in mergedSources) {
                    val mName = deriveModuleName(fName, fSrc)
                    projectByMod[mName] = fName to fSrc
                }

                // Collect the entry module and all its transitive <[dep] project modules
                val units = collectTransitiveProjectUnits(
                    rootFileName = entryPoint.fileName,
                    rootModName = entryPoint.modName,
                    rootSource = entrySource,
                    projectFilesByModName = projectByMod
                )

                val (results, varsSummary) = executeUnitsViaEngineSync(
                    ctx = ctx,
                    projectName = safeProj,
                    units = units,
                    targetFileName = entryPoint.fileName,
                    targetModName = entryPoint.modName,
                    targetRawSource = entrySource,
                    entryPoint = entryPoint
                )
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

/** Resets runtime variables and overloads in [modName] (or flushes user modules on the shared engine). */
fun MathEngineManager.resetModule(
    modName: String,
    projectName: String = activeProjectName,
    onDone: (ok: Boolean) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone(false)
                return@execute
            }
            val ok = synchronized(this) {
                val handle = ensureSharedEngineSync(ctx, safeProj)
                if (handle == 0L) {
                    false
                } else {
                    linkedModuleSourcesByName.remove(modName)
                    runCatching {
                        MathEngineBridge.nativeModuleReset(handle, modName)
                    }.getOrDefault(false)
                }
            }
            onDone(ok)
        } catch (t: Throwable) {
            Log.e(TAG, "resetModule failed for '$modName'", t)
            onDone(false)
        }
    }
}

/** Inspects all variables and constants currently bound in [modName] on the shared engine. */
fun MathEngineManager.inspectModuleVariables(
    modName: String,
    projectName: String = activeProjectName,
    onDone: (summary: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone("")
                return@execute
            }
            synchronized(this) {
                val handle = ensureSharedEngineSync(ctx, safeProj)
                val summary = if (linkedProjectName == safeProj) {
                    formatModuleVariablesOnHandleSync(handle, modName)
                } else {
                    ""
                }
                onDone(summary)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "inspectModuleVariables failed", t)
            onDone("")
        }
    }
}

/** Exports [modName]'s current variables and constants as reproducible `.math` module source. */
fun MathEngineManager.exportModuleSource(
    modName: String,
    projectName: String = activeProjectName,
    onDone: (source: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone("")
                return@execute
            }
            val src = synchronized(this) {
                val handle = ensureSharedEngineSync(ctx, safeProj)
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
 * Ensures that [modName] (and any `<[dep]` sibling modules in [projectSourcesByFileName]) are
 * linked on the shared engine when the user opens the 2D Plotter or Symbolic CAS Inspector.
 */
@Synchronized
internal fun MathEngineManager.ensureModuleDeclarationsLinkedSync(
    ctx: Context,
    projectName: String,
    modName: String,
    moduleSource: String?,
    projectSourcesByFileName: Map<String, String>
) {
    val handle = ensureSharedEngineSync(ctx, projectName)
    if (handle == 0L || moduleSource.isNullOrBlank()) return
    val safeProj = sanitizeProjectName(projectName)
    if (linkedProjectName == safeProj && linkedModuleSourcesByName[modName] == moduleSource) {
        MathEngineBridge.nativeSetModule(handle, modName, true)
        return
    }
    val projectByMod = LinkedHashMap<String, Pair<String, String>>()
    for ((fName, fSrc) in projectSourcesByFileName) {
        projectByMod[deriveModuleName(fName, fSrc)] = fName to fSrc
    }
    projectByMod[modName] = "$modName.math" to moduleSource

    val units = collectTransitiveProjectUnits(
        rootFileName = "$modName.math",
        rootModName = modName,
        rootSource = moduleSource,
        projectFilesByModName = projectByMod
    )
    executeUnitsViaEngineSync(
        ctx = ctx,
        projectName = safeProj,
        units = units,
        targetFileName = "$modName.math",
        targetModName = modName,
        targetRawSource = moduleSource,
        entryPoint = null
    )
}
