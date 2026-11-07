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

internal val MATH_EDITOR_STATIC_SUGGESTIONS: List<SuggestionItem> = listOf(
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
        "unit_dim", "unit_dim(val, axis)", "unit_dim(, 0)", 4, "fn", "Inspect SI exponent",
        listOf(
            "val: real — Quantity carrying physical SI units",
            "axis: int — SI dimension index (0=m, 1=kg, 2=s, 3=A, 4=K, 5=mol, 6=cd)"
        ),
        "real — Exponent of the requested SI base dimension on val"
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
        "<[conversions", "<[conversions]", "<[conversions]", 0, "mod", "Unit conversions (into, to_)",
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
            "x: any — Target value or struct/vector pattern to match against",
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
        "struct", "struct Name(x: real, y: real)", "struct Point(x: real, y: real)", 0, "type", "Struct type declaration",
        listOf(
            "Name: ident — Custom struct type name (or \$Name(x: real, y: real))",
            "fields: (f1: type, ...) — Ordered typed fields for the struct constructor"
        ),
        "type — Registers constructor Name(...) and field accessors (.f1, ...)"
    ),
    SuggestionItem(
        "into", "val into unit_or_vec", " into ", 0, "op", "Unit convert / append",
        listOf(
            "lhs: any — Unit-tagged value (e.g. 2km) or element to append",
            "rhs: unit | vec — Target unit (e.g. m) or destination container"
        ),
        "any — Converted value in target unit (2km into m -> 2000 [length]) or updated container"
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
        "unit_of", "unit_of(val)", "unit_of()", 1, "fn", "Physical unit group name",
        listOf("val: real — Value carrying 7D SI physical dimensions"),
        "str — Physical quantity category (e.g. \"force\", \"energy\", \"none\")"
    ),
    SuggestionItem(
        "type_of", "type_of(val)", "type_of()", 1, "fn", "Entity type name",
        listOf("val: any — Any scalar, container, closure, or struct instance"),
        "str — Runtime type name (\"real\", \"vec\", \"map\", \"str\", struct name)"
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

