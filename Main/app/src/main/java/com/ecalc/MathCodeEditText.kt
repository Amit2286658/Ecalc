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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
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
        val detail: String
    )

    private class SyntaxColorSpan(color: Int) : ForegroundColorSpan(color)
    private class SyntaxStyleSpan(style: Int) : StyleSpan(style)

    var isDarkThemeMode: Boolean = true
        set(value) {
            field = value
            updateThemePalette()
            applySyntaxHighlighting(editableText)
            dismissSuggestionsPopup()
            invalidate()
        }

    var errorLine: Int? = null
        private set

    var errorMessage: String? = null
        private set

    private var isApplyingSpans = false
    private var isHandlingAutoIndent = false
    private var isProgrammaticEdit = false

    // Live Autocomplete Popup Window & Project Symbol Context
    private var suggestionPopup: PopupWindow? = null
    private var suggestionListContainer: LinearLayout? = null
    private var suggestionPopupCard: LinearLayout? = null
    private val projectCustomSuggestions = mutableListOf<SuggestionItem>()
    private var activePrefixRange: IntRange? = null

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
            "pub", "priv", "const", "mut", "main",
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

        private val STATIC_SUGGESTIONS: List<SuggestionItem> = listOf(
            // Entry points & directives
            SuggestionItem("main", "pub main() = { ... }", "pub main() = {\n    \n}", 2, "entry", "Module start point"),
            SuggestionItem("pub main", "pub main() = { ... }", "pub main() = {\n    \n}", 2, "entry", "Module start point"),
            SuggestionItem("@entry", "@entry(main)", "@entry(main)", 1, "dir", "Explicit entry function"),
            SuggestionItem("@dim", "@dim(unit, m, kg, s, A, K, mol, cd)", "@dim(custom_unit, 1, 0, 0, 0, 0, 0, 0)", 0, "dir", "7D SI unit dimension"),
            SuggestionItem("@unit_dim", "@unit_dim(val, axis)", "@unit_dim()", 1, "dir", "Inspect SI exponent"),

            // Standard Library & Patch <[dep] modules
            SuggestionItem("<[arith", "<[arith]", "<[arith]", 0, "mod", "Core arithmetic & stats"),
            SuggestionItem("<[trig", "<[trig]", "<[trig]", 0, "mod", "Trigonometric functions"),
            SuggestionItem("<[units", "<[units]", "<[units]", 0, "mod", "7D SI physical units"),
            SuggestionItem("<[conversions", "<[conversions]", "<[conversions]", 0, "mod", "Unit conversions (in, to_)"),
            SuggestionItem("<[calculus", "<[calculus]", "<[calculus]", 0, "mod", "Calculus, ODE & symbolic CAS"),
            SuggestionItem("<[algebra", "<[algebra]", "<[algebra]", 0, "mod", "Solvers, polynomials & finance"),
            SuggestionItem("<[vec", "<[vec]", "<[vec]", 0, "mod", "Vectors, maps, ranges & HOFs"),
            SuggestionItem("<[mat", "<[mat]", "<[mat]", 0, "mod", "Matrices, LU/QR/SVD/Eigen"),
            SuggestionItem("<[string", "<[string]", "<[string]", 0, "mod", "Strings, formatting & regex"),
            SuggestionItem("<[complex_ext", "<[complex_ext]", "<[complex_ext]", 0, "mod", "Complex numbers ($complex)"),
            SuggestionItem("<[geom_ext", "<[geom_ext]", "<[geom_ext]", 0, "mod", "Geometry ($vec2, $vec3, $circle)"),
            SuggestionItem("<[physics", "<[physics]", "<[physics]", 0, "mod", "Classical & modern physics"),
            SuggestionItem("<[chemistry", "<[chemistry]", "<[chemistry]", 0, "mod", "Gas laws, pH & stoichiometry"),
            SuggestionItem("<[biology", "<[biology]", "<[biology]", 0, "mod", "Genetics, kinetics & ecology"),
            SuggestionItem("<[currency", "<[currency]", "<[currency]", 0, "mod", "Global currency patch"),

            // Keywords & Control Flow
            SuggestionItem("pub", "pub ", "pub ", 0, "kw", "Public visibility"),
            SuggestionItem("priv", "priv ", "priv ", 0, "kw", "Private to module"),
            SuggestionItem("const", "const ", "const ", 0, "kw", "Immutable binding"),
            SuggestionItem("mut", "mut ", "mut ", 0, "kw", "Mutable variable"),
            SuggestionItem("if", "if (cond) { ... }", "if () {\n    \n}", 9, "kw", "Conditional branch"),
            SuggestionItem("or", "or (cond) { ... }", "or () {\n    \n}", 9, "kw", "Else-if branch"),
            SuggestionItem("else", "else { ... }", "else {\n    \n}", 2, "kw", "Fallback branch"),
            SuggestionItem("switch", "switch (x) { case ... }", "switch (x) {\n    case 0: 0\n    else: 1\n}", 0, "kw", "Pattern-matching switch"),
            SuggestionItem("try", "try { ... } catch (e) { ... }", "try {\n    \n} catch (e) {\n    e\n}", 18, "kw", "Exception handler"),
            SuggestionItem("return", "return ", "return ", 0, "kw", "Early return from function"),
            SuggestionItem("break", "break", "break", 0, "kw", "Exit active loop/switch"),
            SuggestionItem("continue", "continue", "continue", 0, "kw", "Next loop iteration"),
            SuggestionItem("import", "import \"module.math\"", "import \"\"", 1, "kw", "Import module or file"),

            // Types & Data Structures
            SuggestionItem("$struct", "$struct Name(x: real, y: real)", "$struct Point(x: real, y: real)", 0, "type", "Struct type declaration"),
            SuggestionItem("$enum", "$enum Name { Idle, Active(v: real) }", "$enum State { Idle, Active(v: real) }", 0, "type", "Enum / tagged union"),
            SuggestionItem("real", "real", "real", 0, "type", "64-bit float type"),
            SuggestionItem("str", "str", "str", 0, "type", "UTF-8 string type"),
            SuggestionItem("vec", "vec(a, b, ...)", "vec()", 1, "type", "Dynamic vector"),
            SuggestionItem("vec2", "vec2(x, y)", "vec2(, )", 3, "type", "2D vector struct"),
            SuggestionItem("vec3", "vec3(x, y, z)", "vec3(, , )", 5, "type", "3D vector struct"),
            SuggestionItem("complex", "complex(re, im)", "complex(, )", 3, "type", "Complex number"),
            SuggestionItem("dict", "dict(\"k\", v)", "dict(\"\", )", 3, "type", "Key-value $map dictionary"),
            SuggestionItem("map", "map(\"k\", v)", "map(\"\", )", 3, "type", "Key-value $map dictionary"),

            // Console, File I/O, JSON, CSV & Bytecode VM
            SuggestionItem("println", "println(val)", "println(\"\")", 2, "fn", "Print line to console"),
            SuggestionItem("print", "print(val)", "print(\"\")", 2, "fn", "Print to console"),
            SuggestionItem("read_json", "read_json(\"file.json\")", "read_json(\"config.json\")", 2, "fn", "Parse JSON file into $map/$vec"),
            SuggestionItem("to_json", "to_json(val)", "to_json()", 1, "fn", "Serialize value to JSON"),
            SuggestionItem("read_file", "read_file(\"file\")", "read_file(\"\")", 2, "fn", "Read sandboxed project file"),
            SuggestionItem("write_file", "write_file(\"file\", text)", "write_file(\"\", )", 2, "fn", "Write sandboxed project file"),
            SuggestionItem("csv_to_maps", "csv_to_maps(csv_str)", "csv_to_maps(read_file(\"sensor_data.csv\"))", 0, "fn", "Parse CSV into vec<map>"),
            SuggestionItem("bc_disasm", "bc_disasm(fn)", "bc_disasm()", 1, "fn", "Disassemble VM bytecode"),
            SuggestionItem("unit_of", "unit_of(val)", "unit_of()", 1, "fn", "Physical unit group name"),
            SuggestionItem("typeof", "typeof(val)", "typeof()", 1, "fn", "Entity type name"),

            // Symbolic CAS & Numerical Calculus
            SuggestionItem("sym_simplify", "sym_simplify(expr)", "sym_simplify()", 1, "std", "Symbolic algebraic simplify"),
            SuggestionItem("sym_diff", "sym_diff(x, expr)", "sym_diff(x, )", 1, "std", "Symbolic derivative d/dx"),
            SuggestionItem("sym_integ", "sym_integ(x, expr)", "sym_integ(x, )", 1, "std", "Symbolic indefinite integral"),
            SuggestionItem("sym_solve", "sym_solve(x, eq == 0)", "sym_solve(x, )", 1, "std", "Symbolic equation solver"),
            SuggestionItem("diff", "diff(x, expr, at)", "diff(x, , )", 3, "std", "Numerical derivative at x"),
            SuggestionItem("integrate", "integrate(x, expr, a, b)", "integrate(x, , 0, 1)", 6, "std", "Numerical definite integral"),
            SuggestionItem("sigma", "sigma(k, expr, lo, hi)", "sigma(k, , 1, 10)", 7, "std", "Discrete summation ∑"),
            SuggestionItem("prod", "prod(k, expr, lo, hi)", "prod(k, , 1, 5)", 6, "std", "Discrete product ∏"),
            SuggestionItem("lim", "lim(x, expr, target)", "lim(x, , 0)", 3, "std", "Two-sided limit"),
            SuggestionItem("solve", "solve(x, lhs == rhs, guess)", "solve(x, )", 1, "std", "Numerical root solver"),
            SuggestionItem("ode_rk4", "ode_rk4(t, y, dy_dt, t0, y0, t1, steps)", "ode_rk4(t, y, , 0, 1, 1, 100)", 15, "std", "4th-order Runge-Kutta ODE"),

            // Math, Trig, Vectors, Matrices & Currency Patch
            SuggestionItem("sin", "sin(x)", "sin()", 1, "std", "Sine (rad)"),
            SuggestionItem("cos", "cos(x)", "cos()", 1, "std", "Cosine (rad)"),
            SuggestionItem("tan", "tan(x)", "tan()", 1, "std", "Tangent (rad)"),
            SuggestionItem("asin", "asin(x)", "asin()", 1, "std", "Inverse sine"),
            SuggestionItem("acos", "acos(x)", "acos()", 1, "std", "Inverse cosine"),
            SuggestionItem("atan", "atan(x)", "atan()", 1, "std", "Inverse tangent"),
            SuggestionItem("deg2rad", "deg2rad(deg)", "deg2rad()", 1, "std", "Degrees to radians"),
            SuggestionItem("rad2deg", "rad2deg(rad)", "rad2deg()", 1, "std", "Radians to degrees"),
            SuggestionItem("sqrt", "sqrt(x)", "sqrt()", 1, "std", "Square root √x"),
            SuggestionItem("cbrt", "cbrt(x)", "cbrt()", 1, "std", "Cube root ∛x"),
            SuggestionItem("abs", "abs(x)", "abs()", 1, "std", "Absolute value |x|"),
            SuggestionItem("exp", "exp(x)", "exp()", 1, "std", "Natural exponential e^x"),
            SuggestionItem("ln", "ln(x)", "ln()", 1, "std", "Natural logarithm"),
            SuggestionItem("log10", "log10(x)", "log10()", 1, "std", "Base-10 logarithm"),
            SuggestionItem("log2", "log2(x)", "log2()", 1, "std", "Base-2 logarithm"),
            SuggestionItem("gcd", "gcd(a, b)", "gcd(, )", 3, "std", "Greatest common divisor"),
            SuggestionItem("lcm", "lcm(a, b)", "lcm(, )", 3, "std", "Least common multiple"),
            SuggestionItem("ncr", "ncr(n, r)", "ncr(, )", 3, "std", "Combinations nCr"),
            SuggestionItem("npr", "npr(n, r)", "npr(, )", 3, "std", "Permutations nPr"),
            SuggestionItem("sum", "sum(vec)", "sum()", 1, "std", "Sum of vector elements"),
            SuggestionItem("mean", "mean(vec)", "mean()", 1, "std", "Arithmetic mean of vector"),
            SuggestionItem("stddev", "stddev(vec)", "stddev()", 1, "std", "Standard deviation"),
            SuggestionItem("median", "median(vec)", "median()", 1, "std", "Median of vector"),
            SuggestionItem("linspace", "linspace(start, stop, n)", "linspace(0, 1, 50)", 0, "std", "Evenly spaced vector"),
            SuggestionItem("linsolve", "linsolve(A, b)", "linsolve(, )", 3, "std", "Solve linear system A*x = b"),
            SuggestionItem("det", "det(M)", "det()", 1, "std", "Matrix determinant"),
            SuggestionItem("inv", "inv(M)", "inv()", 1, "std", "Matrix inverse"),
            SuggestionItem("transpose", "transpose(M)", "transpose()", 1, "std", "Matrix transpose"),
            SuggestionItem("eigvals", "eigvals(M)", "eigvals()", 1, "std", "Symmetric eigenvalues"),
            SuggestionItem("svd_s", "svd_s(M)", "svd_s()", 1, "std", "Singular values"),
            SuggestionItem("pinv", "pinv(M)", "pinv()", 1, "std", "Moore-Penrose pseudoinverse"),
            SuggestionItem("lstsq", "lstsq(A, b)", "lstsq(, )", 3, "std", "Linear least squares"),
            SuggestionItem("convert_fx", "convert_fx(amt, \"USD\", \"INR\")", "convert_fx(100, \"USD\", \"INR\")", 0, "std", "Currency patch conversion"),
            SuggestionItem("fx_rate", "fx_rate(\"USD\", \"INR\")", "fx_rate(\"USD\", \"INR\")", 0, "std", "Live currency FX rate")
        )
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
                    updateSuggestionsForCurrentCursor(s)
                }
            }
        })
    }

    /**
     * Updates the editor's autocomplete symbol table with sibling `[module]` names, functions,
     * variables, constants, and `$struct`/`$enum` types from the active project.
     */
    fun updateProjectAutocompleteContext(projectSourcesByFile: Map<String, String>) {
        projectCustomSuggestions.clear()
        val seenKeys = HashSet<String>()
        val fnRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()]*)\\)\\s*(?:->[^=]+|:[a-zA-Z0-9_,\\s]+)?(?::=|=)(?![=>])")
        val varRegex = Regex("(?m)^\\s*(?:(?:pub|priv|const|mut)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::\\s*[a-zA-Z_][a-zA-Z0-9_<>,\\s]*)?(?::=|=)(?![=>])")
        val typeRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$|struct\\s+|enum\\s+)([a-zA-Z_][a-zA-Z0-9_]*)")

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
                        detail = "Project module ($fileName)"
                    )
                )
            }
            for (m in typeRegex.findAll(rawSrc)) {
                val tName = m.groupValues[1]
                if (tName !in BUILTIN_TYPES && seenKeys.add("type:$tName")) {
                    projectCustomSuggestions.add(
                        SuggestionItem(
                            matchKey = tName,
                            displayLabel = "$tName(...)",
                            insertText = "$tName()",
                            cursorBackOffset = 1,
                            category = "type",
                            detail = "Type in [$modName]"
                        )
                    )
                }
            }
            for (m in fnRegex.findAll(rawSrc)) {
                val fnName = m.groupValues[1]
                val params = m.groupValues[2].trim()
                if (fnName !in KEYWORDS && seenKeys.add("fn:$fnName")) {
                    val hasParams = params.isNotEmpty()
                    projectCustomSuggestions.add(
                        SuggestionItem(
                            matchKey = fnName,
                            displayLabel = "$fnName($params)",
                            insertText = "$fnName()",
                            cursorBackOffset = if (hasParams) 1 else 0,
                            category = "proj",
                            detail = "fn in [$modName]"
                        )
                    )
                }
            }
            for (m in varRegex.findAll(rawSrc)) {
                val vName = m.groupValues[1]
                if (vName !in KEYWORDS && vName !in BUILTIN_TYPES && seenKeys.add("var:$vName")) {
                    projectCustomSuggestions.add(
                        SuggestionItem(
                            matchKey = vName,
                            displayLabel = vName,
                            insertText = vName,
                            cursorBackOffset = 0,
                            category = "var",
                            detail = "var in [$modName]"
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

    fun dismissSuggestionsPopup() {
        activePrefixRange = null
        runCatching {
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

    private fun extractTypingPrefix(textStr: CharSequence, cursor: Int): Pair<String, IntRange>? {
        if (cursor <= 0 || cursor > textStr.length) return null
        val lineStart = textStr.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
        val lineSlice = textStr.subSequence(lineStart, cursor).toString()

        // Ignore if typing inside a `#` comment
        var inQuotes = false
        for (i in lineSlice.indices) {
            val c = lineSlice[i]
            if (c == '"' && (i == 0 || lineSlice[i - 1] != '\\')) inQuotes = !inQuotes
            else if (c == '#' && !inQuotes) return null
        }
        if (inQuotes) return null

        // Check for `<[dep` prefix at start of line
        val depMatch = Regex("^\\s*(<\\[[a-zA-Z0-9_]*)$").find(lineSlice)
        if (depMatch != null) {
            val tok = depMatch.groupValues[1]
            val startIdx = cursor - tok.length
            return tok to (startIdx until cursor)
        }

        // Check for `@directive`, `$type`, or identifier prefix before cursor
        var start = cursor
        while (start > lineStart) {
            val ch = textStr[start - 1]
            if (ch.isLetterOrDigit() || ch == '_' || ch == '@' || ch == '$') {
                start--
                if (ch == '@' || ch == '$') break
            } else {
                break
            }
        }
        if (start >= cursor) return null
        val prefix = textStr.subSequence(start, cursor).toString()
        if (prefix.length == 1 && !prefix[0].isLetter() && prefix[0] != '@' && prefix[0] != '$') {
            return null
        }
        if (prefix[0].isDigit()) return null
        return prefix to (start until cursor)
    }

    private fun collectLiveBufferSymbols(src: String, excludeRange: IntRange): List<SuggestionItem> {
        val out = mutableListOf<SuggestionItem>()
        val seen = HashSet<String>()
        val fnRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()]*)\\)\\s*(?:->[^=]+|:[a-zA-Z0-9_,\\s]+)?(?::=|=)(?![=>])")
        val varRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::\\s*[a-zA-Z_][a-zA-Z0-9_<>,\\s]*)?(?::=|=)(?![=>])")

        for (m in fnRegex.findAll(src)) {
            if (m.range.first in excludeRange) continue
            val fn = m.groupValues[1]
            val params = m.groupValues[2].trim()
            if (fn !in KEYWORDS && seen.add(fn)) {
                out.add(
                    SuggestionItem(
                        matchKey = fn,
                        displayLabel = "$fn($params)",
                        insertText = "$fn()",
                        cursorBackOffset = if (params.isNotEmpty()) 1 else 0,
                        category = "fn",
                        detail = "Current module fn"
                    )
                )
            }
        }
        for (m in varRegex.findAll(src)) {
            if (m.range.first in excludeRange) continue
            val v = m.groupValues[1]
            if (v !in KEYWORDS && v !in BUILTIN_TYPES && seen.add(v)) {
                out.add(
                    SuggestionItem(
                        matchKey = v,
                        displayLabel = v,
                        insertText = v,
                        cursorBackOffset = 0,
                        category = "var",
                        detail = "Current module var"
                    )
                )
            }
        }
        return out
    }

    private fun updateSuggestionsForCurrentCursor(editable: Editable) {
        if (selectionStart != selectionEnd || selectionStart < 0) {
            dismissSuggestionsPopup()
            return
        }
        val cursor = selectionStart
        val extracted = extractTypingPrefix(editable, cursor)
        if (extracted == null) {
            dismissSuggestionsPopup()
            return
        }
        val (prefix, range) = extracted
        val lowerPrefix = prefix.lowercase()

        val liveSymbols = collectLiveBufferSymbols(editable.toString(), range)
        val combined = ArrayList<SuggestionItem>(liveSymbols.size + projectCustomSuggestions.size + STATIC_SUGGESTIONS.size)
        val seenDisplay = HashSet<String>()

        for (srcList in listOf(liveSymbols, projectCustomSuggestions, STATIC_SUGGESTIONS)) {
            for (item in srcList) {
                val keyLower = item.matchKey.lowercase()
                val matches = if (lowerPrefix.startsWith("<[") || lowerPrefix.startsWith("@") || lowerPrefix.startsWith("$")) {
                    keyLower.startsWith(lowerPrefix)
                } else {
                    !keyLower.startsWith("<[") &&
                        (keyLower.startsWith(lowerPrefix) || (lowerPrefix.length >= 2 && keyLower.contains(lowerPrefix))) &&
                        item.insertText != prefix
                }
                if (matches && seenDisplay.add(item.displayLabel)) {
                    combined.add(item)
                }
            }
        }

        if (combined.isEmpty()) {
            dismissSuggestionsPopup()
            return
        }

        // Rank exact prefix matches above substring matches
        combined.sortWith(
            compareBy<SuggestionItem> { !it.matchKey.lowercase().startsWith(lowerPrefix) }
                .thenBy {
                    when (it.category) {
                        "entry" -> 0
                        "fn", "var", "proj" -> 1
                        "kw", "dir", "mod" -> 2
                        "type" -> 3
                        else -> 4
                    }
                }
                .thenBy { it.matchKey.length }
        )

        activePrefixRange = range
        showSuggestionsPopupWindow(combined.take(7), prefix)
    }

    private fun ensureSuggestionPopupCreated(): PopupWindow {
        suggestionPopup?.let { return it }
        val dp = { v: Float -> (v * density).toInt() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            elevation = dp(14f).toFloat()
        }
        suggestionPopupCard = card

        val listScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        suggestionListContainer = listCol
        listScroll.addView(listCol)
        card.addView(listScroll)

        val popupW = (resources.displayMetrics.widthPixels - dp(40f)).coerceIn(dp(230f), dp(340f))
        val pw = PopupWindow(card, popupW, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isFocusable = false
            isTouchable = true
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        suggestionPopup = pw
        return pw
    }

    private fun showSuggestionsPopupWindow(items: List<SuggestionItem>, currentPrefix: String) {
        if (!isAttachedToWindow || width == 0 || height == 0) return
        val pw = ensureSuggestionPopupCreated()
        val card = suggestionPopupCard ?: return
        val container = suggestionListContainer ?: return
        val dp = { v: Float -> (v * density).toInt() }

        val dark = isDarkThemeMode
        val cardBg = Color.parseColor(if (dark) "#23262B" else "#F7F9FC")
        val rowBg = Color.parseColor(if (dark) "#2C3037" else "#EDF1F7")
        val strokeCol = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
        val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#24292F")
        val secondaryText = Color.parseColor(if (dark) "#9BA4B0" else "#57606A")

        card.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(cardBg)
            setStroke(max(1, dp(1.2f)), strokeCol)
        }

        container.removeAllViews()

        // Header bar: [💡 Autocomplete "prefix"] + [✕]
        val hdrRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), 0, dp(4f), dp(4f))
        }
        val tvHdr = TextView(context).apply {
            text = "💡 Suggestions for \"$currentPrefix\" (tap to insert)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(strokeCol)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvDismiss = TextView(context).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(secondaryText)
            setPadding(dp(6f), 0, dp(2f), 0)
            setOnClickListener { dismissSuggestionsPopup() }
        }
        hdrRow.addView(tvHdr)
        hdrRow.addView(tvDismiss)
        container.addView(hdrRow)

        items.forEach { item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(3f) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 9f * density
                    setColor(rowBg)
                }
                setOnClickListener {
                    applySuggestionItem(item)
                }
            }

            val badgeCol = when (item.category) {
                "entry", "dir" -> colIntrinsic
                "kw" -> colKeyword
                "mod" -> colModule
                "type" -> colType
                "fn", "proj" -> colOperator
                else -> colNumber
            }

            val tvBadge = TextView(context).apply {
                text = item.category
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.2f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(badgeCol)
                setPadding(dp(5f), dp(2f), dp(5f), dp(2f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 6f * density
                    setColor(Color.parseColor(if (dark) "#1A1D21" else "#FFFFFF"))
                    setStroke(1, badgeCol)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(7f) }
            }

            val tvLabel = TextView(context).apply {
                text = item.displayLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(primaryText)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f)
            }

            val tvDetail = TextView(context).apply {
                text = item.detail
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
                setTextColor(secondaryText)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply {
                    marginStart = dp(6f)
                }
            }

            row.addView(tvBadge)
            row.addView(tvLabel)
            row.addView(tvDetail)
            container.addView(row)
        }

        // Compute anchor coordinates relative to current cursor line
        val textLayout = layout
        var offsetX = currentGutterWidthPx + dp(6f)
        var offsetY = -height + dp(44f)
        if (textLayout != null) {
            val cur = selectionStart.coerceIn(0, text?.length ?: 0)
            val line = textLayout.getLineForOffset(cur)
            val lineBottom = textLayout.getLineBottom(line) - scrollY + paddingTop
            val lineTop = textLayout.getLineTop(line) - scrollY + paddingTop
            val primX = (textLayout.getPrimaryHorizontal(cur) - scrollX + paddingLeft).toInt()
            offsetX = primX.coerceIn(currentGutterWidthPx, (width - dp(240f)).coerceAtLeast(currentGutterWidthPx))

            val spaceBelow = height - lineBottom
            offsetY = if (spaceBelow >= dp(120f) || lineTop < dp(130f)) {
                lineBottom - height + dp(4f)
            } else {
                lineTop - height - dp(165f)
            }
        }

        runCatching {
            if (pw.isShowing) {
                pw.update(this, offsetX, offsetY, pw.width, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                pw.showAsDropDown(this, offsetX, offsetY)
            }
        }
    }

    private fun applySuggestionItem(item: SuggestionItem) {
        val editable = editableText ?: return
        val range = activePrefixRange ?: return
        val st = range.first.coerceIn(0, editable.length)
        val en = (range.last + 1).coerceIn(st, editable.length)
        dismissSuggestionsPopup()

        isProgrammaticEdit = true
        try {
            editable.replace(st, en, item.insertText)
            val newCursor = (st + item.insertText.length - item.cursorBackOffset).coerceIn(0, editable.length)
            setSelection(newCursor)
        } finally {
            isProgrammaticEdit = false
        }
        requestFocus()
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

    private fun applySyntaxHighlighting(editable: Editable?) {
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
