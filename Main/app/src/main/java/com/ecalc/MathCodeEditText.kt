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

    // Live Autocomplete Popup Window, Hold-to-Inspect Signature Popup & Project Symbol Context
    private var suggestionPopup: PopupWindow? = null
    private var suggestionListContainer: LinearLayout? = null
    private var suggestionPopupCard: LinearLayout? = null
    private var suggestionDocPopup: PopupWindow? = null
    private var suggestionDocCard: LinearLayout? = null
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
            SuggestionItem(
                "main", "pub main() = { ... }", "pub main() = {\n    \n}", 2, "entry", "Module start point",
                listOf("None — Zero-argument program entry point"),
                "any — Final expression value of the main() block"
            ),
            SuggestionItem(
                "pub main", "pub main() = { ... }", "pub main() = {\n    \n}", 2, "entry", "Module start point",
                listOf("None — Zero-argument program entry point"),
                "any — Final expression value of the main() block"
            ),
            SuggestionItem(
                "@entry", "@entry(main)", "@entry(main)", 1, "dir", "Explicit entry function",
                listOf("fn_name: ident — Zero-arg function to invoke as module start point"),
                "void — Registers fn_name as the module's execution entry point"
            ),
            SuggestionItem(
                "@dim", "@dim(unit, m, kg, s, A, K, mol, cd)", "@dim(custom_unit, 1, 0, 0, 0, 0, 0, 0)", 0, "dir", "7D SI unit dimension",
                listOf(
                    "unit: ident — Unit symbol name to register",
                    "m, kg, s, A, K, mol, cd: int — 7 integer SI base dimension exponents"
                ),
                "void — Binds the 7D SI physical dimension vector into the symbol table"
            ),
            SuggestionItem(
                "@unit_dim", "@unit_dim(val, axis)", "@unit_dim()", 1, "dir", "Inspect SI exponent",
                listOf(
                    "val: real — Quantity carrying physical SI units",
                    "axis: int — SI dimension index (0=m, 1=kg, 2=s, 3=A, 4=K, 5=mol, 6=cd)"
                ),
                "int — Exponent of the requested SI base dimension on val"
            ),

            // Standard Library & Patch <[dep] modules
            SuggestionItem(
                "<[arith", "<[arith]", "<[arith]", 0, "mod", "Core arithmetic & stats",
                listOf("arith: module — Standard arithmetic, combinatorics & descriptive stats"),
                "void — Imports public functions and constants from [arith]"
            ),
            SuggestionItem(
                "<[trig", "<[trig]", "<[trig]", 0, "mod", "Trigonometric functions",
                listOf("trig: module — Circular, inverse, hyperbolic & degree/radian helpers"),
                "void — Imports public functions and constants from [trig]"
            ),
            SuggestionItem(
                "<[units", "<[units]", "<[units]", 0, "mod", "7D SI physical units",
                listOf("units: module — Base and derived 7D SI physical unit definitions"),
                "void — Imports SI unit symbols (m, kg, s, N, J, W, Pa, Hz, ...)"
            ),
            SuggestionItem(
                "<[conversions", "<[conversions]", "<[conversions]", 0, "mod", "Unit conversions (in, to_)",
                listOf("conversions: module — Unit conversion factors & temperature helpers"),
                "void — Imports conversion helpers from [conversions]"
            ),
            SuggestionItem(
                "<[calculus", "<[calculus]", "<[calculus]", 0, "mod", "Calculus, ODE & symbolic CAS",
                listOf("calculus: module — Numerical derivatives, integrals, RK4 ODE & symbolic CAS"),
                "void — Imports diff, integrate, ode_rk4, sym_diff, sym_integ, sym_solve"
            ),
            SuggestionItem(
                "<[algebra", "<[algebra]", "<[algebra]", 0, "mod", "Solvers, polynomials & finance",
                listOf("algebra: module — Root solvers, polynomial roots & financial math"),
                "void — Imports quadratic, cubic, poly_roots, solve, npv, irr"
            ),
            SuggestionItem(
                "<[vec", "<[vec]", "<[vec]", 0, "mod", "Vectors, maps, ranges & HOFs",
                listOf("vec: module — Dynamic vectors, \$map dictionaries, CSV & HOF pipelines"),
                "void — Imports vector/map utilities and higher-order functions"
            ),
            SuggestionItem(
                "<[mat", "<[mat]", "<[mat]", 0, "mod", "Matrices, LU/QR/SVD/Eigen",
                listOf("mat: module — 2D matrices, linear solvers & decompositions"),
                "void — Imports det, inv, linsolve, eigvals, svd_s, pinv, lstsq"
            ),
            SuggestionItem(
                "<[string", "<[string]", "<[string]", 0, "mod", "Strings, formatting & regex",
                listOf("string: module — UTF-8 string manipulation, splitting & formatting"),
                "void — Imports string helpers from [string]"
            ),
            SuggestionItem(
                "<[complex_ext", "<[complex_ext]", "<[complex_ext]", 0, "mod", "Complex numbers (\$complex)",
                listOf("complex_ext: module — Complex number arithmetic, polar form & phasors"),
                "void — Imports \$complex type, conj, c_abs, c_arg, c_exp"
            ),
            SuggestionItem(
                "<[geom_ext", "<[geom_ext]", "<[geom_ext]", 0, "mod", "Geometry (\$vec2, \$vec3, \$circle)",
                listOf("geom_ext: module — 2D/3D vectors, lines, circles, planes & spheres"),
                "void — Imports \$vec2, \$vec3, \$circle, \$line2, \$plane3, \$sphere"
            ),
            SuggestionItem(
                "<[physics", "<[physics]", "<[physics]", 0, "mod", "Classical & modern physics",
                listOf("physics: module — Mechanics, electromagnetism, relativity & waves"),
                "void — Imports physical constants and equations from [physics]"
            ),
            SuggestionItem(
                "<[chemistry", "<[chemistry]", "<[chemistry]", 0, "mod", "Gas laws, pH & stoichiometry",
                listOf("chemistry: module — Ideal gas, pH, Arrhenius & molar mass utilities"),
                "void — Imports chemistry formulas and constants from [chemistry]"
            ),
            SuggestionItem(
                "<[biology", "<[biology]", "<[biology]", 0, "mod", "Genetics, kinetics & ecology",
                listOf("biology: module — Michaelis-Menten kinetics, logistic growth & genetics"),
                "void — Imports biology models from [biology]"
            ),
            SuggestionItem(
                "<[currency", "<[currency]", "<[currency]", 0, "mod", "Global currency patch",
                listOf("currency: module — Global stdlib patch for currency rates & conversion"),
                "void — Imports live currency conversion functions and ISO rate constants"
            ),

            // Keywords & Control Flow
            SuggestionItem(
                "pub", "pub ", "pub ", 0, "kw", "Public visibility",
                listOf("decl: fn | var | const | \$type — Symbol exported to importing modules"),
                "void — Marks declaration as public across <[dep] module imports"
            ),
            SuggestionItem(
                "priv", "priv ", "priv ", 0, "kw", "Private to module",
                listOf("decl: fn | var | const | \$type — Symbol hidden from importing modules"),
                "void — Restricts symbol visibility to the declaring .math module"
            ),
            SuggestionItem(
                "const", "const ", "const ", 0, "kw", "Immutable binding",
                listOf("name = expr — Identifier and initial value expression"),
                "any — Binds an immutable constant that cannot be reassigned"
            ),
            SuggestionItem(
                "mut", "mut ", "mut ", 0, "kw", "Mutable variable",
                listOf("name := expr — Identifier and initial mutable value"),
                "any — Binds a mutable variable that can be updated with = or :="
            ),
            SuggestionItem(
                "if", "if (cond) { ... }", "if () {\n    \n}", 9, "kw", "Conditional branch",
                listOf(
                    "cond: bool — Condition expression (non-zero is true)",
                    "body: { ... } — Block evaluated when cond is true"
                ),
                "any — Value of the executed branch block"
            ),
            SuggestionItem(
                "or", "or (cond) { ... }", "or () {\n    \n}", 9, "kw", "Else-if branch",
                listOf(
                    "cond: bool — Secondary condition tested if preceding if/or was false",
                    "body: { ... } — Block evaluated when cond is true"
                ),
                "any — Value of the matching branch block"
            ),
            SuggestionItem(
                "else", "else { ... }", "else {\n    \n}", 2, "kw", "Fallback branch",
                listOf("body: { ... } — Block evaluated when all preceding if/or conditions are false"),
                "any — Value of the fallback block"
            ),
            SuggestionItem(
                "switch", "switch (x) { case ... }", "switch (x) {\n    case 0: 0\n    else: 1\n}", 0, "kw", "Pattern-matching switch",
                listOf(
                    "x: any — Target value or enum variant to match against",
                    "cases: case pat: expr — Ordered pattern arms with an else fallback"
                ),
                "any — Result of the first matching case arm"
            ),
            SuggestionItem(
                "try", "try { ... } catch (e) { ... }", "try {\n    \n} catch (e) {\n    e\n}", 18, "kw", "Exception handler",
                listOf(
                    "try_block: { ... } — Protected block that may throw or fail",
                    "e: str — Caught error message bound in the catch block"
                ),
                "any — Value of try_block on success, or catch_block on error"
            ),
            SuggestionItem(
                "return", "return ", "return ", 0, "kw", "Early return from function",
                listOf("expr: any — Value to return immediately from the enclosing function"),
                "any — Exits the current function with expr"
            ),
            SuggestionItem(
                "break", "break", "break", 0, "kw", "Exit active loop/switch",
                listOf("None — Immediately terminates the innermost loop"),
                "void — Transfers control past the enclosing loop"
            ),
            SuggestionItem(
                "continue", "continue", "continue", 0, "kw", "Next loop iteration",
                listOf("None — Skips the rest of the current loop body"),
                "void — Advances to the next iteration of the enclosing loop"
            ),
            SuggestionItem(
                "import", "import \"module.math\"", "import \"\"", 1, "kw", "Import module or file",
                listOf("path: str — Module name or .math file to load in declaration-only mode"),
                "void — Links public declarations from the target module"
            ),

            // Types & Data Structures
            SuggestionItem(
                "\$struct", "\$struct Name(x: real, y: real)", "\$struct Point(x: real, y: real)", 0, "type", "Struct type declaration",
                listOf(
                    "Name: ident — Custom struct type name",
                    "fields: (f1: type, ...) — Ordered typed fields for the struct constructor"
                ),
                "type — Registers constructor Name(...) and field accessors (.f1, ...)"
            ),
            SuggestionItem(
                "\$enum", "\$enum Name { Idle, Active(v: real) }", "\$enum State { Idle, Active(v: real) }", 0, "type", "Enum / tagged union",
                listOf(
                    "Name: ident — Tagged union / enum type name",
                    "Variants: { V1, V2(field: type) } — Unit or payload-carrying variants"
                ),
                "type — Registers variant constructors Name::V1, Name::V2(...)"
            ),
            SuggestionItem(
                "real", "real", "real", 0, "type", "64-bit float type",
                listOf("None — IEEE-754 double-precision scalar type annotation"),
                "type — 64-bit floating-point number (with optional 7D SI units)"
            ),
            SuggestionItem(
                "str", "str", "str", 0, "type", "UTF-8 string type",
                listOf("None — Immutable UTF-8 string type annotation"),
                "type — Heap-managed string value"
            ),
            SuggestionItem(
                "vec", "vec(a, b, ...)", "vec()", 1, "type", "Dynamic vector",
                listOf("elems: any... — Zero or more elements to pack into a vector"),
                "vec — 1D dynamic array supporting indexing, slicing & HOFs"
            ),
            SuggestionItem(
                "vec2", "vec2(x, y)", "vec2(, )", 3, "type", "2D vector struct",
                listOf(
                    "x: real — Horizontal X component",
                    "y: real — Vertical Y component"
                ),
                "vec2 — 2D geometric vector instance"
            ),
            SuggestionItem(
                "vec3", "vec3(x, y, z)", "vec3(, , )", 5, "type", "3D vector struct",
                listOf(
                    "x: real — X coordinate component",
                    "y: real — Y coordinate component",
                    "z: real — Z coordinate component"
                ),
                "vec3 — 3D geometric vector instance"
            ),
            SuggestionItem(
                "complex", "complex(re, im)", "complex(, )", 3, "type", "Complex number",
                listOf(
                    "re: real — Real part of the complex number",
                    "im: real — Imaginary part (coefficient of i)"
                ),
                "complex — Complex number instance (re + im*i)"
            ),
            SuggestionItem(
                "dict", "dict(\"k\", v)", "dict(\"\", )", 3, "type", "Key-value \$map dictionary",
                listOf(
                    "key: str — String key name (alternating k1, v1, k2, v2, ...)",
                    "val: any — Associated value for each key"
                ),
                "map — Key-value dictionary supporting m[\"key\"] lookup & mutation"
            ),
            SuggestionItem(
                "map", "map(\"k\", v)", "map(\"\", )", 3, "type", "Key-value \$map dictionary",
                listOf(
                    "key: str — String key name (alternating k1, v1, k2, v2, ...)",
                    "val: any — Associated value for each key"
                ),
                "map — Key-value dictionary supporting m[\"key\"] lookup & mutation"
            ),

            // Console, File I/O, JSON, CSV & Bytecode VM
            SuggestionItem(
                "println", "println(val)", "println(\"\")", 2, "fn", "Print line to console",
                listOf("val: any... — One or more values or \$\"...\" interpolated strings to print"),
                "any — Appends formatted line to Console Stream and returns last argument"
            ),
            SuggestionItem(
                "print", "print(val)", "print(\"\")", 2, "fn", "Print to console",
                listOf("val: any... — One or more values to write without trailing newline"),
                "any — Appends formatted text to Console Stream and returns last argument"
            ),
            SuggestionItem(
                "read_json", "read_json(\"file.json\")", "read_json(\"config.json\")", 2, "fn", "Parse JSON file into \$map/\$vec",
                listOf("path: str — Relative filename inside the active project folder"),
                "map | vec — Parsed RFC 8259 JSON object (\$map) or array (\$vec)"
            ),
            SuggestionItem(
                "to_json", "to_json(val)", "to_json()", 1, "fn", "Serialize value to JSON",
                listOf("val: any — \$map, \$vec, \$struct, str, or real value to serialize"),
                "str — Valid RFC 8259 JSON string representation"
            ),
            SuggestionItem(
                "read_file", "read_file(\"file\")", "read_file(\"\")", 2, "fn", "Read sandboxed project file",
                listOf("path: str — Relative filename inside the active project directory"),
                "str — Full UTF-8 text contents of the file"
            ),
            SuggestionItem(
                "write_file", "write_file(\"file\", text)", "write_file(\"\", )", 2, "fn", "Write sandboxed project file",
                listOf(
                    "path: str — Relative filename to write inside the active project folder",
                    "text: str — UTF-8 string content to persist to disk"
                ),
                "real — 1.0 when the file write succeeds"
            ),
            SuggestionItem(
                "csv_to_maps", "csv_to_maps(csv_str)", "csv_to_maps(read_file(\"sensor_data.csv\"))", 0, "fn", "Parse CSV into vec<map>",
                listOf("csv_str: str — Raw CSV text with a header row in the first line"),
                "vec — Vector of \$map rows keyed by column header names"
            ),
            SuggestionItem(
                "bc_disasm", "bc_disasm(fn)", "bc_disasm()", 1, "fn", "Disassemble VM bytecode",
                listOf("fn: fn — Function identifier or inline lambda expression"),
                "str — Human-readable stack VM bytecode disassembly dump"
            ),
            SuggestionItem(
                "unit_of", "unit_of(val)", "unit_of()", 1, "fn", "Physical unit group name",
                listOf("val: real — Value carrying 7D SI physical dimensions"),
                "str — Physical quantity category (e.g. \"force\", \"energy\", \"none\")"
            ),
            SuggestionItem(
                "typeof", "typeof(val)", "typeof()", 1, "fn", "Entity type name",
                listOf("val: any — Any scalar, container, closure, or struct instance"),
                "str — Runtime type name ("real", "vec", "map", "str", struct name)"
            ),

            // Symbolic CAS & Numerical Calculus
            SuggestionItem(
                "sym_simplify", "sym_simplify(expr)", "sym_simplify()", 1, "std", "Symbolic algebraic simplify",
                listOf("expr: ast — Algebraic or trigonometric expression to simplify"),
                "str — Exact symbolically simplified expression string"
            ),
            SuggestionItem(
                "sym_diff", "sym_diff(x, expr)", "sym_diff(x, )", 1, "std", "Symbolic derivative d/dx",
                listOf(
                    "x: ident — Differentiation variable symbol",
                    "expr: ast — Symbolic expression f(x) to differentiate"
                ),
                "str — Exact symbolic derivative df/dx simplified algebraically"
            ),
            SuggestionItem(
                "sym_integ", "sym_integ(x, expr)", "sym_integ(x, )", 1, "std", "Symbolic indefinite integral",
                listOf(
                    "x: ident — Integration variable symbol",
                    "expr: ast — Symbolic integrand expression f(x)"
                ),
                "str — Symbolic antiderivative F(x) such that F'(x) = f(x)"
            ),
            SuggestionItem(
                "sym_solve", "sym_solve(x, eq == 0)", "sym_solve(x, )", 1, "std", "Symbolic equation solver",
                listOf(
                    "x: ident — Unknown variable symbol to solve for",
                    "eq: ast — Polynomial or algebraic equation (e.g. x^2 - 5*x + 6 == 0)"
                ),
                "vec — Vector of exact/numeric roots satisfying the equation"
            ),
            SuggestionItem(
                "diff", "diff(x, expr, at)", "diff(x, , )", 3, "std", "Numerical derivative at x",
                listOf(
                    "x: ident — Variable of differentiation",
                    "expr: ast — Expression f(x) to differentiate",
                    "at: real — Point x0 at which to evaluate df/dx"
                ),
                "real — Numerical derivative f'(at) via central differences"
            ),
            SuggestionItem(
                "integrate", "integrate(x, expr, a, b)", "integrate(x, , 0, 1)", 6, "std", "Numerical definite integral",
                listOf(
                    "x: ident — Integration dummy variable",
                    "expr: ast — Integrand expression f(x)",
                    "a: real — Lower integration limit",
                    "b: real — Upper integration limit"
                ),
                "real — Definite integral ∫[a..b] f(x) dx via adaptive Simpson quadrature"
            ),
            SuggestionItem(
                "sigma", "sigma(k, expr, lo, hi)", "sigma(k, , 1, 10)", 7, "std", "Discrete summation ∑",
                listOf(
                    "k: ident — Loop index variable",
                    "expr: ast — Term expression a(k) evaluated at each integer step",
                    "lo: int — Starting index (inclusive)",
                    "hi: int — Ending index (inclusive)"
                ),
                "real — Sum ∑_{k=lo..hi} expr(k)"
            ),
            SuggestionItem(
                "prod", "prod(k, expr, lo, hi)", "prod(k, , 1, 5)", 6, "std", "Discrete product ∏",
                listOf(
                    "k: ident — Loop index variable",
                    "expr: ast — Factor expression a(k) evaluated at each integer step",
                    "lo: int — Starting index (inclusive)",
                    "hi: int — Ending index (inclusive)"
                ),
                "real — Product ∏_{k=lo..hi} expr(k)"
            ),
            SuggestionItem(
                "lim", "lim(x, expr, target)", "lim(x, , 0)", 3, "std", "Two-sided limit",
                listOf(
                    "x: ident — Approaching variable symbol",
                    "expr: ast — Expression f(x) whose limit is evaluated",
                    "target: real — Point x0 that x approaches"
                ),
                "real — Numerical two-sided limit as x -> target"
            ),
            SuggestionItem(
                "solve", "solve(x, lhs == rhs, guess)", "solve(x, )", 1, "std", "Numerical root solver",
                listOf(
                    "x: ident — Unknown variable to solve for",
                    "eq: ast — Equation lhs == rhs or residual expression f(x)",
                    "guess: real — (Optional) Initial root estimate (defaults to 1.0)"
                ),
                "real — Root x satisfying the equation via secant/Newton iteration"
            ),
            SuggestionItem(
                "ode_rk4", "ode_rk4(t, y, dy_dt, t0, y0, t1, steps)", "ode_rk4(t, y, , 0, 1, 1, 100)", 15, "std", "4th-order Runge-Kutta ODE",
                listOf(
                    "t, y: ident — Independent time variable and state variable symbols",
                    "dy_dt: ast — Differential equation right-hand side f(t, y)",
                    "t0, y0: real — Initial time t0 and initial state y(t0) = y0",
                    "t1, steps: real — Target time t1 and number of RK4 integration steps"
                ),
                "real — Approximated state y(t1) at the end of the integration interval"
            ),

            // Math, Trig, Vectors, Matrices & Currency Patch
            SuggestionItem(
                "sin", "sin(x)", "sin()", 1, "std", "Sine (rad)",
                listOf("x: real — Angle in radians"),
                "real — Trigonometric sine sin(x) in [-1, 1]"
            ),
            SuggestionItem(
                "cos", "cos(x)", "cos()", 1, "std", "Cosine (rad)",
                listOf("x: real — Angle in radians"),
                "real — Trigonometric cosine cos(x) in [-1, 1]"
            ),
            SuggestionItem(
                "tan", "tan(x)", "tan()", 1, "std", "Tangent (rad)",
                listOf("x: real — Angle in radians"),
                "real — Trigonometric tangent tan(x)"
            ),
            SuggestionItem(
                "asin", "asin(x)", "asin()", 1, "std", "Inverse sine",
                listOf("x: real — Ratio in [-1, 1]"),
                "real — Arcsine angle in radians in [-π/2, π/2]"
            ),
            SuggestionItem(
                "acos", "acos(x)", "acos()", 1, "std", "Inverse cosine",
                listOf("x: real — Ratio in [-1, 1]"),
                "real — Arccosine angle in radians in [0, π]"
            ),
            SuggestionItem(
                "atan", "atan(x)", "atan()", 1, "std", "Inverse tangent",
                listOf("x: real — Slope or ratio (-∞, ∞)"),
                "real — Arctangent angle in radians in (-π/2, π/2)"
            ),
            SuggestionItem(
                "deg2rad", "deg2rad(deg)", "deg2rad()", 1, "std", "Degrees to radians",
                listOf("deg: real — Angle measured in degrees"),
                "real — Equivalent angle in radians (deg * π / 180)"
            ),
            SuggestionItem(
                "rad2deg", "rad2deg(rad)", "rad2deg()", 1, "std", "Radians to degrees",
                listOf("rad: real — Angle measured in radians"),
                "real — Equivalent angle in degrees (rad * 180 / π)"
            ),
            SuggestionItem(
                "sqrt", "sqrt(x)", "sqrt()", 1, "std", "Square root √x",
                listOf("x: real — Non-negative real value (halves SI exponents)"),
                "real — Principal square root √x"
            ),
            SuggestionItem(
                "cbrt", "cbrt(x)", "cbrt()", 1, "std", "Cube root ∛x",
                listOf("x: real — Real scalar value"),
                "real — Real cube root ∛x"
            ),
            SuggestionItem(
                "abs", "abs(x)", "abs()", 1, "std", "Absolute value |x|",
                listOf("x: real — Signed scalar value (preserves SI units)"),
                "real — Non-negative magnitude |x|"
            ),
            SuggestionItem(
                "exp", "exp(x)", "exp()", 1, "std", "Natural exponential e^x",
                listOf("x: real — Dimensionless exponent"),
                "real — Euler's constant raised to x (ℯ^x)"
            ),
            SuggestionItem(
                "ln", "ln(x)", "ln()", 1, "std", "Natural logarithm",
                listOf("x: real — Positive dimensionless real number (x > 0)"),
                "real — Natural logarithm base ℯ"
            ),
            SuggestionItem(
                "log10", "log10(x)", "log10()", 1, "std", "Base-10 logarithm",
                listOf("x: real — Positive dimensionless real number (x > 0)"),
                "real — Common logarithm base 10"
            ),
            SuggestionItem(
                "log2", "log2(x)", "log2()", 1, "std", "Base-2 logarithm",
                listOf("x: real — Positive dimensionless real number (x > 0)"),
                "real — Binary logarithm base 2"
            ),
            SuggestionItem(
                "gcd", "gcd(a, b)", "gcd(, )", 3, "std", "Greatest common divisor",
                listOf(
                    "a: int — First integer value",
                    "b: int — Second integer value"
                ),
                "int — Largest positive integer dividing both a and b"
            ),
            SuggestionItem(
                "lcm", "lcm(a, b)", "lcm(, )", 3, "std", "Least common multiple",
                listOf(
                    "a: int — First integer value",
                    "b: int — Second integer value"
                ),
                "int — Smallest positive integer divisible by both a and b"
            ),
            SuggestionItem(
                "ncr", "ncr(n, r)", "ncr(, )", 3, "std", "Combinations nCr",
                listOf(
                    "n: int — Total number of items in the set",
                    "r: int — Number of items chosen without order (0 <= r <= n)"
                ),
                "int — Binomial coefficient n! / (r! * (n - r)!)"
            ),
            SuggestionItem(
                "npr", "npr(n, r)", "npr(, )", 3, "std", "Permutations nPr",
                listOf(
                    "n: int — Total number of items in the set",
                    "r: int — Number of ordered positions selected (0 <= r <= n)"
                ),
                "int — Number of ordered permutations n! / (n - r)!"
            ),
            SuggestionItem(
                "sum", "sum(vec)", "sum()", 1, "std", "Sum of vector elements",
                listOf("vec: vec — Input numeric vector"),
                "real — Total sum of all elements in vec"
            ),
            SuggestionItem(
                "mean", "mean(vec)", "mean()", 1, "std", "Arithmetic mean of vector",
                listOf("vec: vec — Non-empty numeric vector"),
                "real — Arithmetic average (sum(vec) / len(vec))"
            ),
            SuggestionItem(
                "stddev", "stddev(vec)", "stddev()", 1, "std", "Standard deviation",
                listOf("vec: vec — Numeric sample vector"),
                "real — Sample standard deviation of the elements"
            ),
            SuggestionItem(
                "median", "median(vec)", "median()", 1, "std", "Median of vector",
                listOf("vec: vec — Non-empty numeric vector"),
                "real — Middle value (50th percentile) of sorted elements"
            ),
            SuggestionItem(
                "linspace", "linspace(start, stop, n)", "linspace(0, 1, 50)", 0, "std", "Evenly spaced vector",
                listOf(
                    "start: real — First value of the interval",
                    "stop: real — Last value of the interval (inclusive)",
                    "n: int — Number of evenly spaced samples to generate"
                ),
                "vec — Vector of n linearly spaced points from start to stop"
            ),
            SuggestionItem(
                "linsolve", "linsolve(A, b)", "linsolve(, )", 3, "std", "Solve linear system A*x = b",
                listOf(
                    "A: mat — Square n×n coefficient matrix",
                    "b: vec — Right-hand side n-element vector"
                ),
                "vec — Solution vector x satisfying A * x = b via LU pivoting"
            ),
            SuggestionItem(
                "det", "det(M)", "det()", 1, "std", "Matrix determinant",
                listOf("M: mat — Square n×n matrix"),
                "real — Scalar determinant det(M)"
            ),
            SuggestionItem(
                "inv", "inv(M)", "inv()", 1, "std", "Matrix inverse",
                listOf("M: mat — Invertible square n×n matrix"),
                "mat — Inverse matrix M^-1 such that M * M^-1 = I"
            ),
            SuggestionItem(
                "transpose", "transpose(M)", "transpose()", 1, "std", "Matrix transpose",
                listOf("M: mat — Input m×n matrix"),
                "mat — Transposed n×m matrix M^T"
            ),
            SuggestionItem(
                "eigvals", "eigvals(M)", "eigvals()", 1, "std", "Symmetric eigenvalues",
                listOf("M: mat — Symmetric square matrix"),
                "vec — Vector of real eigenvalues of M"
            ),
            SuggestionItem(
                "svd_s", "svd_s(M)", "svd_s()", 1, "std", "Singular values",
                listOf("M: mat — Input m×n matrix"),
                "vec — Singular values sorted in descending order"
            ),
            SuggestionItem(
                "pinv", "pinv(M)", "pinv()", 1, "std", "Moore-Penrose pseudoinverse",
                listOf("M: mat — Rectangular m×n matrix"),
                "mat — Moore-Penrose pseudoinverse matrix M^+"
            ),
            SuggestionItem(
                "lstsq", "lstsq(A, b)", "lstsq(, )", 3, "std", "Linear least squares",
                listOf(
                    "A: mat — Design matrix (m×n)",
                    "b: vec — Observation target vector (m elements)"
                ),
                "vec — Least-squares coefficient vector minimizing ||A*x - b||_2"
            ),
            SuggestionItem(
                "convert_fx", "convert_fx(amt, \"USD\", \"INR\")", "convert_fx(100, \"USD\", \"INR\")", 0, "std", "Currency patch conversion",
                listOf(
                    "amt: real — Monetary amount in the source currency",
                    "from: str — Source 3-letter ISO currency code (e.g. \"USD\")",
                    "to: str — Target 3-letter ISO currency code (e.g. \"INR\")"
                ),
                "real — Converted amount in the target currency using global patch rates"
            ),
            SuggestionItem(
                "fx_rate", "fx_rate(\"USD\", \"INR\")", "fx_rate(\"USD\", \"INR\")", 0, "std", "Live currency FX rate",
                listOf(
                    "from: str — Base 3-letter ISO currency code (e.g. \"USD\")",
                    "to: str — Quote 3-letter ISO currency code (e.g. \"INR\")"
                ),
                "real — Exchange rate multiplier (1 unit of 'from' in 'to')"
            )
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
        val fnRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()]*)\\)\\s*(?:->\\s*([^=:]+)|:\\s*([a-zA-Z0-9_,\\s]+))?(?::=|=)(?![=>])")
        val varRegex = Regex("(?m)^\\s*(?:(?:pub|priv|const|mut)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::\\s*([a-zA-Z_][a-zA-Z0-9_<>,\\s]*))?(?::=|=)(?![=>])")
        val typeRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$struct\\s+|\\$enum\\s+|\\$|struct\\s+|enum\\s+)([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:\\(([^()]*)\\))?")

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

    fun isSuggestionsPopupShowing(): Boolean =
        suggestionPopup?.isShowing == true

    fun dismissSuggestionDocPopup() {
        runCatching {
            if (suggestionDocPopup?.isShowing == true) {
                suggestionDocPopup?.dismiss()
            }
        }
    }

    fun dismissSuggestionsPopup() {
        activePrefixRange = null
        dismissSuggestionDocPopup()
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

    private fun buildParamBulletsFromSignature(paramsRaw: String, roleHint: String): List<String> {
        val trimmed = paramsRaw.trim()
        if (trimmed.isEmpty()) {
            return listOf("None — Takes no parameters")
        }
        return trimmed.split(",").mapIndexed { idx, rawPart ->
            val part = rawPart.trim()
            val colonIdx = part.indexOf(':')
            if (colonIdx > 0) {
                val pName = part.substring(0, colonIdx).trim()
                val pType = part.substring(colonIdx + 1).trim().ifEmpty { "any" }
                "$pName: $pType — Argument #${idx + 1} ($roleHint)"
            } else {
                "$part: any — Argument #${idx + 1} ($roleHint)"
            }
        }
    }

    private fun collectLiveBufferSymbols(src: String, excludeRange: IntRange): List<SuggestionItem> {
        val out = mutableListOf<SuggestionItem>()
        val seen = HashSet<String>()
        val fnRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()]*)\\)\\s*(?:->\\s*([^=:]+)|:\\s*([a-zA-Z0-9_,\\s]+))?(?::=|=)(?![=>])")
        val varRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::\\s*([a-zA-Z_][a-zA-Z0-9_<>,\\s]*))?(?::=|=)(?![=>])")

        for (m in fnRegex.findAll(src)) {
            if (m.range.first in excludeRange) continue
            val fn = m.groupValues[1]
            val params = m.groupValues[2].trim()
            val retType = m.groupValues.getOrNull(3)?.trim()?.ifEmpty { null }
                ?: m.groupValues.getOrNull(4)?.trim()?.ifEmpty { null }
                ?: "any"
            if (fn !in KEYWORDS && seen.add(fn)) {
                out.add(
                    SuggestionItem(
                        matchKey = fn,
                        displayLabel = "$fn($params)",
                        insertText = "$fn()",
                        cursorBackOffset = if (params.isNotEmpty()) 1 else 0,
                        category = "fn",
                        detail = "Current module fn",
                        paramBullets = buildParamBulletsFromSignature(params, "Parameter of $fn"),
                        returnBullet = "$retType — Computed result of $fn(...) in active module"
                    )
                )
            }
        }
        for (m in varRegex.findAll(src)) {
            if (m.range.first in excludeRange) continue
            val v = m.groupValues[1]
            val vType = m.groupValues.getOrNull(2)?.trim()?.ifEmpty { null } ?: "real | any"
            if (v !in KEYWORDS && v !in BUILTIN_TYPES && seen.add(v)) {
                out.add(
                    SuggestionItem(
                        matchKey = v,
                        displayLabel = v,
                        insertText = v,
                        cursorBackOffset = 0,
                        category = "var",
                        detail = "Current module var",
                        paramBullets = listOf("None — Local or module variable in current buffer"),
                        returnBullet = "$vType — Current bound value of $v"
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
        dismissSuggestionDocPopup()

        val tvHdr = TextView(context).apply {
            text = "💡 Suggestions for \"$currentPrefix\" (tap: insert • hold: params & returns)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
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

        val heldRowBg = Color.parseColor(if (dark) "#1D3638" else "#FCEAE6")

        items.forEach { item ->
            var isHoldingDoc = false
            val rowDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 9f * density
                setColor(rowBg)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(3f) }
                background = rowDrawable
                isClickable = true
                isLongClickable = true
                setOnClickListener {
                    if (!isHoldingDoc) {
                        applySuggestionItem(item)
                    }
                }
                setOnLongClickListener {
                    isHoldingDoc = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    rowDrawable.setColor(heldRowBg)
                    rowDrawable.setStroke(max(1, dp(1f)), strokeCol)
                    showSuggestionDocPopup(this, item)
                    true
                }
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            isHoldingDoc = false
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isHoldingDoc) {
                                dismissSuggestionDocPopup()
                                rowDrawable.setColor(rowBg)
                                rowDrawable.setStroke(0, Color.TRANSPARENT)
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                isHoldingDoc = false
                                v.isPressed = false
                                return@setOnTouchListener true
                            }
                        }
                    }
                    false
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

    /**
     * Displays a secondary elevated popup on top of the suggestions popup while a suggestion item
     * is held down, listing its parameters and return value in an ordered bulleted format with a
     * concise single-line summary for each bullet item. Disappears immediately on release.
     */
    private fun showSuggestionDocPopup(anchorRow: View, item: SuggestionItem) {
        dismissSuggestionDocPopup()
        val dp = { v: Float -> (v * density).toInt() }
        val dark = isDarkThemeMode

        val cardBg = Color.parseColor(if (dark) "#191C20" else "#FFFFFF")
        val secBg = Color.parseColor(if (dark) "#22262C" else "#F3F6FA")
        val strokeCol = Color.parseColor(if (dark) "#26C6DA" else "#E58A7B")
        val subStrokeCol = Color.parseColor(if (dark) "#353B45" else "#D8DEE9")
        val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#1F2328")
        val secondaryText = Color.parseColor(if (dark) "#A5B0C0" else "#57606A")

        val badgeCol = when (item.category) {
            "entry", "dir" -> colIntrinsic
            "kw" -> colKeyword
            "mod" -> colModule
            "type" -> colType
            "fn", "proj" -> colOperator
            else -> colNumber
        }

        val docCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11f), dp(9f), dp(11f), dp(9f))
            elevation = dp(26f).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 13f * density
                setColor(cardBg)
                setStroke(max(1, dp(1.4f)), strokeCol)
            }
        }
        suggestionDocCard = docCard

        // 1. Top title row: [category badge] + signature + short detail
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        val tvBadge = TextView(context).apply {
            text = item.category.uppercase()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(badgeCol)
            setPadding(dp(5f), dp(2f), dp(5f), dp(2f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 5f * density
                setColor(secBg)
                setStroke(1, badgeCol)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(7f) }
        }
        val tvSig = TextView(context).apply {
            text = item.displayLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(primaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvShort = TextView(context).apply {
            text = item.detail
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
            setTextColor(strokeCol)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6f) }
        }
        titleRow.addView(tvBadge)
        titleRow.addView(tvSig)
        titleRow.addView(tvShort)
        docCard.addView(titleRow)

        fun makeSectionBox(): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8.5f * density
                    setColor(secBg)
                    setStroke(1, subStrokeCol)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5f) }
            }
        }

        fun makeBulletLine(bulletText: String, accentPrefix: Boolean = false): TextView {
            return TextView(context).apply {
                text = "• $bulletText"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.4f)
                typeface = Typeface.MONOSPACE
                setTextColor(if (accentPrefix) primaryText else secondaryText)
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(1.5f), 0, dp(1.5f))
            }
        }

        // 2. Ordered Parameters Section
        val paramsBox = makeSectionBox()
        val tvParamsHdr = TextView(context).apply {
            text = "PARAMETERS (IN ORDER)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(strokeCol)
            setPadding(0, 0, 0, dp(2f))
        }
        paramsBox.addView(tvParamsHdr)

        val effectiveParams = item.paramBullets.ifEmpty {
            listOf("None — Takes no parameters")
        }
        effectiveParams.forEachIndexed { idx, pLine ->
            paramsBox.addView(makeBulletLine("${idx + 1}. $pLine", accentPrefix = true))
        }
        docCard.addView(paramsBox)

        // 3. Returns Section
        val retBox = makeSectionBox().apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        }
        val tvRetHdr = TextView(context).apply {
            text = "RETURNS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(strokeCol)
            setPadding(0, 0, 0, dp(2f))
        }
        retBox.addView(tvRetHdr)
        val effectiveRet = item.returnBullet.ifBlank {
            "${item.category} — ${item.detail}"
        }
        retBox.addView(makeBulletLine(effectiveRet, accentPrefix = true))
        docCard.addView(retBox)

        val popupW = (resources.displayMetrics.widthPixels - dp(32f)).coerceIn(dp(244f), dp(356f))
        docCard.measure(
            MeasureSpec.makeMeasureSpec(popupW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val measuredH = docCard.measuredHeight.coerceAtLeast(dp(88f))

        val docPw = PopupWindow(docCard, popupW, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isFocusable = false
            isTouchable = false
            isOutsideTouchable = false
            elevation = dp(28f).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        suggestionDocPopup = docPw

        // Position directly on top of / above the held suggestion row
        val rowLoc = IntArray(2)
        anchorRow.getLocationOnScreen(rowLoc)
        val screenW = resources.displayMetrics.widthPixels
        val xPos = (rowLoc[0] + (anchorRow.width - popupW) / 2).coerceIn(dp(12f), (screenW - popupW - dp(12f)).coerceAtLeast(dp(12f)))
        val candidateAboveY = rowLoc[1] - measuredH - dp(6f)
        val yPos = if (candidateAboveY >= dp(36f)) {
            candidateAboveY
        } else {
            rowLoc[1] + anchorRow.height + dp(6f)
        }

        runCatching {
            docPw.showAtLocation(this, Gravity.NO_GRAVITY, xPos, yPos)
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