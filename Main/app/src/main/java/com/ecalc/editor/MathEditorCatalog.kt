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

import com.mathengine.MathEngineBridge
internal val MATH_EDITOR_STATIC_SUGGESTIONS: List<MathCodeEditText.SuggestionItem> = listOf(
    // Entry points & directives
    MathCodeEditText.SuggestionItem(
        "main", "pub main() = { ... }", "pub main() = {\n    \n}", 2, "entry", "Module start point",
        listOf("None — Zero-argument program entry point"),
        "any — Final expression value of the main() block"
    ),
    MathCodeEditText.SuggestionItem(
        "pub main", "pub main() = { ... }", "pub main() = {\n    \n}", 2, "entry", "Module start point",
        listOf("None — Zero-argument program entry point"),
        "any — Final expression value of the main() block"
    ),
    MathCodeEditText.SuggestionItem(
        "@entry", "@entry(main)", "@entry(main)", 1, "dir", "Explicit entry function",
        listOf("fn_name: ident — Zero-arg function to invoke as module start point"),
        "void — Registers fn_name as the module's execution entry point"
    ),
    MathCodeEditText.SuggestionItem(
        "@dim", "@dim(unit, m, kg, s, A, K, mol, cd)", "@dim(custom_unit, 1, 0, 0, 0, 0, 0, 0)", 0, "dir", "7D SI unit dimension",
        listOf(
            "unit: ident — Unit symbol name to register",
            "m, kg, s, A, K, mol, cd: int — 7 integer SI base dimension exponents"
        ),
        "void — Binds the 7D SI physical dimension vector into the symbol table"
    ),
    MathCodeEditText.SuggestionItem(
        "unit_dim", "unit_dim(val, axis)", "unit_dim(, 0)", 4, "fn", "Inspect SI exponent",
        listOf(
            "val: real — Quantity carrying physical SI units",
            "axis: int — SI dimension index (0=m, 1=kg, 2=s, 3=A, 4=K, 5=mol, 6=cd)"
        ),
        "real — Exponent of the requested SI base dimension on val"
    ),

    // Standard Library & Patch <[dep] modules
    MathCodeEditText.SuggestionItem(
        "<[arith", "<[arith]", "<[arith]", 0, "mod", "Core arithmetic & stats",
        listOf("arith: module — Standard arithmetic, combinatorics & descriptive stats"),
        "void — Imports public functions and constants from [arith]"
    ),
    MathCodeEditText.SuggestionItem(
        "<[trig", "<[trig]", "<[trig]", 0, "mod", "Trigonometric functions",
        listOf("trig: module — Circular, inverse, hyperbolic & degree/radian helpers"),
        "void — Imports public functions and constants from [trig]"
    ),
    MathCodeEditText.SuggestionItem(
        "<[units", "<[units]", "<[units]", 0, "mod", "7D SI physical units",
        listOf("units: module — Base and derived 7D SI physical unit definitions"),
        "void — Imports SI unit symbols (m, kg, s, N, J, W, Pa, Hz, ...)"
    ),
    MathCodeEditText.SuggestionItem(
        "<[conversions", "<[conversions]", "<[conversions]", 0, "mod", "Unit conversions (into, to_)",
        listOf("conversions: module — Unit conversion factors & temperature helpers"),
        "void — Imports conversion helpers from [conversions]"
    ),
    MathCodeEditText.SuggestionItem(
        "<[calculus", "<[calculus]", "<[calculus]", 0, "mod", "Calculus, ODE & symbolic CAS",
        listOf("calculus: module — Numerical derivatives, integrals, RK4 ODE & symbolic CAS"),
        "void — Imports diff, integrate, ode_rk4, sym_diff, sym_integ, sym_solve"
    ),
    MathCodeEditText.SuggestionItem(
        "<[algebra", "<[algebra]", "<[algebra]", 0, "mod", "Solvers, polynomials & finance",
        listOf("algebra: module — Root solvers, polynomial roots & financial math"),
        "void — Imports quadratic, cubic, poly_roots, solve, npv, irr"
    ),
    MathCodeEditText.SuggestionItem(
        "<[vec", "<[vec]", "<[vec]", 0, "mod", "Vectors, maps, ranges & HOFs",
        listOf("vec: module — Dynamic vectors, \$map dictionaries, CSV & HOF pipelines"),
        "void — Imports vector/map utilities and higher-order functions"
    ),
    MathCodeEditText.SuggestionItem(
        "<[mat", "<[mat]", "<[mat]", 0, "mod", "Matrices, LU/QR/SVD/Eigen",
        listOf("mat: module — 2D matrices, linear solvers & decompositions"),
        "void — Imports det, inv, linsolve, eigvals, svd_s, pinv, lstsq"
    ),
    MathCodeEditText.SuggestionItem(
        "<[string", "<[string]", "<[string]", 0, "mod", "Strings, formatting & regex",
        listOf("string: module — UTF-8 string manipulation, splitting & formatting"),
        "void — Imports string helpers from [string]"
    ),
    MathCodeEditText.SuggestionItem(
        "<[complex_ext", "<[complex_ext]", "<[complex_ext]", 0, "mod", "Complex numbers (\$complex)",
        listOf("complex_ext: module — Complex number arithmetic, polar form & phasors"),
        "void — Imports \$complex type, conj, c_abs, c_arg, c_exp"
    ),
    MathCodeEditText.SuggestionItem(
        "<[geom_ext", "<[geom_ext]", "<[geom_ext]", 0, "mod", "Geometry (\$vec2, \$vec3, \$circle)",
        listOf("geom_ext: module — 2D/3D vectors, lines, circles, planes & spheres"),
        "void — Imports \$vec2, \$vec3, \$circle, \$line2, \$plane3, \$sphere"
    ),
    MathCodeEditText.SuggestionItem(
        "<[physics", "<[physics]", "<[physics]", 0, "mod", "Classical & modern physics",
        listOf("physics: module — Mechanics, electromagnetism, relativity & waves"),
        "void — Imports physical constants and equations from [physics]"
    ),
    MathCodeEditText.SuggestionItem(
        "<[chemistry", "<[chemistry]", "<[chemistry]", 0, "mod", "Gas laws, pH & stoichiometry",
        listOf("chemistry: module — Ideal gas, pH, Arrhenius & molar mass utilities"),
        "void — Imports chemistry formulas and constants from [chemistry]"
    ),
    MathCodeEditText.SuggestionItem(
        "<[biology", "<[biology]", "<[biology]", 0, "mod", "Genetics, kinetics & ecology",
        listOf("biology: module — Michaelis-Menten kinetics, logistic growth & genetics"),
        "void — Imports biology models from [biology]"
    ),
    MathCodeEditText.SuggestionItem(
        "<[currency", "<[currency]", "<[currency]", 0, "mod", "Global currency patch",
        listOf("currency: module — Global stdlib patch for currency rates & conversion"),
        "void — Imports live currency conversion functions and ISO rate constants"
    ),

    // Operators & Word Operators (into, in, by, and, or, not, xor, ...)
    MathCodeEditText.SuggestionItem(
        "into", "expr into unit_or_vec", "into", 0, "op", "Convert unit / push to vec",
        listOf(
            "lhs: real | any — Quantity with units (e.g. 2km, 25$) or element to append",
            "rhs: unit | vec — Target unit/currency (e.g. m, RS, INR, hex) or destination vector"
        ),
        "real | vec — Value converted into target unit/currency (e.g. 2km into m -> 2000 [length], 25$ into RS) or updated vector"
    ),
    MathCodeEditText.SuggestionItem(
        "in", "x in collection_or_range", "in", 0, "op", "Boolean membership / loop range",
        listOf(
            "lhs: any | ident — Value to test for membership, or loop variable in (i in 1...10)",
            "rhs: vec | map | range — Target vector, map keys, or numeric range (a...b / a..<b)"
        ),
        "bool | loop — Returns 1 (true) if lhs is an element of rhs, or binds loop iterator variable"
    ),
    MathCodeEditText.SuggestionItem(
        "by", "start...stop by step", "by", 0, "op", "Range step increment",
        listOf(
            "range: a...b | a..<b — Inclusive or half-open numeric range expression",
            "step: real — Non-zero step increment between consecutive range elements"
        ),
        "vec — Stepped range vector (e.g. 0...10 by 2 -> [0, 2, 4, 6, 8, 10])"
    ),
    MathCodeEditText.SuggestionItem(
        "and", "a and b", "and", 0, "op", "Logical / bitwise AND",
        listOf(
            "a: bool | real — Left condition or operand",
            "b: bool | real — Right condition or operand"
        ),
        "bool — 1 if both a and b are non-zero, otherwise 0"
    ),
    MathCodeEditText.SuggestionItem(
        "not", "not(x)", "not()", 1, "op", "Logical negation",
        listOf("x: bool | real — Boolean condition (0 or 1) to negate"),
        "bool — 1 if x == 0, or 0 if x == 1"
    ),
    MathCodeEditText.SuggestionItem(
        "xor", "xor(a, b)", "xor(, )", 3, "op", "Logical exclusive-OR",
        listOf(
            "a: bool | real — First boolean operand",
            "b: bool | real — Second boolean operand"
        ),
        "bool — 1 if exactly one of a or b is non-zero, otherwise 0"
    ),
    MathCodeEditText.SuggestionItem(
        "nand", "nand(a, b)", "nand(, )", 3, "op", "Logical NAND",
        listOf("a, b: bool — Input boolean conditions"),
        "bool — Negated conjunction !(a && b)"
    ),
    MathCodeEditText.SuggestionItem(
        "nor", "nor(a, b)", "nor(, )", 3, "op", "Logical NOR",
        listOf("a, b: bool — Input boolean conditions"),
        "bool — Negated disjunction !(a || b)"
    ),
    MathCodeEditText.SuggestionItem(
        "xnor", "xnor(a, b)", "xnor(, )", 3, "op", "Logical equivalence XNOR",
        listOf("a, b: bool — Input boolean conditions"),
        "bool — 1 if a and b have the same truth value, otherwise 0"
    ),
    MathCodeEditText.SuggestionItem(
        "for", "(i in 1...10) { ... }", "(i in 1...10) {\n    \n}", 2, "kw", "For-in range/vector loop",
        listOf(
            "i: ident — Loop iteration variable bound on each step",
            "iter: range | vec — Range (1...10, 0..<n) or vector to iterate over"
        ),
        "any — Evaluates the loop body for each element and returns the final iteration value"
    ),
    MathCodeEditText.SuggestionItem(
        "while", "(cond) ?? { ... }", "() ?? {\n    \n}", 8, "kw", "While loop (cond ?? body)",
        listOf(
            "cond: bool — Loop continuation condition tested before each iteration",
            "body: { ... } — Block executed while cond evaluates to non-zero"
        ),
        "any — Value of the last executed loop iteration"
    ),
    MathCodeEditText.SuggestionItem(
        "do", "{ ... } ?? (cond)", "{\n    \n} ?? ()", 1, "kw", "Do-while loop (body ?? cond)",
        listOf(
            "body: { ... } — Block executed at least once before testing condition",
            "cond: bool — Continuation condition tested after each pass"
        ),
        "any — Value of the last executed loop pass"
    ),
    MathCodeEditText.SuggestionItem(
        "case", "case pattern: expr", "case : ", 2, "kw", "Switch pattern arm",
        listOf(
            "pattern: any — Literal, range, or destructuring pattern inside switch (x)",
            "expr: any — Result expression evaluated when pattern matches"
        ),
        "any — Value returned for the matching switch arm"
    ),
    MathCodeEditText.SuggestionItem(
        "catch", "catch (e) { ... }", "catch (e) {\n    e\n}", 2, "kw", "Catch error block",
        listOf("e: str — Variable receiving the thrown or runtime error message"),
        "any — Recovery value returned when the preceding try block fails"
    ),
    MathCodeEditText.SuggestionItem(
        "throw", "throw(\"message\")", "throw(\"\")", 2, "kw", "Raise exception",
        listOf("msg: str | any — Error message or value to raise"),
        "void — Transfers control to the nearest enclosing try/catch handler or halts evaluation"
    ),
    MathCodeEditText.SuggestionItem(
        "println", "println(\"{expr}\")", "println(\"\")", 2, "kw", "Print line with interpolation",
        listOf("args: any... — Values or interpolated strings (e.g. \"{25$ into RS}\") to print with newline"),
        "any — Appends formatted output + newline to Console Stream and returns the last argument"
    ),
    MathCodeEditText.SuggestionItem(
        "print", "print(\"{expr}\")", "print(\"\")", 2, "kw", "Print without newline",
        listOf("args: any... — Values or interpolated strings (e.g. \"{2km into m}\") to print"),
        "any — Appends formatted output to Console Stream and returns the last argument"
    ),
    MathCodeEditText.SuggestionItem(
        "true", "true", "true", 0, "kw", "Boolean true (1)",
        listOf("None — Boolean truth constant equal to 1"),
        "real — Numeric boolean value 1.0"
    ),
    MathCodeEditText.SuggestionItem(
        "false", "false", "false", 0, "kw", "Boolean false (0)",
        listOf("None — Boolean false constant equal to 0"),
        "real — Numeric boolean value 0.0"
    ),
    // Common Units & Currencies for `into` conversions
    MathCodeEditText.SuggestionItem(
        "RS", "RS (Indian Rupee)", "RS", 0, "unit", "Indian Rupee currency unit",
        listOf("x: real — Monetary amount (e.g. 25$ into RS or 500 RS into USD)"),
        "real [inr] — Quantity tagged with Indian Rupee [inr] currency unit"
    ),
    MathCodeEditText.SuggestionItem(
        "Rs", "Rs (Indian Rupee)", "Rs", 0, "unit", "Indian Rupee currency unit",
        listOf("x: real — Monetary amount (e.g. 25$ into Rs or 500 Rs into USD)"),
        "real [inr] — Quantity tagged with Indian Rupee [inr] currency unit"
    ),
    MathCodeEditText.SuggestionItem(
        "INR", "INR (Indian Rupee)", "INR", 0, "unit", "Indian Rupee ISO unit",
        listOf("x: real — Monetary amount (e.g. 25$ into INR or 1000 INR into EUR)"),
        "real [inr] — Quantity tagged with Indian Rupee [inr] currency unit"
    ),
    MathCodeEditText.SuggestionItem(
        "USD", "USD (US Dollar)", "USD", 0, "unit", "US Dollar ISO unit ($)",
        listOf("x: real — Monetary amount (e.g. 25 USD into RS or 1000 INR into USD)"),
        "real [usd] — Quantity tagged with US Dollar [usd] currency unit"
    ),
    MathCodeEditText.SuggestionItem(
        "EUR", "EUR (Euro)", "EUR", 0, "unit", "Euro ISO unit (€)",
        listOf("x: real — Monetary amount (e.g. 50 EUR into USD or 25$ into EUR)"),
        "real [eur] — Quantity tagged with Euro [eur] currency unit"
    ),
    MathCodeEditText.SuggestionItem(
        "GBP", "GBP (British Pound)", "GBP", 0, "unit", "British Pound ISO unit (£)",
        listOf("x: real — Monetary amount (e.g. 50 GBP into INR or 25$ into GBP)"),
        "real [gbp] — Quantity tagged with British Pound [gbp] currency unit"
    ),
    MathCodeEditText.SuggestionItem(
        "JPY", "JPY (Japanese Yen)", "JPY", 0, "unit", "Japanese Yen ISO unit (¥)",
        listOf("x: real — Monetary amount (e.g. 25$ into JPY or 5000 JPY into USD)"),
        "real [jpy] — Quantity tagged with Japanese Yen [jpy] currency unit"
    ),
    MathCodeEditText.SuggestionItem(
        "km", "km (kilometer)", "km", 0, "unit", "SI length unit (1000 m)",
        listOf("x: real — Length magnitude (e.g. 2km into m)"),
        "real [length] — Length quantity in kilometers (10^3 m)"
    ),
    MathCodeEditText.SuggestionItem(
        "m", "m (meter)", "m", 0, "unit", "SI base length unit",
        listOf("x: real — Length magnitude (e.g. 2km into m)"),
        "real [length] — Length quantity in meters"
    ),
    MathCodeEditText.SuggestionItem(
        "cm", "cm (centimeter)", "cm", 0, "unit", "SI length unit (0.01 m)",
        listOf("x: real — Length magnitude (e.g. 1.5m into cm)"),
        "real [length] — Length quantity in centimeters (10^-2 m)"
    ),
    MathCodeEditText.SuggestionItem(
        "mm", "mm (millimeter)", "mm", 0, "unit", "SI length unit (0.001 m)",
        listOf("x: real — Length magnitude (e.g. 5cm into mm)"),
        "real [length] — Length quantity in millimeters (10^-3 m)"
    ),
    MathCodeEditText.SuggestionItem(
        "kg", "kg (kilogram)", "kg", 0, "unit", "SI base mass unit",
        listOf("x: real — Mass magnitude (e.g. 2.5kg into g)"),
        "real [mass] — Mass quantity in kilograms"
    ),
    MathCodeEditText.SuggestionItem(
        "degC", "degC (Celsius)", "degC", 0, "unit", "Celsius temperature unit",
        listOf("x: real — Temperature in °C (e.g. 100 degC into degF)"),
        "real [temperature] — Temperature converted to Kelvin [temperature]"
    ),
    MathCodeEditText.SuggestionItem(
        "degF", "degF (Fahrenheit)", "degF", 0, "unit", "Fahrenheit temperature unit",
        listOf("x: real — Temperature in °F (e.g. 212 degF into degC)"),
        "real [temperature] — Temperature converted to Kelvin [temperature]"
    ),
    MathCodeEditText.SuggestionItem(
        "hex", "hex (base-16 str)", "hex", 0, "unit", "Hexadecimal conversion target",
        listOf("n: int — Integer to format in base 16 (e.g. 255 into hex -> \"0xff\")"),
        "str — Hexadecimal string representation with 0x prefix"
    ),
    MathCodeEditText.SuggestionItem(
        "bin", "bin (base-2 str)", "bin", 0, "unit", "Binary conversion target",
        listOf("n: int — Integer to format in base 2 (e.g. 42 into bin -> \"0b101010\")"),
        "str — Binary string representation with 0b prefix"
    ),
    MathCodeEditText.SuggestionItem(
        "oct", "oct (base-8 str)", "oct", 0, "unit", "Octal conversion target",
        listOf("n: int — Integer to format in base 8 (e.g. 64 into oct -> \"0o100\")"),
        "str — Octal string representation with 0o prefix"
    ),

    // Keywords & Control Flow
    MathCodeEditText.SuggestionItem(
        "pub", "pub ", "pub ", 0, "kw", "Public visibility",
        listOf("decl: fn | var | const | \$type — Symbol exported to importing modules"),
        "void — Marks declaration as public across <[dep] module imports"
    ),
    MathCodeEditText.SuggestionItem(
        "priv", "priv ", "priv ", 0, "kw", "Private to module",
        listOf("decl: fn | var | const | \$type — Symbol hidden from importing modules"),
        "void — Restricts symbol visibility to the declaring .math module"
    ),
    MathCodeEditText.SuggestionItem(
        "const", "const ", "const ", 0, "kw", "Immutable binding",
        listOf("name = expr — Identifier and initial value expression"),
        "any — Binds an immutable constant that cannot be reassigned"
    ),
    MathCodeEditText.SuggestionItem(
        "mut", "mut ", "mut ", 0, "kw", "Mutable variable",
        listOf("name := expr — Identifier and initial mutable value"),
        "any — Binds a mutable variable that can be updated with = or :="
    ),
    MathCodeEditText.SuggestionItem(
        "if", "if (cond) { ... }", "if () {\n    \n}", 9, "kw", "Conditional branch",
        listOf(
            "cond: bool — Condition expression (non-zero is true)",
            "body: { ... } — Block evaluated when cond is true"
        ),
        "any — Value of the executed branch block"
    ),
    MathCodeEditText.SuggestionItem(
        "or", "or (cond) { ... }", "or () {\n    \n}", 9, "kw", "Else-if branch",
        listOf(
            "cond: bool — Secondary condition tested if preceding if/or was false",
            "body: { ... } — Block evaluated when cond is true"
        ),
        "any — Value of the matching branch block"
    ),
    MathCodeEditText.SuggestionItem(
        "else", "else { ... }", "else {\n    \n}", 2, "kw", "Fallback branch",
        listOf("body: { ... } — Block evaluated when all preceding if/or conditions are false"),
        "any — Value of the fallback block"
    ),
    MathCodeEditText.SuggestionItem(
        "switch", "switch (x) { case ... }", "switch (x) {\n    case 0: 0\n    else: 1\n}", 0, "kw", "Pattern-matching switch",
        listOf(
            "x: any — Target value or struct/vector pattern to match against",
            "cases: case pat: expr — Ordered pattern arms with an else fallback"
        ),
        "any — Result of the first matching case arm"
    ),
    MathCodeEditText.SuggestionItem(
        "try", "try { ... } catch (e) { ... }", "try {\n    \n} catch (e) {\n    e\n}", 18, "kw", "Exception handler",
        listOf(
            "try_block: { ... } — Protected block that may throw or fail",
            "e: str — Caught error message bound in the catch block"
        ),
        "any — Value of try_block on success, or catch_block on error"
    ),
    MathCodeEditText.SuggestionItem(
        "return", "return ", "return ", 0, "kw", "Early return from function",
        listOf("expr: any — Value to return immediately from the enclosing function"),
        "any — Exits the current function with expr"
    ),
    MathCodeEditText.SuggestionItem(
        "break", "break", "break", 0, "kw", "Exit active loop/switch",
        listOf("None — Immediately terminates the innermost loop"),
        "void — Transfers control past the enclosing loop"
    ),
    MathCodeEditText.SuggestionItem(
        "continue", "continue", "continue", 0, "kw", "Next loop iteration",
        listOf("None — Skips the rest of the current loop body"),
        "void — Advances to the next iteration of the enclosing loop"
    ),
    MathCodeEditText.SuggestionItem(
        "import", "import \"module.math\"", "import \"\"", 1, "kw", "Import module or file",
        listOf("path: str — Module name or .math file to load in declaration-only mode"),
        "void — Links public declarations from the target module"
    ),

    // Types & Data Structures
    MathCodeEditText.SuggestionItem(
        "struct", "struct Name(x: real, y: real)", "struct Point(x: real, y: real)", 0, "type", "Struct type declaration",
        listOf(
            "Name: ident — Custom struct type name (or \$Name(x: real, y: real))",
            "fields: (f1: type, ...) — Ordered typed fields for the struct constructor"
        ),
        "type — Registers constructor Name(...) and field accessors (.f1, ...)"
    ),
    MathCodeEditText.SuggestionItem(
        "real", "real", "real", 0, "type", "64-bit float type",
        listOf("None — IEEE-754 double-precision scalar type annotation"),
        "type — 64-bit floating-point number (with optional 7D SI units)"
    ),
    MathCodeEditText.SuggestionItem(
        "str", "str", "str", 0, "type", "UTF-8 string type",
        listOf("None — Immutable UTF-8 string type annotation"),
        "type — Heap-managed string value"
    ),
    MathCodeEditText.SuggestionItem(
        "vec", "vec(a, b, ...)", "vec()", 1, "type", "Dynamic vector",
        listOf("elems: any... — Zero or more elements to pack into a vector"),
        "vec — 1D dynamic array supporting indexing, slicing & HOFs"
    ),
    MathCodeEditText.SuggestionItem(
        "vec2", "vec2(x, y)", "vec2(, )", 3, "type", "2D vector struct",
        listOf(
            "x: real — Horizontal X component",
            "y: real — Vertical Y component"
        ),
        "vec2 — 2D geometric vector instance"
    ),
    MathCodeEditText.SuggestionItem(
        "vec3", "vec3(x, y, z)", "vec3(, , )", 5, "type", "3D vector struct",
        listOf(
            "x: real — X coordinate component",
            "y: real — Y coordinate component",
            "z: real — Z coordinate component"
        ),
        "vec3 — 3D geometric vector instance"
    ),
    MathCodeEditText.SuggestionItem(
        "complex", "complex(re, im)", "complex(, )", 3, "type", "Complex number",
        listOf(
            "re: real — Real part of the complex number",
            "im: real — Imaginary part (coefficient of i)"
        ),
        "complex — Complex number instance (re + im*i)"
    ),
    MathCodeEditText.SuggestionItem(
        "dict", "dict(\"k\", v)", "dict(\"\", )", 3, "type", "Key-value \$map dictionary",
        listOf(
            "key: str — String key name (alternating k1, v1, k2, v2, ...)",
            "val: any — Associated value for each key"
        ),
        "map — Key-value dictionary supporting m[\"key\"] lookup & mutation"
    ),
    MathCodeEditText.SuggestionItem(
        "map", "map(\"k\", v)", "map(\"\", )", 3, "type", "Key-value \$map dictionary",
        listOf(
            "key: str — String key name (alternating k1, v1, k2, v2, ...)",
            "val: any — Associated value for each key"
        ),
        "map — Key-value dictionary supporting m[\"key\"] lookup & mutation"
    ),

    // Console, File I/O, JSON, CSV & Bytecode VM
    MathCodeEditText.SuggestionItem(
        "println", "println(val)", "println(\"\")", 2, "fn", "Print line to console",
        listOf("val: any... — One or more values or \$\"...\" interpolated strings to print"),
        "any — Appends formatted line to Console Stream and returns last argument"
    ),
    MathCodeEditText.SuggestionItem(
        "print", "print(val)", "print(\"\")", 2, "fn", "Print to console",
        listOf("val: any... — One or more values to write without trailing newline"),
        "any — Appends formatted text to Console Stream and returns last argument"
    ),
    MathCodeEditText.SuggestionItem(
        "read_json", "read_json(\"file.json\")", "read_json(\"config.json\")", 2, "fn", "Parse JSON file into \$map/\$vec",
        listOf("path: str — Relative filename inside the active project folder"),
        "map | vec — Parsed RFC 8259 JSON object (\$map) or array (\$vec)"
    ),
    MathCodeEditText.SuggestionItem(
        "to_json", "to_json(val)", "to_json()", 1, "fn", "Serialize value to JSON",
        listOf("val: any — \$map, \$vec, \$struct, str, or real value to serialize"),
        "str — Valid RFC 8259 JSON string representation"
    ),
    MathCodeEditText.SuggestionItem(
        "read_file", "read_file(\"file\")", "read_file(\"\")", 2, "fn", "Read sandboxed project file",
        listOf("path: str — Relative filename inside the active project directory"),
        "str — Full UTF-8 text contents of the file"
    ),
    MathCodeEditText.SuggestionItem(
        "write_file", "write_file(\"file\", text)", "write_file(\"\", )", 2, "fn", "Write sandboxed project file",
        listOf(
            "path: str — Relative filename to write inside the active project folder",
            "text: str — UTF-8 string content to persist to disk"
        ),
        "real — 1.0 when the file write succeeds"
    ),
    MathCodeEditText.SuggestionItem(
        "csv_to_maps", "csv_to_maps(csv_str)", "csv_to_maps(read_file(\"sensor_data.csv\"))", 0, "fn", "Parse CSV into vec<map>",
        listOf("csv_str: str — Raw CSV text with a header row in the first line"),
        "vec — Vector of \$map rows keyed by column header names"
    ),

    MathCodeEditText.SuggestionItem(
        "unit_of", "unit_of(val)", "unit_of()", 1, "fn", "Physical unit group name",
        listOf("val: real — Value carrying 7D SI physical dimensions"),
        "str — Physical quantity category (e.g. \"force\", \"energy\", \"none\")"
    ),
    MathCodeEditText.SuggestionItem(
        "type_of", "type_of(val)", "type_of()", 1, "fn", "Entity type name",
        listOf("val: any — Any scalar, container, closure, or struct instance"),
        "str — Runtime type name (\"real\", \"vec\", \"map\", \"str\", struct name)"
    ),

    // Symbolic CAS & Numerical Calculus
    MathCodeEditText.SuggestionItem(
        "sym_simplify", "sym_simplify(expr)", "sym_simplify()", 1, "std", "Symbolic algebraic simplify",
        listOf("expr: ast — Algebraic or trigonometric expression to simplify"),
        "str — Exact symbolically simplified expression string"
    ),
    MathCodeEditText.SuggestionItem(
        "sym_diff", "sym_diff(x, expr)", "sym_diff(x, )", 1, "std", "Symbolic derivative d/dx",
        listOf(
            "x: ident — Differentiation variable symbol",
            "expr: ast — Symbolic expression f(x) to differentiate"
        ),
        "str — Exact symbolic derivative df/dx simplified algebraically"
    ),
    MathCodeEditText.SuggestionItem(
        "sym_integ", "sym_integ(x, expr)", "sym_integ(x, )", 1, "std", "Symbolic indefinite integral",
        listOf(
            "x: ident — Integration variable symbol",
            "expr: ast — Symbolic integrand expression f(x)"
        ),
        "str — Symbolic antiderivative F(x) such that F'(x) = f(x)"
    ),
    MathCodeEditText.SuggestionItem(
        "sym_solve", "sym_solve(x, eq == 0)", "sym_solve(x, )", 1, "std", "Symbolic equation solver",
        listOf(
            "x: ident — Unknown variable symbol to solve for",
            "eq: ast — Polynomial or algebraic equation (e.g. x^2 - 5*x + 6 == 0)"
        ),
        "vec — Vector of exact/numeric roots satisfying the equation"
    ),
    MathCodeEditText.SuggestionItem(
        "diff", "diff(x, expr, at)", "diff(x, , )", 3, "std", "Numerical derivative at x",
        listOf(
            "x: ident — Variable of differentiation",
            "expr: ast — Expression f(x) to differentiate",
            "at: real — Point x0 at which to evaluate df/dx"
        ),
        "real — Numerical derivative f'(at) via central differences"
    ),
    MathCodeEditText.SuggestionItem(
        "integrate", "integrate(x, expr, a, b)", "integrate(x, , 0, 1)", 6, "std", "Numerical definite integral",
        listOf(
            "x: ident — Integration dummy variable",
            "expr: ast — Integrand expression f(x)",
            "a: real — Lower integration limit",
            "b: real — Upper integration limit"
        ),
        "real — Definite integral ∫[a..b] f(x) dx via adaptive Simpson quadrature"
    ),
    MathCodeEditText.SuggestionItem(
        "sigma", "sigma(k, expr, lo, hi)", "sigma(k, , 1, 10)", 7, "std", "Discrete summation ∑",
        listOf(
            "k: ident — Loop index variable",
            "expr: ast — Term expression a(k) evaluated at each integer step",
            "lo: int — Starting index (inclusive)",
            "hi: int — Ending index (inclusive)"
        ),
        "real — Sum ∑_{k=lo..hi} expr(k)"
    ),
    MathCodeEditText.SuggestionItem(
        "prod", "prod(k, expr, lo, hi)", "prod(k, , 1, 5)", 6, "std", "Discrete product ∏",
        listOf(
            "k: ident — Loop index variable",
            "expr: ast — Factor expression a(k) evaluated at each integer step",
            "lo: int — Starting index (inclusive)",
            "hi: int — Ending index (inclusive)"
        ),
        "real — Product ∏_{k=lo..hi} expr(k)"
    ),
    MathCodeEditText.SuggestionItem(
        "lim", "lim(x, expr, target)", "lim(x, , 0)", 3, "std", "Two-sided limit",
        listOf(
            "x: ident — Approaching variable symbol",
            "expr: ast — Expression f(x) whose limit is evaluated",
            "target: real — Point x0 that x approaches"
        ),
        "real — Numerical two-sided limit as x -> target"
    ),
    MathCodeEditText.SuggestionItem(
        "solve", "solve(x, lhs == rhs, guess)", "solve(x, )", 1, "std", "Numerical root solver",
        listOf(
            "x: ident — Unknown variable to solve for",
            "eq: ast — Equation lhs == rhs or residual expression f(x)",
            "guess: real — (Optional) Initial root estimate (defaults to 1.0)"
        ),
        "real — Root x satisfying the equation via secant/Newton iteration"
    ),
    MathCodeEditText.SuggestionItem(
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
    MathCodeEditText.SuggestionItem(
        "sin", "sin(x)", "sin()", 1, "std", "Sine (rad)",
        listOf("x: real — Angle in radians"),
        "real — Trigonometric sine sin(x) in [-1, 1]"
    ),
    MathCodeEditText.SuggestionItem(
        "cos", "cos(x)", "cos()", 1, "std", "Cosine (rad)",
        listOf("x: real — Angle in radians"),
        "real — Trigonometric cosine cos(x) in [-1, 1]"
    ),
    MathCodeEditText.SuggestionItem(
        "tan", "tan(x)", "tan()", 1, "std", "Tangent (rad)",
        listOf("x: real — Angle in radians"),
        "real — Trigonometric tangent tan(x)"
    ),
    MathCodeEditText.SuggestionItem(
        "asin", "asin(x)", "asin()", 1, "std", "Inverse sine",
        listOf("x: real — Ratio in [-1, 1]"),
        "real — Arcsine angle in radians in [-π/2, π/2]"
    ),
    MathCodeEditText.SuggestionItem(
        "acos", "acos(x)", "acos()", 1, "std", "Inverse cosine",
        listOf("x: real — Ratio in [-1, 1]"),
        "real — Arccosine angle in radians in [0, π]"
    ),
    MathCodeEditText.SuggestionItem(
        "atan", "atan(x)", "atan()", 1, "std", "Inverse tangent",
        listOf("x: real — Slope or ratio (-∞, ∞)"),
        "real — Arctangent angle in radians in (-π/2, π/2)"
    ),
    MathCodeEditText.SuggestionItem(
        "deg2rad", "deg2rad(deg)", "deg2rad()", 1, "std", "Degrees to radians",
        listOf("deg: real — Angle measured in degrees"),
        "real — Equivalent angle in radians (deg * π / 180)"
    ),
    MathCodeEditText.SuggestionItem(
        "rad2deg", "rad2deg(rad)", "rad2deg()", 1, "std", "Radians to degrees",
        listOf("rad: real — Angle measured in radians"),
        "real — Equivalent angle in degrees (rad * 180 / π)"
    ),
    MathCodeEditText.SuggestionItem(
        "sqrt", "sqrt(x)", "sqrt()", 1, "std", "Square root √x",
        listOf("x: real — Non-negative real value (halves SI exponents)"),
        "real — Principal square root √x"
    ),
    MathCodeEditText.SuggestionItem(
        "cbrt", "cbrt(x)", "cbrt()", 1, "std", "Cube root ∛x",
        listOf("x: real — Real scalar value"),
        "real — Real cube root ∛x"
    ),
    MathCodeEditText.SuggestionItem(
        "abs", "abs(x)", "abs()", 1, "std", "Absolute value |x|",
        listOf("x: real — Signed scalar value (preserves SI units)"),
        "real — Non-negative magnitude |x|"
    ),
    MathCodeEditText.SuggestionItem(
        "exp", "exp(x)", "exp()", 1, "std", "Natural exponential e^x",
        listOf("x: real — Dimensionless exponent"),
        "real — Euler's constant raised to x (ℯ^x)"
    ),
    MathCodeEditText.SuggestionItem(
        "ln", "ln(x)", "ln()", 1, "std", "Natural logarithm",
        listOf("x: real — Positive dimensionless real number (x > 0)"),
        "real — Natural logarithm base ℯ"
    ),
    MathCodeEditText.SuggestionItem(
        "log10", "log10(x)", "log10()", 1, "std", "Base-10 logarithm",
        listOf("x: real — Positive dimensionless real number (x > 0)"),
        "real — Common logarithm base 10"
    ),
    MathCodeEditText.SuggestionItem(
        "log2", "log2(x)", "log2()", 1, "std", "Base-2 logarithm",
        listOf("x: real — Positive dimensionless real number (x > 0)"),
        "real — Binary logarithm base 2"
    ),
    MathCodeEditText.SuggestionItem(
        "gcd", "gcd(a, b)", "gcd(, )", 3, "std", "Greatest common divisor",
        listOf(
            "a: int — First integer value",
            "b: int — Second integer value"
        ),
        "int — Largest positive integer dividing both a and b"
    ),
    MathCodeEditText.SuggestionItem(
        "lcm", "lcm(a, b)", "lcm(, )", 3, "std", "Least common multiple",
        listOf(
            "a: int — First integer value",
            "b: int — Second integer value"
        ),
        "int — Smallest positive integer divisible by both a and b"
    ),
    MathCodeEditText.SuggestionItem(
        "ncr", "ncr(n, r)", "ncr(, )", 3, "std", "Combinations nCr",
        listOf(
            "n: int — Total number of items in the set",
            "r: int — Number of items chosen without order (0 <= r <= n)"
        ),
        "int — Binomial coefficient n! / (r! * (n - r)!)"
    ),
    MathCodeEditText.SuggestionItem(
        "npr", "npr(n, r)", "npr(, )", 3, "std", "Permutations nPr",
        listOf(
            "n: int — Total number of items in the set",
            "r: int — Number of ordered positions selected (0 <= r <= n)"
        ),
        "int — Number of ordered permutations n! / (n - r)!"
    ),
    MathCodeEditText.SuggestionItem(
        "sum", "sum(vec)", "sum()", 1, "std", "Sum of vector elements",
        listOf("vec: vec — Input numeric vector"),
        "real — Total sum of all elements in vec"
    ),
    MathCodeEditText.SuggestionItem(
        "mean", "mean(vec)", "mean()", 1, "std", "Arithmetic mean of vector",
        listOf("vec: vec — Non-empty numeric vector"),
        "real — Arithmetic average (sum(vec) / len(vec))"
    ),
    MathCodeEditText.SuggestionItem(
        "stddev", "stddev(vec)", "stddev()", 1, "std", "Standard deviation",
        listOf("vec: vec — Numeric sample vector"),
        "real — Sample standard deviation of the elements"
    ),
    MathCodeEditText.SuggestionItem(
        "median", "median(vec)", "median()", 1, "std", "Median of vector",
        listOf("vec: vec — Non-empty numeric vector"),
        "real — Middle value (50th percentile) of sorted elements"
    ),
    MathCodeEditText.SuggestionItem(
        "linspace", "linspace(start, stop, n)", "linspace(0, 1, 50)", 0, "std", "Evenly spaced vector",
        listOf(
            "start: real — First value of the interval",
            "stop: real — Last value of the interval (inclusive)",
            "n: int — Number of evenly spaced samples to generate"
        ),
        "vec — Vector of n linearly spaced points from start to stop"
    ),
    MathCodeEditText.SuggestionItem(
        "linsolve", "linsolve(A, b)", "linsolve(, )", 3, "std", "Solve linear system A*x = b",
        listOf(
            "A: mat — Square n×n coefficient matrix",
            "b: vec — Right-hand side n-element vector"
        ),
        "vec — Solution vector x satisfying A * x = b via LU pivoting"
    ),
    MathCodeEditText.SuggestionItem(
        "det", "det(M)", "det()", 1, "std", "Matrix determinant",
        listOf("M: mat — Square n×n matrix"),
        "real — Scalar determinant det(M)"
    ),
    MathCodeEditText.SuggestionItem(
        "inv", "inv(M)", "inv()", 1, "std", "Matrix inverse",
        listOf("M: mat — Invertible square n×n matrix"),
        "mat — Inverse matrix M^-1 such that M * M^-1 = I"
    ),
    MathCodeEditText.SuggestionItem(
        "transpose", "transpose(M)", "transpose()", 1, "std", "Matrix transpose",
        listOf("M: mat — Input m×n matrix"),
        "mat — Transposed n×m matrix M^T"
    ),
    MathCodeEditText.SuggestionItem(
        "eigvals", "eigvals(M)", "eigvals()", 1, "std", "Symmetric eigenvalues",
        listOf("M: mat — Symmetric square matrix"),
        "vec — Vector of real eigenvalues of M"
    ),
    MathCodeEditText.SuggestionItem(
        "svd_s", "svd_s(M)", "svd_s()", 1, "std", "Singular values",
        listOf("M: mat — Input m×n matrix"),
        "vec — Singular values sorted in descending order"
    ),
    MathCodeEditText.SuggestionItem(
        "pinv", "pinv(M)", "pinv()", 1, "std", "Moore-Penrose pseudoinverse",
        listOf("M: mat — Rectangular m×n matrix"),
        "mat — Moore-Penrose pseudoinverse matrix M^+"
    ),
    MathCodeEditText.SuggestionItem(
        "lstsq", "lstsq(A, b)", "lstsq(, )", 3, "std", "Linear least squares",
        listOf(
            "A: mat — Design matrix (m×n)",
            "b: vec — Observation target vector (m elements)"
        ),
        "vec — Least-squares coefficient vector minimizing ||A*x - b||_2"
    ),
    MathCodeEditText.SuggestionItem(
        "convert_fx", "convert_fx(amt, \"USD\", \"INR\")", "convert_fx(100, \"USD\", \"INR\")", 0, "std", "Currency patch conversion",
        listOf(
            "amt: real — Monetary amount in the source currency",
            "from: str — Source 3-letter ISO currency code (e.g. \"USD\")",
            "to: str — Target 3-letter ISO currency code (e.g. \"INR\")"
        ),
        "real — Converted amount in the target currency using global patch rates"
    ),
    MathCodeEditText.SuggestionItem(
        "fx_rate", "fx_rate(\"USD\", \"INR\")", "fx_rate(\"USD\", \"INR\")", 0, "std", "Live currency FX rate",
        listOf(
            "from: str — Base 3-letter ISO currency code (e.g. \"USD\")",
            "to: str — Quote 3-letter ISO currency code (e.g. \"INR\")"
        ),
        "real — Exchange rate multiplier (1 unit of 'from' in 'to')"
    )
)

/**
 * Live `SymTable`-driven autocomplete suggestions exported directly from the C11 engine
 * via `MathEngineBridge.nativeExportCatalog(handle)`. Automatically reflects all active
 * stdlib functions, operators, units, types/structs, constants, modules, OTA `.math` patches,
 * and linked project symbols.
 */
@Volatile
internal var MATH_EDITOR_LIVE_ENGINE_SUGGESTIONS: List<MathCodeEditText.SuggestionItem> = emptyList()

internal val CORE_STDLIB_MODULES = setOf(
    "default", "bootstrap", "boot_types", "boot_control", "units",
    "arith", "trig", "algebra", "calculus", "complex",
    "vec", "mat", "string", "physics", "chemistry", "biology", "geom", "conversions"
)

/**
 * Rich catalog item representing a callable function, operator (including operator overloads),
 * or module constant from `stdlib + patches + project1` displayed in the Basic Calculator's
 * Functions Tab.
 */
data class CalculatorCatalogItem(
    val name: String,
    val signature: String,
    val insertText: String,
    val originGroup: String,       // "project1", "patch", or "stdlib"
    val moduleName: String,        // e.g. "custom_ops", "physics_sim", "currency", "trig"
    val kind: String,              // "fn", "op", or "const"
    val shortDescription: String,
    val paramBullets: List<String>,
    val returnBullet: String,
    val sourceFile: String? = null,
    val fullDescription: String = shortDescription
) {
    /** Formatted text for the frosted hold-popup info window. */
    fun toPopupInfoText(): String {
        val originLabel = when (originGroup) {
            "project1" -> "project1 • [$moduleName]" + (sourceFile?.let { " ($it)" } ?: "")
            "patch" -> "patch • [$moduleName]" + (sourceFile?.let { " ($it)" } ?: "")
            else -> "stdlib • [$moduleName]" + (sourceFile?.let { " ($it)" } ?: "")
        }
        val kindLabel = when (kind) {
            "op" -> "Operator / Overload"
            "const" -> "Constant / Variable"
            else -> "Function"
        }
        val paramsBlock = if (paramBullets.isEmpty()) {
            "  • None — Takes no parameters"
        } else {
            paramBullets.mapIndexed { idx, line -> "  ${idx + 1}. $line" }.joinToString("\n")
        }
        val bodyDesc = fullDescription.ifBlank { shortDescription }
        return buildString {
            append(signature).append("\n")
            append("Source: ").append(originLabel).append(" • ").append(kindLabel).append("\n")
            if (bodyDesc.isNotBlank()) {
                append(bodyDesc).append("\n")
            }
            append("\nParameters / Operands:\n")
            append(paramsBlock).append("\n\n")
            append("Returns:\n  • ").append(returnBullet.ifBlank { "real | any" })
        }
    }
}

internal fun mergeLiveEngineCatalog(rawCatalog: String) {
    if (rawCatalog.isBlank()) return
    // Ensure bundled stdlib .math doc comments are registered in CalculatorFunctionDocs
    MathEngineManager.appContext?.let { runCatching { ensureBundledStdlibDocsLoaded(it) } }
    val staticKeys = MATH_EDITOR_STATIC_SUGGESTIONS.mapTo(HashSet()) { "${it.category}:${it.matchKey}" }
    val seen = HashSet<String>()
    val items = ArrayList<MathCodeEditText.SuggestionItem>(256)

    for (rawLine in rawCatalog.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val parts = line.split('|')
        if (parts.size < 6) continue
        val kind = parts[0].trim()
        val name = parts[1].trim()
        val sig = parts[2].trim()
        val retType = parts[3].trim().ifEmpty { "real" }
        val retUnit = parts[4].trim().ifEmpty { "none" }
        val modName = parts[5].trim().ifEmpty { "default" }

        if (name.isEmpty() || name.startsWith("__mep_base_")) continue
        val dedupKey = "$kind:$modName:$name:$sig"
        if (!seen.add(dedupKey)) continue

        val isCoreStdlib = modName in CORE_STDLIB_MODULES
        val retBadge = if (retUnit != "none" && retUnit.isNotEmpty()) "$retType [$retUnit]" else retType

        when (kind) {
            "mod" -> {
                val matchKey = "<[$name"
                if ("mod:$matchKey" in staticKeys) continue
                items.add(
                    MathCodeEditText.SuggestionItem(
                        matchKey = matchKey,
                        displayLabel = "<[$name]",
                        insertText = "<[$name]",
                        cursorBackOffset = 0,
                        category = "mod",
                        detail = "Live engine module [$name]",
                        paramBullets = listOf("$name: module — Loaded in native SymTable"),
                        returnBullet = "void — Imports public functions, operators, units, and constants from [$name]"
                    )
                )
            }
            "fn" -> {
                if (!name[0].isLetter() && name[0] != '_') continue
                if (isCoreStdlib && ("fn:$name" in staticKeys || "std:$name" in staticKeys)) continue
                val doc = CalculatorFunctionDocs.forName(name)
                val hasArgs = !sig.contains("$name()")
                val paramsInside = sig.substringAfter("(", "").substringBeforeLast(")", "").trim()
                val displaySig = if (doc != null && doc.signature.isNotBlank() && sig.contains("p1:")) {
                    "${doc.signature} -> $retType"
                } else {
                    sig
                }
                val paramBullets = when {
                    doc != null && doc.params.isNotEmpty() -> doc.params
                    paramsInside.isEmpty() -> listOf("None — Zero-argument function in [$modName]")
                    else -> paramsInside.split(',').mapIndexed { idx, p ->
                        "${p.trim()} — Parameter #${idx + 1} in [$modName]"
                    }
                }
                val detailText = if (doc != null && doc.summary.isNotBlank()) {
                    "[$modName] ${doc.summary}"
                } else {
                    "[$modName] function"
                }
                val retText = if (doc != null && doc.returns.isNotBlank()) {
                    doc.returns
                } else {
                    "$retBadge — Result from [$modName]::$name"
                }
                items.add(
                    MathCodeEditText.SuggestionItem(
                        matchKey = name,
                        displayLabel = displaySig,
                        insertText = "$name()",
                        cursorBackOffset = if (hasArgs) 1 else 0,
                        category = if (isCoreStdlib) "std" else "fn",
                        detail = detailText,
                        paramBullets = paramBullets,
                        returnBullet = retText
                    )
                )
            }
            "op" -> {
                if (isCoreStdlib && "op:$name" in staticKeys) continue
                val doc = CalculatorFunctionDocs.forName(name)
                items.add(
                    MathCodeEditText.SuggestionItem(
                        matchKey = name,
                        displayLabel = if (doc != null && doc.signature.isNotBlank()) doc.signature else sig,
                        insertText = if (name[0].isLetterOrDigit()) " $name " else name,
                        cursorBackOffset = 0,
                        category = "op",
                        detail = if (doc != null && doc.summary.isNotBlank()) "[$modName] ${doc.summary}" else "[$modName] operator",
                        paramBullets = if (doc != null && doc.params.isNotEmpty()) doc.params else listOf("Operands matching signature: $sig"),
                        returnBullet = if (doc != null && doc.returns.isNotBlank()) doc.returns else "$retBadge — Operator result from [$modName]"
                    )
                )
            }
            "unit" -> {
                if (!name[0].isLetter() && name[0] != '_') continue
                if ("unit:$name" in staticKeys) continue
                items.add(
                    MathCodeEditText.SuggestionItem(
                        matchKey = name,
                        displayLabel = sig,
                        insertText = name,
                        cursorBackOffset = 0,
                        category = "unit",
                        detail = "[$modName] unit [$retUnit]",
                        paramBullets = listOf("x: real — Numeric magnitude before unit $name"),
                        returnBullet = "$retBadge — Quantity tagged with 7D SI unit [$retUnit]"
                    )
                )
            }
            "type" -> {
                if ("type:$name" in staticKeys || "type:\$$name" in staticKeys) continue
                val isStruct = sig.startsWith("struct ")
                items.add(
                    MathCodeEditText.SuggestionItem(
                        matchKey = name,
                        displayLabel = sig,
                        insertText = if (isStruct) "$name()" else name,
                        cursorBackOffset = if (isStruct) 1 else 0,
                        category = "type",
                        detail = "[$modName] ${if (isStruct) "struct" else "type"}",
                        paramBullets = listOf(sig),
                        returnBullet = "$name — Registered entity type in [$modName]"
                    )
                )
            }
            "const" -> {
                if (!name[0].isLetter() && name[0] != '_') continue
                if (isCoreStdlib && ("const:$name" in staticKeys || "var:$name" in staticKeys)) continue
                items.add(
                    MathCodeEditText.SuggestionItem(
                        matchKey = name,
                        displayLabel = sig,
                        insertText = name,
                        cursorBackOffset = 0,
                        category = "const",
                        detail = "[$modName] constant",
                        paramBullets = listOf("None — Module constant or public variable in [$modName]"),
                        returnBullet = "$retBadge — Bound constant/variable in [$modName]"
                    )
                )
            }
        }
    }
    MATH_EDITOR_LIVE_ENGINE_SUGGESTIONS = items
}

private val catalogFnDeclRegex = Regex(
    "^\\s*(?:(pub|priv)\\s+)*(?:\\$?fn\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)(?:::([a-zA-Z_][a-zA-Z0-9_]*))?\\s*\\(([^()]*)\\)\\s*(?:->\\s*([^=:]+)|:\\s*([a-zA-Z0-9_,\\s<>]+))?\\s*(?::=|=)(?![=>])\\s*(.+)$",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

private val catalogInfixOpDeclRegex = Regex(
    "^\\s*(?:(pub|priv)\\s+)*\\(\\s*([^()]+)\\s*\\)\\s*(\"[^\"]+\"|[a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*([^()]+)\\s*\\)\\s*(:[<>]?\\d+)\\s*(?:->\\s*([^=]+))?\\s*=\\s*(.+)$",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

private val catalogPrefixOpDeclRegex = Regex(
    "^\\s*(?:(pub|priv)\\s+)*(\"[^\"]+\"|[a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(\\s*([^()]+)\\s*\\)\\s*(:\\d+)\\s*(?:->\\s*([^=]+))?\\s*=\\s*(.+)$",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

private val catalogPostfixOpDeclRegex = Regex(
    "^\\s*(?:(pub|priv)\\s+)*\\(\\s*([^()]+)\\s*\\)\\s*(\"[^\"]+\"|[a-zA-Z_][a-zA-Z0-9_]*)\\s*(:\\d+)\\s*(?:->\\s*([^=]+))?\\s*=\\s*(.+)$",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

private val nonNumericTypeTokens = setOf(
    "str", "vec", "mat", "map", "dict", "fn", "ast",
    "complex", "vec2", "vec3", "mat2", "circle", "line2", "plane3", "sphere", "void"
)

private val unitAnnotationTokens = setOf(
    "none", "T", "length", "time", "mass", "speed", "force", "energy", "power", "pressure",
    "usd", "eur", "gbp", "inr", "jpy", "cny", "aud", "cad", "chf", "sgd", "hkd", "krw", "nzd"
)

private val untypedNonNumericalParamNames = setOf(
    "path", "file", "csv_str", "json_str", "from", "to", "sep", "pat", "msg", "fmt",
    "expr_str", "sym", "var_x", "var_t", "var_y", "var_z", "var_k", "expr", "eq",
    "rhs_vec", "dx", "dy", "dz", "tag", "cond", "body", "then_val", "else_val", "self"
)

/**
 * Returns true if a parameter specification (`a: real`, `x: int`, `m: real, mass`, or untyped `x`)
 * accepts a numerical scalar value usable from the Basic Calculator.
 */
private fun isNumericalParamSpec(paramSpec: String): Boolean {
    val trimmed = paramSpec.trim()
    if (trimmed.isEmpty() || trimmed in unitAnnotationTokens) return true
    val colonIdx = trimmed.indexOf(':')
    val pName = (if (colonIdx >= 0) trimmed.substring(0, colonIdx) else trimmed)
        .substringBefore('=')
        .trim()
        .lowercase()
    if (pName in untypedNonNumericalParamNames) {
        return false
    }
    if (colonIdx < 0) return true
    val typePart = trimmed.substring(colonIdx + 1)
        .substringBefore('=')
        .substringBefore(',')
        .trim()
        .lowercase()
    if (typePart.isEmpty()) return true
    if (typePart in nonNumericTypeTokens || typePart.startsWith("vec<") || typePart.startsWith("map<")) {
        return false
    }
    return typePart == "real" || typePart == "int" || typePart == "any" || typePart == "t"
}

private fun isNumericalReturnSpec(retSpec: String, rhsRaw: String, paramCount: Int = 1): Boolean {
    val cleanRet = retSpec.substringBefore(',').trim().lowercase()
    if (cleanRet in nonNumericTypeTokens || cleanRet.startsWith("vec<") || cleanRet.startsWith("map<")) {
        return false
    }
    if (rhsRaw.contains("println(") || rhsRaw.contains("print(") ||
        rhsRaw.contains("read_file(") || rhsRaw.contains("read_json(") ||
        rhsRaw.contains("csv_to_maps(") || rhsRaw.contains("@str_fmt") ||
        rhsRaw.contains("@file_") || rhsRaw.contains("@json_") ||
        rhsRaw.contains("@sym_") || rhsRaw.contains("@type_name") ||
        (rhsRaw.contains("@unit_name") && paramCount < 2)
    ) {
        return false
    }
    return true
}

/**
 * Formats a `.math` parameter list (`a, b` or `x, digits = 0` or `d: real, length`) into a clean
 * canonical signature `fnName(a: real, b: real)`.
 */
private fun formatCanonicalSignature(fnName: String, rawParams: String): String {
    if (rawParams.isBlank()) return "$fnName()"
    val formatted = ArrayList<String>(4)
    for (part in rawParams.split(',')) {
        val p = part.trim()
        if (p.isEmpty() || p in unitAnnotationTokens) continue
        if (p.contains(':')) {
            val pName = p.substringBefore(':').trim()
            val pRest = p.substringAfter(':').trim()
            if (pRest.contains('=')) {
                val tPart = pRest.substringBefore('=').trim().ifEmpty { "real" }
                val defPart = pRest.substringAfter('=').trim()
                formatted.add("$pName: $tPart = $defPart")
            } else {
                formatted.add("$pName: $pRest")
            }
        } else if (p.contains('=')) {
            val pName = p.substringBefore('=').trim()
            val defPart = p.substringAfter('=').trim()
            formatted.add("$pName: real = $defPart")
        } else {
            formatted.add("$p: real")
        }
    }
    return "$fnName(${formatted.joinToString(", ")})"
}

/**
 * Names that must never surface in the Basic Calculator's Functions tab even though the engine
 * catalog reports them as numerical `fn` entries: language keywords / overload shims registered
 * by the `boot_control` module (`if`, `then`, `switch`, `return`, ...) and engine internals
 * (`raw_val`, `gc_compact`, ...).
 */
private val calculatorExcludedFunctionNames = setOf(
    "call", "if", "or", "and", "not", "in", "is", "mod", "then", "else", "default",
    "switch", "case", "for", "while", "jump", "run", "break", "continue", "return",
    "raw_val", "with_unit", "gc_compact", "pool_used", "error", "assert"
)

/**
 * Derives numerical `fn` entries for the Basic Calculator's Functions tab from the live
 * engine catalog dump (`eng_export_catalog` → `kind|name|sig|retType|retUnit|modName`) for
 * any active numerical function not already extracted from `.math` source files.
 */
internal fun extractLiveCatalogNumericalItems(rawCatalog: String): List<CalculatorCatalogItem> {
    if (rawCatalog.isBlank()) return emptyList()
    val items = ArrayList<CalculatorCatalogItem>(96)
    val takenSigs = HashSet<String>()
    for (rawLine in rawCatalog.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val parts = line.split('|')
        if (parts.size < 6) continue
        val kind = parts[0].trim()
        if (kind != "fn") continue
        val name = parts[1].trim()
        val sig = parts[2].trim()
        val retType = parts[3].trim().ifEmpty { "real" }
        val retUnit = parts[4].trim().ifEmpty { "none" }
        val modName = parts[5].trim().ifEmpty { "default" }
        if (name.isEmpty() || sig.isEmpty()) continue
        if (!name[0].isLetter() && name[0] != '_') continue
        if (name.startsWith("__mep_base_")) continue
        if (name in calculatorExcludedFunctionNames) continue
        if (!sig.startsWith("$name(")) continue
        val openIdx = sig.indexOf('(')
        val closeIdx = sig.lastIndexOf(')')
        val arrowIdx = sig.indexOf("->")
        if (openIdx < 0 || closeIdx < openIdx || arrowIdx <= closeIdx) continue
        val paramsInside = sig.substring(openIdx + 1, closeIdx).trim()
        val retSpec = sig.substring(arrowIdx + 2).trim().substringBefore(',').trim()
        val paramSpecs = if (paramsInside.isEmpty()) emptyList() else paramsInside.split(',')
        if (!isNumericalReturnSpec(retSpec, "", paramSpecs.size)) continue
        if (!paramSpecs.all { isNumericalParamSpec(it) }) continue
        if (!takenSigs.add("$kind:$name|$paramsInside")) continue
        val hasArgs = paramsInside.isNotEmpty()
        val retBadge = if (retUnit != "none" && retUnit.isNotEmpty()) "$retType [$retUnit]" else retType
        val doc = CalculatorFunctionDocs.forName(name)
        val richSig = if (doc != null && doc.signature.isNotBlank()) doc.signature else "$name($paramsInside)"
        val genericDesc = "Numerical function in [$modName] — accepts numeric parameters, returns $retBadge"
        val fullDesc = doc?.definition?.takeIf { it.isNotBlank() } ?: genericDesc
        val shortDesc = doc?.summary?.takeIf { it.isNotBlank() } ?: fullDesc
        val richParams = when {
            doc != null && doc.params.isNotEmpty() -> doc.params
            hasArgs -> paramSpecs.mapIndexed { idx, p -> "${p.trim()} — Numeric parameter #${idx + 1} in [$modName]" }
            else -> listOf("None — Zero-argument numerical function in [$modName]")
        }
        val richRet = doc?.returns?.takeIf { it.isNotBlank() }
            ?: "$retBadge — Numerical result returned by [$modName]::$name"
        items.add(
            CalculatorCatalogItem(
                name = name,
                signature = richSig,
                insertText = if (hasArgs) "$name(" else "$name()",
                originGroup = "stdlib",
                moduleName = modName,
                kind = "fn",
                shortDescription = shortDesc,
                paramBullets = richParams,
                returnBullet = richRet,
                fullDescription = fullDesc
            )
        )
    }
    return items
}

/**
 * Dynamically extracts all numerical functions and numerical operators from any `.math` source file
 * (`project1`, `patch`, or `stdlib`), parsing the `# @desc`, `# @param`, and `# @return` comment
 * block directly above each declaration and falling back to clean generic descriptions when absent.
 */
private fun extractStatementsWithStartLines(rawSource: String): List<Pair<Int, String>> {
    val lines = rawSource.replace("\r\n", "\n").replace("\r", "\n").lines()
    val result = ArrayList<Pair<Int, String>>(lines.size / 2 + 1)
    var i = 0
    while (i < lines.size) {
        val rawLine = MathEngineManager.stripCommentOutsideQuotes(lines[i]).trim()
        if (rawLine.isEmpty() || rawLine.startsWith("$") || rawLine.startsWith("struct ") || rawLine.startsWith("@dim") || rawLine.startsWith("@entry") || (rawLine.startsWith("\"") && rawLine.contains("=> @"))) {
            i++
            continue
        }
        val startLineNum = i + 1
        val sb = StringBuilder(rawLine)
        var braceDepth = rawLine.count { it == '{' } - rawLine.count { it == '}' }
        var parenDepth = rawLine.count { it == '(' } - rawLine.count { it == ')' }
        var curTrim = rawLine
        while ((curTrim.endsWith("=") || braceDepth > 0 || parenDepth > 0) && i + 1 < lines.size) {
            i++
            val nxt = MathEngineManager.stripCommentOutsideQuotes(lines[i]).trim()
            if (nxt.isEmpty()) continue
            sb.append(' ').append(nxt)
            braceDepth += nxt.count { it == '{' } - nxt.count { it == '}' }
            parenDepth += nxt.count { it == '(' } - nxt.count { it == ')' }
            curTrim = nxt
        }
        var built = sb.toString().trim()
        while (built.endsWith(";")) {
            built = built.dropLast(1).trimEnd()
        }
        if (built.isNotEmpty()) {
            result.add(startLineNum to built)
        }
        i++
    }
    return result
}

internal fun extractCatalogItemsFromMathSource(
    rawSource: String,
    originGroup: String,
    fileName: String? = null
): List<CalculatorCatalogItem> {
    val out = ArrayList<CalculatorCatalogItem>()
    var curModName = MathEngineManager.deriveModuleName(fileName, rawSource)
    val docBlocksByLine = CalculatorFunctionDocs.parseDocBlocksByLine(rawSource)
    val stmtsWithLines = extractStatementsWithStartLines(rawSource)

    for ((lineNum, rawStmt) in stmtsWithLines) {
        val trimmed = rawStmt.replace(Regex("\\s+"), " ").trim()
        if (trimmed.isEmpty()) continue

        val hdrMatch = MathEngineManager.modHeaderSingleLineRegex.matchEntire(trimmed)
        if (hdrMatch != null) {
            curModName = hdrMatch.groupValues[1]
            continue
        }

        val docBlock = docBlocksByLine[lineNum]

        // 1. Infix operator declaration `(a: real) "op" (b: real) :<prec = rhs`
        val infixMatch = catalogInfixOpDeclRegex.matchEntire(trimmed)
        if (infixMatch != null) {
            val vis = infixMatch.groupValues[1]
            if (vis == "priv") continue
            val p1Raw = infixMatch.groupValues[2].trim()
            val opRaw = infixMatch.groupValues[3].trim().removeSurrounding("\"")
            val p2Raw = infixMatch.groupValues[4].trim()
            val precRaw = infixMatch.groupValues[5].trim()
            val retRaw = infixMatch.groupValues[6].trim().ifEmpty { "real" }
            val rhsRaw = infixMatch.groupValues[7].trim().take(80)

            if (opRaw.startsWith("__mep_base_")) continue
            if (opRaw == "into") continue
            // Built-in stdlib arithmetic/comparison operators are on the main keypad; surface custom/patch/project1 operators
            if (originGroup == "stdlib") continue
            if (!isNumericalParamSpec(p1Raw) || !isNumericalParamSpec(p2Raw)) continue
            if (!isNumericalReturnSpec(retRaw, rhsRaw, 2)) continue

            val sig = "($p1Raw) $opRaw ($p2Raw) $precRaw"
            val isStandardOverload = opRaw in setOf("+", "-", "*", "/", "%", "^", "==", "!=", "<", "<=", ">", ">=")
            val genericDesc = if (isStandardOverload) {
                "Overloaded '$opRaw' operator in $originGroup [$curModName]: $rhsRaw"
            } else {
                "Custom numeric operator '$opRaw' in $originGroup [$curModName]: $rhsRaw"
            }
            val fullDesc = docBlock?.definition?.takeIf { it.isNotBlank() } ?: genericDesc
            val shortDesc = docBlock?.summary?.takeIf { it.isNotBlank() } ?: fullDesc
            val paramBullets = docBlock?.params?.takeIf { it.isNotEmpty() } ?: listOf(
                "$p1Raw — Left-hand numeric operand of operator '$opRaw'",
                "$p2Raw — Right-hand numeric operand of operator '$opRaw'",
                "Associativity & Precedence: $precRaw"
            )
            val retDesc = docBlock?.returns?.takeIf { it.isNotBlank() }
                ?: "$retRaw — Numeric result of ($p1Raw) $opRaw ($p2Raw) = $rhsRaw"

            CalculatorFunctionDocs.registerFromMathSource(opRaw, sig, fullDesc, paramBullets, retDesc)
            val ins = if (opRaw.firstOrNull()?.isLetterOrDigit() == true) " $opRaw " else opRaw
            out.add(
                CalculatorCatalogItem(
                    name = opRaw,
                    signature = sig,
                    insertText = ins,
                    originGroup = originGroup,
                    moduleName = curModName,
                    kind = "op",
                    shortDescription = shortDesc,
                    paramBullets = paramBullets,
                    returnBullet = retDesc,
                    sourceFile = fileName,
                    fullDescription = fullDesc
                )
            )
            continue
        }

        // 2. Prefix operator declaration `"op" (x: real) :prec = rhs`
        val prefixMatch = catalogPrefixOpDeclRegex.matchEntire(trimmed)
        if (prefixMatch != null) {
            val vis = prefixMatch.groupValues[1]
            if (vis == "priv" || originGroup == "stdlib") continue
            val opRaw = prefixMatch.groupValues[2].trim().removeSurrounding("\"")
            val p1Raw = prefixMatch.groupValues[3].trim()
            val precRaw = prefixMatch.groupValues[4].trim()
            val retRaw = prefixMatch.groupValues[5].trim().ifEmpty { "real" }
            val rhsRaw = prefixMatch.groupValues[6].trim().take(80)
            if (opRaw !in MathCodeEditText.KEYWORDS &&
                isNumericalParamSpec(p1Raw) &&
                isNumericalReturnSpec(retRaw, rhsRaw, 1)
            ) {
                val sig = "$opRaw ($p1Raw) $precRaw"
                val genericDesc = "Prefix numeric operator '$opRaw' in $originGroup [$curModName]: $rhsRaw"
                val fullDesc = docBlock?.definition?.takeIf { it.isNotBlank() } ?: genericDesc
                val shortDesc = docBlock?.summary?.takeIf { it.isNotBlank() } ?: fullDesc
                val paramBullets = docBlock?.params?.takeIf { it.isNotEmpty() } ?: listOf(
                    "$p1Raw — Numeric operand",
                    "Precedence: $precRaw"
                )
                val retDesc = docBlock?.returns?.takeIf { it.isNotBlank() }
                    ?: "$retRaw — Evaluated via $opRaw($p1Raw) = $rhsRaw"
                CalculatorFunctionDocs.registerFromMathSource(opRaw, sig, fullDesc, paramBullets, retDesc)
                out.add(
                    CalculatorCatalogItem(
                        name = opRaw,
                        signature = sig,
                        insertText = if (opRaw.firstOrNull()?.isLetterOrDigit() == true) "$opRaw " else opRaw,
                        originGroup = originGroup,
                        moduleName = curModName,
                        kind = "op",
                        shortDescription = shortDesc,
                        paramBullets = paramBullets,
                        returnBullet = retDesc,
                        sourceFile = fileName,
                        fullDescription = fullDesc
                    )
                )
                continue
            }
        }

        // 3. Postfix operator declaration `(x: real) "op" :prec = rhs`
        val postfixMatch = catalogPostfixOpDeclRegex.matchEntire(trimmed)
        if (postfixMatch != null) {
            val vis = postfixMatch.groupValues[1]
            if (vis == "priv" || originGroup == "stdlib") continue
            val p1Raw = postfixMatch.groupValues[2].trim()
            val opRaw = postfixMatch.groupValues[3].trim().removeSurrounding("\"")
            val precRaw = postfixMatch.groupValues[4].trim()
            val retRaw = postfixMatch.groupValues[5].trim().ifEmpty { "real" }
            val rhsRaw = postfixMatch.groupValues[6].trim().take(80)
            if (rhsRaw == "x" && precRaw == ":95") continue
            if (isNumericalParamSpec(p1Raw) && isNumericalReturnSpec(retRaw, rhsRaw, 1)) {
                val sig = "($p1Raw) $opRaw $precRaw"
                val genericDesc = "Postfix numeric operator '$opRaw' in $originGroup [$curModName]: $rhsRaw"
                val fullDesc = docBlock?.definition?.takeIf { it.isNotBlank() } ?: genericDesc
                val shortDesc = docBlock?.summary?.takeIf { it.isNotBlank() } ?: fullDesc
                val paramBullets = docBlock?.params?.takeIf { it.isNotEmpty() } ?: listOf(
                    "$p1Raw — Numeric operand",
                    "Precedence: $precRaw"
                )
                val retDesc = docBlock?.returns?.takeIf { it.isNotBlank() }
                    ?: "$retRaw — Evaluated via ($p1Raw) $opRaw = $rhsRaw"
                CalculatorFunctionDocs.registerFromMathSource(opRaw, sig, fullDesc, paramBullets, retDesc)
                out.add(
                    CalculatorCatalogItem(
                        name = opRaw,
                        signature = sig,
                        insertText = if (opRaw.firstOrNull()?.isLetterOrDigit() == true) " $opRaw" else opRaw,
                        originGroup = originGroup,
                        moduleName = curModName,
                        kind = "op",
                        shortDescription = shortDesc,
                        paramBullets = paramBullets,
                        returnBullet = retDesc,
                        sourceFile = fileName,
                        fullDescription = fullDesc
                    )
                )
                continue
            }
        }

        // 4. Function declaration `pub fn_name(a: real, b: real) = rhs`
        val fnMatch = catalogFnDeclRegex.matchEntire(trimmed)
        if (fnMatch != null) {
            val vis = fnMatch.groupValues[1]
            if (vis == "priv") continue
            val fnName = fnMatch.groupValues[3].ifEmpty { fnMatch.groupValues[2] }.trim()
            if (fnName in MathCodeEditText.KEYWORDS || fnName in calculatorExcludedFunctionNames || fnName == "main") continue
            val paramsRaw = fnMatch.groupValues[4].trim()
            val retRaw = fnMatch.groupValues[5].trim().ifEmpty {
                fnMatch.groupValues[6].trim().ifEmpty { "real" }
            }
            val rhsRaw = fnMatch.groupValues[7].trim().take(80)
            val canonicalSig = formatCanonicalSignature(fnName, paramsRaw)

            // Register parsed .math documentation for Studio autocomplete even if non-scalar
            if (docBlock != null) {
                CalculatorFunctionDocs.registerFromMathSource(
                    fnName,
                    canonicalSig,
                    docBlock.definition,
                    docBlock.params,
                    docBlock.returns
                )
            }

            val paramParts = paramsRaw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it !in unitAnnotationTokens }
            if (!paramParts.all { isNumericalParamSpec(it) }) continue
            if (!isNumericalReturnSpec(retRaw, rhsRaw, paramParts.size)) continue

            val effectiveDoc = docBlock ?: CalculatorFunctionDocs.forName(fnName)
            val paramBullets = effectiveDoc?.params?.takeIf { it.isNotEmpty() } ?: if (paramParts.isEmpty()) {
                listOf("None — Zero-argument numerical function in [$curModName]")
            } else {
                paramParts.mapIndexed { idx, pTrim ->
                    val pTyped = if (pTrim.contains(':')) pTrim else "$pTrim: real"
                    val pIdent = pTrim.substringBefore(':').substringBefore('=').trim()
                    "$pTyped — Numeric input parameter '$pIdent' (argument #${idx + 1} of $fnName)"
                }
            }
            val rhsPreview = if (rhsRaw.startsWith("{")) "{ ... }" else rhsRaw
            val genericDesc = "Numerical function in $originGroup [$curModName] — evaluates $canonicalSig = $rhsPreview"
            val fullDesc = effectiveDoc?.definition?.takeIf { it.isNotBlank() } ?: genericDesc
            val shortDesc = effectiveDoc?.summary?.takeIf { it.isNotBlank() } ?: fullDesc
            val retClean = retRaw.substringBefore(',').trim().ifEmpty { "real" }
            val retDesc = effectiveDoc?.returns?.takeIf { it.isNotBlank() }
                ?: "$retClean — Numeric result computed by $canonicalSig in [$curModName]"

            if (docBlock != null) {
                CalculatorFunctionDocs.registerFromMathSource(fnName, canonicalSig, fullDesc, paramBullets, retDesc)
            }

            val hasArgs = paramParts.isNotEmpty()
            out.add(
                CalculatorCatalogItem(
                    name = fnName,
                    signature = canonicalSig,
                    insertText = if (hasArgs) "$fnName(" else "$fnName()",
                    originGroup = originGroup,
                    moduleName = curModName,
                    kind = "fn",
                    shortDescription = shortDesc,
                    paramBullets = paramBullets,
                    returnBullet = retDesc,
                    sourceFile = fileName,
                    fullDescription = fullDesc
                )
            )
        }
    }
    return out
}

@Volatile
private var cachedBundledStdlibItems: List<CalculatorCatalogItem>? = null

private val stdlibModulePriorityOrder = listOf(
    "trig.math", "arith.math", "algebra.math", "geom.math", "calculus.math",
    "physics.math", "chemistry.math", "biology.math", "units.math", "conversions.math",
    "vec.math", "mat.math", "complex.math", "string.math", "bootstrap.math"
)

/**
 * Dynamically parses all bundled `.math` standard library files under the `assets/Library` folder,
 * extracting every numerical function and its `# @desc` / `# @param` / `# @return` documentation.
 */
internal fun ensureBundledStdlibDocsLoaded(ctx: Context): List<CalculatorCatalogItem> {
    cachedBundledStdlibItems?.let { return it }
    synchronized(CalculatorFunctionDocs) {
        cachedBundledStdlibItems?.let { return it }
        val assetFiles = runCatching {
            ctx.assets.list("Library")?.filter { it.endsWith(".math", ignoreCase = true) }.orEmpty()
        }.getOrDefault(emptyList())
        val orderedFiles = assetFiles.sortedWith(
            compareBy<String> {
                val idx = stdlibModulePriorityOrder.indexOf(it)
                if (idx >= 0) idx else 999
            }.thenBy { it.lowercase() }
        )
        val items = ArrayList<CalculatorCatalogItem>(512)
        val seen = HashSet<String>()
        for (fileName in orderedFiles) {
            val rawSrc = runCatching {
                ctx.assets.open("Library/$fileName").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrDefault("")
            if (rawSrc.isBlank()) continue
            for (item in extractCatalogItemsFromMathSource(rawSrc, originGroup = "stdlib", fileName = fileName)) {
                if (seen.add("${item.kind}:${item.name}")) {
                    items.add(item)
                }
            }
        }
        cachedBundledStdlibItems = items
        return items
    }
}

@Volatile
private var cachedCatalogSignature: String = ""

@Volatile
private var cachedCatalogItems: List<CalculatorCatalogItem> = emptyList()

/**
 * Dynamically collects all numerical functions and operators from all sources:
 * 1. `project1` (`.math` files in `filesDir/math_projects/project1/` + live Studio editor buffers)
 * 2. `patches` (`.math` files in `filesDir/math_patches/`, including newly downloaded OTA patches)
 * 3. `stdlib` (`.math` files in the `assets/Library` folder + live C engine `SymTable` fallback)
 *
 * No functions or descriptions are hardcoded in the app: every item and its documentation is
 * dynamically extracted from the `.math` files (falling back to generic descriptions when
 * `# @desc` / `# @param` / `# @return` tags are omitted).
 */
@Synchronized
fun MathEngineManager.collectCalculatorFunctionCatalog(ctx: Context): List<CalculatorCatalogItem> {
    ensureProject1LinkedForCalculatorSync(ctx)

    val proj1Sources = collectProjectModuleSourcesOnDisk(ctx, DEFAULT_STUDIO_PROJECT_NAME)
    val patchesDir = MathPatchDownloader.getPatchesDir(ctx)
    val patchFiles = patchesDir.listFiles { f ->
        f.isFile && f.name.endsWith(".math", ignoreCase = true) && !f.name.startsWith(".") &&
            !f.name.startsWith("currency_pairs_")
    }?.sortedBy { it.name.lowercase() }.orEmpty()

    val stdlibItems = ensureBundledStdlibDocsLoaded(ctx)

    val liveCatalogDump = if (engineHandle != 0L) {
        runCatching { MathEngineBridge.nativeExportCatalog(engineHandle) }.getOrNull()
    } else {
        null
    }

    val sig = buildString {
        append(linkedProject1Signature).append('|')
        for (pf in patchFiles) {
            append(pf.name).append(':').append(pf.lastModified()).append(':').append(pf.length()).append(';')
        }
        append('|').append(stdlibItems.size)
        if (!liveCatalogDump.isNullOrBlank()) {
            append('|').append(liveCatalogDump.hashCode())
        }
    }
    if (sig == cachedCatalogSignature && cachedCatalogItems.isNotEmpty()) {
        return cachedCatalogItems
    }

    val result = ArrayList<CalculatorCatalogItem>(540)
    val seenKeys = HashSet<String>()
    val proj1OverloadedNames = HashSet<String>()
    val liveModuleGroups = HashMap<String, String>()

    // 1. Numerical functions & operator overloads from `project1`
    for ((fileName, rawSrc) in proj1Sources) {
        for (item in extractCatalogItemsFromMathSource(rawSrc, originGroup = "project1", fileName = fileName)) {
            if (!liveModuleGroups.containsKey(item.moduleName)) {
                liveModuleGroups[item.moduleName] = "project1"
            }
            val key = "project1:${item.moduleName}:${item.kind}:${item.name}"
            if (seenKeys.add(key)) {
                result.add(item)
                proj1OverloadedNames.add("${item.kind}:${item.name}")
            }
        }
    }

    // 2. Numerical functions & operators from `.math` patches
    for (patchFile in patchFiles) {
        val rawSrc = runCatching { patchFile.readText() }.getOrDefault("")
        if (rawSrc.isBlank()) continue
        for (item in extractCatalogItemsFromMathSource(rawSrc, originGroup = "patch", fileName = patchFile.name)) {
            if (!liveModuleGroups.containsKey(item.moduleName)) {
                liveModuleGroups[item.moduleName] = "patch"
            }
            val key = "patch:${item.moduleName}:${item.kind}:${item.name}"
            if (seenKeys.add(key)) {
                result.add(item)
            }
        }
    }

    // 3. Dynamically parsed numerical `stdlib` functions from `assets/Library/*.math`
    for (stdItem in stdlibItems) {
        val key = "stdlib:${stdItem.moduleName}:${stdItem.kind}:${stdItem.name}"
        if (seenKeys.add(key)) {
            val isOverloaded = "${stdItem.kind}:${stdItem.name}" in proj1OverloadedNames
            result.add(
                if (isOverloaded) {
                    stdItem.copy(shortDescription = "${stdItem.shortDescription} (Overloaded in project1)")
                } else {
                    stdItem
                }
            )
        }
    }

    // 4. Live-engine `SymTable` fallback for any additional numerical function registered in C
    if (!liveCatalogDump.isNullOrBlank()) {
        val takenNames = result.mapTo(HashSet()) { "${it.kind}:${it.name}" }
        for (liveItem in extractLiveCatalogNumericalItems(liveCatalogDump)) {
            val grp = liveModuleGroups[liveItem.moduleName]
            val enriched = if (grp != null && liveItem.moduleName !in CORE_STDLIB_MODULES) {
                liveItem.copy(originGroup = grp)
            } else {
                liveItem
            }
            if (takenNames.add("${enriched.kind}:${enriched.name}")) {
                result.add(enriched)
            }
        }
    }

    cachedCatalogSignature = sig
    cachedCatalogItems = result
    return result
}
