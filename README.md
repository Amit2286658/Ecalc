# Ecalc

A scientific Android calculator featuring neumorphic keys, a native math engine, and damped-spring UI physics.

> All project paths and commands below are relative to the `Main/` directory
> (the Gradle project root), except where a full path is given.

## Build & Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (JDK 17+)

1. Open Android Studio and select **Open** on the `Main` project directory.
2. Let Gradle sync and download dependencies.
3. Run the app on an emulator or physical device, or build from the command line:
   ```bash
   ./gradlew assembleDebug
   ```

## Native Engine & Standard Library Architecture (Marketplace Compliant)

The calculator evaluates expressions through the C11 **Math Engine (MEP)** (<https://github.com/Amit2286658/Math-Engine-MEP>), using a separated, store-compliant layout:

1. **Bundled Native Engine (`jniLibs/`) & Precompiled Standard Library (`assets/precompiled/`)** —
   Both `app/src/main/jniLibs/arm64-v8a/libmathengine.so` and `app/src/main/assets/precompiled/stdlib.mathc` are copied from `Math-Engine-MEP/Main/precompiled/` and bundled directly in the app. At startup, `MathEngineManager` loads `libmathengine.so` via standard `System.loadLibrary("mathengine")`, initializes a blank engine (`nativeCreateBlank()`), and loads `assets/precompiled/stdlib.mathc` in $O(1)$ `memcpy` time (`nativeLoadMathc()`). No GitHub network lookup is performed for `.so` or `stdlib.mathc`.
2. **Incremental `.math` Extension Patches (`MathPatchDownloader` & `MathPatchLoader`)** —
   For new `.math` extension modules published after an app release:
   - `MathPatchDownloader.syncMissingPatches(context)` checks the remote `patches/manifest.json` index and downloads any missing `.math` files into `context.filesDir/math_patches/` without modifying the running engine.
   - `MathPatchLoader.loadUnloadedPatches(...)` scans `context.filesDir/math_patches/` and compiles/links any `.math` files that are not yet loaded into the active engine.
3. **Optional Source Build (`-PmepSourceBuild`)** —
   To compile `libmathengine.so` directly from the vendored C11 sources in `app/src/main/cpp/mathengine/` (with `-DMATH_ENGINE_SEPARATE_STDLIB=ON`):
   ```bash
   ./gradlew assembleDebug -PmepSourceBuild
   ```
