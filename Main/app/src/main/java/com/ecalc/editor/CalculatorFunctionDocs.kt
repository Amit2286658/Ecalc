package com.ecalc

import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic `.math` Doc-Comment Parser & Runtime Documentation Registry.
 *
 * Recognizes the fixed `.math` documentation comment pattern placed directly above a
 * function or operator declaration inside any `.math` file (`Library/*.math`,
 * `patches/*.math`, or Studio project `.math` modules):
 *
 * ```math
 * # @desc <Description of what the function does and its formula>
 * # @param <param_name>: <type> — <What the parameter is, units, and constraints>
 * # @return <return_type> — <What the function returns, units, and range/meaning>
 * fn_name(a: real, b: real) = ...
 * ```
 *
 * If a function or operator in a `.math` file has no `@desc` / `@param` / `@return`
 * comments above it, the catalog extractor automatically falls back to a clean generic
 * description derived from its signature, module name, and expression body.
 */
object CalculatorFunctionDocs {

    /** Parsed documentation block extracted from `# @desc`, `# @param`, `# @return` comments in `.math` files. */
    data class FnDoc(
        val definition: String,
        val params: List<String>,
        val returns: String,
        val signature: String = "",
        val summary: String = ""
    )

    private val dynamicDocsByName = ConcurrentHashMap<String, FnDoc>()

    /** Registers a `.math`-parsed documentation entry at runtime. */
    fun registerFromMathSource(
        name: String,
        signature: String,
        definition: String,
        params: List<String>,
        returns: String
    ) {
        if (name.isBlank() || (definition.isBlank() && params.isEmpty() && returns.isBlank())) return
        val sum = definition
            .substringBefore(" Formula:")
            .substringBefore(" Define:")
            .trim()
            .removeSuffix(".")
        dynamicDocsByName[name] = FnDoc(
            definition = definition,
            params = params,
            returns = returns,
            signature = signature,
            summary = sum
        )
    }

    /** Looks up a runtime-parsed `.math` documentation entry by function or operator name. */
    fun forName(name: String): FnDoc? = dynamicDocsByName[name]

    /**
     * Parses all `# @desc`, `# @param`, and `# @return` doc-comment blocks in [rawSource],
     * keyed by the 1-based line number of the non-comment statement immediately following each block.
     */
    fun parseDocBlocksByLine(rawSource: String): Map<Int, FnDoc> {
        val lines = rawSource.replace("\r\n", "\n").replace("\r", "\n").lines()
        val result = HashMap<Int, FnDoc>()

        val descBuilder = StringBuilder()
        val plainBuilder = StringBuilder()
        val paramsList = ArrayList<String>(4)
        var returnText = ""

        fun resetPending() {
            descBuilder.setLength(0)
            plainBuilder.setLength(0)
            paramsList.clear()
            returnText = ""
        }

        for ((idx, rawLine) in lines.withIndex()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("#")) {
                val content = trimmed.removePrefix("#").trim()
                if (content.isEmpty() ||
                    content.startsWith("===") ||
                    content.startsWith("---") ||
                    content.matches(Regex("^\\d+\\.\\s+.*"))
                ) {
                    continue
                }
                when {
                    content.startsWith("@desc ", ignoreCase = true) ||
                        content.startsWith("@description ", ignoreCase = true) ||
                        content.startsWith("@summary ", ignoreCase = true) -> {
                        val text = content.substringAfter(' ').trim()
                        if (text.isNotEmpty()) {
                            if (descBuilder.isNotEmpty()) descBuilder.append(" ")
                            descBuilder.append(text)
                        }
                    }
                    content.startsWith("@param ", ignoreCase = true) -> {
                        val pText = content.substringAfter(' ').trim()
                        if (pText.isNotEmpty() && !pText.startsWith("None —", ignoreCase = true)) {
                            paramsList.add(pText)
                        }
                    }
                    content.startsWith("@return ", ignoreCase = true) ||
                        content.startsWith("@returns ", ignoreCase = true) -> {
                        val rText = content.substringAfter(' ').trim()
                        if (rText.isNotEmpty()) {
                            returnText = rText
                        }
                    }
                    else -> {
                        if (descBuilder.isEmpty() && paramsList.isEmpty() && returnText.isEmpty()) {
                            if (plainBuilder.isNotEmpty()) plainBuilder.append(" ")
                            plainBuilder.append(content)
                        }
                    }
                }
            } else if (trimmed.isNotEmpty()) {
                val finalDesc = if (descBuilder.isNotEmpty()) descBuilder.toString() else plainBuilder.toString()
                if (finalDesc.isNotEmpty() || paramsList.isNotEmpty() || returnText.isNotEmpty()) {
                    val sum = finalDesc
                        .substringBefore(" Formula:")
                        .substringBefore(" Define:")
                        .trim()
                        .removeSuffix(".")
                    result[idx + 1] = FnDoc(
                        definition = finalDesc,
                        params = paramsList.toList(),
                        returns = returnText,
                        summary = sum
                    )
                    resetPending()
                }
            } else {
                resetPending()
            }
        }
        return result
    }
}

/**
 * Formats the hold-popup info text for a Functions-tab catalog item using the `.math`-parsed
 * documentation stored on the item (or generic fallback descriptions when the `.math` file
 * omitted `@desc` / `@param` / `@return` tags).
 */
fun CalculatorCatalogItem.richPopupText(): String = toPopupInfoText()
