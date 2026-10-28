# Ecalc

A scientific Android calculator featuring neumorphic keys, a native math engine, and damped-spring UI physics.

## Build & Run Locally

**Prerequisites:** [Android Studio](https://developer.android.com/studio) (JDK 17+)

1. Open Android Studio and select **Open** on the `Main` project directory.
2. Let Gradle sync and download dependencies.
3. Run the app on an emulator or physical device, or build from the command line:
   ```bash
   ./gradlew assembleDebug
   ```

## Native Engine (Math Engine MEP)

The calculator evaluates through the C11 **Math Engine (MEP)**,
<https://github.com/Amit2286658/Math-Engine-MEP>, loaded as
`libmathengine.so` via `com.mathengine.MathEngineBridge` (JNI).

Two sources for the library are configured:

* **Prebuilt (default)** — `app/src/main/jniLibs/arm64-v8a/libmathengine.so`
  is packed into the APK directly. No NDK required; this is what plain
  `./gradlew assembleDebug` uses.
* **Source build** — the engine sources are vendored at
  `app/src/main/cpp/mathengine/` (see its `SYNC.md`). Building with
  ```bash
  ./gradlew assembleDebug -PmepSourceBuild
  ```
  compiles the vendored engine instead (requires Android NDK). This is how
  to get engine-level changes (e.g. the *unbounded custom-module count*)
  into the APK without awaiting a new checked-in binary.

To refresh the prebuilt library after an engine update, rebuild it from the
source in `app/src/main/cpp/mathengine/` (Android NDK r23+ … r27+):

```bash
cmake -S app/src/main/cpp/mathengine -B build-native \
      -DMATH_ENGINE_BUILD_SHARED=ON -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_TOOLCHAIN_FILE=$NDK_HOME/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26
cmake --build build-native
cp build-native/libmathengine.so app/src/main/jniLibs/arm64-v8a/libmathengine.so
```

