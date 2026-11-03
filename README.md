# Ecalc

A scientific Android calculator featuring neumorphic keys, a native C11 math engine, and damped-spring UI physics.

> All project paths and commands below are relative to the `Main/` directory
> (the Gradle project root), except where a full path is given.

## Author & Project Information

- **Author :** Amit kumar
- **Email :** amitkumar13234353@gmail.com
- **Project GitHub :** [GitHub.com/Ecalc](https://github.com/Ecalc)
- **Cmath engine :** [GitHub.com/Math-Engine-MEP](https://github.com/Math-Engine-MEP) (<https://github.com/Amit2286658/Math-Engine-MEP>)
- **Support & Feedback :** Contact the developer on the email address (`amitkumar13234353@gmail.com`) for any query/bug report/feature request.
- **Engine Documentation & Guides :** Included in-project at [`docs/CMATH_ENGINE_DOCS.md`](docs/CMATH_ENGINE_DOCS.md) and bundled in the app at `Main/app/src/main/assets/docs/CMATH_ENGINE_DOCS.md` (accessible directly inside the in-app **Info (`i`)** modal below the License & Terms & Conditions).

## Build & Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (JDK 17+)

1. Open Android Studio and select **Open** on the `Main` project directory.
2. Let Gradle sync and download dependencies.
3. Run the app on an emulator or physical device, or build from the command line:
   ```bash
   ./gradlew assembleDebug
   ```

## Native Engine, `.math` Studio & Standard Library Architecture

The calculator and `.math` Studio evaluate expressions and scripts through the C11 **Math Engine (MEP)** (<https://github.com/Amit2286658/Math-Engine-MEP>), built around a unified, session-free top-to-bottom executable module architecture:

1. **Unified `.math` Module Studio (No Separate Sessions Layer)** —
   Every `.math` file is a first-class executable module (`[module_name]`) that runs top-to-bottom on its own (Python-style) with no entry point required. Modules can freely mix `$struct` types, `@dim` physical units, overloaded functions/operators, constants, mutable/immutable variables (`:=`, `mut`, `const`, `pub`, `priv`), and standalone expressions, and can import other workspace or standard-library modules via `<[dep]` with automatic topological dependency resolution.
2. **Variable Privacy, Scoping & Mutability** —
   - **Privacy**: Declarations default to `pub`; prefixing variables, constants, functions, or `$type`s with `priv` encapsulates them within the declaring module (accessible to that module's own public functions, hidden from external modules).
   - **Scoping**: 3-tier lexical resolution where local `{ ... }` block/parameter variables shadow current-module variables, which shadow imported public variables from active modules.
   - **Mutability**: `:=` (or `mut`) binds a mutable variable (`SYM_VAR_MUTABLE`), while `const` (or `=` on a new module binding) binds an immutable constant (`SYM_VAR_EXPLICIT_CONST`) that prevents both reassignment and in-place container/struct mutation.
3. **Bundled Native Engine (`jniLibs/`) & Precompiled Standard Library (`assets/precompiled/`)** —
   Both `app/src/main/jniLibs/arm64-v8a/libmathengine.so` and `app/src/main/assets/precompiled/stdlib.mathc` are copied from `Math-Engine-MEP/Main/precompiled/` and bundled directly in the app. At startup, `MathEngineManager` loads `libmathengine.so` via standard `System.loadLibrary("mathengine")`, initializes a blank engine (`nativeCreateBlank()`), and loads `assets/precompiled/stdlib.mathc` in $O(1)$ `memcpy` time (`nativeLoadMathc()`).
4. **Incremental `.math` Extension Patches (`MathPatchDownloader` & `MathPatchLoader`)** —
   For new `.math` extension modules published after an app release:
   - `MathPatchDownloader.syncMissingPatches(context)` checks the remote `patches/manifest.json` index and downloads any missing `.math` files into `context.filesDir/math_patches/` without modifying the running engine.
   - `MathPatchLoader.loadUnloadedPatches(...)` scans `context.filesDir/math_patches/` and compiles/links any `.math` files that are not yet loaded into the active engine.
5. **Optional Source Build (`-PmepSourceBuild`)** —
   To compile `libmathengine.so` directly from the vendored C11 sources in `app/src/main/cpp/mathengine/`:
   ```bash
   ./gradlew assembleDebug -PmepSourceBuild
   ```

## License

Released under the **MIT License** (see [`LICENSE`](LICENSE)).

Copyright (c) 2026 Amit kumar (`amitkumar13234353@gmail.com`).
