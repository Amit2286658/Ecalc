# Ecalc — Android Scientific Calculator & `.math` Studio (Powered by Math-Engine-MEP)

**Ecalc** is a neumorphic Android scientific calculator and multi-file `.math` development studio powered by the **Math-Engine-MEP** C11 symbolic and numerical runtime via JNI (`libmathengine.so` + `stdlib.mathc`).

---

## Core Features

1. **Single Recyclable `Engine*` Instance (`O(1)` User-Module Flush)**:
   - Maintains **one** shared native `Engine*` instance across the entire app, booted once with `stdlib.mathc` + global patches (`filesDir/math_patches/compiled_state.mathc`) and snapshotted at the Base-Layer watermark (`eng_snapshot_base`).
   - Eliminates per-project `Engine*` heap allocations (~1 MB each) and per-project `engine.so` / `stdlib.mathc` disk copies (~550 KB each); project directories (`filesDir/math_projects/<projectName>/`) store only `.math` source files and data files (`.json`, `.csv`).
2. **Two Unified Execution Paths Matching the C11 Engine**:
   - **Path 1 — Standalone Mode (`nativeEval` & single-unit `nativeBuildAndRun`)**: Used by the Basic & Scientific Calculator (`MainActivity` keypad) and standalone single-file `.math` scripts (`unit_count == 1`). If project modules were previously linked, `nativeFlushUserModules` rewinds the engine to `stdlib + patches` in O(1) time (`< 5 µs`).
   - **Path 2 — Projects Mode (`nativeFlushUserModules` + multi-unit `nativeBuildAndRun`)**: Used by Multi-File `.math` Studio Projects (`project1`, `project2`, ...). Flushes previous user modules in O(1) time, sets the project's sandboxed `import_dir`, topologically links all project units via Kahn's `<[dep]` sort in C (`eng_build_and_run`), and executes the project's `@entry(fn)` or `pub main()` entry point.
3. **Interactive 2D Function, Vector & Parametric Plotter (`📈 Plot`)**:
   - Plots 1-argument functions `f(x)`, algebraic expressions in `x`/`t`, 1D vectors (`vec`), and 2D parametric `vec<vec2>` curves (`vec2(x=..., y=...)`) with pan, pinch-to-zoom, automatic root/extremum markers, and touch trace crosshair.
4. **Symbolic CAS & Expression Inspector (`⚙ CAS`)**:
   - Inspects any module function, lambda `x => ...`, or algebraic expression using the C11 engine's native Symbolic CAS (`sym_simplify`, `sym_diff`, `sym_integ`, `sym_taylor`, `sym_solve`) and runtime type/unit evaluator.
5. **7D SI Units (`length`, `mass`, `force`, ...) & Live Currency Conversion (`into`)**:
   - Supports physical unit algebra and unit conversion via `into` (`2km into m` -> `2000 [length]`, `180deg into rad`, `100 USD into INR`).
   - `CurrencyConverter` registers live `@currency_convert` and `@fx_rate` host `@extern` callbacks backed by free, no-API-key exchange-rate feeds with offline caching.
6. **OTA `.math` Patch Pipeline (`MathPatchDownloader` + `MathPatchLoader`)**:
   - Verifies remote `.math` patches by SHA-256 against `manifest.json`, links them via `MathEngineBridge.nativeApplyPatch` (supporting both new modules like `[currency]` and stdlib hotfixes), and caches the resulting `.mathc` image in `compiled_state.mathc` for O(1) cold-start boot.

---

## Project Directory Structure

```text
Ecalc/
├── README.md
├── docs/
│   └── CMATH_ENGINE_DOCS.md                        # Complete .math language & stdlib reference
├── .github/workflows/
│   ├── check_mathc.py                              # .mathc binary layout, checksum & intr_hash validator
│   ├── debug-build.yml                             # CI workflow building libmathengine.so + Debug APK
│   └── engine-artifacts.yml                        # CI workflow validating stdlib.mathc & NDK artifacts
└── Main/
    ├── build.gradle.kts / settings.gradle.kts
    └── app/
        ├── build.gradle.kts
        └── src/main/
            ├── AndroidManifest.xml
            ├── assets/
            │   ├── docs/CMATH_ENGINE_DOCS.md       # In-app bundled documentation
            │   └── precompiled/stdlib.mathc        # Precompiled 17-module standard library image
            ├── cpp/mathengine/                     # Vendored modular C11 Math-Engine-MEP sources
            │   ├── CMakeLists.txt
            │   ├── Core/
            │   │   ├── Headers/                    # Public & internal C11 headers
            │   │   ├── Code/                       # Modularized arena, lexer, parser, evaluator, engine
            │   │   ├── compile/                    # Modularized compiler, linker, mathc_bin, user_loader
            │   │   ├── lib/delegate/               # Modularized @intrinsic delegates & symbolic CAS
            │   │   ├── lib/hardcode/               # Modularized grammar & ergonomic rules
            │   │   └── jni/                        # JNI C bridge (math_engine_jni.c / .h)
            │   ├── precompiled/stdlib.mathc
            │   └── tools/build_android_arm64.sh
            └── java/
                ├── com/mathengine/
                │   ├── MathEngineBridge.java       # Java JNI declarations
                │   └── MathEngineBridge.kt         # Kotlin JNI bridge & EvalResult wire parser
                └── com/ecalc/
                    ├── calculator/                 # Scientific Calculator UI & Input Pipeline
                    │   ├── MainActivity.kt         # Activity lifecycle, pager & workspace switcher
                    │   ├── CalculatorInputHandler.kt # Keypad input state machine & expression eval
                    │   ├── CalculatorAboutDialog.kt  # Frosted-glass About & embedded docs modal
                    │   ├── CalculatorThemeAndTooltips.kt # Theme circular reveal, tab physics & tooltips
                    │   ├── KeyGridFragment.kt      # 4 calculator keypad tabs
                    │   └── HistoryFragment.kt      # Calculation history tab
                    ├── engine/                     # Native Engine Management & Host Externs
                    │   ├── MathEngineManager.kt    # Multi-project engine lifecycle & state
                    │   ├── MathEngineNormalizer.kt # .math source normalizer & calculator dialect translator
                    │   ├── MathEngineProjectRunner.kt # Standalone file & multi-file project runner
                    │   ├── MathEnginePlotAndCas.kt # 2D plot sampler & Symbolic CAS analyzer
                    │   ├── NativeMathEngine.kt     # Synchronous evaluation helper
                    │   └── CurrencyConverter.kt    # Multi-provider live FX host @extern provider
                    ├── patches/                    # OTA .math Patch Pipeline & Binary Cache
                    │   ├── MathPatchDownloader.kt  # SHA-256 verified .math patch downloader
                    │   └── MathPatchLoader.kt      # Topological patch linker & compiled_state.mathc cache
                    ├── editor/                     # Custom .math Code Editor & Autocomplete
                    │   ├── MathCodeEditText.kt     # Syntax-highlighted code editor with line gutter
                    │   ├── MathEditorCatalog.kt    # Verified .math stdlib & keyword suggestion catalog
                    │   └── MathEditorAutocomplete.kt # Live popup suggestions & hold-to-inspect cards
                    ├── workspace/                  # Freeform .math Studio IDE Fragment & Modals
                    │   ├── MathWorkspaceFragment.kt # Studio root, top bar & frosted Project/File drawer
                    │   ├── WorkspaceModulesView.kt # Editor/Log panes, snippet chips & default project seeding
                    │   ├── WorkspaceExecutionAndProjects.kt # File/Project execution & CRUD controllers
                    │   ├── WorkspaceModalsAndInspector.kt # Frosted dialogs & interactive mat/map table view
                    │   └── WorkspacePlotterAndCasModals.kt # Interactive 2D Plotter & ⚙ CAS Inspector modals
                    └── ui/                         # Shared Custom Views & Compose Theme
                        ├── Frost.kt                # Frosted-glass backdrop blur utility
                        ├── NeumorphicKeyView.kt    # Dual-shadow neumorphic button view
                        ├── MathPlotView.kt         # Interactive 2D pan/zoom/trace plot view
                        └── theme/                  # Color.kt, Theme.kt, Type.kt
```

---

## Building & Verifying

```bash
# Validate the committed stdlib.mathc binary against the vendored C11 headers & intrinsic table
python3 .github/workflows/check_mathc.py Main/app/src/main/assets/precompiled/stdlib.mathc

# Build Debug APK (compiles C11 mathengine via CMake/NDK by default)
cd Main
./gradlew assembleDebug

# Or build with prebuilt libmathengine.so after running tools/build_android_arm64.sh
./gradlew assembleDebug -PusePrebuiltMepSo
```


## Recent Engine Upgrades (Base-Layer Watermarks, SBO+Heap AST/Overloads, Batch Plotter & Live Catalog)

1. **Single Recyclable `Engine*` via Base-Layer Watermarks (`eng_snapshot_base` & `eng_flush_user_modules`)**
   - After loading `precompiled/stdlib.mathc` and applying global OTA `.math` patches (`eng_apply_patches`), the engine records base-layer watermarks across `SymTable` and `MemArena`.
   - `eng_flush_user_modules(eng)` (`nativeFlushUserModules` over JNI) rewinds all user project modules, types, ephemeral 7D SI units, variables, AST nodes, and overloads back to the `stdlib + patches` baseline in $O(1)$ time (`< 5 µs`) without reloading `stdlib.mathc` or reallocating `Engine*`.
2. **Unbounded SBO + Dynamic Heap Growth for `ast` & `overloads`**
   - `MemArena` keeps `MEM_SBO_AST_NODES = 24,576` AST nodes inline (zero heap allocations for `stdlib.mathc` + normal scripts) and transparently spills to `heap_ast` up to `MEM_MAX_AST_NODES = 65,534` (`0xFFFE`) for large multi-file projects.
   - `SymTable` keeps `SYM_SBO_OVERLOADS = 2,048` operator/function overloads inline and transparently spills to `heap_overloads` up to `SYM_MAX_OVERLOADS = 65,534`, reclaiming heap buffers automatically on `eng_flush_user_modules` and `eng_destroy`.
3. **Native C11 Batch Curve Sampler (`eng_sample_plot` / `nativeSamplePlot`)**
   - Parses a plotted expression, 1-arg function, `vec<real>`, or `vec<vec2>` trajectory once into a temporary AST and evaluates all `N` sample points across `[x_min, x_max]` in a single C loop, returning a flat `double[]` (`[x0, y0, x1, y1, ...]`) over a single JNI call.
4. **Live `SymTable` Catalog Export (`eng_export_catalog` / `nativeExportCatalog`)**
   - Exports all active functions, operators, units, types/structs, constants, and modules directly from the live C `SymTable` (`kind|name|sig|retType|retUnit|modName`) so host IDEs and editors automatically autocomplete newly loaded OTA `.math` patches and linked project modules.

5. **Multi-Curve 2D Plotting (`MathPlotView` & `sampleMultiPlotSeries`)**
   - Separate multiple functions or expressions with `;` or `,` (e.g. `sin(x); cos(x)` or `x^2 - 2; 2*x + 1`) in the Interactive 2D Plotter to overlay up to 6 curves simultaneously with distinct colors, a color-coded legend bar, root/extremum/intersection markers, and multi-curve crosshair readouts.
