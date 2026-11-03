# CMath Engine (Math-Engine-MEP) Reference Guide

- **Unified Executable Module Architecture**:
  Self-contained C11 symbolic, numerical, and dimensional runtime with 8-byte IEEE-754 NaN-boxed `Value`s, 8-byte `AstNode`s, $O(1)$ `stdlib.mathc` binary boot (`"MTHC"` v1), and a unified Python-style top-to-bottom executable `.math` module architecture (737.5 KB `Engine` footprint, unbounded custom modules).
- **Top-to-Bottom `.math` Modules & Multi-File `<[dep]` Projects**:
  Every `.math` file (`[module_name]`) is directly executable top-to-bottom with no entry point required. A single `.math` file can mix `$struct` types, `@dim` 7D SI unit rules, polymorphic functions, custom operators, constants, module-level variables, and top-level expressions. Multi-file projects link automatically in topological order via `<[dep]` dependencies.
- **Privacy, Scoping & Mutability Rules**:
  - **Privacy (`pub` / `priv`)**: Module declarations are `pub` by default. Prefixing a variable, constant, function/operator, or `$type` with `priv` restricts direct access to the declaring module while allowing that module's public functions to access its private helpers and state.
  - **Scoping (3-Tier Lexical)**: Local `{ ... }` blocks and function parameters shadow current-module variables (`SymTable.vars`), which in turn shadow public variables imported from other active modules.
  - **Mutability (`:=` / `mut` vs `=` / `const`)**: `x := expr` or `mut x = expr` declares a mutable variable (`SYM_VAR_MUTABLE`). `const c = expr` (or `c = expr` on a new module-level binding) creates an immutable constant (`SYM_VAR_EXPLICIT_CONST`) that rejects reassignment (`=`, `:=`, `+=`) and in-place container/struct mutation (`v[i] = ...`, `v.push(...)`, `s.x = ...`).
- **Expressions, Ranges, Comprehensions & Units**:
  Implicit multiplication (`2x`, `2π`), concrete type/unit slot locks (`x : real, distance := 50m`), generic ranges (`1...10`, `1..<10`, `1>..10`, `1>..<10`, nested skip ranges `1..<25...75>..100`, stepped `by`), list comprehensions (`[x^2 for x in 1...8 if x % 2 == 0]`), lexical closures (`(x, y = 1) => x + y`), functional pipes (`x |> f`), 1D–4D matrices (`[1, 2; 3, 4]`), and 7D SI unit conversions (`25 km in m`, `36 kph to_mps`).
- **Symbolic & Numerical Calculus**:
  `sym_diff`, `sym_integ`, `sym_solve`, `sym_simplify`, `sym_taylor`, `diff`, `diff2`, `diff_n`, `integrate`, `lim`, `sigma`, `prod`, `solve`, `fmin`, `fmax`, `grad2`, `grad3`, `hessian2`, `laplacian2`, `ode_rk4`.
- **Standard Library (18 Modules)**:
  `[boot_syntax]`, `[boot_types]`, `[boot_control]`, `[arith]`, `[trig]`, `[complex_ext]`, `[vec]`, `[mat]`, `[algebra]`, `[calculus]`, `[units]`, `[conversions]`, `[physics]`, `[chemistry]`, `[biology]`, `[geom_ext]`, `[string]`.
