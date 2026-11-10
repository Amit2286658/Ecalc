package com.ecalc

import android.util.Log
import com.mathengine.MathEngineBridge

private val infixOpDeclUpgradeRegex = Regex(
    "^((?:(?:pub|priv)\\s+)*)\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::\\s*([a-zA-Z_][a-zA-Z0-9_]*)(?:\\s*,\\s*([a-zA-Z_][a-zA-Z0-9_]*))?)?\\s*\\)\\s*(\"[^\"]+\"|[a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::\\s*([a-zA-Z_][a-zA-Z0-9_]*)(?:\\s*,\\s*([a-zA-Z_][a-zA-Z0-9_]*))?)?\\s*\\)\\s*(:[<>]?\\d+(?:\\s*->\\s*[^=]+)?)\\s*=\\s*(.+)$",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

/**
 * Replaces top-level binary occurrences of [opSymbol] outside string literals with [replacementOp]
 * so an operator overload on `real` (such as `pub (a: real) "+" (b: real) :<50 = a + b + 1`)
 * can reference the built-in arithmetic operator in its own body without infinite self-recursion.
 */
private fun replaceSelfOperatorOutsideQuotes(rhs: String, opSymbol: String, replacementOp: String): String {
    if (!rhs.contains(opSymbol)) return rhs
    val sb = StringBuilder(rhs.length + 32)
    var inQuotes = false
    var i = 0
    while (i < rhs.length) {
        val c = rhs[i]
        if (c == '\\' && inQuotes && i + 1 < rhs.length) {
            sb.append(c).append(rhs[i + 1])
            i += 2
            continue
        }
        if (c == '"') {
            inQuotes = !inQuotes
            sb.append(c)
            i++
            continue
        }
        if (!inQuotes && rhs.startsWith(opSymbol, i)) {
            val prevChar = rhs.substring(0, i).trimEnd().lastOrNull()
            val nextIdx = i + opSymbol.length
            val nextChar = if (nextIdx < rhs.length) rhs[nextIdx] else ' '
            val isCompound = nextChar == '=' || nextChar == '>' || (opSymbol == "+" && nextChar == '+') ||
                (opSymbol == "-" && nextChar == '-') || (prevChar == '+' && opSymbol == "+") ||
                (prevChar == '-' && opSymbol == "-")
            val isUnary = prevChar == null || prevChar in "({[,:;?=<>&|+-*/%^"
            if (!isCompound && !isUnary) {
                sb.append(" ").append(replacementOp).append(" ")
                i += opSymbol.length
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/**
 * Upgrades user infix operator overload declarations on `real` (or untyped parameters) so that:
 * 1. Omitted unit parameters `(a: real)` or `(a)` become `(a: real, T)`, matching/exceeding the
 *    dispatch specificity of `arith.math`'s generic `T` operators (`+`, `-`, `*`, `/`, `%`, `^`).
 * 2. Self-referential arithmetic operators (`+`, `-`, `*`, `/`, `%`, `^`) in the overload body
 *    automatically dispatch to the underlying intrinsic (`@add`, `@sub`, `@mul`, `@div`, `@mod`, `@pow`)
 *    instead of causing infinite recursion.
 */
internal fun MathEngineManager.upgradeUserOperatorOverloadStatement(stmt: String): String {
    val trimmed = stmt.trim()
    if (trimmed.endsWith("=>") || trimmed.contains("=> @")) return stmt
    val m = infixOpDeclUpgradeRegex.matchEntire(trimmed) ?: return stmt

    val mods = m.groupValues[1]
    val p1Name = m.groupValues[2]
    val p1Type = m.groupValues[3].ifEmpty { "real" }
    val p1Unit = m.groupValues[4]
    val rawOpToken = m.groupValues[5]
    val p2Name = m.groupValues[6]
    val p2Type = m.groupValues[7].ifEmpty { "real" }
    val p2Unit = m.groupValues[8]
    val precAndRet = m.groupValues[9].trim()
    val rhsBody = m.groupValues[10].trim()

    val opClean = rawOpToken.removeSurrounding("\"")
    if (opClean.startsWith("__mep_base_")) return stmt

    val effP1Unit = if (p1Type == "real" && p1Unit.isEmpty()) "T" else p1Unit
    val effP2Unit = if (p2Type == "real" && p2Unit.isEmpty()) "T" else p2Unit

    val p1Spec = if (effP1Unit.isNotEmpty()) "$p1Name: $p1Type, $effP1Unit" else "$p1Name: $p1Type"
    val p2Spec = if (effP2Unit.isNotEmpty()) "$p2Name: $p2Type, $effP2Unit" else "$p2Name: $p2Type"
    val quotedOp = "\"$opClean\""

    val baseIntrinsicInfo: Triple<String, String, String>? = if (p1Type == "real" && p2Type == "real") {
        when (opClean) {
            "+" -> Triple("__mep_base_add", ":<50 -> real, T", "@add")
            "-" -> Triple("__mep_base_sub", ":<50 -> real, T", "@sub")
            "*" -> Triple("__mep_base_mul", ":<60 -> real", "@mul")
            "/" -> Triple("__mep_base_div", ":<60 -> real", "@div")
            "%" -> Triple("__mep_base_mod", ":<60 -> real", "@mod")
            "^" -> Triple("__mep_base_pow", ":>85 -> real", "@pow")
            else -> null
        }
    } else null

    return if (baseIntrinsicInfo != null && rhsBody.contains(opClean)) {
        val (helperOp, helperPrec, intrName) = baseIntrinsicInfo
        val safeRhs = replaceSelfOperatorOutsideQuotes(rhsBody, opClean, helperOp)
        val helperDecl = "priv (__lhs: real, T) \"$helperOp\" (__rhs: real, T) $helperPrec = $intrName(__lhs, __rhs)"
        "$helperDecl\n$mods($p1Spec) $quotedOp ($p2Spec) $precAndRet = $safeRhs"
    } else {
        "$mods($p1Spec) $quotedOp ($p2Spec) $precAndRet = $rhsBody"
    }
}

/**
 * Normalizes `.math` module source code before passing to the C11 Math Engine.
 */
internal fun MathEngineManager.normalizeExtensionSource(raw: String): String {
    val cleaned = MathPatchDownloader.upgradeLegacyCurrencyPatch(
        raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
    )
    val codeLines = mutableListOf<String>()

    for (rawLine in cleaned.lines()) {
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
            codeLines.add(codePart)
        }
    }

    val merged = mutableListOf<String>()
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
                    !prevStripped.startsWith("@entry")
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

    val reservedWords = setOf(
        "pub", "priv", "const", "mut", "if", "else", "while", "for", "in",
        "return", "break", "continue", "or", "try", "catch", "throw"
    )
    var dParen = 0
    var dBrace = 0
    var dBrack = 0
    val finalLines = merged.map { line ->
        val out = if (dParen == 0 && dBrace == 0 && dBrack == 0) {
            val m = unitVarDeclRegex.matchEntire(line)
            if (m != null && m.groupValues[2] !in reservedWords) {
                "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
            } else {
                upgradeUserOperatorOverloadStatement(line)
            }
        } else {
            line
        }
        var inQ = false
        var idx = 0
        while (idx < line.length) {
            val ch = line[idx]
            if (ch == '\\' && inQ && idx + 1 < line.length) {
                idx += 2
                continue
            }
            if (ch == '\"') {
                inQ = !inQ
            } else if (!inQ) {
                when (ch) {
                    '(' -> dParen++
                    ')' -> if (dParen > 0) dParen--
                    '{' -> dBrace++
                    '}' -> if (dBrace > 0) dBrace--
                    '[' -> dBrack++
                    ']' -> if (dBrack > 0) dBrack--
                }
            }
            idx++
        }
        out
    }

    return finalLines.joinToString("\n").trim()
}

@Synchronized
internal fun MathEngineManager.compileAndLoadPatchOnHandleSync(handle: Long, mathSource: String): Triple<Boolean, String, ByteArray?> {
    if (handle == 0L) return Triple(false, "Engine not initialized", null)
    registerHostExternsOnHandle(handle, activeProjectName)
    val trimmed = normalizeExtensionSource(mathSource)
    if (trimmed.isEmpty()) return Triple(false, "Empty .math source", null)
    return try {
        val mathcBytes = runCatching { MathEngineBridge.nativeApplyPatch(handle, trimmed) }.getOrNull()
            ?: MathEngineBridge.nativeCompileExt(handle, trimmed)
        if (mathcBytes == null || mathcBytes.isEmpty()) {
            val err = MathEngineBridge.nativeLastError(handle) ?: "Compilation failed"
            Triple(false, err, null)
        } else {
            modHeaderMultilineRegex.findAll(trimmed).forEach { m ->
                val modName = m.groupValues[1]
                MathEngineBridge.nativeSetModule(handle, modName, true)
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
 * Checks whether a top-level normalized statement is a declaration (`@dim`, `$type`,
 * operator/function definition, or module variable/constant binding) rather than a
 * standalone scratch expression.
 */
internal fun MathEngineManager.isDeclarationOrBindingStatement(stmt: String): Boolean {
    val t = stmt.trim()
    if (t.isEmpty()) return true
    if ((t.startsWith("[") && t.endsWith("]")) || (t.startsWith("<[") && t.endsWith("]"))) return true
    if (t.startsWith("@dim") || t.startsWith("@entry") || t.startsWith("import ")) return true
    val noMods = t.replace(declModifiersRegex, "").trimStart()
    if (noMods.startsWith("$") || noMods.startsWith("struct ") || noMods.startsWith("\"")) {
        return true
    }
    if (noMods.startsWith("(") && opDefPrefixRegex.containsMatchIn(noMods)) {
        return true
    }
    if (fnDefRegex.containsMatchIn(noMods)) return true
    if (varDefRegex.containsMatchIn(noMods)) return true
    return false
}

/**
 * Splits a normalized `.math` module source into balanced top-level declarations,
 * headers, and statements.
 */
internal fun MathEngineManager.splitModuleStatements(rawSource: String): List<String> {
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

@Synchronized
internal fun MathEngineManager.formatModuleVariablesOnHandleSync(handle: Long, modName: String): String {
    if (handle == 0L || modName.isBlank()) return ""
    val raw = runCatching {
        MathEngineBridge.nativeModuleFormatVars(handle, modName)
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
                val vMut = parts[3]
                val vVis = parts[4]
                val vVal = parts[5]
                val unitBadge = if (vUnit != "none" && vUnit.isNotEmpty()) " [$vUnit]" else ""
                val visPad = if (vVis == "priv") "priv" else "pub "
                val mutPad = if (vMut == "const") "const" else "mut  "
                "$visPad $mutPad $vName : $vType$unitBadge = $vVal"
            }
        }
        .joinToString("\n")
}

/** Destroys the single shared `Engine*` instance when the process exits. */
@Synchronized
fun MathEngineManager.shutdown() {
    if (engineHandle != 0L) {
        runCatching { MathEngineBridge.nativeDestroy(engineHandle) }
        engineHandle = 0L
    }
    linkedModuleSourcesByName.clear()
    linkedProjectName = null
    linkedProject1Signature = ""
    isProjectModeActive = false
    MathPatchLoader.resetTracking()
    worker.shutdown()
}

internal fun MathEngineManager.findMatchingParen(s: String, openIdx: Int): Int {
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

internal fun MathEngineManager.rewriteFunctionCalls(
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

internal fun MathEngineManager.replaceInfixNthRoot(expr: String): String {
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

/**
 * Rewrites postfix percentage `%` in Basic Calculator expressions (`200 + 10%`, `200 - 10%`,
 * `200 * 10%`, `50%`) while preserving binary modulo `a % b`.
 */
internal fun MathEngineManager.rewritePostfixPercent(expr: String): String {
    var cur = expr
    var idx = 0
    while (idx < cur.length) {
        if (cur[idx] == '%') {
            var nextNonSpace = idx + 1
            while (nextNonSpace < cur.length && cur[nextNonSpace].isWhitespace()) nextNonSpace++
            val isBinaryMod = nextNonSpace < cur.length &&
                (cur[nextNonSpace].isLetterOrDigit() || cur[nextNonSpace] == '(' || cur[nextNonSpace] == '√')
            if (!isBinaryMod && idx > 0) {
                var endB = idx
                while (endB > 0 && cur[endB - 1].isWhitespace()) endB--
                var startB = endB - 1
                if (startB >= 0 && cur[startB] == ')') {
                    var depth = 0
                    while (startB >= 0) {
                        if (cur[startB] == ')') depth++
                        else if (cur[startB] == '(') {
                            depth--
                            if (depth == 0) break
                        }
                        startB--
                    }
                    if (startB < 0) startB = 0
                } else {
                    while (startB >= 0 && (cur[startB].isLetterOrDigit() || cur[startB] == '.')) {
                        startB--
                    }
                    startB++
                }
                if (startB < endB) {
                    val bTerm = cur.substring(startB, endB)
                    var opPos = startB - 1
                    while (opPos >= 0 && cur[opPos].isWhitespace()) opPos--
                    val opChar = if (opPos >= 0) cur[opPos] else ' '
                    if (opChar == '+' || opChar == '-') {
                        var startA = opPos - 1
                        var pDepth = 0
                        while (startA >= 0) {
                            val ch = cur[startA]
                            if (ch == ')') pDepth++
                            else if (ch == '(') {
                                if (pDepth == 0) {
                                    startA++
                                    break
                                }
                                pDepth--
                            }
                            startA--
                        }
                        if (startA < 0) startA = 0
                        val aTerm = cur.substring(startA, opPos).trim()
                        if (aTerm.isNotEmpty()) {
                            val replacement = "(($aTerm) $opChar (($aTerm) * (($bTerm) / 100.0)))"
                            cur = cur.substring(0, startA) + replacement + cur.substring(idx + 1)
                            idx = startA + replacement.length
                            continue
                        }
                    }
                    val replacement = "(($bTerm) / 100.0)"
                    cur = cur.substring(0, startB) + replacement + cur.substring(idx + 1)
                    idx = startB + replacement.length
                    continue
                }
            }
        }
        idx++
    }
    return cur
}

/** Translates the calculator's display expression into engine dialect. */
fun MathEngineManager.toEngineSyntax(
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
    e = rewritePostfixPercent(e)

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

/**
 * **Basic/Scientific Calculator Evaluation (`eng_eval`) Linked with `project1`**:
 *
 * Uses the single shared `Engine*` instance ([MathEngineManager.engineHandle]) with `stdlib + patches + project1`
 * linked. If another Studio project (`project2`, etc.) was previously active, flushes its user modules in
 * O(1) time via [flushUserModulesSync] and links `project1`'s modules (`custom_ops`, `physics_sim`,
 * `vectors_stats`, `data_io_json`, and any user-added `.math` files in `project1`) so any functions,
 * constants, or operator overloads in `project1` reflect directly in the Basic Calculator.
 */
fun MathEngineManager.evaluate(
    displayExpr: String,
    isDegreeMode: Boolean = true,
    lastAnswer: String = "0.0",
    onDone: (EvalOutcome) -> Unit
) {
    worker.execute {
        try {
            val ctx = appContext
            if (ctx == null || !ensureEngine()) {
                onDone(EvalOutcome(false, "0.0", "", "none", "Engine init failed"))
                return@execute
            }
            val engineExpr = toEngineSyntax(displayExpr, isDegreeMode, lastAnswer)

            synchronized(this) {
                val handle = ensureProject1LinkedForCalculatorSync(ctx)
                if (handle == 0L) {
                    onDone(EvalOutcome(false, "0.0", "", "none", "Engine init failed"))
                    return@execute
                }

                runCatching { MathEngineBridge.nativeClearOutput(handle) }
                val wire = try {
                    MathEngineBridge.nativeEval(handle, engineExpr)
                } catch (t: Throwable) { null }
                val stdout = runCatching { MathEngineBridge.nativeGetOutput(handle).orEmpty() }.getOrDefault("")

                if (wire == null) {
                    onDone(EvalOutcome(false, "0.0", "", "none", "Engine call failed", stdout = stdout))
                    return@execute
                }

                val res = MathEngineBridge.EvalResult.parseWire(wire)
                if (res.ok) {
                    onDone(EvalOutcome(true, res.value, res.type, res.unit, null, stdout = stdout))
                } else {
                    onDone(EvalOutcome(false, "0.0", "", "none", res.error, stdout = stdout))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "evaluate failed", t)
            onDone(EvalOutcome(false, "0.0", "", "none", t.message ?: "Evaluation error"))
        }
    }
}
