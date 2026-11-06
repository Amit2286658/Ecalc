# Ecalc — Android Scientific Calculator & `.math` Studio (Powered by Math-Engine-MEP)

**Ecalc** is a neumorphic Android scientific calculator and multi-file `.math` development studio powered by the **Math-Engine-MEP** C11 symbolic and numerical runtime via JNI (`libmathengine.so` + `stdlib.mathc`).

---

## Core Features

1. **Dual-Workspace Architecture with Multi-Project Isolation**:
   - **Basic & Scientific Calculator (`project0`)**: Runs in its own isolated `filesDir/math_projects/project0/` environment with 5 spring-animated tabs (*History*, *Basic Keypad*, *Extended Keypad*, *Trigonometry* with `deg`/`rad` mode toggle, and *Functions/Constants*), single-slot memory (`M+`, `M-`, `MR`), and long-press frosted-glass tooltips.
   - **Freeform `.math` Studio (`project1`, `project2`, ...)**: Full multi-file `.math` IDE with per-project isolated native `Engine*` instances (`filesDir/math_projects/<projectName>/`), open-file tab strip, live variable & constant inspector, interactive 2D matrix/map table viewer, and console stream output (`print` / `println`).
2. **Unified Standalone & Multi-File Project Execution**:
   - **▶ Run / Link (Single File)**: Executes the active `.math` file top-to-bottom while automatically resolving any `<[dep]` sibling modules in the active project.
   - **▶ Run Project (Multi-File Project)**: Scans all `.math` files in the active project for `@entry(fn)` or `pub main()`, links all sibling `<[dep]` modules in declaration-only mode, and executes the project entry point.
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
