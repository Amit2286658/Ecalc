package com.ecalc

import android.util.Log
import com.mathengine.MathEngineBridge

/** Represents a single named curve `(x, y)` series for [MathPlotView]. */
data class PlotSeries(
    val label: String,
    val points: List<Pair<Double, Double>>
)

/**
 * Splits a multi-curve plot input string into individual expressions on top-level `;` or `,`
 * outside parentheses `(...)`, brackets `[...]`, braces `{...}`, and quotes `"..."`.
 */
internal fun splitMultiPlotExpressions(rawInput: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var parenDepth = 0
    var brackDepth = 0
    var braceDepth = 0
    var inQuotes = false
    var i = 0
    while (i < rawInput.length) {
        val c = rawInput[i]
        if (c == '\\' && inQuotes && i + 1 < rawInput.length) {
            current.append(c).append(rawInput[i + 1])
            i += 2
            continue
        }
        if (c == '"') {
            inQuotes = !inQuotes
            current.append(c)
        } else if (!inQuotes) {
            when (c) {
                '(' -> {
                    parenDepth++
                    current.append(c)
                }
                ')' -> {
                    if (parenDepth > 0) parenDepth--
                    current.append(c)
                }
                '[' -> {
                    brackDepth++
                    current.append(c)
                }
                ']' -> {
                    if (brackDepth > 0) brackDepth--
                    current.append(c)
                }
                '{' -> {
                    braceDepth++
                    current.append(c)
                }
                '}' -> {
                    if (braceDepth > 0) braceDepth--
                    current.append(c)
                }
                ';', ',' -> {
                    if (parenDepth == 0 && brackDepth == 0 && braceDepth == 0) {
                        val trimmed = current.toString().trim()
                        if (trimmed.isNotEmpty()) {
                            result.add(trimmed)
                        }
                        current.setLength(0)
                    } else {
                        current.append(c)
                    }
                }
                else -> current.append(c)
            }
        } else {
            current.append(c)
        }
        i++
    }
    val tail = current.toString().trim()
    if (tail.isNotEmpty()) {
        result.add(tail)
    }
    return result
}

/**
 * Analyzes a function, lambda, or algebraic expression inside [modName] in [projectName]
 * using the C11 engine's Symbolic CAS (`sym_simplify`, `sym_diff`, `sym_integ`, `sym_taylor`, `sym_solve`)
 * and runtime type/unit introspection on the single shared `Engine*` instance.
 */
fun MathEngineManager.analyzeSymbolicInModule(
    modName: String,
    targetExpr: String,
    projectName: String = activeProjectName,
    moduleSource: String? = null,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    onDone: (ok: Boolean, analysisText: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || targetExpr.isBlank()) {
                onDone(false, "Engine not ready or empty expression")
                return@execute
            }
            val effMod = modName.ifBlank { "default" }
            val trimmed = targetExpr.trim()
            synchronized(this) {
                val handle = ensureSharedEngineSync(ctx, safeProj)
                if (handle == 0L) {
                    onDone(false, "Shared engine not ready")
                    return@execute
                }
                registerHostExternsOnHandle(handle, safeProj)
                ensureModuleDeclarationsLinkedSync(ctx, safeProj, effMod, moduleSource, projectSourcesByFileName)
                MathEngineBridge.nativeSetModule(handle, effMod, true)

                var varName = "x"
                var symExpr = trimmed

                val fnDefMatch = inlineFnDefExtractRegex.matchEntire(trimmed)
                val lambdaMatch = lambdaExprRegex.matchEntire(trimmed)
                if (fnDefMatch != null) {
                    val definedName = fnDefMatch.groupValues[1]
                    evalInModuleWithoutAutoMain(handle, effMod, trimmed)
                    symExpr = "$definedName(x)"
                } else if (lambdaMatch != null) {
                    varName = lambdaMatch.groupValues[1].trim().ifEmpty { "x" }
                    symExpr = lambdaMatch.groupValues[2].trim()
                } else if (simpleIdentRegex.matches(trimmed) && moduleSource != null) {
                    val fnBodyRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*" + Regex.escape(trimmed) + "\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::[^)]*)?\\)\\s*(?:->[^=]+)?=\\s*(.+)$")
                    val m = fnBodyRegex.find(moduleSource)
                    if (m != null) {
                        varName = m.groupValues[1].trim().ifEmpty { "x" }
                        symExpr = m.groupValues[2].trim()
                    }
                }

                val report = StringBuilder()
                report.append("=== Symbolic CAS & Expression Analysis [$effMod] ===\n")
                report.append("Target     : $trimmed\n")
                report.append("Variable   : $varName\n")
                report.append("Expression : $symExpr\n\n")

                val simplified = runCatching { MathEngineBridge.nativeSymSimplify(handle, symExpr) }.getOrNull()
                val deriv = runCatching { MathEngineBridge.nativeSymDiff(handle, varName, symExpr) }.getOrNull()
                val integ = runCatching { MathEngineBridge.nativeSymInteg(handle, varName, symExpr) }.getOrNull()
                val roots = runCatching {
                    val eq = if (symExpr.contains("==") || symExpr.contains("=")) symExpr else "$symExpr == 0"
                    MathEngineBridge.nativeSymSolve(handle, varName, eq)
                }.getOrNull()

                val taylorWire = runCatching {
                    evalInModuleWithoutAutoMain(handle, effMod, "sym_taylor($varName, $symExpr, 0, 4)")
                }.getOrNull()
                val taylorRes = taylorWire?.let { MathEngineBridge.EvalResult.parseWire(it) }

                var anySuccess = false
                if (!simplified.isNullOrBlank()) {
                    report.append("• Simplified : $simplified\n")
                    anySuccess = true
                }
                if (!deriv.isNullOrBlank()) {
                    report.append("• d/d$varName       : $deriv\n")
                    anySuccess = true
                }
                if (!integ.isNullOrBlank()) {
                    report.append("• ∫ d$varName       : $integ + C\n")
                    anySuccess = true
                }
                if (taylorRes != null && taylorRes.ok) {
                    val cleanTaylor = taylorRes.value.removeSurrounding("\"")
                    report.append("• Taylor(0,4): $cleanTaylor\n")
                    anySuccess = true
                }
                if (!roots.isNullOrBlank()) {
                    report.append("• Roots (=0) : $roots\n")
                    anySuccess = true
                }

                val probeWire = runCatching {
                    evalInModuleWithoutAutoMain(handle, effMod, "{ $varName := 1 ; $symExpr }")
                }.getOrNull()
                val probeRes = probeWire?.let { MathEngineBridge.EvalResult.parseWire(it) }
                if (probeRes != null && probeRes.ok) {
                    val uBadge = if (probeRes.unit != "none" && probeRes.unit.isNotEmpty()) " [${probeRes.unit}]" else ""
                    report.append("\n• Eval @$varName=1  : ${probeRes.value} : ${probeRes.type}$uBadge\n")
                    anySuccess = true
                }

                if (anySuccess) {
                    onDone(true, report.toString().trimEnd())
                } else {
                    val err = MathEngineBridge.nativeLastError(handle) ?: "Could not analyze '$targetExpr'"
                    onDone(false, err)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "analyzeSymbolicInModule failed", t)
            onDone(false, t.message ?: "Symbolic analysis error")
        }
    }
}

fun MathEngineManager.disassembleInModule(
    modName: String,
    targetExpr: String,
    projectName: String = activeProjectName,
    moduleSource: String? = null,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    onDone: (ok: Boolean, disasmText: String) -> Unit
) = analyzeSymbolicInModule(modName, targetExpr, projectName, moduleSource, projectSourcesByFileName, onDone)

/**
 * Samples a single curve expression/function/vector on [handle] inside [effMod]:
 * - Uses the C11 Native Batch Plotter (`MathEngineBridge.nativeSamplePlot`) first so the AST is
 *   parsed once and all `N` sample points are evaluated in a single C loop.
 * - Falls back to Kotlin-side `evalInModuleWithoutAutoMain` if a legacy `.so` without
 *   `nativeSamplePlot` is loaded or if the expression is a `vec<vec2>` struct trajectory.
 */
internal fun MathEngineManager.sampleSinglePlotSeriesSync(
    handle: Long,
    effMod: String,
    exprOrFn: String,
    xMin: Double,
    xMax: Double,
    samples: Int
): Pair<PlotSeries?, String?> {
    val trimmed = exprOrFn.trim()
    if (trimmed.isEmpty()) return null to "Empty plot expression"

    val lo = if (xMin < xMax) xMin else xMax - 1.0
    val hi = if (xMax > xMin) xMax else xMin + 10.0
    val n = samples.coerceIn(16, 512)

    // 1. Fast Path: Native C11 Batch Plotter (`eng_sample_plot` via JNI `nativeSamplePlot`)
    val batchPairs = try {
        MathEngineBridge.nativeSamplePlot(handle, effMod, trimmed, lo, hi, n)
    } catch (_: UnsatisfiedLinkError) {
        null
    } catch (_: Throwable) {
        null
    }

    if (batchPairs != null && batchPairs.size >= 2) {
        val pairCount = batchPairs.size / 2
        val pts = ArrayList<Pair<Double, Double>>(pairCount)
        var i = 0
        while (i + 1 < batchPairs.size) {
            val px = batchPairs[i]
            val py = batchPairs[i + 1]
            if (px.isFinite() && py.isFinite()) {
                pts.add(px to py)
            }
            i += 2
        }
        if (pts.isNotEmpty()) {
            val label = if (simpleIdentRegex.matches(trimmed)) "$trimmed(x)" else trimmed
            return PlotSeries(label, pts) to null
        }
    }

    // 2. Fallback Path: Check for `vec<vec2>` parametric trajectories or legacy `.so`
    val directWire = runCatching {
        evalInModuleWithoutAutoMain(handle, effMod, trimmed)
    }.getOrNull()
    val directRes = directWire?.let { MathEngineBridge.EvalResult.parseWire(it) }

    if (directRes != null && directRes.ok && (directRes.type == "vec" || directRes.type == "mat")) {
        val rawVal = directRes.value
        val vec2Regex = Regex("vec2\\(\\s*(?:x\\s*=\\s*)?([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\s*,\\s*(?:y\\s*=\\s*)?([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\s*\\)")
        val v2Matches = vec2Regex.findAll(rawVal).toList()
        if (v2Matches.isNotEmpty()) {
            val pts = v2Matches.mapNotNull { m ->
                val px = m.groupValues[1].toDoubleOrNull()
                val py = m.groupValues[2].toDoubleOrNull()
                if (px != null && py != null && px.isFinite() && py.isFinite()) px to py else null
            }
            if (pts.isNotEmpty()) {
                return PlotSeries("$trimmed (vec2)", pts) to null
            }
        }
        val numRegex = Regex("[-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?")
        val nums = numRegex.findAll(rawVal).mapNotNull { it.value.toDoubleOrNull() }.filter { it.isFinite() }.toList()
        if (nums.isNotEmpty()) {
            val pts = nums.mapIndexed { idx, y -> idx.toDouble() to y }
            return PlotSeries("$trimmed (${nums.size} pts)", pts) to null
        }
    }

    val step = (hi - lo) / (n - 1).toDouble()
    val pts = ArrayList<Pair<Double, Double>>(n)
    val isCallableIdentifier = (directRes != null && directRes.ok && directRes.type == "fn") ||
        simpleIdentRegex.matches(trimmed)

    var useCallSyntax = false
    if (isCallableIdentifier) {
        val probeWire = runCatching {
            evalInModuleWithoutAutoMain(handle, effMod, "$trimmed($lo)")
        }.getOrNull()
        val probeRes = probeWire?.let { MathEngineBridge.EvalResult.parseWire(it) }
        if (probeRes != null && probeRes.ok) {
            useCallSyntax = true
        }
    }

    var lastErr: String? = null
    for (i in 0 until n) {
        val x = lo + i * step
        val evalExpr = if (useCallSyntax) {
            "$trimmed($x)"
        } else {
            "{ x := $x ; t := $x ; $trimmed }"
        }
        val w = runCatching {
            evalInModuleWithoutAutoMain(handle, effMod, evalExpr)
        }.getOrNull()
        val r = w?.let { MathEngineBridge.EvalResult.parseWire(it) }
        if (r != null && r.ok) {
            val numToken = r.value.trim().split(Regex("\\s+")).firstOrNull()
            val y = numToken?.toDoubleOrNull()
            if (y != null && y.isFinite()) {
                pts.add(x to y)
            }
        } else if (r != null && !r.ok && lastErr == null) {
            lastErr = r.error
        }
    }

    return if (pts.isNotEmpty()) {
        val title = if (useCallSyntax) "$trimmed(x)" else trimmed
        PlotSeries(title, pts) to null
    } else {
        null to (lastErr ?: "Expression '$trimmed' did not produce finite numeric values over [$lo, $hi]")
    }
}

/**
 * Evaluates one or more plot expressions (separated by top-level `;` or `,`, e.g. `sin(x); cos(x)`)
 * inside [modName] in [projectName] using the Native Batch Plotter (`nativeSamplePlot`).
 */
fun MathEngineManager.sampleMultiPlotSeries(
    modName: String,
    exprOrFnList: String,
    xMin: Double,
    xMax: Double,
    samples: Int = 180,
    projectName: String = activeProjectName,
    moduleSource: String? = null,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    onDone: (ok: Boolean, seriesList: List<PlotSeries>, error: String?) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || exprOrFnList.isBlank()) {
                onDone(false, emptyList(), "Empty plot expression")
                return@execute
            }
            val effMod = modName.ifBlank { "default" }
            val subExprs = splitMultiPlotExpressions(exprOrFnList)
            if (subExprs.isEmpty()) {
                onDone(false, emptyList(), "Empty plot expression")
                return@execute
            }

            synchronized(this) {
                val handle = ensureSharedEngineSync(ctx, safeProj)
                if (handle == 0L) {
                    onDone(false, emptyList(), "Shared engine not ready")
                    return@execute
                }
                registerHostExternsOnHandle(handle, safeProj)
                ensureModuleDeclarationsLinkedSync(ctx, safeProj, effMod, moduleSource, projectSourcesByFileName)
                MathEngineBridge.nativeSetModule(handle, effMod, true)

                val seriesList = mutableListOf<PlotSeries>()
                var firstError: String? = null
                for (sub in subExprs.take(6)) {
                    val (series, err) = sampleSinglePlotSeriesSync(handle, effMod, sub, xMin, xMax, samples)
                    if (series != null && series.points.isNotEmpty()) {
                        seriesList.add(series)
                    } else if (err != null && firstError == null) {
                        firstError = err
                    }
                }

                if (seriesList.isNotEmpty()) {
                    onDone(true, seriesList, null)
                } else {
                    onDone(false, emptyList(), firstError ?: "Could not plot '$exprOrFnList'")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sampleMultiPlotSeries failed", t)
            onDone(false, emptyList(), t.message ?: "Plot evaluation error")
        }
    }
}

/**
 * Single-series wrapper around [sampleMultiPlotSeries] kept for call-site compatibility.
 */
fun MathEngineManager.samplePlotPoints(
    modName: String,
    exprOrFn: String,
    xMin: Double,
    xMax: Double,
    samples: Int = 180,
    projectName: String = activeProjectName,
    moduleSource: String? = null,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    onDone: (ok: Boolean, label: String, points: List<Pair<Double, Double>>, error: String?) -> Unit
) {
    sampleMultiPlotSeries(
        modName = modName,
        exprOrFnList = exprOrFn,
        xMin = xMin,
        xMax = xMax,
        samples = samples,
        projectName = projectName,
        moduleSource = moduleSource,
        projectSourcesByFileName = projectSourcesByFileName
    ) { ok, seriesList, err ->
        val first = seriesList.firstOrNull()
        if (ok && first != null) {
            onDone(true, first.label, first.points, null)
        } else {
            onDone(false, exprOrFn.trim(), emptyList(), err)
        }
    }
}
