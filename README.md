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

## Native Engine (Math Engine MEP)

The calculator evaluates through the C11 **Math Engine (MEP)**,
<https://github.com/Amit2286658/Math-Engine-MEP>, loaded as
`libmathengine.so` via `com.mathengine.MathEngineBridge` (JNI).

Three layers keep the engine current **without re-releasing the APK**:

1. **Hot-update at startup (default branch of the loader)** —
   `MathEngineUpdater` runs in the engine bootstrap thread. It checks the
   engine repo's `Prebuilt/arm64-v8a/libmathengine.so` (GitHub contents API,
   git-blob SHA compare), downloads it on change (integrity-verified via the
   blob SHA, atomic file replace) into app-private storage, and
   `MathEngineBridge` then `System.load`s it instead of the bundled binary.
   Works offline/rate-limited/first-run safe: anything goes wrong → bundled
   `jniLibs` copy is used. The new engine applies on the **next launch**
   (a loaded `.so` cannot be swapped mid-process).
   To publish an engine update for all users: rebuild the engine and commit
   the fresh `.so` at `Prebuilt/arm64-v8a/libmathengine.so` in the
   Math-Engine-MEP repo. Never push an older build there — the app adopts it
   verbatim (there is no version floor).
2. **Prebuilt (offline fallback)** — `app/src/main/jniLibs/arm64-v8a/libmathengine.so`
   is packed into the APK directly. No NDK required.
3. **Source build** — sources vendored at `app/src/main/cpp/mathengine/`
   (see its `SYNC.md`):
   ```bash
   ./gradlew assembleDebug -PmepSourceBuild
   ```
   compiles the vendored engine during the Gradle build (needs NDK).


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

