package com.ecalc

import android.content.Context
import android.os.Build
import android.util.Log
import com.mathengine.MathEngineBridge
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Analyzes a function, lambda, or algebraic expression inside [modName] in [projectName]
 * using the C11 engine's Symbolic CAS (`sym_simplify`, `sym_diff`, `sym_integ`, `sym_taylor`, `sym_solve`)
 * and runtime type/unit introspection.
 */
fun MathEngineManager.analyzeSymbolicInModule(
    modName: String,
    targetExpr: String,
    projectName: String = activeProjectName,
    moduleSource: String? = null,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    onDone: (ok: Boolean, analysisText: String) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
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
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (handle == 0L) {
                    onDone(false, "Project '$safeProj' engine not ready")
                    return@execute
                }
                val projState = projectStates.getOrPut(safeProj) { ProjectRuntimeState(safeProj, handle) }
                registerHostExternsOnHandle(handle, safeProj)
                ensureModuleDeclarationsLinkedSync(projState, effMod, moduleSource, projectSourcesByFileName)
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
 * Evaluates a 1D vector, `vec<vec2>` path, 1-arg function `f`, or expression in `x`/`t`
 * inside [modName] in [projectName] to produce `(x, y)` coordinates for the interactive 2D Plotter.
 */
fun MathEngineManager.samplePlotPoints(
    modName: String,
    exprOrFn: String,
    xMin: Double,
    xMax: Double,
    samples: Int = 160,
    projectName: String = activeProjectName,
    moduleSource: String? = null,
    projectSourcesByFileName: Map<String, String> = emptyMap(),
    onDone: (ok: Boolean, label: String, points: List<Pair<Double, Double>>, error: String?) -> Unit
) {
    val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || exprOrFn.isBlank()) {
                onDone(false, exprOrFn, emptyList(), "Empty plot expression")
                return@execute
            }
            val effMod = modName.ifBlank { "default" }
            val trimmed = exprOrFn.trim()
            synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (handle == 0L) {
                    onDone(false, exprOrFn, emptyList(), "Project '$safeProj' engine not ready")
                    return@execute
                }
                val projState = projectStates.getOrPut(safeProj) { ProjectRuntimeState(safeProj, handle) }
                registerHostExternsOnHandle(handle, safeProj)
                ensureModuleDeclarationsLinkedSync(projState, effMod, moduleSource, projectSourcesByFileName)
                MathEngineBridge.nativeSetModule(handle, effMod, true)

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
                            onDone(true, "$trimmed (vec2 path)", pts, null)
                            return@execute
                        }
                    }
                    val numRegex = Regex("[-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?")
                    val nums = numRegex.findAll(rawVal).mapNotNull { it.value.toDoubleOrNull() }.filter { it.isFinite() }.toList()
                    if (nums.isNotEmpty()) {
                        val pts = nums.mapIndexed { idx, y -> idx.toDouble() to y }
                        onDone(true, "$trimmed (${nums.size} elements)", pts, null)
                        return@execute
                    }
                }

                val lo = if (xMin < xMax) xMin else xMax - 1.0
                val hi = if (xMax > xMin) xMax else xMin + 10.0
                val n = samples.coerceIn(16, 400)
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

                if (pts.isNotEmpty()) {
                    val title = if (useCallSyntax) "$trimmed(x)" else trimmed
                    onDone(true, title, pts, null)
                } else {
                    onDone(false, trimmed, emptyList(), lastErr ?: "Expression did not produce finite numeric values over [$lo, $hi]")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "samplePlotPoints failed", t)
            onDone(false, exprOrFn, emptyList(), t.message ?: "Plot evaluation error")
        }
    }
}
