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
 * Global Patch Propagation (`stdlib.mathc` + `math_patches/`), Project-wide Entry Execution,
 * and Top-to-Bottom Individual Module Execution.
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

    /** Describes a project-level `main()` or `@entry(fn)` entry point located in any `.math` file of a project. */
    data class ProjectEntryPoint(
        val fileName: String,
        val modName: String,
        val entryFn: String,
        val isExplicitDirective: Boolean,
        val sourceLine: Int = 1
    )

    internal class ProjectRuntimeState(
        val projectName: String,
        var engineHandle: Long = 0L,
        val studioModuleSourcesByName: LinkedHashMap<String, String> = linkedMapOf()
    )

    internal const val TAG = "MathEngineManager"
    internal const val BUNDLED_STDLIB_ASSET = "precompiled/stdlib.mathc"
    internal const val NOOP_TRAILING_MODULE = "noopMod"

    /** `project0` is dedicated exclusively to the Basic Calculator. */
    const val CALC_PROJECT_NAME = "project0"

    /** `project1` is the default project in the Freeform `.math` Studio with all demos & examples. */
    const val DEFAULT_STUDIO_PROJECT_NAME = "project1"

    /** Alias kept for callers referencing the default Studio project. */
    const val DEFAULT_PROJECT_NAME = DEFAULT_STUDIO_PROJECT_NAME

    const val PROJECTS_ROOT_FOLDER = "math_projects"
    const val PROJECT_ENGINE_SO_NAME = "engine.so"
    const val PROJECT_STDLIB_MATHC_NAME = "stdlib.mathc"
    internal const val PROJECT_BASE_STDLIB_BACKUP = ".base_stdlib.mathc"

    // Precompiled regexes (validated for Android/Java Pattern syntax)
    internal val sanitizeNameRegex = Regex("[^a-zA-Z0-9_\\-]")
    internal val sanitizeIdentRegex = Regex("[^a-zA-Z0-9_]")
    internal val modHeaderSingleLineRegex = Regex("^\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
    internal val modHeaderMultilineRegex = Regex("(?m)^\\s*\\[([a-zA-Z_][a-zA-Z0-9_]*)]")
    internal val depHeaderSingleLineRegex = Regex("^<\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
    internal val explicitEntryRegex = Regex("^@entry\\s*(?:\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)|([a-zA-Z_][a-zA-Z0-9_]*))$")
    internal val mainDeclRegex = Regex("^(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?main\\s*\\(\\s*\\)\\s*(?:->[^=]+|:[a-zA-Z0-9_,\\s<>]+)?(?::=|=)(?![=>])")
    internal val syntaxBindingRegex = Regex("^(?:(?:pub|priv)\\s+)*\"[^\"]+\"\\s*=>\\s*@")
    internal val typeDeclRegex = Regex("^(?:(?:pub|priv)\\s+)*\\$[a-zA-Z_]")
    internal val anyTypeOrStructDeclRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$[a-zA-Z_]|struct\\s+[a-zA-Z_])")
    internal val contEndRegex = Regex("(:=|\\+=|-=|\\*=|/=|%=|\\^=|==|!=|<=|>=|&&|\\|\\||\\|>|=|\\+|-|\\*|/|\\^|\\?|:)$")
    internal val contStartRegex = Regex("^(\\|>|\\?|:|else\\b|or\\b|catch\\b)")
    internal val unitVarDeclRegex = Regex(
        "^(\\s*(?:(?:pub|priv|const|mut)\\s+)*)([a-zA-Z_][a-zA-Z0-9_]*)(\\s*:\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*,\\s*[a-zA-Z_][a-zA-Z0-9_]*\\s*(?::=|=)(?![=>]).*)$"
    )
    internal val declModifiersRegex = Regex("^(?:(?:pub|priv|const|mut)\\s+)+")
    internal val opDefPrefixRegex = Regex("^\\([^()]+\\)\\s*(?:\"[^\"]+\"|[a-zA-Z_][a-zA-Z0-9_]*)")
    internal val fnDefRegex = Regex("^(?:\\$?fn\\s+)?[a-zA-Z_][a-zA-Z0-9_]*(?:::[a-zA-Z_][a-zA-Z0-9_]*)?\\s*\\([^()\"]*\\)\\s*(?:->[^=]+|:[a-zA-Z0-9_,\\s<>]+)?(?::=|=)(?![=>])")
    internal val varDefRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(?:\\s*,\\s*[a-zA-Z_][a-zA-Z0-9_]*)*\\s*(?::\\s*[a-zA-Z_][a-zA-Z0-9_<>,\\s]*)?(?::=|=)(?![=>])")
    internal val lambdaExprRegex = Regex("^\\(?\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\)?\\s*=>\\s*(.+)$", setOf(RegexOption.DOT_MATCHES_ALL))
    internal val inlineFnDefExtractRegex = Regex("^(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()\"]*)\\)\\s*(?:->[^=]+|:[a-zA-Z0-9_,\\s<>]+)?(?::=|=)(?![=>])\\s*(.+)$", setOf(RegexOption.DOT_MATCHES_ALL))
    internal val simpleIdentRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

    @Volatile
    internal var appContext: Context? = null

    /** Per-project isolated runtime state and native `Engine*` handles (includes `project0` and Studio projects). */
    internal val projectStates = linkedMapOf<String, ProjectRuntimeState>()

    /** Currently active project in the freeform `.math` Studio (defaults to `project1`). */
    @Volatile
    var activeProjectName: String = DEFAULT_STUDIO_PROJECT_NAME

    internal val worker = Executors.newSingleThreadExecutor()
    internal val customExternCallbacks = linkedMapOf<String, MathEngineBridge.ExternCallback>()

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
            .replace(sanitizeNameRegex, "_")
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
    internal fun getUniversalPatchedStdlibBytes(ctx: Context): ByteArray {
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
    internal fun copyNativeEngineSoToProject(ctx: Context, destEngineSo: File): Boolean {
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
            try {
                val ok = synchronized(this) {
                    ensureProjectSetup(ctx, safeName, allowCalcProject0 = false)
                    ensureProjectEngineSync(ctx, safeName, allowCalcProject0 = false) != 0L
                }
                onDone?.invoke(ok, safeName)
            } catch (t: Throwable) {
                Log.e(TAG, "setActiveProject failed for '$safeName'", t)
                onDone?.invoke(false, safeName)
            }
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
            try {
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
            } catch (t: Throwable) {
                Log.e(TAG, "resetProjectEngine failed for '$safeName'", t)
                onDone?.invoke(false)
            }
        }
    }

    /** Registers all built-in host externs ([CurrencyConverter]) and any custom app externs on [handle]. */
    @Synchronized
    internal fun registerHostExternsOnHandle(handle: Long, projectName: String) {
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

    internal fun stripCommentOutsideQuotes(line: String): String {
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
        for (rawLine in rawSource.lineSequence()) {
            val trimmed = stripCommentOutsideQuotes(rawLine).trim()
            if (trimmed.isEmpty()) continue
            val m = modHeaderSingleLineRegex.matchEntire(trimmed)
            if (m != null) {
                return m.groupValues[1]
            }
        }
        val stem = (fileName ?: "workspace")
            .removeSuffix(".math")
            .replace(sanitizeIdentRegex, "_")
            .trim('_')
        if (stem.isEmpty()) return "workspace"
        return if (stem[0].isDigit()) "mod_$stem" else stem
    }

    /** Extracts all `<[dep]` module names referenced in [rawSource]. */
    fun extractModuleDependencies(rawSource: String): List<String> {
        val deps = LinkedHashSet<String>()
        for (rawLine in rawSource.lineSequence()) {
            val trimmed = stripCommentOutsideQuotes(rawLine).trim()
            if (trimmed.isEmpty()) continue
            depHeaderSingleLineRegex.matchEntire(trimmed)?.let { m ->
                deps.add(m.groupValues[1])
            }
        }
        return deps.toList()
    }

    /**
     * Extracts an explicit `@entry(fn_name)` / `@entry fn_name` directive or detects a 0-arg
     * `pub main()` / `main()` definition in [rawSource].
     */
    fun detectModuleEntryPoint(rawSource: String): Pair<String?, Boolean> {
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
        for ((_, stmt) in splitModuleStatementsWithLines(rawSource)) {
            val trimmed = stmt.trim()
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

    /**
     * Scans all `.math` files in a project ([projectSourcesByFileName]) to locate a project-level
     * `@entry(fn)` directive or a 0-arg `pub main()` / `main()` function located anywhere in any file.
     *
     * Priority order:
     * 1. Explicit `@entry(fn)` directive (preferring [preferredActiveFile] if multiple files declare `@entry`).
     * 2. 0-arg `pub main()` / `main()` function (preferring `main.math`, then [preferredActiveFile], then sorted file order).
     * 3. Returns `null` if no `main` or `@entry` exists anywhere in the project.
     */
    fun findProjectEntryPoint(
        projectSourcesByFileName: Map<String, String>,
        preferredActiveFile: String? = null
    ): ProjectEntryPoint? {
        if (projectSourcesByFileName.isEmpty()) return null
        val explicitCandidates = mutableListOf<ProjectEntryPoint>()
        val mainCandidates = mutableListOf<ProjectEntryPoint>()

        val sortedEntries = projectSourcesByFileName.entries
            .filter { it.key.endsWith(".math", ignoreCase = true) || !it.key.contains('.') }
            .sortedWith(
                compareBy<Map.Entry<String, String>> { entry ->
                    when {
                        entry.key.equals("main.math", ignoreCase = true) -> 0
                        preferredActiveFile != null && entry.key.equals(preferredActiveFile, ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenBy { it.key.lowercase() }
            )

        for ((fileName, rawSource) in sortedEntries) {
            var curMod = deriveModuleName(fileName, rawSource)
            val stmts = splitModuleStatementsWithLines(rawSource)
            var fileExplicitAdded = false
            var fileMainAdded = false
            for ((lineNum, stmt) in stmts) {
                val trimmed = stmt.trim()
                val hdrMatch = modHeaderSingleLineRegex.matchEntire(trimmed)
                if (hdrMatch != null) {
                    curMod = hdrMatch.groupValues[1]
                    continue
                }
                val em = explicitEntryRegex.matchEntire(trimmed)
                if (em != null && !fileExplicitAdded) {
                    val fn = em.groupValues[1].ifEmpty { em.groupValues[2] }
                    if (fn.isNotBlank()) {
                        explicitCandidates.add(
                            ProjectEntryPoint(
                                fileName = fileName,
                                modName = curMod,
                                entryFn = fn,
                                isExplicitDirective = true,
                                sourceLine = lineNum
                            )
                        )
                        fileExplicitAdded = true
                    }
                }
                if (mainDeclRegex.containsMatchIn(trimmed) && !fileMainAdded) {
                    mainCandidates.add(
                        ProjectEntryPoint(
                            fileName = fileName,
                            modName = curMod,
                            entryFn = "main",
                            isExplicitDirective = false,
                            sourceLine = lineNum
                        )
                    )
                    fileMainAdded = true
                }
            }
        }

        if (explicitCandidates.isNotEmpty()) {
            if (preferredActiveFile != null) {
                explicitCandidates.firstOrNull { it.fileName.equals(preferredActiveFile, ignoreCase = true) }?.let { return it }
            }
            return explicitCandidates.first()
        }
        if (mainCandidates.isNotEmpty()) {
            if (preferredActiveFile != null) {
                mainCandidates.firstOrNull { it.fileName.equals(preferredActiveFile, ignoreCase = true) }?.let { return it }
            }
            return mainCandidates.first()
        }
        return null
    }

    /**
     * Reads all `.math` files in [projectName] from disk (overlaying [activeFileName] -> [activeEditorSource]
     * if provided) and returns the discovered [ProjectEntryPoint], or `null` if no `main`/`@entry` exists.
     */
    fun findProjectEntryPointOnDisk(
        ctx: Context,
        projectName: String,
        activeFileName: String? = null,
        activeEditorSource: String? = null
    ): ProjectEntryPoint? {
        val safeProj = sanitizeProjectName(projectName, allowCalcProject0 = false)
        val projDir = getProjectDir(ctx, safeProj, allowCalcProject0 = false)
        val sources = LinkedHashMap<String, String>()
        projDir.listFiles { f ->
            f.isFile && f.name.endsWith(".math", ignoreCase = true) && !f.name.startsWith(".")
        }?.sortedBy { it.name.lowercase() }?.forEach { file ->
            runCatching { sources[file.name] = file.readText() }
        }
        if (!activeFileName.isNullOrBlank() && activeEditorSource != null && safeProj == activeProjectName) {
            sources[activeFileName] = activeEditorSource
        }
        return findProjectEntryPoint(sources, preferredActiveFile = activeFileName)
    }

    /** Creates a blank engine loaded strictly with `assets/precompiled/stdlib.mathc` and registers host externs. */
    internal fun createFreshStdlibEngine(ctx: Context, projectName: String = CALC_PROJECT_NAME): Long {
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
     * Evaluates [payload] inside [modName] while appending a trailing `[noopMod]` module switch
     * so the C compiler's end-of-slice hook (`ext_eval_slice_ex` Step 6) never auto-invokes
     * `main()` prematurely during top-to-bottom or declaration-only evaluation.
     */
    internal fun evalInModuleWithoutAutoMain(handle: Long, modName: String, payload: String): String {
        val wire = MathEngineBridge.nativeModuleEval(handle, modName, payload)
        MathEngineBridge.nativeSetModule(handle, modName, true)
        return wire ?: "ERR|Native module eval returned null"
    }

    /**
     * Boots or returns the isolated `Engine*` handle for [projectName] (`project0` for Basic Calculator,
     * `project1`+ for Freeform Studio), loading the universal patched `stdlib.mathc` baseline.
     */
    @Synchronized
    internal fun ensureProjectEngineSync(
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

        val stdlibFile = File(projDir, PROJECT_STDLIB_MATHC_NAME)
        val baseBackupFile = File(projDir, PROJECT_BASE_STDLIB_BACKUP)

        val handle = MathEngineBridge.nativeCreateBlank().takeIf { it != 0L }
            ?: MathEngineBridge.nativeCreate().takeIf { it != 0L }
            ?: return 0L

        // Always boot from the clean universal patched stdlib baseline so runtime module variables
        // (which are not serialized in .mathc bytecode images) are cleanly evaluated on demand.
        val cleanBytes = runCatching {
            getUniversalPatchedStdlibBytes(ctx)
        }.getOrNull()

        var loaded = false
        if (cleanBytes != null && cleanBytes.isNotEmpty()) {
            loaded = MathEngineBridge.nativeLoadMathc(handle, cleanBytes)
            if (loaded) {
                runCatching {
                    if (!baseBackupFile.exists() || baseBackupFile.length() != cleanBytes.size.toLong()) {
                        baseBackupFile.writeBytes(cleanBytes)
                    }
                    if (!stdlibFile.exists() || stdlibFile.length() == 0L) {
                        stdlibFile.writeBytes(cleanBytes)
                    }
                }
            }
        }
        if (!loaded) {
            val fallbackBytes = runCatching {
                if (stdlibFile.isFile && stdlibFile.length() > 0L) stdlibFile.readBytes() else null
            }.getOrNull()
            if (fallbackBytes != null && fallbackBytes.isNotEmpty()) {
                loaded = MathEngineBridge.nativeLoadMathc(handle, fallbackBytes)
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
     * in `state.studioModuleSourcesByName`.
     */
    @Synchronized
    internal fun rebuildProjectEngineFromBaselineSync(
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

        for ((modName, src) in state.studioModuleSourcesByName) {
            val declStmts = splitModuleStatements(src).filter { stmt ->
                !stmt.trim().startsWith("@entry") && isDeclarationOrBindingStatement(stmt)
            }
            val declOnlySrc = declStmts.joinToString("\n")
            if (declOnlySrc.isNotEmpty()) {
                runCatching { evalInModuleWithoutAutoMain(newHandle, modName, declOnlySrc) }
                runCatching { MathEngineBridge.nativeSetModule(newHandle, modName, true) }
            }
        }
        return newHandle
    }

    /**
     * Propagates the universal patched `stdlib.mathc` (`assets/precompiled/stdlib.mathc` + all
     * `math_patches/`) to `project0` (Basic Calculator) and all Studio projects (`project1`, ...),
     * ensuring patches are visible everywhere as extensions of `stdlib`.
     */
    @Synchronized
    internal fun propagatePatchedStdlibToAllProjectsSync(ctx: Context) {
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
    internal fun rebuildCalcProject0AndPatchesSync(
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
            try {
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
            } catch (t: Throwable) {
                Log.e(TAG, "loadPendingPatches failed", t)
                onDone?.invoke(0)
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
            val downloaded = runCatching { MathPatchDownloader.syncMissingPatches(app, onStatus) }.getOrDefault(0)
            loadPendingPatches(
                onDone = { loaded ->
                    onDone?.invoke(downloaded, loaded)
                },
                onStatus = onStatus
            )
        }.start()
    }

}
