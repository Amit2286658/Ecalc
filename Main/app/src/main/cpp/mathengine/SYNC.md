# mathengine — vendored sync

This directory is a vendored copy of the Math Engine (MEP) C11 sources from:

    https://github.com/Amit2286658/Math-Engine-MEP

## Precompiled Default Layout (`jniLibs/` + `assets/precompiled/`)

By default, `Ecalc` ships the separated precompiled artifacts from `Math-Engine-MEP/Main/precompiled/` directly inside the APK/AAB so startup requires **zero network calls** for the core engine or standard library:

* `app/src/main/jniLibs/arm64-v8a/libmathengine.so` — Precompiled C11 native engine binary (synced from `Math-Engine-MEP/Main/precompiled/arm64-v8a/libmathengine.so`).
* `app/src/main/assets/precompiled/stdlib.mathc` — Precompiled 18-module `.mathc` standard library image (`244,423` bytes, synced from `Math-Engine-MEP/Main/precompiled/stdlib.mathc`). Loaded at boot via `MathEngineBridge.nativeCreateBlank()` + `MathEngineBridge.nativeLoadMathc()`.

## Incremental `.math` Patches

New `.math` extension modules published after an app release are listed in `patches/manifest.json` in the repo:
* `MathPatchDownloader` checks `manifest.json` and downloads any missing `.math` files into `context.filesDir/math_patches/` without touching the running engine.
* `MathPatchLoader` scans `context.filesDir/math_patches/` and compiles/links any unloaded `.math` files into the active engine.
