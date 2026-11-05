package com.ecalc

import android.content.Context
import android.os.Build
import android.util.Log
import com.mathengine.MathEngineBridge
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * App-side manager for the C11 Math Engine (MEP) with Multi-Project Isolation,
 * Global Patch Propagation (`stdlib.mathc` + `math_patches/`), and Hybrid Entry Points.
 *
 * Project Architecture:
 * 1. **`project0` ([CALC_PROJECT_NAME]) — Dedicated Basic Calculator Project**:
 *    - Lives in `filesDir/math_projects/project0/` with its own `engine.so` and `stdlib.mathc`.
 *    - Used exclusively by the Basic/Scientific Calculator (`MainActivity` keypad -> [evaluate]).
 *    - Completely isolated from freeform Studio projects so user experiments never interfere
 *      with basic calculator evaluation.
 * 2. **`project1` ([DEFAULT_STUDIO_PROJECT_NAME]) & User Projects (`project2`, ...)**:
 *    - `project1` is the default project opened in the Freeform `.math Studio` and holds all
 *      bundled demos, tests, and examples (`physics_sim.math`, `custom_ops.math`,
 *      `vectors_stats.math`, `data_io_json.math`, `config.json`, `sensor_data.csv`).
 *    - Every project lives in `filesDir/math_projects/<projectName>/` accompanied by its own
 *      `engine.so` and `stdlib.mathc` and runs on its own isolated native `Engine*` handle.
 * 3. **Global Patches as Extensions of `stdlib.mathc`**:
 *    - All `.math` patches in `filesDir/math_patches/` (e.g. `currency.math`) are compiled onto
 *      `assets/precompiled/stdlib.mathc` to form the universal baseline (`compiled_state.mathc`).
 *    - Every project (`project0`, `project1`, `project2`, ...) receives this patched `stdlib.mathc`
 *      baseline so patches are visible everywhere just like `stdlib`.
 */
object MathEngineManager {

    /** Result of an evaluation, mirroring the engine's wire format. */
    data class EvalOutcome(
        val ok: Boolean,
        val value: String,
        val type: String,
        val unit: String,
        val error: String?,
        val stdout: String = "",
        val sourceLine: Int = 1
    )

    /** Per-project binary & linked-module status for UI inspection in the Project Drawer. */
    data class ProjectBinaryStatus(
        val projectName: String,
        val hasEngineSo: Boolean,
        val engineSoBytes: Long,
        val hasStdlibMathc: Boolean,
        val stdlibMathcBytes: Long,
        val linkedModulesCount: Int
    )

    private class ProjectRuntimeState(
        val projectName: String,
        var engineHandle: Long = 0L,
        val studioModuleSourcesByName: LinkedHashMap<String, String> = linkedMapOf()
    )

    private const val TAG = "MathEngineManager"
    private const val BUNDLED_STDLIB_ASSET = "precompiled/stdlib.mathc"

    /** `project0` is dedicated exclusively to the Basic Calculator. */
    const val CALC_PROJECT_NAME = "project0"

    /** `project1` is the default project in the Freeform `.math` Studio with all demos & examples. */
    const val DEFAULT_STUDIO_PROJECT_NAME = "project1"

    /** Alias kept for callers referencing the default Studio project. */
    const val DEFAULT_PROJECT_NAME = DEFAULT_STUDIO_PROJECT_NAME

    const val PROJECTS_ROOT_FOLDER = "math_projects"
    const val PROJECT_ENGINE_SO_NAME = "engine.so"
    const val PROJECT_STDLIB_MATHC_NAME = "stdlib.mathc"
    private const val PROJECT_BASE_STDLIB_BACKUP = ".base_stdlib.mathc"

    @Volatile
    private var appContext: Context? = null

    /** Per-project isolated runtime state and native `Engine*` handles (includes `project0` and Studio projects). */
    private val projectStates = linkedMapOf<String, ProjectRuntimeState>()

    /** Currently active project in the freeform `.math` Studio (defaults to `project1`). */
    @Volatile
    var activeProjectName: String = DEFAULT_STUDIO_PROJECT_NAME

    private val worker = Executors.newSingleThreadExecutor()
    private val customExternCallbacks = linkedMapOf<String, MathEngineBridge.ExternCallback>()

    /** Provides the application context so `assets/precompiled/stdlib.mathc`,
     *  remotely fetched patches in `filesDir/math_patches/`, `project0` (Basic Calculator),
     *  `project1` (Freeform Studio demos), and [CurrencyConverter] live rates can be initialized. */
    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        CurrencyConverter.initialize(app)
    }

    /** Sanitizes a user-supplied project name into a safe directory name. */
    fun sanitizeProjectName(raw: String?, allowCalcProject0: Boolean = false): String {
        val cleaned = (raw ?: "")
            .trim()
            .removeSuffix(".math")
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .trim('_')
        if (cleaned.isEmpty()) return if (allowCalcProject0) CALC_PROJECT_NAME else DEFAULT_STUDIO_PROJECT_NAME
        if (!allowCalcProject0 && cleaned.equals(CALC_PROJECT_NAME, ignoreCase = true)) {
            return DEFAULT_STUDIO_PROJECT_NAME
        }
        return cleaned
    }

    /** Root directory containing all projects (`filesDir/math_projects/`). */
    fun getProjectsRootDir(ctx: Context): File =
        File(ctx.filesDir, PROJECTS_ROOT_FOLDER).also { if (!it.exists()) it.mkdirs() }

    /** Directory for a specific project (`filesDir/math_projects/<projectName>/`). */
    fun getProjectDir(
        ctx: Context,
        projectName: String = activeProjectName,
        allowCalcProject0: Boolean = true
    ): File {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = allowCalcProject0)
        return File(getProjectsRootDir(ctx), safeName).also { if (!it.exists()) it.mkdirs() }
    }

    /** Returns the sandboxed file I/O directory for [projectName] (`filesDir/math_projects/<projectName>/`). */
    fun getWorkspaceSandboxDir(ctx: Context, projectName: String = activeProjectName): File =
        getProjectDir(ctx, projectName, allowCalcProject0 = true)

    /**
     * Returns the universal baseline `stdlib.mathc` byte array (`assets/precompiled/stdlib.mathc`
     * extended with all compiled patches from `filesDir/math_patches/compiled_state.mathc`).
     * Because patches are extensions of `stdlib` rather than custom project modules, every
     * project (`project0`, `project1`, `project2`, ...) uses this patched image as its base stdlib.
     */
    @Synchronized
    private fun getUniversalPatchedStdlibBytes(ctx: Context): ByteArray {
        val patchesDir = MathPatchDownloader.getPatchesDir(ctx)
        val cachedPatchedStdlib = File(patchesDir, MathPatchDownloader.CACHE_MATHC_FILE)
        if (cachedPatchedStdlib.isFile && cachedPatchedStdlib.canRead() && cachedPatchedStdlib.length() > 0L) {
            runCatching { return cachedPatchedStdlib.readBytes() }
        }
        return ctx.assets.open(BUNDLED_STDLIB_ASSET).use { it.readBytes() }
    }

    /**
     * Ensures that [projectName]'s directory contains its own `engine.so` and `stdlib.mathc`
     * (plus `.base_stdlib.mathc` seeded with `stdlib + patches` so every project sees all global patches).
     */
    @Synchronized
    fun ensureProjectSetup(
        ctx: Context,
        projectName: String = activeProjectName,
        allowCalcProject0: Boolean = true
    ): File {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = allowCalcProject0)
        val projDir = getProjectDir(ctx, safeName, allowCalcProject0 = allowCalcProject0)
        val stdlibFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
        val baseBackupFile = File(projDir, PROJECT_BASE_STDLIB_BACKUP)
        val engineSoFile = File(projDir, PROJECT_ENGINE_SO_NAME)

        // 1. Provision per-project stdlib.mathc + clean baseline backup (including global patches)
        runCatching {
            val universalBaseBytes = getUniversalPatchedStdlibBytes(ctx)
            if (!baseBackupFile.exists() || baseBackupFile.length() != universalBaseBytes.size.toLong()) {
                baseBackupFile.writeBytes(universalBaseBytes)
            }
            if (!stdlibFile.exists() || stdlibFile.length() == 0L) {
                stdlibFile.writeBytes(universalBaseBytes)
            }
        }.onFailure { t ->
            Log.w(TAG, "Could not provision stdlib.mathc for project '$safeName': ${t.message}")
        }

        // 2. Provision per-project engine.so from installed nativeLibraryDir or APK zip
        runCatching {
            if (!engineSoFile.exists() || engineSoFile.length() == 0L) {
                val copied = copyNativeEngineSoToProject(ctx, engineSoFile)
                if (copied) {
                    engineSoFile.setReadable(true, false)
                    engineSoFile.setExecutable(true, false)
                }
            }
        }.onFailure { t ->
            Log.w(TAG, "Could not provision engine.so for project '$safeName': ${t.message}")
        }

        return projDir
    }

    /**
     * Copies `libmathengine.so` into [destEngineSo] from `applicationInfo.nativeLibraryDir`
     * or extracts it directly from the APK archive (`applicationInfo.sourceDir`).
     */
    private fun copyNativeEngineSoToProject(ctx: Context, destEngineSo: File): Boolean {
        val nativeDir = ctx.applicationInfo?.nativeLibraryDir?.let { File(it) }
        if (nativeDir != null && nativeDir.isDirectory) {
            val candidate = File(nativeDir, "libmathengine.so")
            if (candidate.isFile && candidate.canRead() && candidate.length() > 0L) {
                candidate.copyTo(destEngineSo, overwrite = true)
                return true
            }
        }
        val apkPath = ctx.applicationInfo?.sourceDir
        if (!apkPath.isNullOrBlank()) {
            val apkFile = File(apkPath)
            if (apkFile.isFile && apkFile.canRead()) {
                ZipFile(apkFile).use { zip ->
                    val abis = Build.SUPPORTED_ABIS.orEmpty().toList() + listOf("arm64-v8a", "x86_64", "armeabi-v7a")
                    for (abi in abis.distinct()) {
                        val entry = zip.getEntry("lib/$abi/libmathengine.so")
                        if (entry != null) {
                            zip.getInputStream(entry).use { input ->
                                destEngineSo.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            if (destEngineSo.length() > 0L) return true
                        }
                    }
                }
            }
        }
        return false
    }

    /**
     * Lists all Freeform Studio project names in `filesDir/math_projects/`,
     * keeping `project0` dedicated to the Basic Calculator and ordering `project1` first.
     */
    @Synchronized
    fun listProjects(ctx: Context): List<String> {
        ensureProjectSetup(ctx, CALC_PROJECT_NAME, allowCalcProject0 = true)
        ensureProjectSetup(ctx, DEFAULT_STUDIO_PROJECT_NAME, allowCalcProject0 = false)
        val root = getProjectsRootDir(ctx)
        val dirs = root.listFiles { f ->
            f.isDirectory && !f.name.startsWith(".") && !f.name.equals(CALC_PROJECT_NAME, ignoreCase = true)
        }
            ?.map { it.name }
            ?.sortedWith(compareBy<String> { if (it == DEFAULT_STUDIO_PROJECT_NAME) 0 else 1 }.thenBy { it })
            ?: listOf(DEFAULT_STUDIO_PROJECT_NAME)
        return if (DEFAULT_STUDIO_PROJECT_NAME in dirs) dirs else (listOf(DEFAULT_STUDIO_PROJECT_NAME) + dirs)
    }

    /** Inspects the on-disk `engine.so` and `stdlib.mathc` binaries for [projectName]. */
    @Synchronized
    fun getProjectBinaryStatus(ctx: Context, projectName: String = activeProjectName): ProjectBinaryStatus {
        val isCalc = projectName.equals(CALC_PROJECT_NAME, ignoreCase = true)
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = isCalc)
        val projDir = ensureProjectSetup(ctx, safeName, allowCalcProject0 = isCalc)
        val soFile = File(projDir, PROJECT_ENGINE_SO_NAME)
        val mathcFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
        val state = projectStates[safeName]
        return ProjectBinaryStatus(
            projectName = safeName,
            hasEngineSo = soFile.isFile && soFile.length() > 0L,
            engineSoBytes = if (soFile.isFile) soFile.length() else 0L,
            hasStdlibMathc = mathcFile.isFile && mathcFile.length() > 0L,
            stdlibMathcBytes = if (mathcFile.isFile) mathcFile.length() else 0L,
            linkedModulesCount = state?.studioModuleSourcesByName?.size ?: 0
        )
    }

    /** Creates a new isolated Studio project folder with its own `engine.so` and patched `stdlib.mathc`. */
    @Synchronized
    fun createProject(ctx: Context, projectName: String): File {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = false)
        val projDir = ensureProjectSetup(ctx, safeName, allowCalcProject0 = false)
        projectStates.getOrPut(safeName) { ProjectRuntimeState(safeName) }
        return projDir
    }

    /** Switches the active Studio project and boots its isolated engine handle if needed. */
    fun setActiveProject(
        ctx: Context,
        projectName: String,
        onDone: ((ok: Boolean, activeProject: String) -> Unit)? = null
    ) {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = false)
        activeProjectName = safeName
        worker.execute {
            val ok = synchronized(this) {
                ensureProjectSetup(ctx, safeName, allowCalcProject0 = false)
                ensureProjectEngineSync(ctx, safeName, allowCalcProject0 = false) != 0L
            }
            onDone?.invoke(ok, safeName)
        }
    }

    /** Renames or duplicates an existing Studio project along with its `.math` files, `engine.so`, and `stdlib.mathc`. */
    @Synchronized
    fun renameOrCopyProject(
        ctx: Context,
        sourceProjectName: String,
        targetProjectName: String,
        copyOnly: Boolean
    ): String {
        val srcSafe = sanitizeProjectName(sourceProjectName, allowCalcProject0 = false)
        var dstSafe = sanitizeProjectName(targetProjectName, allowCalcProject0 = false)
        if (dstSafe == srcSafe && copyOnly) {
            dstSafe = "${srcSafe}_copy"
        }
        if (dstSafe == srcSafe) return srcSafe

        val srcDir = getProjectDir(ctx, srcSafe, allowCalcProject0 = false)
        val dstDir = getProjectDir(ctx, dstSafe, allowCalcProject0 = false)
        srcDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                runCatching { file.copyTo(File(dstDir, file.name), overwrite = true) }
            }
        }
        ensureProjectSetup(ctx, dstSafe, allowCalcProject0 = false)

        if (!copyOnly && srcSafe != DEFAULT_STUDIO_PROJECT_NAME) {
            projectStates.remove(srcSafe)?.let { oldState ->
                if (oldState.engineHandle != 0L) {
                    runCatching { MathEngineBridge.nativeDestroy(oldState.engineHandle) }
                }
            }
            srcDir.deleteRecursively()
        }
        if (activeProjectName == srcSafe && !copyOnly) {
            activeProjectName = dstSafe
        }
        return dstSafe
    }

    /** Deletes a Studio project and destroys its isolated native `Engine*` handle. If `project1` is reset, re-provisions it. */
    @Synchronized
    fun deleteProject(ctx: Context, projectName: String): String {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = false)
        projectStates.remove(safeName)?.let { st ->
            if (st.engineHandle != 0L) {
                runCatching { MathEngineBridge.nativeDestroy(st.engineHandle) }
                st.engineHandle = 0L
            }
        }
        val projDir = getProjectDir(ctx, safeName, allowCalcProject0 = false)
        projDir.deleteRecursively()

        if (safeName == DEFAULT_STUDIO_PROJECT_NAME) {
            ensureProjectSetup(ctx, DEFAULT_STUDIO_PROJECT_NAME, allowCalcProject0 = false)
        }
        val remaining = listProjects(ctx)
        if (activeProjectName == safeName) {
            activeProjectName = remaining.firstOrNull() ?: DEFAULT_STUDIO_PROJECT_NAME
        }
        return activeProjectName
    }

    /**
     * Resets a project's isolated `stdlib.mathc` back to the universal baseline (`stdlib + patches`)
     * and recreates its native `Engine*` handle so any previously compiled/linked custom modules are unlinked.
     */
    fun resetProjectEngine(
        ctx: Context,
        projectName: String = activeProjectName,
        onDone: ((ok: Boolean) -> Unit)? = null
    ) {
        val isCalc = projectName.equals(CALC_PROJECT_NAME, ignoreCase = true)
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = isCalc)
        worker.execute {
            val ok = synchronized(this) {
                val projDir = ensureProjectSetup(ctx, safeName, allowCalcProject0 = isCalc)
                val baseBackup = File(projDir, PROJECT_BASE_STDLIB_BACKUP)
                val stdlibFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
                runCatching {
                    val cleanBytes = getUniversalPatchedStdlibBytes(ctx)
                    baseBackup.writeBytes(cleanBytes)
                    stdlibFile.writeBytes(cleanBytes)
                }
                val st = projectStates.getOrPut(safeName) { ProjectRuntimeState(safeName) }
                if (st.engineHandle != 0L) {
                    runCatching { MathEngineBridge.nativeDestroy(st.engineHandle) }
                    st.engineHandle = 0L
                }
                st.studioModuleSourcesByName.clear()
                ensureProjectEngineSync(ctx, safeName, allowCalcProject0 = isCalc) != 0L
            }
            onDone?.invoke(ok)
        }
    }

    /** Registers all built-in host externs ([CurrencyConverter]) and any custom app externs on [handle]. */
    @Synchronized
    private fun registerHostExternsOnHandle(handle: Long, projectName: String) {
        if (handle == 0L) return
        appContext?.let { ctx ->
            val sandboxDir = getWorkspaceSandboxDir(ctx, projectName).absolutePath
            runCatching { MathEngineBridge.nativeSetImportDir(handle, sandboxDir) }
        }
        CurrencyConverter.registerWithEngine(handle)
        for ((extName, cb) in customExternCallbacks) {
            runCatching { MathEngineBridge.nativeRegisterExtern(handle, extName, cb) }
        }
    }

    /** Registers a custom host extern callback (`@<name>(...)`) on all active engines and persists it across rebuilds. */
    @Synchronized
    fun registerExtern(name: String, callback: MathEngineBridge.ExternCallback): Boolean {
        customExternCallbacks[name] = callback
        var ok = true
        for ((_, st) in projectStates) {
            if (st.engineHandle != 0L) {
                ok = runCatching {
                    MathEngineBridge.nativeRegisterExtern(st.engineHandle, name, callback)
                }.getOrDefault(false) && ok
            }
        }
        return ok
    }

    private fun stripCommentOutsideQuotes(line: String): String {
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '\\' && inQuotes && i + 1 < line.length) {
                i += 2
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
            } else if (ch == '#' && !inQuotes) {
                return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /**
     * Derives the canonical `[module]` name for a `.math` file:
     * - If [rawSource] declares an explicit `[mod_name]` header, returns that name.
     * - Otherwise derives a clean identifier from [fileName] (e.g. `physics_sim.math` -> `physics_sim`).
     */
    fun deriveModuleName(fileName: String?, rawSource: String): String {
        val modRegex = Regex("^\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
        for (rawLine in rawSource.lineSequence()) {
            val trimmed = stripCommentOutsideQuotes(rawLine).trim()
            if (trimmed.isEmpty()) continue
            val m = modRegex.matchEntire(trimmed)
            if (m != null) {
                return m.groupValues[1]
            }
        }
        val stem = (fileName ?: "workspace")
            .removeSuffix(".math")
            .replace(Regex("[^a-zA-Z0-9_]"), "_")
            .trim('_')
        if (stem.isEmpty()) return "workspace"
        return if (stem[0].isDigit()) "mod_$stem" else stem
    }

    /** Extracts all `<[dep]` module names referenced in [rawSource]. */
    fun extractModuleDependencies(rawSource: String): List<String> {
        val deps = LinkedHashSet<String>()
        val depRegex = Regex("^<\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
        for (rawLine in rawSource.lineSequence()) {
            val trimmed = stripCommentOutsideQuotes(rawLine).trim()
            if (trimmed.isEmpty()) continue
            depRegex.matchEntire(trimmed)?.let { m ->
                deps.add(m.groupValues[1])
            }
        }
        return deps.toList()
    }

    /**
     * Extracts an explicit `@entry(fn_name)` / `@entry fn_name` directive or detects a 0-arg
     * `pub main()` / `main()` definition in [rawSource] for Hybrid Start Point execution.
     */
    fun detectModuleEntryPoint(rawSource: String): Pair<String?, Boolean> {
        val explicitEntryRegex = Regex("^@entry\\s*(?:\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)|([a-zA-Z_][a-zA-Z0-9_]*))$")
        val mainDeclRegex = Regex("^(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?main\\s*\\(\\s*\\)\\s*(?:->[^=]+|:[a-zA-Z0-9_,\\s]+)?=")
        var hasMain = false
        for (rawLine in rawSource.lineSequence()) {
            val trimmed = stripCommentOutsideQuotes(rawLine).trim()
            if (trimmed.isEmpty()) continue
            val em = explicitEntryRegex.matchEntire(trimmed)
            if (em != null) {
                val fn = em.groupValues[1].ifEmpty { em.groupValues[2] }
                return fn to true
            }
            if (mainDeclRegex.containsMatchIn(trimmed)) {
                hasMain = true
            }
        }
        return if (hasMain) ("main" to false) else (null to false)
    }

    /** Creates a blank engine loaded strictly with `assets/precompiled/stdlib.mathc` and registers host externs. */
    private fun createFreshStdlibEngine(ctx: Context, projectName: String = CALC_PROJECT_NAME): Long {
        val stdlibBytes = ctx.assets.open(BUNDLED_STDLIB_ASSET).use { it.readBytes() }
        val handle = MathEngineBridge.nativeCreateBlank().takeIf { it != 0L }
            ?: MathEngineBridge.nativeCreate().takeIf { it != 0L }
        if (handle == null) {
            Log.e(TAG, "Engine allocation failed (nativeCreateBlank + nativeCreate both returned 0)")
            return 0L
        }
        if (MathEngineBridge.nativeLoadMathc(handle, stdlibBytes)) {
            registerHostExternsOnHandle(handle, projectName)
            return handle
        }
        MathEngineBridge.nativeDestroy(handle)
        return 0L
    }

    /**
     * Boots or returns the isolated `Engine*` handle for [projectName] (`project0` for Basic Calculator,
     * `project1`+ for Freeform Studio), loading that project's own `stdlib.mathc` (which includes
     * all global patches) and pairing with its `engine.so` in `filesDir/math_projects/<projectName>/`.
     */
    @Synchronized
    private fun ensureProjectEngineSync(
        ctx: Context,
        projectName: String = activeProjectName,
        allowCalcProject0: Boolean = true
    ): Long {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = allowCalcProject0)
        val projDir = ensureProjectSetup(ctx, safeName, allowCalcProject0 = allowCalcProject0)
        val state = projectStates.getOrPut(safeName) { ProjectRuntimeState(safeName) }
        if (state.engineHandle != 0L) {
            registerHostExternsOnHandle(state.engineHandle, safeName)
            return state.engineHandle
        }

        val engineSoFile = File(projDir, PROJECT_ENGINE_SO_NAME)
        if (engineSoFile.isFile && engineSoFile.length() > 0L) {
            runCatching { System.load(engineSoFile.absolutePath) }
        }

        val stdlibFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
        val baseBackupFile = File(projDir, PROJECT_BASE_STDLIB_BACKUP)

        val handle = MathEngineBridge.nativeCreateBlank().takeIf { it != 0L }
            ?: MathEngineBridge.nativeCreate().takeIf { it != 0L }
            ?: return 0L

        val projectMathcBytes = runCatching {
            if (stdlibFile.isFile && stdlibFile.length() > 0L) stdlibFile.readBytes() else null
        }.getOrNull()

        var loaded = false
        if (projectMathcBytes != null && projectMathcBytes.isNotEmpty()) {
            loaded = MathEngineBridge.nativeLoadMathc(handle, projectMathcBytes)
        }
        if (!loaded) {
            val cleanBytes = runCatching {
                getUniversalPatchedStdlibBytes(ctx)
            }.getOrNull()
            if (cleanBytes != null && cleanBytes.isNotEmpty()) {
                loaded = MathEngineBridge.nativeLoadMathc(handle, cleanBytes)
                if (loaded) {
                    runCatching {
                        baseBackupFile.writeBytes(cleanBytes)
                        stdlibFile.writeBytes(cleanBytes)
                    }
                }
            }
        }

        if (!loaded) {
            MathEngineBridge.nativeDestroy(handle)
            return 0L
        }

        state.engineHandle = handle
        registerHostExternsOnHandle(handle, safeName)
        return handle
    }

    /**
     * Rebuilds [projectName]'s isolated engine from the universal patched baseline
     * (`stdlib.mathc` + all global `math_patches/`) and re-links all active custom modules
     * in `state.studioModuleSourcesByName`, persisting the updated `.mathc` image to
     * `filesDir/math_projects/<projectName>/stdlib.mathc`.
     */
    @Synchronized
    private fun rebuildProjectEngineFromBaselineSync(
        ctx: Context,
        projectName: String,
        allowCalcProject0: Boolean = true
    ): Long {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = allowCalcProject0)
        val projDir = ensureProjectSetup(ctx, safeName, allowCalcProject0 = allowCalcProject0)
        val baseBackupFile = File(projDir, PROJECT_BASE_STDLIB_BACKUP)
        val stdlibFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
        val state = projectStates.getOrPut(safeName) { ProjectRuntimeState(safeName) }

        if (state.engineHandle != 0L) {
            runCatching { MathEngineBridge.nativeDestroy(state.engineHandle) }
            state.engineHandle = 0L
        }

        val cleanBytes = runCatching { getUniversalPatchedStdlibBytes(ctx) }.getOrNull() ?: return 0L
        runCatching {
            baseBackupFile.writeBytes(cleanBytes)
            stdlibFile.writeBytes(cleanBytes)
        }

        val newHandle = ensureProjectEngineSync(ctx, safeName, allowCalcProject0 = allowCalcProject0)
        if (newHandle == 0L) return 0L

        var latestMathc: ByteArray? = null
        for ((modName, src) in state.studioModuleSourcesByName) {
            val norm = normalizeExtensionSource(src)
            if (norm.isNotEmpty()) {
                val compiled = runCatching { MathEngineBridge.nativeCompileExt(newHandle, norm) }.getOrNull()
                if (compiled != null && compiled.isNotEmpty()) {
                    latestMathc = compiled
                } else {
                    runCatching { MathEngineBridge.nativeModuleEval(newHandle, modName, norm) }
                }
                runCatching { MathEngineBridge.nativeSetModule(newHandle, modName, true) }
            }
        }
        if (latestMathc != null && latestMathc.isNotEmpty()) {
            runCatching { stdlibFile.writeBytes(latestMathc) }
        }
        return newHandle
    }

    /**
     * Propagates the universal patched `stdlib.mathc` (`assets/precompiled/stdlib.mathc` + all
     * `math_patches/`) to `project0` (Basic Calculator) and all Studio projects (`project1`, ...),
     * ensuring patches are visible everywhere as extensions of `stdlib`.
     */
    @Synchronized
    private fun propagatePatchedStdlibToAllProjectsSync(ctx: Context) {
        val universalBytes = runCatching { getUniversalPatchedStdlibBytes(ctx) }.getOrNull() ?: return
        if (universalBytes.isEmpty()) return

        val allProjNames = (listOf(CALC_PROJECT_NAME) + listProjects(ctx)).distinct()
        for (projName in allProjNames) {
            val isCalc = projName.equals(CALC_PROJECT_NAME, ignoreCase = true)
            val projDir = ensureProjectSetup(ctx, projName, allowCalcProject0 = isCalc)
            val baseBackupFile = File(projDir, PROJECT_BASE_STDLIB_BACKUP)
            val stdlibFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
            val prevBaseLen = if (baseBackupFile.isFile) baseBackupFile.length() else -1L
            runCatching { baseBackupFile.writeBytes(universalBytes) }

            val st = projectStates[projName]
            if (st == null || st.studioModuleSourcesByName.isEmpty()) {
                runCatching { stdlibFile.writeBytes(universalBytes) }
                if (st != null && st.engineHandle != 0L && prevBaseLen != universalBytes.size.toLong()) {
                    rebuildProjectEngineFromBaselineSync(ctx, projName, allowCalcProject0 = isCalc)
                }
            } else if (prevBaseLen != universalBytes.size.toLong()) {
                rebuildProjectEngineFromBaselineSync(ctx, projName, allowCalcProject0 = isCalc)
            }
        }
    }

    /**
     * Rebuilds `project0` (Basic Calculator) + universal patch cache from `stdlib.mathc` +
     * all current `.math` patches in `filesDir/math_patches/`, then propagates to all projects.
     */
    @Synchronized
    private fun rebuildCalcProject0AndPatchesSync(
        ctx: Context,
        onStatus: ((String) -> Unit)? = null
    ): Boolean {
        val st0 = projectStates.getOrPut(CALC_PROJECT_NAME) { ProjectRuntimeState(CALC_PROJECT_NAME) }
        val oldHandle = st0.engineHandle
        MathPatchDownloader.invalidateCompiledCache(MathPatchDownloader.getPatchesDir(ctx))
        val newHandle = createFreshStdlibEngine(ctx, CALC_PROJECT_NAME)
        if (newHandle == 0L) {
            onStatus?.invoke("Engine rebuild failed: could not load $BUNDLED_STDLIB_ASSET")
            return false
        }

        if (oldHandle != 0L) {
            runCatching { MathEngineBridge.nativeDestroy(oldHandle) }
        }
        st0.engineHandle = newHandle
        registerHostExternsOnHandle(newHandle, CALC_PROJECT_NAME)
        MathPatchLoader.resetTracking()

        MathPatchLoader.loadUnloadedPatches(
            ctx,
            newHandle,
            { src -> compileAndLoadPatchOnHandleSync(newHandle, src) },
            onStatus
        )
        propagatePatchedStdlibToAllProjectsSync(ctx)
        return true
    }

    /**
     * Ensures the Basic Calculator's dedicated `project0` engine (`filesDir/math_projects/project0/`)
     * and the global patch cache (`compiled_state.mathc`) are booted and up to date.
     */
    @Synchronized
    fun ensureEngine(onStatus: ((String) -> Unit)? = null): Boolean {
        val ctx = appContext ?: return false
        ensureProjectSetup(ctx, CALC_PROJECT_NAME, allowCalcProject0 = true)
        ensureProjectSetup(ctx, activeProjectName, allowCalcProject0 = false)

        val st0 = projectStates.getOrPut(CALC_PROJECT_NAME) { ProjectRuntimeState(CALC_PROJECT_NAME) }
        if (st0.engineHandle != 0L) {
            registerHostExternsOnHandle(st0.engineHandle, CALC_PROJECT_NAME)
            if (MathPatchLoader.needsEngineRebuild(ctx, st0.engineHandle)) {
                return rebuildCalcProject0AndPatchesSync(ctx, onStatus)
            }
            val newlyLoaded = MathPatchLoader.loadUnloadedPatches(
                ctx,
                st0.engineHandle,
                { src -> compileAndLoadPatchOnHandleSync(st0.engineHandle, src) },
                onStatus
            )
            if (newlyLoaded > 0) {
                propagatePatchedStdlibToAllProjectsSync(ctx)
            }
            return true
        }
        return try {
            val cachedHandle = MathPatchLoader.tryBootFromCompiledCache(ctx, onStatus)
            if (cachedHandle != 0L) {
                st0.engineHandle = cachedHandle
                registerHostExternsOnHandle(cachedHandle, CALC_PROJECT_NAME)
                if (onStatus == null) {
                    MathPatchLoader.loadUnloadedPatches(
                        ctx,
                        cachedHandle,
                        { src -> compileAndLoadPatchOnHandleSync(cachedHandle, src) },
                        null
                    )
                }
                propagatePatchedStdlibToAllProjectsSync(ctx)
                return true
            }

            val handle = createFreshStdlibEngine(ctx, CALC_PROJECT_NAME)
            st0.engineHandle = handle
            if (handle != 0L) {
                registerHostExternsOnHandle(handle, CALC_PROJECT_NAME)
                MathPatchLoader.loadUnloadedPatches(
                    ctx,
                    handle,
                    { src -> compileAndLoadPatchOnHandleSync(handle, src) },
                    onStatus
                )
                propagatePatchedStdlibToAllProjectsSync(ctx)
                true
            } else {
                Log.e(TAG, "Failed to initialize project0 engine with $BUNDLED_STDLIB_ASSET")
                onStatus?.invoke("Engine init failed: could not load $BUNDLED_STDLIB_ASSET")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception while initializing project0 engine from $BUNDLED_STDLIB_ASSET", t)
            onStatus?.invoke("Engine init exception: ${t.message}")
            false
        }
    }

    /** Scans `filesDir/math_patches/` on the engine worker thread, links any unloaded `.math` patches,
     *  and propagates the updated `stdlib + patches` image to `project0` and all Studio projects. */
    fun loadPendingPatches(
        onDone: ((loadedCount: Int) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ) {
        worker.execute {
            val ctx = appContext
            if (ctx == null) {
                onStatus?.invoke("Patch load skipped: app context not initialized")
                onDone?.invoke(0)
                return@execute
            }
            synchronized(this) {
                val st0 = projectStates.getOrPut(CALC_PROJECT_NAME) { ProjectRuntimeState(CALC_PROJECT_NAME) }
                if (st0.engineHandle == 0L) {
                    val ok = ensureEngine(onStatus = onStatus)
                    if (!ok) {
                        onStatus?.invoke("Patch load skipped: engine not ready")
                    }
                    onDone?.invoke(0)
                    return@execute
                }
                registerHostExternsOnHandle(st0.engineHandle, CALC_PROJECT_NAME)
                if (MathPatchLoader.needsEngineRebuild(ctx, st0.engineHandle)) {
                    rebuildCalcProject0AndPatchesSync(ctx, onStatus)
                    onDone?.invoke(1)
                    return@execute
                }
                val count = MathPatchLoader.loadUnloadedPatches(
                    ctx,
                    st0.engineHandle,
                    { src -> compileAndLoadPatchOnHandleSync(st0.engineHandle, src) },
                    onStatus
                )
                propagatePatchedStdlibToAllProjectsSync(ctx)
                onDone?.invoke(count)
            }
        }
    }

    /** Runs the complete startup patch pipeline in deterministic order. */
    fun syncAndLoadPatches(
        context: Context,
        onStatus: ((String) -> Unit)? = null,
        onDone: ((downloadedCount: Int, loadedCount: Int) -> Unit)? = null
    ) {
        val app = context.applicationContext
        appContext = app
        CurrencyConverter.initialize(app)
        Thread {
            val downloaded = MathPatchDownloader.syncMissingPatches(app, onStatus)
            loadPendingPatches(
                onDone = { loaded ->
                    onDone?.invoke(downloaded, loaded)
                },
                onStatus = onStatus
            )
        }.start()
    }

    /**
     * Normalizes `.math` module source code before passing to `nativeCompileExt` or `nativeModuleEval`.
     */
    internal fun normalizeExtensionSource(raw: String): String {
        val cleaned = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val codeLines = mutableListOf<String>()
        val syntaxBindingRegex = Regex("^(?:(?:pub|priv)\\s+)*\"[^\"]+\"\\s*=>\\s*@")
        val typeDeclRegex = Regex("^(?:(?:pub|priv)\\s+)*\\$[a-zA-Z_]")

        for (rawLine in cleaned.lines()) {
            var codePart = stripCommentOutsideQuotes(rawLine)
            val trimmed = codePart.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("@dim") ||
                trimmed.startsWith("@unit_dim") ||
                trimmed.startsWith("@entry") ||
                typeDeclRegex.containsMatchIn(trimmed) ||
                syntaxBindingRegex.containsMatchIn(trimmed)
            ) {
                while (codePart.endsWith(";")) {
                    codePart = codePart.dropLast(1).trimEnd()
                }
            }
            if (codePart.isNotBlank()) {
                codeLines.add(codePart)
            }
        }

        val merged = mutableListOf<String>()
        var parenDepth = 0
        var braceDepth = 0
        var brackDepth = 0
        val contEndRegex = Regex("(:=|\\+=|-=|\\*=|/=|%=|\\^=|==|!=|<=|>=|&&|\\|\\||\\|>|=|\\+|-|\\*|/|\\^|\\?|:)$")
        val contStartRegex = Regex("^(\\|>|\\?|:|else\\b|or\\b|catch\\b)")

        fun updateDepths(segment: String) {
            var inQuotes = false
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                if (c == '\\' && inQuotes && i + 1 < segment.length) {
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                } else if (!inQuotes) {
                    when (c) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                        '[' -> brackDepth++
                        ']' -> if (brackDepth > 0) brackDepth--
                    }
                }
                i++
            }
        }

        for (line in codeLines) {
            val stripped = line.trim()
            if (merged.isNotEmpty() && parenDepth == 0 && braceDepth == 0 && brackDepth == 0) {
                val prev = merged.last().trimEnd()
                val prevStripped = prev.trim()
                val isPrevHeaderOrDep =
                    (prevStripped.startsWith("[") && prevStripped.endsWith("]")) ||
                    (prevStripped.startsWith("<[") && prevStripped.endsWith("]"))
                val isCurHeaderOrDep =
                    (stripped.startsWith("[") && stripped.endsWith("]")) ||
                    (stripped.startsWith("<[") && stripped.endsWith("]"))

                if (!isPrevHeaderOrDep && !isCurHeaderOrDep) {
                    val prevContinues = contEndRegex.containsMatchIn(prevStripped) &&
                        !prevStripped.endsWith(";") &&
                        !typeDeclRegex.containsMatchIn(prevStripped) &&
                        !prevStripped.startsWith("@dim") &&
                        !prevStripped.startsWith("@unit_dim") &&
                        !prevStripped.startsWith("@entry")
                    val curContinues = contStartRegex.containsMatchIn(stripped)
                    if (prevContinues || curContinues) {
                        merged[merged.lastIndex] = "$prev $stripped"
                        updateDepths(stripped)
                        continue
                    }
                }
            }
            merged.add(line)
            updateDepths(stripped)
        }

        val unitVarDeclRegex = Regex(
            "^(\\s*(?:(?:pub|priv|const|mut)\\s+)*)([a-zA-Z_][a-zA-Z0-9_]*)(\\s*:\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*,\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*(?::=|=)(?![=>]).*)$"
        )
        val reservedWords = setOf(
            "pub", "priv", "const", "mut", "if", "else", "while", "for", "in",
            "return", "break", "continue", "or", "try", "catch", "throw"
        )
        var dParen = 0
        var dBrace = 0
        var dBrack = 0
        val finalLines = merged.map { line ->
            val out = if (dParen == 0 && dBrace == 0 && dBrack == 0) {
                val m = unitVarDeclRegex.matchEntire(line)
                if (m != null && m.groupValues[2] !in reservedWords) {
                    "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
                } else {
                    line
                }
            } else {
                line
            }
            var inQ = false
            var idx = 0
            while (idx < line.length) {
                val ch = line[idx]
                if (ch == '\\' && inQ && idx + 1 < line.length) {
                    idx += 2
                    continue
                }
                if (ch == '\"') {
                    inQ = !inQ
                } else if (!inQ) {
                    when (ch) {
                        '(' -> dParen++
                        ')' -> if (dParen > 0) dParen--
                        '{' -> dBrace++
                        '}' -> if (dBrace > 0) dBrace--
                        '[' -> dBrack++
                        ']' -> if (dBrack > 0) dBrack--
                    }
                }
                idx++
            }
            out
        }

        return finalLines.joinToString("\n").trim()
    }

    @Synchronized
    private fun compileAndLoadPatchOnHandleSync(handle: Long, mathSource: String): Triple<Boolean, String, ByteArray?> {
        if (handle == 0L) return Triple(false, "Engine not initialized", null)
        registerHostExternsOnHandle(handle, CALC_PROJECT_NAME)
        val trimmed = normalizeExtensionSource(mathSource)
        if (trimmed.isEmpty()) return Triple(false, "Empty .math source", null)
        return try {
            val mathcBytes = MathEngineBridge.nativeCompileExt(handle, trimmed)
            if (mathcBytes == null || mathcBytes.isEmpty()) {
                val err = MathEngineBridge.nativeLastError(handle) ?: "Compilation failed"
                Triple(false, err, null)
            } else {
                Regex("(?m)^\\s*\\[([a-zA-Z0-9_]+)]").findAll(trimmed).forEach { m ->
                    val modName = m.groupValues[1]
                    MathEngineBridge.nativeSetModule(handle, modName, true)
                }
                Triple(
                    true,
                    "Compiled ${mathcBytes.size} bytes (.mathc) and linked into active engine.",
                    mathcBytes
                )
            }
        } catch (t: Throwable) {
            Triple(false, t.message ?: "Native exception", null)
        }
    }

    /**
     * Checks whether a top-level normalized statement is a declaration (`@dim`, `$type`,
     * operator/function definition, or module variable/constant binding) rather than a
     * standalone scratch expression. Used for declaration-only `<[dep]` dependency loading
     * and hybrid `pub main()` start-point detection.
     */
    internal fun isDeclarationOrBindingStatement(stmt: String): Boolean {
        val t = stmt.trim()
        if (t.isEmpty()) return true
        if ((t.startsWith("[") && t.endsWith("]")) || (t.startsWith("<[") && t.endsWith("]"))) return true
        if (t.startsWith("@dim") || t.startsWith("@unit_dim") || t.startsWith("@entry") || t.startsWith("import ")) return true
        val noMods = t.replace(Regex("^(?:(?:pub|priv|const|mut)\\s+)+"), "").trimStart()
        if (noMods.startsWith("$") || noMods.startsWith("struct ") || noMods.startsWith("enum ") || noMods.startsWith("\"")) {
            return true
        }
        if (noMods.startsWith("(") && Regex("^\\([^()]+\\)\\s*(?:\"[^\"]+\"|[a-zA-Z_][a-zA-Z0-9_]*)").containsMatchIn(noMods)) {
            return true
        }
        // Function or method declaration: name(...) = ...
        val fnDefRegex = Regex("^(?:\\$?fn\\s+)?[a-zA-Z_][a-zA-Z0-9_]*(?:::[a-zA-Z_][a-zA-Z0-9_]*)?\\s*\\([^]*\\)\\s*(?:->[^=]+|:[a-zA-Z0-9_,\\s<>]+)?(?::=|=)(?![=>])")
        if (fnDefRegex.containsMatchIn(noMods)) return true
        // Module variable/constant binding: name := ... or name = ... or name : type = ...
        val varDefRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(?:\\s*,\\s*[a-zA-Z_][a-zA-Z0-9_]*)*\\s*(?::\\s*[a-zA-Z_][a-zA-Z0-9_<>,\\s]*)?(?::=|=)(?![=>])")
        if (varDefRegex.containsMatchIn(noMods)) return true
        return false
    }

    /**
     * Splits a normalized `.math` module source into balanced top-level declarations,
     * headers, and statements so top-to-bottom execution can log every item's result.
     */
    internal fun splitModuleStatements(rawSource: String): List<String> {
        val normalized = normalizeExtensionSource(rawSource)
        if (normalized.isEmpty()) return emptyList()

        val statements = mutableListOf<String>()
        val currentStmt = StringBuilder()
        var braceDepth = 0
        var parenDepth = 0
        var brackDepth = 0

        for (rawLine in normalized.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (currentStmt.isNotEmpty()) currentStmt.append("\n")
            currentStmt.append(line)

            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (ch == '\\' && inQuotes && i + 1 < line.length) {
                    i += 2
                    continue
                }
                if (ch == '"') {
                    inQuotes = !inQuotes
                } else if (!inQuotes) {
                    when (ch) {
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '[' -> brackDepth++
                        ']' -> if (brackDepth > 0) brackDepth--
                    }
                }
                i++
            }
            if (braceDepth == 0 && parenDepth == 0 && brackDepth == 0) {
                var built = currentStmt.toString().trim()
                while (built.endsWith(";")) {
                    built = built.dropLast(1).trimEnd()
                }
                if (built.isNotEmpty()) {
                    statements.add(built)
                }
                currentStmt.setLength(0)
            }
        }
        if (currentStmt.isNotEmpty()) {
            var built = currentStmt.toString().trim()
            while (built.endsWith(";")) {
                built = built.dropLast(1).trimEnd()
            }
            if (built.isNotEmpty()) {
                statements.add(built)
            }
        }
        return statements
    }

    @Synchronized
    private fun formatModuleVariablesOnHandleSync(handle: Long, modName: String): String {
        if (handle == 0L || modName.isBlank()) return ""
        val raw = runCatching {
            MathEngineBridge.nativeModuleFormatVars(handle, modName)
        }.getOrNull() ?: return ""
        if (raw.isBlank()) return ""

        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { record ->
                val parts = record.split("|", limit = 6)
                if (parts.size < 6) null
                else {
                    val vName = parts[0]
                    val vType = parts[1]
                    val vUnit = parts[2]
                    val vMut = parts[3]
                    val vVis = parts[4]
                    val vVal = parts[5]
                    val unitBadge = if (vUnit != "none" && vUnit.isNotEmpty()) " [$vUnit]" else ""
                    val visPad = if (vVis == "priv") "priv" else "pub "
                    val mutPad = if (vMut == "const") "const" else "mut  "
                    "$visPad $mutPad $vName : $vType$unitBadge = $vVal"
                }
            }
            .joinToString("\n")
    }

    /** Destroy all engines when the process exits. */
    @Synchronized
    fun shutdown() {
        for ((_, st) in projectStates) {
            if (st.engineHandle != 0L) {
                runCatching { MathEngineBridge.nativeDestroy(st.engineHandle) }
                st.engineHandle = 0L
            }
            st.studioModuleSourcesByName.clear()
        }
        projectStates.clear()
        MathPatchLoader.resetTracking()
        worker.shutdown()
    }

    private fun findMatchingParen(s: String, openIdx: Int): Int {
        var depth = 0
        for (i in openIdx until s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return s.length - 1
    }

    private fun rewriteFunctionCalls(
        expr: String,
        fnNames: List<String>,
        replacer: (fn: String, inner: String) -> String
    ): String {
        val pattern = Regex("\\b(" + fnNames.joinToString("|") + ")\\(")
        var cur = expr
        var searchFrom = 0
        while (true) {
            val match = pattern.find(cur, searchFrom) ?: break
            val fn = match.groupValues[1]
            val openIdx = match.range.last
            val closeIdx = findMatchingParen(cur, openIdx)
            val inner = cur.substring(openIdx + 1, closeIdx)
            val innerTransformed = rewriteFunctionCalls(inner, fnNames, replacer)
            val replacement = replacer(fn, innerTransformed)
            cur = cur.substring(0, match.range.first) + replacement + cur.substring(closeIdx + 1)
            searchFrom = match.range.first + replacement.length
        }
        return cur
    }

    private fun replaceInfixNthRoot(expr: String): String {
        var cur = expr
        while (cur.contains("√(")) {
            val idx = cur.indexOf("√(")
            if (idx == 0) {
                cur = "__SQRT__(" + cur.substring(2)
                continue
            }
            val endN = idx
            var startN = endN - 1
            if (cur[startN] == ')') {
                var depth = 0
                while (startN >= 0) {
                    if (cur[startN] == ')') depth++
                    else if (cur[startN] == '(') {
                        depth--
                        if (depth == 0) break
                    }
                    startN--
                }
                if (startN < 0) startN = 0
            } else {
                while (startN >= 0 && (cur[startN].isDigit() || cur[startN] == '.')) {
                    startN--
                }
                startN++
            }
            if (startN == endN) {
                cur = cur.substring(0, idx) + "__SQRT__(" + cur.substring(idx + 2)
                continue
            }
            val nStr = cur.substring(startN, endN)
            val openP = idx + 1
            val closeP = findMatchingParen(cur, openP)
            val xStr = cur.substring(openP + 1, closeP)
            val replacement = "root($xStr,$nStr)"
            cur = cur.substring(0, startN) + replacement + cur.substring(closeP + 1)
        }
        return cur.replace("__SQRT__(", "√(")
    }

    /** Translates the calculator's display expression into engine dialect. */
    fun toEngineSyntax(
        displayExpr: String,
        isDegreeMode: Boolean = true,
        lastAnswer: String = "0.0"
    ): String {
        var e = displayExpr
            .replace(" x ", " * ").replace("( x ", "( * ").replace("^ x ", "^ * ")
            .replace("÷", "/").replace("×", "*")
            .replace("EE", "E")
            .replace("ⁿ√(", "root(")
            .replace("log₂(", "log2(")

        e = Regex("\\bans\\b").replace(e, "($lastAnswer)")

        var depth = 0
        for (ch in e) {
            when (ch) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
        }
        if (depth > 0) e += ")".repeat(depth)

        e = replaceInfixNthRoot(e)

        if (isDegreeMode) {
            e = rewriteFunctionCalls(
                e,
                listOf(
                    "sin", "cos", "tan", "sec", "csc", "cosec", "cot",
                    "asin", "acos", "atan", "asec", "acsc", "acosec", "acot"
                )
            ) { fn, inner ->
                if (fn.startsWith("a")) {
                    "rad2deg($fn($inner))"
                } else {
                    "$fn(deg2rad($inner))"
                }
            }
        }

        e = Regex("\\blog\\(").replace(e, "log10(")

        return e.trim()
    }

    /**
     * Stateless single-shot evaluation via `eng_eval` for the Basic Calculator keypad.
     *
     * Runs strictly in its own dedicated `project0` (`filesDir/math_projects/project0/`
     * with its own `engine.so` and patched `stdlib.mathc`), completely isolated from
     * `project1` and any other projects in the Freeform `.math` Studio.
     */
    fun evaluate(
        displayExpr: String,
        isDegreeMode: Boolean = true,
        lastAnswer: String = "0.0",
        onDone: (EvalOutcome) -> Unit
    ) {
        worker.execute {
            val ctx = appContext
            if (ctx == null || !ensureEngine()) {
                onDone(EvalOutcome(false, "0.0", "", "none", "Engine init failed"))
                return@execute
            }
            val engineExpr = toEngineSyntax(displayExpr, isDegreeMode, lastAnswer)

            synchronized(this) {
                val calcHandle = ensureProjectEngineSync(ctx, CALC_PROJECT_NAME, allowCalcProject0 = true)
                if (calcHandle == 0L) {
                    onDone(EvalOutcome(false, "0.0", "", "none", "project0 engine init failed"))
                    return@execute
                }

                runCatching { MathEngineBridge.nativeClearOutput(calcHandle) }
                val wire = try {
                    MathEngineBridge.nativeEval(calcHandle, engineExpr)
                } catch (t: Throwable) { null }
                val stdout = runCatching { MathEngineBridge.nativeGetOutput(calcHandle).orEmpty() }.getOrDefault("")

                if (wire == null) {
                    onDone(EvalOutcome(false, "0.0", "", "none", "Engine call failed", stdout = stdout))
                    return@execute
                }

                val res = MathEngineBridge.EvalResult.parseWire(wire)
                if (res.ok) {
                    onDone(EvalOutcome(true, res.value, res.type, res.unit, null, stdout = stdout))
                } else {
                    onDone(EvalOutcome(false, "0.0", "", "none", res.error, stdout = stdout))
                }
            }
        }
    }

    // =========================================================================
    //  Unified Top-to-Bottom & Hybrid Start Point (`pub main()` / `@entry`) API
    // =========================================================================

    /**
     * Resolves and loads any `<[dep]` modules from [projectFilesByModName] in topological
     * dependency order on [projectState]'s isolated engine in **declaration-only dependency mode**
     * (binding types, dimensions, functions, operators, and `pub`/`priv` variables/constants while
     * skipping standalone scratch expressions and the dependency's own `main()`).
     */
    @Synchronized
    private fun ensureProjectDependenciesSync(
        projectState: ProjectRuntimeState,
        targetModName: String,
        targetSource: String,
        projectFilesByModName: Map<String, String>,
        visited: MutableSet<String>
    ): String? {
        val handle = projectState.engineHandle
        if (handle == 0L) return "Project engine not initialized"
        visited.add(targetModName)
        val deps = extractModuleDependencies(targetSource)
        for (dep in deps) {
            if (dep == targetModName) {
                return "Circular module dependency involving '[$dep]'"
            }
            val depSource = projectFilesByModName[dep]
            if (depSource != null) {
                if (dep !in visited) {
                    val depErr = ensureProjectDependenciesSync(
                        projectState,
                        dep,
                        depSource,
                        projectFilesByModName,
                        visited
                    )
                    if (depErr != null) return depErr
                    val prevLoaded = projectState.studioModuleSourcesByName[dep]
                    if (prevLoaded != depSource) {
                        MathEngineBridge.nativeModuleReset(handle, dep)
                        // Filter dependency statements to declarations & variable bindings only
                        val declStmts = splitModuleStatements(depSource).filter { stmt ->
                            !stmt.trim().startsWith("@entry") && isDeclarationOrBindingStatement(stmt)
                        }
                        val declOnlySrc = declStmts.joinToString("\n")
                        if (declOnlySrc.isNotEmpty()) {
                            val wire = MathEngineBridge.nativeModuleEval(handle, dep, declOnlySrc)
                            val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                            if (!parsed.ok) {
                                return "Error in dependency '[$dep]': ${parsed.error}"
                            }
                            MathEngineBridge.nativeSetModule(handle, dep, true)
                            projectState.studioModuleSourcesByName[dep] = depSource
                        }
                    }
                }
            } else {
                // Check if dep is a loaded stdlib or global patch module in this project's engine
                val active = MathEngineBridge.nativeIsModuleActive(handle, dep)
                if (!active && !MathEngineBridge.nativeSetModule(handle, dep, true)) {
                    return "Unresolved module dependency '<[$dep]' required by '[$targetModName]' in project '${projectState.projectName}'"
                }
            }
        }
        return null
    }

    /**
     * Persists the cumulative `.mathc` image (`stdlib.mathc` + global patches + linked modules in [projectState])
     * into `filesDir/math_projects/<projectName>/stdlib.mathc` so each project's `stdlib.mathc`
     * reflects its linked modules without affecting any other project.
     */
    @Synchronized
    private fun persistProjectStdlibMathcSync(ctx: Context, projectState: ProjectRuntimeState) {
        val projDir = ensureProjectSetup(ctx, projectState.projectName, allowCalcProject0 = false)
        val stdlibFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
        val baseBytes = runCatching { getUniversalPatchedStdlibBytes(ctx) }.getOrNull() ?: return

        val tempHandle = MathEngineBridge.nativeCreateBlank().takeIf { it != 0L } ?: return
        try {
            if (!MathEngineBridge.nativeLoadMathc(tempHandle, baseBytes)) return
            registerHostExternsOnHandle(tempHandle, projectState.projectName)
            var latestMathc: ByteArray? = null
            for ((modName, rawSrc) in projectState.studioModuleSourcesByName) {
                val declStmts = splitModuleStatements(rawSrc).filter { stmt ->
                    !stmt.trim().startsWith("@entry") && isDeclarationOrBindingStatement(stmt)
                }
                val norm = declStmts.joinToString("\n").trim()
                if (norm.isNotEmpty()) {
                    val withHeader = if (Regex("(?m)^\\s*\\[[a-zA-Z_][a-zA-Z0-9_]*]").containsMatchIn(norm)) {
                        norm
                    } else {
                        "[$modName]\n$norm"
                    }
                    val bytes = runCatching { MathEngineBridge.nativeCompileExt(tempHandle, withHeader) }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        latestMathc = bytes
                    }
                }
            }
            if (latestMathc != null && latestMathc.isNotEmpty()) {
                runCatching { stdlibFile.writeBytes(latestMathc) }
            }
        } finally {
            runCatching { MathEngineBridge.nativeDestroy(tempHandle) }
        }
    }

    /**
     * Executes a `.math` module file inside [projectName]'s isolated engine:
     * - Automatically resolves any `<[dep]` sibling modules in declaration-only dependency mode.
     * - Executes top-level declarations and statements top-to-bottom.
     * - Supports the Hybrid Start Point: if the module declares `@entry(fn)` or defines a 0-arg
     *   `pub main()` / `main()` (without a trailing standalone expression), automatically invokes
     *   the entry function and logs its result.
     */
    fun runModuleFile(
        fileName: String?,
        mathSource: String,
        projectSourcesByFileName: Map<String, String> = emptyMap(),
        resetBeforeRun: Boolean = true,
        projectName: String = activeProjectName,
        onDone: (modName: String, results: List<Pair<String, EvalOutcome>>, varsSummary: String) -> Unit
    ) {
        val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
        worker.execute {
            val ctx = appContext
            if (ctx == null) {
                val fallbackMod = deriveModuleName(fileName, mathSource)
                onDone(
                    fallbackMod,
                    listOf("(init)" to EvalOutcome(false, "0.0", "void", "none", "App context not initialized")),
                    ""
                )
                return@execute
            }

            synchronized(this) {
                activeProjectName = safeProj
                var projHandle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (projHandle == 0L) {
                    val fallbackMod = deriveModuleName(fileName, mathSource)
                    onDone(
                        fallbackMod,
                        listOf("(init)" to EvalOutcome(false, "0.0", "void", "none", "Project '$safeProj' engine init failed")),
                        ""
                    )
                    return@execute
                }

                val projState = projectStates.getOrPut(safeProj) { ProjectRuntimeState(safeProj, projHandle) }
                registerHostExternsOnHandle(projHandle, safeProj)
                val primaryMod = deriveModuleName(fileName, mathSource)

                val hasTypeDecl = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$[a-zA-Z_]|struct\\s+[a-zA-Z_]|enum\\s+[a-zA-Z_])")
                    .containsMatchIn(mathSource)
                if (resetBeforeRun && hasTypeDecl && projState.studioModuleSourcesByName.containsKey(primaryMod)) {
                    projState.studioModuleSourcesByName.remove(primaryMod)
                    projHandle = rebuildProjectEngineFromBaselineSync(ctx, safeProj, allowCalcProject0 = false)
                }

                val projectByMod = LinkedHashMap<String, String>()
                for ((fName, fSrc) in projectSourcesByFileName) {
                    val mName = deriveModuleName(fName, fSrc)
                    projectByMod[mName] = fSrc
                }
                projectByMod[primaryMod] = mathSource

                val depErr = ensureProjectDependenciesSync(
                    projState,
                    primaryMod,
                    mathSource,
                    projectByMod,
                    mutableSetOf()
                )
                if (depErr != null) {
                    onDone(
                        primaryMod,
                        listOf("<[dep]" to EvalOutcome(false, "0.0", "void", "none", depErr)),
                        formatModuleVariablesOnHandleSync(projHandle, primaryMod)
                    )
                    return@execute
                }

                if (resetBeforeRun) {
                    runCatching { MathEngineBridge.nativeModuleReset(projHandle, primaryMod) }
                }

                val statementsWithLines = splitModuleStatementsWithLines(mathSource)
                val results = mutableListOf<Pair<String, EvalOutcome>>()
                var curMod = primaryMod
                val modHeaderRegex = Regex("^\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
                val entryDirectiveRegex = Regex("^@entry\\s*(?:\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)|([a-zA-Z_][a-zA-Z0-9_]*))$")
                var explicitEntryFn: String? = null
                var explicitEntryLine = 1
                var hadStandaloneExpr = false
                var allOk = true

                for ((stmtLine, stmt) in statementsWithLines) {
                    val trimmedStmt = stmt.trim()
                    val hdrMatch = modHeaderRegex.matchEntire(trimmedStmt)
                    if (hdrMatch != null) {
                        curMod = hdrMatch.groupValues[1]
                        if (resetBeforeRun && curMod != primaryMod) {
                            runCatching { MathEngineBridge.nativeModuleReset(projHandle, curMod) }
                        }
                        MathEngineBridge.nativeSetModule(projHandle, curMod, true)
                        continue
                    }

                    val entryMatch = entryDirectiveRegex.matchEntire(trimmedStmt)
                    if (entryMatch != null) {
                        explicitEntryFn = entryMatch.groupValues[1].ifEmpty { entryMatch.groupValues[2] }
                        explicitEntryLine = stmtLine
                        results.add(trimmedStmt to EvalOutcome(true, "void", "void", "none", null, sourceLine = stmtLine))
                        continue
                    }

                    if (!isDeclarationOrBindingStatement(trimmedStmt)) {
                        hadStandaloneExpr = true
                    }

                    // Note: If the statement is `pub main() = ...`, evaluating it in single-statement
                    // mode on the C engine would auto-invoke `main()` immediately on that line unless
                    // we let the end-of-module hook invoke `main()` after all declarations are bound.
                    val isMainFnDecl = Regex("^(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?main\\s*\\(\\s*\\)").containsMatchIn(trimmedStmt)
                    runCatching { MathEngineBridge.nativeClearOutput(projHandle) }
                    val evalPayload = if (isMainFnDecl) "$trimmedStmt ; 0" else stmt
                    val wire = try {
                        MathEngineBridge.nativeModuleEval(projHandle, curMod, evalPayload)
                    } catch (t: Throwable) {
                        "ERR|${t.message ?: "Native exception"}"
                    }
                    val stdout = runCatching { MathEngineBridge.nativeGetOutput(projHandle).orEmpty() }.getOrDefault("")

                    val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                    val outcome = if (parsed.ok) {
                        if (isMainFnDecl) {
                            EvalOutcome(true, "void", "void", "none", null, stdout = stdout, sourceLine = stmtLine)
                        } else {
                            EvalOutcome(true, parsed.value, parsed.type, parsed.unit, null, stdout = stdout, sourceLine = stmtLine)
                        }
                    } else {
                        allOk = false
                        EvalOutcome(false, "0.0", "void", "none", parsed.error, stdout = stdout, sourceLine = stmtLine)
                    }
                    results.add(stmt to outcome)
                    if (!parsed.ok) break
                }

                // Hybrid Start Point: Invoke `@entry(fn)` or `pub main()` / `main()` if no standalone expression ran
                if (allOk) {
                    val (detectedEntry, isExplicit) = detectModuleEntryPoint(mathSource)
                    val targetEntryFn = explicitEntryFn ?: detectedEntry
                    val shouldRunEntry = targetEntryFn != null && (explicitEntryFn != null || isExplicit || !hadStandaloneExpr)
                    if (shouldRunEntry && targetEntryFn != null) {
                        runCatching { MathEngineBridge.nativeClearOutput(projHandle) }
                        val entryCall = "$targetEntryFn()"
                        val wire = try {
                            MathEngineBridge.nativeModuleEval(projHandle, curMod, entryCall)
                        } catch (t: Throwable) {
                            "ERR|${t.message ?: "Native exception"}"
                        }
                        val stdout = runCatching { MathEngineBridge.nativeGetOutput(projHandle).orEmpty() }.getOrDefault("")
                        val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                        val entryOutcome = if (parsed.ok) {
                            EvalOutcome(true, parsed.value, parsed.type, parsed.unit, null, stdout = stdout, sourceLine = explicitEntryLine)
                        } else {
                            allOk = false
                            EvalOutcome(false, "0.0", "void", "none", parsed.error, stdout = stdout, sourceLine = explicitEntryLine)
                        }
                        results.add("▶ @entry $entryCall" to entryOutcome)
                    }
                }

                MathEngineBridge.nativeSetModule(projHandle, curMod, true)
                if (allOk) {
                    projState.studioModuleSourcesByName[primaryMod] = mathSource
                    persistProjectStdlibMathcSync(ctx, projState)
                }
                val varsSummary = formatModuleVariablesOnHandleSync(projHandle, curMod)
                onDone(curMod, results, varsSummary)
            }
        }
    }

    /** Resets all runtime variables, constants, and overloads in [modName] inside [projectName]. */
    fun resetModule(
        modName: String,
        projectName: String = activeProjectName,
        onDone: (ok: Boolean) -> Unit
    ) {
        val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
        worker.execute {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone(false)
                return@execute
            }
            val ok = synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (handle == 0L) {
                    false
                } else {
                    val st = projectStates[safeProj]
                    st?.studioModuleSourcesByName?.remove(modName)
                    val r = runCatching {
                        MathEngineBridge.nativeModuleReset(handle, modName)
                    }.getOrDefault(false)
                    if (st != null) {
                        persistProjectStdlibMathcSync(ctx, st)
                    }
                    r
                }
            }
            onDone(ok)
        }
    }

    /** Inspects all variables and constants currently bound in [modName] inside [projectName]. */
    fun inspectModuleVariables(
        modName: String,
        projectName: String = activeProjectName,
        onDone: (summary: String) -> Unit
    ) {
        val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
        worker.execute {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone("")
                return@execute
            }
            synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                onDone(formatModuleVariablesOnHandleSync(handle, modName))
            }
        }
    }

    /** Exports [modName]'s current variables and constants in [projectName] as reproducible `.math` module source. */
    fun exportModuleSource(
        modName: String,
        projectName: String = activeProjectName,
        onDone: (source: String) -> Unit
    ) {
        val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
        worker.execute {
            val ctx = appContext
            if (ctx == null || modName.isBlank()) {
                onDone("")
                return@execute
            }
            val src = synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (handle == 0L) ""
                else runCatching {
                    MathEngineBridge.nativeModuleExportSource(handle, modName) ?: ""
                }.getOrDefault("")
            }
            onDone(src)
        }
    }

    internal fun splitModuleStatementsWithLines(rawSource: String): List<Pair<Int, String>> {
        val cleaned = rawSource.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        val rawLines = cleaned.lines()
        val codeLinesWithNum = mutableListOf<Pair<Int, String>>()
        val syntaxBindingRegex = Regex("^(?:(?:pub|priv)\\s+)*\"[^\"]+\"\\s*=>\\s*@")
        val typeDeclRegex = Regex("^(?:(?:pub|priv)\\s+)*\\$[a-zA-Z_]")

        for ((idx, rawLine) in rawLines.withIndex()) {
            var codePart = stripCommentOutsideQuotes(rawLine)
            val trimmed = codePart.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("@dim") ||
                trimmed.startsWith("@unit_dim") ||
                trimmed.startsWith("@entry") ||
                typeDeclRegex.containsMatchIn(trimmed) ||
                syntaxBindingRegex.containsMatchIn(trimmed)
            ) {
                while (codePart.endsWith(";")) {
                    codePart = codePart.dropLast(1).trimEnd()
                }
            }
            if (codePart.isNotBlank()) {
                codeLinesWithNum.add((idx + 1) to codePart)
            }
        }

        val merged = mutableListOf<Pair<Int, String>>()
        var parenDepth = 0
        var braceDepth = 0
        var brackDepth = 0
        val contEndRegex = Regex("(:=|\\+=|-=|\\*=|/=|%=|\\^=|==|!=|<=|>=|&&|\\|\\||\\|>|=|\\+|-|\\*|/|\\^|\\?|:)$")
        val contStartRegex = Regex("^(\\||\\?|:|else\\b|or\\b|catch\\b)")

        fun updateDepths(segment: String) {
            var inQuotes = false
            var i = 0
            while (i < segment.length) {
                val c = segment[i]
                if (c == '\\' && inQuotes && i + 1 < segment.length) {
                    i += 2
                    continue
                }
                if (c == '"') {
                    inQuotes = !inQuotes
                } else if (!inQuotes) {
                    when (c) {
                        '(' -> parenDepth++
                        ')' -> if (parenDepth > 0) parenDepth--
                        '{' -> braceDepth++
                        '}' -> if (braceDepth > 0) braceDepth--
                        '[' -> brackDepth++
                        ']' -> if (brackDepth > 0) brackDepth--
                    }
                }
                i++
            }
        }

        for ((lineNum, line) in codeLinesWithNum) {
            val stripped = line.trim()
            if (merged.isNotEmpty() && parenDepth == 0 && braceDepth == 0 && brackDepth == 0) {
                val (prevLineNum, prevText) = merged.last()
                val prev = prevText.trimEnd()
                val prevStripped = prev.trim()
                val isPrevHeaderOrDep =
                    (prevStripped.startsWith("[") && prevStripped.endsWith("]")) ||
                    (prevStripped.startsWith("<[") && prevStripped.endsWith("]"))
                val isCurHeaderOrDep =
                    (stripped.startsWith("[") && stripped.endsWith("]")) ||
                    (stripped.startsWith("<[") && stripped.endsWith("]"))

                if (!isPrevHeaderOrDep && !isCurHeaderOrDep) {
                    val prevContinues = contEndRegex.containsMatchIn(prevStripped) &&
                        !prevStripped.endsWith(";") &&
                        !typeDeclRegex.containsMatchIn(prevStripped) &&
                        !prevStripped.startsWith("@dim") &&
                        !prevStripped.startsWith("@unit_dim") &&
                        !prevStripped.startsWith("@entry")
                    val curContinues = contStartRegex.containsMatchIn(stripped)
                    if (prevContinues || curContinues) {
                        merged[merged.lastIndex] = prevLineNum to "$prev $stripped"
                        updateDepths(stripped)
                        continue
                    }
                }
            }
            merged.add(lineNum to line)
            updateDepths(stripped)
        }

        val statements = mutableListOf<Pair<Int, String>>()
        val currentStmt = StringBuilder()
        var currentStartLine = 1
        var bDepth = 0
        var pDepth = 0
        var kDepth = 0

        for ((lineNum, rawLine) in merged) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (currentStmt.isEmpty()) {
                currentStartLine = lineNum
            } else {
                currentStmt.append("\n")
            }
            currentStmt.append(line)

            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (ch == '\\' && i + 1 < line.length && inQuotes) {
                    i += 2
                    continue
                }
                if (ch == '"') {
                    inQuotes = !inQuotes
                } else if (!inQuotes) {
                    when (ch) {
                        '{' -> bDepth++
                        '}' -> if (bDepth > 0) bDepth--
                        '(' -> pDepth++
                        ')' -> if (pDepth > 0) pDepth--
                        '[' -> kDepth++
                        ']' -> if (kDepth > 0) kDepth--
                    }
                }
                i++
            }
            if (bDepth == 0 && pDepth == 0 && kDepth == 0) {
                val norm = normalizeExtensionSource(currentStmt.toString())
                var built = norm.trim()
                while (built.endsWith(";")) {
                    built = built.dropLast(1).trimEnd()
                }
                if (built.isNotEmpty()) {
                    statements.add(currentStartLine to built)
                }
                currentStmt.setLength(0)
            }
        }
        if (currentStmt.isNotEmpty()) {
            val norm = normalizeExtensionSource(currentStmt.toString())
            var built = norm.trim()
            while (built.endsWith(";")) {
                built = built.dropLast(1).trimEnd()
            }
            if (built.isNotEmpty()) {
                statements.add(currentStartLine to built)
            }
        }
        return statements
    }

    /** Disassembles a function or expression inside [modName] in [projectName] using `bc_disasm(...)`. */
    fun disassembleInModule(
        modName: String,
        targetExpr: String,
        projectName: String = activeProjectName,
        onDone: (ok: Boolean, disasmText: String) -> Unit
    ) {
        val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
        worker.execute {
            val ctx = appContext
            if (ctx == null || targetExpr.isBlank()) {
                onDone(false, "Engine not ready or empty target")
                return@execute
            }
            val effMod = modName.ifBlank { "default" }
            synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (handle == 0L) {
                    onDone(false, "Project '$safeProj' engine not ready")
                    return@execute
                }
                registerHostExternsOnHandle(handle, safeProj)
                val wire = runCatching {
                    MathEngineBridge.nativeModuleEval(handle, effMod, "bc_disasm(${targetExpr.trim()})")
                }.getOrDefault("ERR|Native call failed")
                val parsed = MathEngineBridge.EvalResult.parseWire(wire)
                if (parsed.ok) {
                    val cleaned = parsed.value.removeSurrounding("\"")
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                    onDone(true, cleaned)
                } else {
                    onDone(false, parsed.error ?: "Could not disassemble '$targetExpr'")
                }
            }
        }
    }

    /**
     * Evaluates a 1D vector, `vec<vec2>` path, 1-arg function `f`, or expression in `x`/`t`
     * inside [modName] in [projectName] to produce `(x, y)` coordinates for the interactive 2D Plotter.
     */
    fun samplePlotPoints(
        modName: String,
        exprOrFn: String,
        xMin: Double,
        xMax: Double,
        samples: Int = 160,
        projectName: String = activeProjectName,
        onDone: (ok: Boolean, label: String, points: List<Pair<Double, Double>>, error: String?) -> Unit
    ) {
        val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
        worker.execute {
            val ctx = appContext
            if (ctx == null || exprOrFn.isBlank()) {
                onDone(false, exprOrFn, emptyList(), "Empty plot expression")
                return@execute
            }
            val effMod = modName.ifBlank { "default" }
            val trimmed = exprOrFn.trim()
            synchronized(this) {
                val handle = ensureProjectEngineSync(ctx, safeProj, allowCalcProject0 = false)
                if (handle == 0L) {
                    onDone(false, exprOrFn, emptyList(), "Project '$safeProj' engine not ready")
                    return@execute
                }
                registerHostExternsOnHandle(handle, safeProj)
                val directWire = runCatching {
                    MathEngineBridge.nativeModuleEval(handle, effMod, trimmed)
                }.getOrNull()
                val directRes = directWire?.let { MathEngineBridge.EvalResult.parseWire(it) }

                if (directRes != null && directRes.ok && (directRes.type == "vec" || directRes.type == "mat")) {
                    val rawVal = directRes.value
                    val vec2Regex = Regex("vec2\\(\\s*([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\s*,\\s*([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\s*\\)")
                    val v2Matches = vec2Regex.findAll(rawVal).toList()
                    if (v2Matches.isNotEmpty()) {
                        val pts = v2Matches.mapNotNull { m ->
                            val px = m.groupValues[1].toDoubleOrNull()
                            val py = m.groupValues[2].toDoubleOrNull()
                            if (px != null && py != null && px.isFinite() && py.isFinite()) px to py else null
                        }
                        if (pts.isNotEmpty()) {
                            onDone(true, "$trimmed (vec2 path)", pts, null)
                            return@execute
                        }
                    }
                    val numRegex = Regex("[-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?")
                    val nums = numRegex.findAll(rawVal).mapNotNull { it.value.toDoubleOrNull() }.filter { it.isFinite() }.toList()
                    if (nums.isNotEmpty()) {
                        val pts = nums.mapIndexed { idx, y -> idx.toDouble() to y }
                        onDone(true, "$trimmed (${nums.size} elements)", pts, null)
                        return@execute
                    }
                }

                val lo = if (xMin < xMax) xMin else xMax - 1.0
                val hi = if (xMax > xMin) xMax else xMin + 10.0
                val n = samples.coerceIn(16, 400)
                val step = (hi - lo) / (n - 1).toDouble()
                val pts = ArrayList<Pair<Double, Double>>(n)

                val isCallableIdentifier = (directRes != null && directRes.ok && directRes.type == "fn") ||
                    Regex("^[a-zA-Z_][a-zA-Z0-9_]*$").matches(trimmed)

                var useCallSyntax = false
                if (isCallableIdentifier) {
                    val probeWire = runCatching {
                        MathEngineBridge.nativeModuleEval(handle, effMod, "$trimmed($lo)")
                    }.getOrNull()
                    val probeRes = probeWire?.let { MathEngineBridge.EvalResult.parseWire(it) }
                    if (probeRes != null && probeRes.ok) {
                        useCallSyntax = true
                    }
                }

                var lastErr: String? = null
                for (i in 0 until n) {
                    val x = lo + i * step
                    val evalExpr = if (useCallSyntax) {
                        "$trimmed($x)"
                    } else {
                        "{ x := $x ; t := $x ; $trimmed }"
                    }
                    val w = runCatching {
                        MathEngineBridge.nativeModuleEval(handle, effMod, evalExpr)
                    }.getOrNull()
                    val r = w?.let { MathEngineBridge.EvalResult.parseWire(it) }
                    if (r != null && r.ok) {
                        val numToken = r.value.trim().split(Regex("\\s+")).firstOrNull()
                        val y = numToken?.toDoubleOrNull()
                        if (y != null && y.isFinite()) {
                            pts.add(x to y)
                        }
                    } else if (r != null && !r.ok && lastErr == null) {
                        lastErr = r.error
                    }
                }

                if (pts.isNotEmpty()) {
                    val title = if (useCallSyntax) "$trimmed(x)" else trimmed
                    onDone(true, title, pts, null)
                } else {
                    onDone(false, trimmed, emptyList(), lastErr ?: "Expression did not produce finite numeric values over [$lo, $hi]")
                }
            }
        }
    }
}
