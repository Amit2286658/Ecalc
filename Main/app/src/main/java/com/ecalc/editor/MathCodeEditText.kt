package com.ecalc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

/**
 * In-process `.math` Code Editor (`EditText` subclass) with:
 * 1. Live zero-IPC syntax highlighting (keywords, `[module]`, `<[dep]`, `$struct`/`$enum`,
 *    `@intrinsics`/`@entry`, `$"..."` string interpolation, numbers, operators, and `#` comments).
 * 2. Left line-number gutter (`1`, `2`, `3`, ...) with active cursor line highlight.
 * 3. Inline diagnostic error line highlighting (`errorLine` crimson band + `⚠` gutter icon).
 * 4. Smart auto-indent on newline (`{` block indentation) and one-tap snippet insertion.
 * 5. Live Popup Suggestions (Autocomplete) Menu as the user types in the editor — suggesting
 *    `.math` keywords, `pub main()` / `@entry`, `<[dep]` modules, `stdlib` + global patch functions,
 *    and user-defined functions/variables/types from the active project without stealing IME focus.
 */
class MathCodeEditText(context: Context) : EditText(context) {

    data class SuggestionItem(
        val matchKey: String,
        val displayLabel: String,
        val insertText: String,
        val cursorBackOffset: Int = 0,
        val category: String,
        val detail: String,
        val paramBullets: List<String> = emptyList(),
        val returnBullet: String = ""
    )

    internal class SyntaxColorSpan(color: Int) : ForegroundColorSpan(color)
    internal class SyntaxStyleSpan(style: Int) : StyleSpan(style)

    var isDarkThemeMode: Boolean = true
        set(value) {
            field = value
            updateThemePalette()
            applySyntaxHighlighting(editableText)
            dismissSuggestionsPopup()
            invalidate()
        }

    var errorLine: Int? = null
        internal set

    var errorMessage: String? = null
        internal set

    internal var isApplyingSpans = false
    internal var isHandlingAutoIndent = false
    internal var isProgrammaticEdit = false

    // Live Autocomplete Popup Window, Hold-to-Inspect Signature Popup & Project Symbol Context
    internal var suggestionPopup: PopupWindow? = null
    internal var suggestionListContainer: LinearLayout? = null
    internal var suggestionPopupCard: FrameLayout? = null
    internal var suggestionDocPopup: PopupWindow? = null
    internal var suggestionDocCard: FrameLayout? = null
    internal val projectCustomSuggestions = mutableListOf<SuggestionItem>()
    internal var activePrefixRange: IntRange? = null

    internal val gutterBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val gutterDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val lineNumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val errorLineNumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val activeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val errorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val lineBoundsRect = Rect()

    // Syntax palette colors
    internal var colComment = Color.parseColor("#6A9955")
    internal var colModule = Color.parseColor("#4EC9B0")
    internal var colKeyword = Color.parseColor("#C586C0")
    internal var colType = Color.parseColor("#569CD6")
    internal var colIntrinsic = Color.parseColor("#DCDCAA")
    internal var colString = Color.parseColor("#CE9178")
    internal var colInterpBrace = Color.parseColor("#FFD700")
    internal var colNumber = Color.parseColor("#B5CEA8")
    internal var colOperator = Color.parseColor("#13959B")

    internal val density = resources.displayMetrics.density
    internal val basePadTop = (10f * density).toInt()
    internal val basePadRight = (12f * density).toInt()
    internal val basePadBottom = (10f * density).toInt()
    internal var currentGutterWidthPx = (40f * density).toInt()

    companion object {
        internal val KEYWORDS = setOf(
            "pub", "priv", "const", "mut", "main",
            "if", "else", "or", "for", "in", "while", "do",
            "switch", "case", "try", "catch", "throw",
            "return", "break", "continue", "into", "by", "import"
        )

        internal val BUILTIN_TYPES = setOf(
            "real", "int", "str", "vec", "mat", "map", "dict", "fn",
            "complex", "vec2", "vec3", "mat2", "circle", "line2", "plane3", "sphere",
            "true", "false", "self"
        )

        internal val COMMENT_REGEX = Regex("(?m)#.*$")
        internal val MODULE_HDR_REGEX = Regex("(?m)^\\s*<?\\[[a-zA-Z_][a-zA-Z0-9_]*]")
        internal val STRING_REGEX = Regex("\\$?\"(?:\\\\.|[^\"\\\\])*\"")
        internal val INTERP_EXPR_REGEX = Regex("\\{[^{}\\n]+\\}")
        internal val INTRINSIC_REGEX = Regex("@[a-zA-Z_][a-zA-Z0-9_]*")
        internal val DOLLAR_TYPE_REGEX = Regex("\\$[a-zA-Z_][a-zA-Z0-9_]*")
        internal val IDENT_REGEX = Regex("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
        internal val NUMBER_REGEX = Regex("\\b\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?\\b|[πℯ∞√]")
        internal val OPERATOR_REGEX = Regex(":=|=>|->|::|\\.\\.<|\\.\\.\\.|\\?\\?|\\|>|&&|\\|\\||==|!=|<=|>=")

        internal val STATIC_SUGGESTIONS: List<SuggestionItem> = MATH_EDITOR_STATIC_SUGGESTIONS
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
            private var wasUserCharChange = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                insertedNewlineAt = -1
                wasUserCharChange = false
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isApplyingSpans && !isHandlingAutoIndent && errorLine != null && (before > 0 || count > 0)) {
                    errorLine = null
                    errorMessage = null
                }
                if (!isHandlingAutoIndent && !isProgrammaticEdit && !isApplyingSpans) {
                    if (before == 0 && count == 1 && s != null && s[start] == '\n') {
                        insertedNewlineAt = start
                    } else if (count > 0 || before > 0) {
                        wasUserCharChange = true
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (s == null || isApplyingSpans) return
                if (!isHandlingAutoIndent && insertedNewlineAt >= 0 && insertedNewlineAt < s.length) {
                    val nlPos = insertedNewlineAt
                    insertedNewlineAt = -1
                    dismissSuggestionsPopup()
                    applySmartAutoIndent(s, nlPos)
                }
                updateGutterPadding(lineCount.coerceAtLeast(1))
                applySyntaxHighlighting(s)
                if (wasUserCharChange && !isProgrammaticEdit && !isHandlingAutoIndent && hasFocus()) {
                    ensureCursorVisible()
                    updateSuggestionsForCurrentCursor(s)
                }
            }
        })
    }

    /**
     * Scrolls the editor vertically when needed so the active cursor line (plus a 1-line margin)
     * stays visible above the bottom of the editor viewport (especially when the soft keyboard is up).
     */
    fun ensureCursorVisible() {
        val textLayout = layout ?: return
        if (height <= 0) return
        val cur = selectionStart.coerceIn(0, text?.length ?: 0)
        val line = textLayout.getLineForOffset(cur)
        val lineTop = textLayout.getLineTop(line)
        val lineBottom = textLayout.getLineBottom(line)
        val visibleContentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        val extraMargin = (lineBottom - lineTop).coerceAtLeast((18f * density).toInt())
        if (lineBottom + extraMargin > scrollY + visibleContentHeight) {
            val targetScrollY = (lineBottom + extraMargin - visibleContentHeight).coerceAtLeast(0)
            scrollTo(scrollX, targetScrollY)
        } else if (lineTop < scrollY) {
            scrollTo(scrollX, lineTop.coerceAtLeast(0))
        }
    }

    /**
     * Updates the editor's autocomplete symbol table with sibling `[module]` names, functions,
     * variables, constants, and `$struct`/`$enum` types from the active project.
     */
    fun updateProjectAutocompleteContext(projectSourcesByFile: Map<String, String>) {
        projectCustomSuggestions.clear()
        val seenKeys = HashSet<String>()
        val fnRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()\"]*)\\)\\s*(?:->\\s*([^=:]+)|:\\s*([a-zA-Z0-9_,\\s]+))?(?::=|=)(?![=>])")
        val varRegex = Regex("(?m)^\\s*(?:(?:pub|priv|const|mut)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::\\s*([a-zA-Z_][a-zA-Z0-9_<>,\\s]*))?(?::=|=)(?![=>])")
        val typeRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:struct\\s+|\\$)([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:\\(([^()]*)\\))?")

        for ((fileName, rawSrc) in projectSourcesByFile) {
            val modName = MathEngineManager.deriveModuleName(fileName, rawSrc)
            val depTrigger = "<[$modName"
            if (seenKeys.add(depTrigger)) {
                projectCustomSuggestions.add(
                    SuggestionItem(
                        matchKey = depTrigger,
                        displayLabel = "<[$modName]",
                        insertText = "<[$modName]",
                        cursorBackOffset = 0,
                        category = "mod",
                        detail = "Project module ($fileName)",
                        paramBullets = listOf("$modName: module — Project module defined in $fileName"),
                        returnBullet = "void — Links public functions, types, and constants from [$modName]"
                    )
                )
            }
            for (m in typeRegex.findAll(rawSrc)) {
                val tName = m.groupValues[1]
                val fieldsRaw = m.groupValues.getOrNull(2)?.trim().orEmpty()
                if (tName !in setOf("struct", "enum", "type", "fn") && tName !in BUILTIN_TYPES && seenKeys.add("type:$tName")) {
                    val fBullets = buildParamBulletsFromSignature(fieldsRaw, "Struct/enum field in $tName")
                    projectCustomSuggestions.add(
                        SuggestionItem(
                            matchKey = tName,
                            displayLabel = if (fieldsRaw.isNotEmpty()) "$tName($fieldsRaw)" else "$tName(...)",
                            insertText = "$tName()",
                            cursorBackOffset = 1,
                            category = "type",
                            detail = "Type in [$modName]",
                            paramBullets = fBullets,
                            returnBullet = "$tName — New instance of user type $tName from [$modName]"
                        )
                    )
                }
            }
            for (m in fnRegex.findAll(rawSrc)) {
                val fnName = m.groupValues[1]
                val params = m.groupValues[2].trim()
                val retType = m.groupValues.getOrNull(3)?.trim()?.ifEmpty { null }
                    ?: m.groupValues.getOrNull(4)?.trim()?.ifEmpty { null }
                    ?: "any"
                if (fnName !in KEYWORDS && seenKeys.add("fn:$fnName")) {
                    val hasParams = params.isNotEmpty()
                    val pBullets = buildParamBulletsFromSignature(params, "Parameter of $fnName in [$modName]")
                    projectCustomSuggestions.add(
                        SuggestionItem(
                            matchKey = fnName,
                            displayLabel = "$fnName($params)",
                            insertText = "$fnName()",
                            cursorBackOffset = if (hasParams) 1 else 0,
                            category = "proj",
                            detail = "fn in [$modName]",
                            paramBullets = pBullets,
                            returnBullet = "$retType — Computed result of $fnName(...) from [$modName]"
                        )
                    )
                }
            }
            for (m in varRegex.findAll(rawSrc)) {
                val vName = m.groupValues[1]
                val vType = m.groupValues.getOrNull(2)?.trim()?.ifEmpty { null } ?: "real | any"
                if (vName !in KEYWORDS && vName !in BUILTIN_TYPES && seenKeys.add("var:$vName")) {
                    projectCustomSuggestions.add(
                        SuggestionItem(
                            matchKey = vName,
                            displayLabel = vName,
                            insertText = vName,
                            cursorBackOffset = 0,
                            category = "var",
                            detail = "var in [$modName]",
                            paramBullets = listOf("None — Bound module variable or constant in [$modName]"),
                            returnBullet = "$vType — Current value of $vName in [$modName]"
                        )
                    )
                }
            }
        }
    }

    /** Suppresses the autocomplete popup when code is loaded programmatically via `setText`. */
    override fun setText(text: CharSequence?, type: BufferType?) {
        isProgrammaticEdit = true
        try {
            dismissSuggestionsPopup()
            super.setText(text, type)
        } finally {
            isProgrammaticEdit = false
        }
    }

    internal fun findHostOverlayContainer(): FrameLayout? {
        var cur: android.view.ViewParent? = parent
        var bestFrame: FrameLayout? = null
        while (cur != null) {
            if (cur is FrameLayout) {
                bestFrame = cur
                if (cur.id == android.R.id.content) break
            }
            cur = cur.parent
        }
        return bestFrame
    }

    fun isSuggestionsPopupShowing(): Boolean =
        (suggestionPopupCard?.parent != null && suggestionPopupCard?.visibility == View.VISIBLE) ||
            suggestionPopup?.isShowing == true

    fun dismissSuggestionDocPopup() {
        runCatching {
            suggestionDocCard?.let { card ->
                if (card.childCount > 0) {
                    Frost.clearLiveBlur(card.getChildAt(0))
                }
                (card.parent as? ViewGroup)?.removeView(card)
            }
            suggestionDocCard = null
            if (suggestionDocPopup?.isShowing == true) {
                suggestionDocPopup?.dismiss()
            }
            suggestionDocPopup = null
        }
    }

    fun dismissSuggestionsPopup() {
        activePrefixRange = null
        dismissSuggestionDocPopup()
        runCatching {
            suggestionPopupCard?.let { card ->
                card.visibility = View.GONE
                (card.parent as? ViewGroup)?.removeView(card)
            }
            if (suggestionPopup?.isShowing == true) {
                suggestionPopup?.dismiss()
            }
        }
    }

    override fun onDetachedFromWindow() {
        dismissSuggestionsPopup()
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        if (!focused) {
            dismissSuggestionsPopup()
        }
    }

    internal fun updateThemePalette() {
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

    internal fun updateGutterPadding(lines: Int) {
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

    internal fun applySmartAutoIndent(s: Editable, nlPos: Int) {
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
        dismissSuggestionsPopup()
        isProgrammaticEdit = true
        try {
            val start = selectionStart.coerceAtLeast(0)
            val end = selectionEnd.coerceAtLeast(start)
            editable.replace(start, end, snippet)
            val newCursor = (start + snippet.length - cursorBackOffset).coerceIn(0, editable.length)
            setSelection(newCursor)
        } finally {
            isProgrammaticEdit = false
        }
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
        if (selStart != selEnd) {
            dismissSuggestionsPopup()
        }
        invalidate()
    }

    internal fun applySyntaxHighlighting(editable: Editable?) {
        if (editable == null || isApplyingSpans) return
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

            // 4. @intrinsics & @directives (@entry, @dim, @unit_dim, @file_read, @json_parse, ...)
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