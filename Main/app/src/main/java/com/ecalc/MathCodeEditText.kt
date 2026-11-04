package com.ecalc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import kotlin.math.max

/**
 * In-process `.math` Code Editor (`EditText` subclass) with:
 * 1. Live zero-IPC syntax highlighting (keywords, `[module]`, `<[dep]`, `$struct`/`$enum`,
 *    `@intrinsics`, `$"..."` string interpolation, numbers, operators, and `#` comments).
 * 2. Left line-number gutter (`1`, `2`, `3`, ...) with active cursor line highlight.
 * 3. Inline diagnostic error line highlighting (`errorLine` crimson band + `⚠` gutter icon).
 * 4. Smart auto-indent on newline (`{` block indentation) and one-tap snippet insertion.
 */
class MathCodeEditText(context: Context) : EditText(context) {

    private class SyntaxColorSpan(color: Int) : ForegroundColorSpan(color)
    private class SyntaxStyleSpan(style: Int) : StyleSpan(style)

    var isDarkThemeMode: Boolean = true
        set(value) {
            field = value
            updateThemePalette()
            applySyntaxHighlighting(editableText)
            invalidate()
        }

    var errorLine: Int? = null
        private set

    var errorMessage: String? = null
        private set

    private var isApplyingSpans = false
    private var isHandlingAutoIndent = false

    private val gutterBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gutterDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineNumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val errorLineNumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val errorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineBoundsRect = Rect()

    // Syntax palette colors
    private var colComment = Color.parseColor("#6A9955")
    private var colModule = Color.parseColor("#4EC9B0")
    private var colKeyword = Color.parseColor("#C586C0")
    private var colType = Color.parseColor("#569CD6")
    private var colIntrinsic = Color.parseColor("#DCDCAA")
    private var colString = Color.parseColor("#CE9178")
    private var colInterpBrace = Color.parseColor("#FFD700")
    private var colNumber = Color.parseColor("#B5CEA8")
    private var colOperator = Color.parseColor("#13959B")

    private val density = resources.displayMetrics.density
    private val basePadTop = (10f * density).toInt()
    private val basePadRight = (12f * density).toInt()
    private val basePadBottom = (10f * density).toInt()
    private var currentGutterWidthPx = (40f * density).toInt()

    companion object {
        private val KEYWORDS = setOf(
            "pub", "priv", "const", "mut",
            "if", "else", "or", "for", "in", "while", "do",
            "switch", "case", "try", "catch", "throw",
            "return", "break", "continue", "into", "by", "import"
        )

        private val BUILTIN_TYPES = setOf(
            "real", "int", "str", "vec", "mat", "map", "dict", "fn",
            "complex", "vec2", "vec3", "mat2", "circle", "line2", "plane3", "sphere",
            "true", "false", "self"
        )

        private val COMMENT_REGEX = Regex("(?m)#.*$")
        private val MODULE_HDR_REGEX = Regex("(?m)^\\s*<?\\[[a-zA-Z_][a-zA-Z0-9_]*]")
        private val STRING_REGEX = Regex("\\$?\"(?:\\\\.|[^\"\\\\])*\"")
        private val INTERP_EXPR_REGEX = Regex("\\{[^{}\\n]+\\}")
        private val INTRINSIC_REGEX = Regex("@[a-zA-Z_][a-zA-Z0-9_]*")
        private val DOLLAR_TYPE_REGEX = Regex("\\$[a-zA-Z_][a-zA-Z0-9_]*")
        private val IDENT_REGEX = Regex("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
        private val NUMBER_REGEX = Regex("\\b\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?\\b|[πℯ∞√]")
        private val OPERATOR_REGEX = Regex(":=|=>|->|::|\\.\\.<|\\.\\.\\.|\\?\\?|\\|>|&&|\\|\\||==|!=|<=|>=")
    }

    init {
        gravity = Gravity.TOP or Gravity.START
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.MONOSPACE
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setHorizontallyScrolling(false)

        lineNumPaint.typeface = Typeface.MONOSPACE
        lineNumPaint.textAlign = Paint.Align.RIGHT
        lineNumPaint.textSize = 11f * resources.displayMetrics.scaledDensity

        errorLineNumPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        errorLineNumPaint.textAlign = Paint.Align.RIGHT
        errorLineNumPaint.textSize = 11f * resources.displayMetrics.scaledDensity

        gutterDividerPaint.strokeWidth = max(1f, 1f * density)

        updateThemePalette()
        updateGutterPadding(1)

        addTextChangedListener(object : TextWatcher {
            private var insertedNewlineAt = -1

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                insertedNewlineAt = -1
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isApplyingSpans && !isHandlingAutoIndent && errorLine != null && (before > 0 || count > 0)) {
                    errorLine = null
                    errorMessage = null
                }
                if (!isHandlingAutoIndent && before == 0 && count == 1 && s != null && s[start] == '\n') {
                    insertedNewlineAt = start
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (s == null || isApplyingSpans) return
                if (!isHandlingAutoIndent && insertedNewlineAt >= 0 && insertedNewlineAt < s.length) {
                    val nlPos = insertedNewlineAt
                    insertedNewlineAt = -1
                    applySmartAutoIndent(s, nlPos)
                }
                updateGutterPadding(lineCount.coerceAtLeast(1))
                applySyntaxHighlighting(s)
            }
        })
    }

    private fun updateThemePalette() {
        if (isDarkThemeMode) {
            gutterBgPaint.color = Color.parseColor("#181818")
            gutterDividerPaint.color = Color.parseColor("#333333")
            lineNumPaint.color = Color.parseColor("#6E7681")
            errorLineNumPaint.color = Color.parseColor("#FF6B6B")
            activeLinePaint.color = Color.parseColor("#1A13959B")
            errorLinePaint.color = Color.parseColor("#38FF5252")

            colComment = Color.parseColor("#6A9955")
            colModule = Color.parseColor("#4EC9B0")
            colKeyword = Color.parseColor("#C586C0")
            colType = Color.parseColor("#569CD6")
            colIntrinsic = Color.parseColor("#DCDCAA")
            colString = Color.parseColor("#CE9178")
            colInterpBrace = Color.parseColor("#FFD700")
            colNumber = Color.parseColor("#B5CEA8")
            colOperator = Color.parseColor("#26C6DA")
        } else {
            gutterBgPaint.color = Color.parseColor("#F2F3F5")
            gutterDividerPaint.color = Color.parseColor("#DCDCDC")
            lineNumPaint.color = Color.parseColor("#8C959F")
            errorLineNumPaint.color = Color.parseColor("#D32F2F")
            activeLinePaint.color = Color.parseColor("#18E58A7B")
            errorLinePaint.color = Color.parseColor("#2EFF5252")

            colComment = Color.parseColor("#008000")
            colModule = Color.parseColor("#00796B")
            colKeyword = Color.parseColor("#AF00DB")
            colType = Color.parseColor("#005CC5")
            colIntrinsic = Color.parseColor("#B06000")
            colString = Color.parseColor("#A31515")
            colInterpBrace = Color.parseColor("#C77800")
            colNumber = Color.parseColor("#098658")
            colOperator = Color.parseColor("#D85A4A")
        }
    }

    private fun updateGutterPadding(lines: Int) {
        val digits = when {
            lines >= 1000 -> 4
            lines >= 100 -> 3
            else -> 2
        }
        val charW = lineNumPaint.measureText("8")
        val targetGutter = (charW * (digits + 1.4f) + 12f * density).toInt()
        val textLeftPad = targetGutter + (8f * density).toInt()
        if (targetGutter != currentGutterWidthPx || paddingLeft != textLeftPad) {
            currentGutterWidthPx = targetGutter
            setPadding(textLeftPad, basePadTop, basePadRight, basePadBottom)
        }
    }

    private fun applySmartAutoIndent(s: Editable, nlPos: Int) {
        val prevLineStart = s.lastIndexOf('\n', nlPos - 1).let { if (it < 0) 0 else it + 1 }
        val prevLine = s.subSequence(prevLineStart, nlPos).toString()
        val leadingSpaces = prevLine.takeWhile { it == ' ' || it == '\t' }
        val trimmedPrev = prevLine.trimEnd()
        val extraIndent = if (trimmedPrev.endsWith("{")) "    " else ""
        val indentToInsert = leadingSpaces + extraIndent
        if (indentToInsert.isNotEmpty()) {
            isHandlingAutoIndent = true
            try {
                s.insert(nlPos + 1, indentToInsert)
            } finally {
                isHandlingAutoIndent = false
            }
        }
    }

    /** Inserts a symbol or multi-line snippet at the current cursor position. */
    fun insertSnippet(snippet: String, cursorBackOffset: Int = 0) {
        val editable = editableText ?: return
        val start = selectionStart.coerceAtLeast(0)
        val end = selectionEnd.coerceAtLeast(start)
        editable.replace(start, end, snippet)
        val newCursor = (start + snippet.length - cursorBackOffset).coerceIn(0, editable.length)
        setSelection(newCursor)
        requestFocus()
    }

    /** Highlights a 1-based offending line in the editor and scrolls to it. */
    fun highlightErrorLine(lineOneBased: Int?, message: String? = null) {
        errorLine = lineOneBased?.takeIf { it >= 1 }
        errorMessage = message
        val targetLine = errorLine
        if (targetLine != null && layout != null) {
            val textStr = text?.toString().orEmpty()
            val lines = textStr.split('\n')
            val clampedIdx = (targetLine - 1).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
            var charOffset = 0
            for (i in 0 until clampedIdx) {
                charOffset += lines[i].length + 1
            }
            if (charOffset <= textStr.length) {
                setSelection(charOffset)
            }
        }
        invalidate()
    }

    fun clearErrorHighlight() {
        if (errorLine != null || errorMessage != null) {
            errorLine = null
            errorMessage = null
            invalidate()
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        invalidate()
    }

    private fun applySyntaxHighlighting(editable: Editable?) {
        if (editable == null || isApplyingSpans) return
        // Avoid disturbing active IME composing spans only if composition is invalid
        val compStart = BaseInputConnection.getComposingSpanStart(editable)
        val compEnd = BaseInputConnection.getComposingSpanEnd(editable)
        val hasComposing = compStart >= 0 && compEnd > compStart

        isApplyingSpans = true
        try {
            val oldColors = editable.getSpans(0, editable.length, SyntaxColorSpan::class.java)
            for (span in oldColors) {
                editable.removeSpan(span)
            }
            val oldStyles = editable.getSpans(0, editable.length, SyntaxStyleSpan::class.java)
            for (span in oldStyles) {
                editable.removeSpan(span)
            }

            val src = editable.toString()
            if (src.isEmpty()) return

            // Track masked intervals (strings and comments) so inner keywords/numbers aren't recolored
            val masked = BooleanArray(src.length)

            fun setColor(start: Int, end: Int, color: Int, bold: Boolean = false, italic: Boolean = false) {
                if (start < 0 || end <= start || end > editable.length) return
                if (hasComposing && start < compEnd && end > compStart) return
                editable.setSpan(SyntaxColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (bold && italic) {
                    editable.setSpan(SyntaxStyleSpan(Typeface.BOLD_ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (bold) {
                    editable.setSpan(SyntaxStyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (italic) {
                    editable.setSpan(SyntaxStyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            // 1. String literals ("..." and $"...")
            for (m in STRING_REGEX.findAll(src)) {
                val st = m.range.first
                val en = m.range.last + 1
                for (i in st until en) masked[i] = true
                setColor(st, en, colString)
                if (m.value.startsWith("$\"")) {
                    for (im in INTERP_EXPR_REGEX.findAll(m.value)) {
                        setColor(st + im.range.first, st + im.range.last + 1, colInterpBrace, bold = true)
                    }
                }
            }

            // 2. Line comments (# ...) outside strings
            for (m in COMMENT_REGEX.findAll(src)) {
                val st = m.range.first
                if (st < masked.size && masked[st]) continue
                val en = m.range.last + 1
                for (i in st until en) masked[i] = true
                setColor(st, en, colComment, italic = true)
            }

            // 3. Module headers [mod] & dependencies <[dep]
            for (m in MODULE_HDR_REGEX.findAll(src)) {
                val st = m.range.first
                val en = m.range.last + 1
                if (masked[st]) continue
                for (i in st until en) masked[i] = true
                setColor(st, en, colModule, bold = true)
            }

            // 4. @intrinsics & @directives (@dim, @unit_dim, @file_read, @json_parse, ...)
            for (m in INTRINSIC_REGEX.findAll(src)) {
                val st = m.range.first
                val en = m.range.last + 1
                if (masked[st]) continue
                for (i in st until en) masked[i] = true
                setColor(st, en, colIntrinsic, bold = true)
            }

            // 5. $struct, $enum, $type declarations
            for (m in DOLLAR_TYPE_REGEX.findAll(src)) {
                val st = m.range.first
                val en = m.range.last + 1
                if (masked[st]) continue
                for (i in st until en) masked[i] = true
                setColor(st, en, colType, bold = true)
            }

            // 6. Multi-character operators (:=, =>, ->, ::, ..<, ..., ??, |>)
            for (m in OPERATOR_REGEX.findAll(src)) {
                val st = m.range.first
                val en = m.range.last + 1
                if (masked[st]) continue
                setColor(st, en, colOperator, bold = true)
            }

            // 7. Keywords & built-in types
            for (m in IDENT_REGEX.findAll(src)) {
                val st = m.range.first
                val en = m.range.last + 1
                if (masked[st]) continue
                val word = m.value
                when (word) {
                    in KEYWORDS -> {
                        for (i in st until en) masked[i] = true
                        setColor(st, en, colKeyword, bold = true)
                    }
                    in BUILTIN_TYPES -> {
                        for (i in st until en) masked[i] = true
                        setColor(st, en, colType, bold = false)
                    }
                }
            }

            // 8. Numbers & mathematical constants
            for (m in NUMBER_REGEX.findAll(src)) {
                val st = m.range.first
                val en = m.range.last + 1
                if (masked[st]) continue
                setColor(st, en, colNumber)
            }
        } finally {
            isApplyingSpans = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        val scX = scrollX
        val scY = scrollY
        val viewTop = scY
        val viewBottom = scY + height
        val gutterRight = scX + currentGutterWidthPx.toFloat()

        // 1. Draw left gutter background
        canvas.drawRect(
            scX.toFloat(),
            viewTop.toFloat(),
            gutterRight,
            viewBottom.toFloat(),
            gutterBgPaint
        )

        val textLayout = layout
        if (textLayout != null) {
            val totalVisualLines = textLayout.lineCount
            val textStr = text?.toString().orEmpty()

            // Determine active logical line (1-based) from cursor position
            val cursorPos = selectionStart.coerceIn(0, textStr.length)
            var activeLogicalLine = 1
            for (i in 0 until cursorPos) {
                if (textStr[i] == '\n') activeLogicalLine++
            }

            var logicalLine = 1
            for (vLine in 0 until totalVisualLines) {
                val baseline = getLineBounds(vLine, lineBoundsRect)
                val lineStartOffset = textLayout.getLineStart(vLine)
                val isFirstSubLine = (vLine == 0) || (lineStartOffset > 0 && textStr[lineStartOffset - 1] == '\n')

                if (lineBoundsRect.bottom >= viewTop && lineBoundsRect.top <= viewBottom) {
                    // Highlight error line or active cursor line
                    if (errorLine != null && logicalLine == errorLine) {
                        canvas.drawRect(
                            gutterRight,
                            lineBoundsRect.top.toFloat(),
                            (scX + width).toFloat(),
                            lineBoundsRect.bottom.toFloat(),
                            errorLinePaint
                        )
                    } else if (hasFocus() && logicalLine == activeLogicalLine) {
                        canvas.drawRect(
                            gutterRight,
                            lineBoundsRect.top.toFloat(),
                            (scX + width).toFloat(),
                            lineBoundsRect.bottom.toFloat(),
                            activeLinePaint
                        )
                    }

                    // Draw logical line number in gutter on the first visual line of each logical line
                    if (isFirstSubLine) {
                        val numX = gutterRight - 6f * density
                        if (errorLine != null && logicalLine == errorLine) {
                            canvas.drawText("⚠$logicalLine", numX, baseline.toFloat(), errorLineNumPaint)
                        } else {
                            canvas.drawText(logicalLine.toString(), numX, baseline.toFloat(), lineNumPaint)
                        }
                    }
                }

                val lineEndOffset = textLayout.getLineEnd(vLine)
                if (lineEndOffset > lineStartOffset && lineEndOffset <= textStr.length && textStr[lineEndOffset - 1] == '\n') {
                    logicalLine++
                }
            }
        }

        // 2. Draw gutter divider line
        canvas.drawLine(
            gutterRight,
            viewTop.toFloat(),
            gutterRight,
            viewBottom.toFloat(),
            gutterDividerPaint
        )

        super.onDraw(canvas)
    }
}
