# mathengine — vendored sync

This directory is a vendored copy of the Math Engine (MEP) C11 sources from:

    https://github.com/Amit2286658/Math-Engine-MEP

What is vendored here:

* `CMakeLists.txt` — engine root build script (builds `libmathengine.so`
  when `-DMATH_ENGINE_BUILD_SHARED=ON`, which Gradle passes in source mode)
* `Core/` — engine core: pipeline (`Code/`), headers, extension compiler
  (`compile/`), JNI layer (`jni/`), delegates (`lib/`), sessions

The Kotlin/Java bridge files already live at
`app/src/main/java/com/mathengine/` and must stay in package
`com.mathengine` (the JNI symbol names are derived from it).

## Syncing a new engine version

1. Clone / pull the latest Math-Engine-MEP.
2. Replace `Core/` and `CMakeLists.txt` here with the fresh copies.
3. Diff `JNI/MathEngineBridge.{java,kt}` from the engine repo against
   `app/src/main/java/com/mathengine/` and update if changed.
4. Rebuild `libmathengine.so` (see README "Native engine" — either
   `-PmepSourceBuild` Gradle mode or `make ndk` + drop into `jniLibs/`).

## Why this exists

The old prebuilt `libmathengine.so` was compiled from a version enforcing a
maximum custom-module count (error string `Maximum module count (%d)
exceeded`, `Maximum module dependencies (16) exceeded`). The current engine
source has an **unbounded module count**, which users of the free-form
editor rely on. Keeping this source copy allows the prebuilt binary to be
rebuilt from source at any time instead of going stale.
