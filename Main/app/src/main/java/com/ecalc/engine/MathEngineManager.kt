package com.ecalc

import android.content.Context
import android.util.Log
import com.mathengine.MathEngineBridge
import java.io.File
import java.util.concurrent.Executors

/**
 * App-side manager for the C11 Math Engine (MEP) using a **Single Recyclable `Engine*` Instance**
 * and Two Unified Execution Modes matching the C engine:
 *
 * 1. **Single Shared `Engine*` Instance ([engineHandle])**:
 *    - Loaded once from `assets/precompiled/stdlib.mathc` + global patches (`filesDir/math_patches/compiled_state.mathc`).
 *    - Its Base-Layer watermarks (`stdlib + patches`) are snapshotted in C (`eng_snapshot_base`).
 *    - Recycled in O(1) time (`< 5 µs`) via `MathEngineBridge.nativeFlushUserModules(engineHandle)`
 *      (`eng_flush_user_modules`), eliminating per-project `Engine*` allocations (~1 MB RAM each)
 *      and eliminating per-project `engine.so` and `stdlib.mathc` disk copies (~550 KB each).
 *
 * 2. **Two Unified Execution Paths**:
 *    - **Standalone Mode (`eng_eval` / single-unit `eng_build_and_run`)**:
 *      Used by the Basic/Scientific Calculator (`MainActivity` -> [evaluate]) and standalone single-file
 *      `.math` scripts (`unit_count == 1`).
 *    - **Projects Mode (`eng_flush_user_modules` + multi-unit `eng_build_and_run`)**:
 *      Used by `.math` Studio Projects ([runProject] / [runModuleFile]). Flushes previous user modules
 *      back to the `stdlib + patches` base watermark in O(1) time, sets the project's sandboxed
 *      `import_dir`, and compiles, topologically links (`<[dep]`), and executes the project's
 *      `@entry(fn)` or `main()` entry point via `MathEngineBridge.nativeBuildAndRun`.
 */
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

/** Describes a project-level `main()` or `@entry(fn)` entry point located in any `.math` file of a project. */
data class ProjectEntryPoint(
    val fileName: String,
    val modName: String,
    val entryFn: String,
    val isExplicitDirective: Boolean,
    val sourceLine: Int = 1
)

object MathEngineManager {

    /** Engine & project status for UI inspection in the Project Drawer. */
    data class ProjectBinaryStatus(
        val projectName: String,
        val hasEngineSo: Boolean,
        val engineSoBytes: Long,
        val hasStdlibMathc: Boolean,
        val stdlibMathcBytes: Long,
        val linkedModulesCount: Int
    )

    internal const val TAG = "MathEngineManager"
    internal const val BUNDLED_STDLIB_ASSET = "precompiled/stdlib.mathc"

    /** Legacy constant kept for migration cleanup only; Basic Calculator now uses Standalone Mode on the single engine. */
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
    internal val errorLineExtractRegex = Regex("(?i)\\bline\\s+(\\d+)\\b")

    @Volatile
    internal var appContext: Context? = null

    /** Single shared native `Engine*` handle recycled across Standalone Mode (Calc) and Projects Mode (Studio). */
    @Volatile
    internal var engineHandle: Long = 0L

    /** Content hash of the `stdlib + patches` baseline currently loaded into [engineHandle]. */
    @Volatile
    internal var loadedBaseHash: Int = 0

    /** True when user project modules are currently linked above the base watermark in [engineHandle]. */
    @Volatile
    internal var isProjectModeActive: Boolean = false

    /** Name of the Studio project whose modules are currently linked in [engineHandle], or `null` in Standalone Mode. */
    @Volatile
    internal var linkedProjectName: String? = null

    /** Content signature of `project1` modules currently linked into [engineHandle] for the Basic Calculator. */
    @Volatile
    internal var linkedProject1Signature: String = ""

    /** Live in-memory `.math` editor buffer overrides for `project1` so edits reflect immediately in the Basic Calculator. */
    internal val liveProject1EditorOverrides = LinkedHashMap<String, String>()

    /** Module sources currently linked in [engineHandle] for the active Studio project. */
    internal val linkedModuleSourcesByName = LinkedHashMap<String, String>()

    /** Currently active project in the freeform `.math` Studio (defaults to `project1`). */
    @Volatile
    var activeProjectName: String = DEFAULT_STUDIO_PROJECT_NAME

    internal val worker = Executors.newSingleThreadExecutor()
    internal val customExternCallbacks = linkedMapOf<String, MathEngineBridge.ExternCallback>()

    /** Initializes application context, bundled patches (`assets/patches/`), and [CurrencyConverter] rates. */
    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        MathPatchDownloader.ensureBundledPatchesSeeded(app)
        CurrencyConverter.initialize(app)
        worker.execute {
            runCatching { ensureDefaultProject1Seeded(app) }
        }
    }

    /** Updates live editor buffer override for `project1` and invalidates the cached calculator link signature. */
    @Synchronized
    fun updateLiveProject1Override(fileName: String?, code: String?) {
        if (!fileName.isNullOrBlank()) {
            if (code != null) {
                liveProject1EditorOverrides[fileName] = code
            } else {
                liveProject1EditorOverrides.remove(fileName)
            }
        }
        linkedProject1Signature = ""
    }

    /** Removes a deleted or renamed file from `project1` live overrides and invalidates the calculator link signature. */
    @Synchronized
    fun invalidateProject1CalculatorLink(removedFileName: String? = null) {
        if (!removedFileName.isNullOrBlank()) {
            liveProject1EditorOverrides.remove(removedFileName)
        }
        linkedProject1Signature = ""
    }

    /** Sanitizes a user-supplied project name into a safe directory name. */
    fun sanitizeProjectName(raw: String?, allowCalcProject0: Boolean = false): String {
        val cleaned = (raw ?: "")
            .trim()
            .removeSuffix(".math")
            .replace(sanitizeNameRegex, "_")
            .trim('_')
        if (cleaned.isEmpty()) return DEFAULT_STUDIO_PROJECT_NAME
        if (!allowCalcProject0 && cleaned.equals(CALC_PROJECT_NAME, ignoreCase = true)) {
            return DEFAULT_STUDIO_PROJECT_NAME
        }
        return cleaned
    }

    /** Root directory containing all Studio projects (`filesDir/math_projects/`). */
    fun getProjectsRootDir(ctx: Context): File =
        File(ctx.filesDir, PROJECTS_ROOT_FOLDER).also { if (!it.exists()) it.mkdirs() }

    /** Directory for a specific Studio project (`filesDir/math_projects/<projectName>/`). */
    fun getProjectDir(
        ctx: Context,
        projectName: String = activeProjectName,
        allowCalcProject0: Boolean = false
    ): File {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = allowCalcProject0)
        return File(getProjectsRootDir(ctx), safeName).also { if (!it.exists()) it.mkdirs() }
    }

    /** Returns the sandboxed file I/O directory for [projectName] (`filesDir/math_projects/<projectName>/`). */
    fun getWorkspaceSandboxDir(ctx: Context, projectName: String = activeProjectName): File =
        getProjectDir(ctx, projectName, allowCalcProject0 = false)

    /**
     * Returns the universal baseline `stdlib.mathc` byte array (`assets/precompiled/stdlib.mathc`
     * extended with all compiled patches from `filesDir/math_patches/compiled_state.mathc`).
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
     * Ensures that [projectName]'s directory exists for `.math` and data files, and removes any
     * obsolete per-project `engine.so`, `stdlib.mathc`, or `.base_stdlib.mathc` duplicates.
     */
    @Synchronized
    fun ensureProjectSetup(
        ctx: Context,
        projectName: String = activeProjectName,
        allowCalcProject0: Boolean = false
    ): File {
        val safeName = sanitizeProjectName(projectName, allowCalcProject0 = false)
        val projDir = getProjectDir(ctx, safeName, allowCalcProject0 = false)

        // Clean up any legacy per-project binary duplicates (engine.so and stdlib.mathc are now shared once)
        runCatching {
            File(projDir, PROJECT_ENGINE_SO_NAME).takeIf { it.exists() }?.delete()
            File(projDir, PROJECT_STDLIB_MATHC_NAME).takeIf { it.exists() }?.delete()
            File(projDir, PROJECT_BASE_STDLIB_BACKUP).takeIf { it.exists() }?.delete()
            val legacyProj0 = File(getProjectsRootDir(ctx), CALC_PROJECT_NAME)
            if (legacyProj0.exists()) {
                File(legacyProj0, PROJECT_ENGINE_SO_NAME).takeIf { it.exists() }?.delete()
                File(legacyProj0, PROJECT_STDLIB_MATHC_NAME).takeIf { it.exists() }?.delete()
                File(legacyProj0, PROJECT_BASE_STDLIB_BACKUP).takeIf { it.exists() }?.delete()
            }
        }
        return projDir
    }

    /** Lists all Studio project names in `filesDir/math_projects/`, ordering `project1` first. */
    @Synchronized
    fun listProjects(ctx: Context): List<String> {
        ensureProjectSetup(ctx, DEFAULT_STUDIO_PROJECT_NAME)
        val root = getProjectsRootDir(ctx)
        val dirs = root.listFiles { f ->
            f.isDirectory && !f.name.startsWith(".") && !f.name.equals(CALC_PROJECT_NAME, ignoreCase = true)
        }
            ?.map { it.name }
            ?.sortedWith(compareBy<String> { if (it == DEFAULT_STUDIO_PROJECT_NAME) 0 else 1 }.thenBy { it })
            ?: listOf(DEFAULT_STUDIO_PROJECT_NAME)
        return if (DEFAULT_STUDIO_PROJECT_NAME in dirs) dirs else (listOf(DEFAULT_STUDIO_PROJECT_NAME) + dirs)
    }

    /** Inspects the shared engine & stdlib+patches status alongside [projectName]'s linked module count. */
    @Synchronized
    fun getProjectBinaryStatus(ctx: Context, projectName: String = activeProjectName): ProjectBinaryStatus {
        val safeName = sanitizeProjectName(projectName)
        ensureProjectSetup(ctx, safeName)
        val nativeSo = ctx.applicationInfo?.nativeLibraryDir?.let { File(it, "libmathengine.so") }
        val soBytes = if (nativeSo != null && nativeSo.isFile) nativeSo.length() else 287_000L
        val stdlibBytes = runCatching { getUniversalPatchedStdlibBytes(ctx).size.toLong() }.getOrDefault(251_891L)
        val linkedCount = if (linkedProjectName == safeName) linkedModuleSourcesByName.size else 0
        return ProjectBinaryStatus(
            projectName = safeName,
            hasEngineSo = engineHandle != 0L,
            engineSoBytes = soBytes,
            hasStdlibMathc = stdlibBytes > 0L,
            stdlibMathcBytes = stdlibBytes,
            linkedModulesCount = linkedCount
        )
    }

    /** Creates a new Studio project folder for `.math` modules (reusing the single shared engine). */
    @Synchronized
    fun createProject(ctx: Context, projectName: String): File {
        val safeName = sanitizeProjectName(projectName)
        return ensureProjectSetup(ctx, safeName)
    }

    /** Switches the active Studio project and flushes previous user modules on the shared engine. */
    fun setActiveProject(
        ctx: Context,
        projectName: String,
        onDone: ((ok: Boolean, activeProject: String) -> Unit)? = null
    ) {
        val safeName = sanitizeProjectName(projectName)
        activeProjectName = safeName
        worker.execute {
            try {
                val ok = synchronized(this) {
                    ensureProjectSetup(ctx, safeName)
                    val handle = ensureSharedEngineSync(ctx)
                    if (handle != 0L && (linkedProjectName != safeName || isProjectModeActive)) {
                        flushUserModulesSync(ctx)
                    }
                    if (handle != 0L) {
                        registerHostExternsOnHandle(handle, safeName)
                    }
                    handle != 0L
                }
                onDone?.invoke(ok, safeName)
            } catch (t: Throwable) {
                Log.e(TAG, "setActiveProject failed for '$safeName'", t)
                onDone?.invoke(false, safeName)
            }
        }
    }

    /** Renames or duplicates an existing Studio project's `.math` and data files. */
    @Synchronized
    fun renameOrCopyProject(
        ctx: Context,
        sourceProjectName: String,
        targetProjectName: String,
        copyOnly: Boolean
    ): String {
        val srcSafe = sanitizeProjectName(sourceProjectName)
        var dstSafe = sanitizeProjectName(targetProjectName)
        if (dstSafe == srcSafe && copyOnly) {
            dstSafe = "${srcSafe}_copy"
        }
        if (dstSafe == srcSafe) return srcSafe

        val srcDir = getProjectDir(ctx, srcSafe)
        val dstDir = getProjectDir(ctx, dstSafe)
        srcDir.listFiles()?.forEach { file ->
            if (file.isFile && !file.name.endsWith(".so") && !file.name.endsWith(".mathc")) {
                runCatching { file.copyTo(File(dstDir, file.name), overwrite = true) }
            }
        }
        ensureProjectSetup(ctx, dstSafe)

        if (!copyOnly && srcSafe != DEFAULT_STUDIO_PROJECT_NAME) {
            if (linkedProjectName == srcSafe) {
                flushUserModulesSync(ctx)
            }
            srcDir.deleteRecursively()
        }
        if (activeProjectName == srcSafe && !copyOnly) {
            activeProjectName = dstSafe
        }
        return dstSafe
    }

    /** Deletes a Studio project folder and flushes its user modules if currently linked in the shared engine. */
    @Synchronized
    fun deleteProject(ctx: Context, projectName: String): String {
        val safeName = sanitizeProjectName(projectName)
        if (linkedProjectName == safeName) {
            flushUserModulesSync(ctx)
        }
        val projDir = getProjectDir(ctx, safeName)
        projDir.deleteRecursively()

        if (safeName == DEFAULT_STUDIO_PROJECT_NAME) {
            ensureProjectSetup(ctx, DEFAULT_STUDIO_PROJECT_NAME)
        }
        val remaining = listProjects(ctx)
        if (activeProjectName == safeName) {
            activeProjectName = remaining.firstOrNull() ?: DEFAULT_STUDIO_PROJECT_NAME
        }
        return activeProjectName
    }

    /**
     * Flushes all user project modules from the shared `Engine*` instance in O(1) time
     * (`nativeFlushUserModules`), returning the engine to the clean `stdlib + patches` baseline.
     */
    @Synchronized
    internal fun flushUserModulesSync(ctx: Context? = appContext): Long {
        val app = ctx ?: appContext ?: return 0L
        val handle = ensureSharedEngineSync(app)
        if (handle == 0L) return 0L

        val flushed = try {
            MathEngineBridge.nativeFlushUserModules(handle)
        } catch (_: UnsatisfiedLinkError) {
            val cleanBytes = runCatching { getUniversalPatchedStdlibBytes(app) }.getOrNull()
            cleanBytes != null && cleanBytes.isNotEmpty() && MathEngineBridge.nativeLoadMathc(handle, cleanBytes)
        } catch (_: Throwable) {
            false
        }

        if (!flushed) {
            val cleanBytes = runCatching { getUniversalPatchedStdlibBytes(app) }.getOrNull()
            if (cleanBytes != null && cleanBytes.isNotEmpty()) {
                runCatching { MathEngineBridge.nativeLoadMathc(handle, cleanBytes) }
            }
        }

        linkedModuleSourcesByName.clear()
        linkedProjectName = null
        linkedProject1Signature = ""
        isProjectModeActive = false
        registerHostExternsOnHandle(handle, activeProjectName)
        refreshLiveAutocompleteCatalogSync(handle)
        return handle
    }

    /**
     * Flushes all user modules on the shared engine back to the clean `stdlib + patches` baseline.
     */
    fun resetProjectEngine(
        ctx: Context,
        projectName: String = activeProjectName,
        onDone: ((ok: Boolean) -> Unit)? = null
    ) {
        val safeName = sanitizeProjectName(projectName)
        worker.execute {
            try {
                val ok = synchronized(this) {
                    ensureProjectSetup(ctx, safeName)
                    flushUserModulesSync(ctx) != 0L
                }
                onDone?.invoke(ok)
            } catch (t: Throwable) {
                Log.e(TAG, "resetProjectEngine failed for '$safeName'", t)
                onDone?.invoke(false)
            }
        }
    }

    /** Exports the live C `SymTable` catalog (`nativeExportCatalog`) and merges it into editor autocomplete. */
    @Synchronized
    internal fun refreshLiveAutocompleteCatalogSync(handle: Long = engineHandle) {
        if (handle == 0L) return
        runCatching {
            val raw = MathEngineBridge.nativeExportCatalog(handle)
            if (!raw.isNullOrBlank()) {
                mergeLiveEngineCatalog(raw)
            }
        }
    }

    /** Registers all built-in host externs ([CurrencyConverter]) and custom app externs on [handle]. */
    @Synchronized
    internal fun registerHostExternsOnHandle(handle: Long, projectName: String = activeProjectName) {
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

    /** Registers a custom host extern callback (`@<name>(...)`) on the shared engine. */
    @Synchronized
    fun registerExtern(name: String, callback: MathEngineBridge.ExternCallback): Boolean {
        customExternCallbacks[name] = callback
        return if (engineHandle != 0L) {
            runCatching {
                MathEngineBridge.nativeRegisterExtern(engineHandle, name, callback)
            }.getOrDefault(false)
        } else {
            true
        }
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
        val safeProj = sanitizeProjectName(projectName)
        val projDir = getProjectDir(ctx, safeProj)
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
    internal fun createFreshStdlibEngine(ctx: Context, projectName: String = activeProjectName): Long {
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

    /** Evaluates [payload] inside [modName] via `nativeModuleEval`. */
    internal fun evalInModuleWithoutAutoMain(handle: Long, modName: String, payload: String): String {
        val wire = MathEngineBridge.nativeModuleEval(handle, modName, payload)
        MathEngineBridge.nativeSetModule(handle, modName, true)
        return wire ?: "ERR|Native module eval returned null"
    }

    /**
     * Ensures the single shared `Engine*` handle ([engineHandle]) is booted with `stdlib.mathc` +
     * all global patches (`filesDir/math_patches/compiled_state.mathc`) and returns it.
     */
    @Synchronized
    internal fun ensureSharedEngineSync(
        ctx: Context,
        projectName: String = activeProjectName
    ): Long {
        val safeName = sanitizeProjectName(projectName)
        ensureEngine()
        if (engineHandle != 0L) {
            registerHostExternsOnHandle(engineHandle, safeName)
        }
        return engineHandle
    }

    /** Compatibility alias returning the single shared `Engine*` handle. */
    @Synchronized
    internal fun ensureProjectEngineSync(
        ctx: Context,
        projectName: String = activeProjectName,
        allowCalcProject0: Boolean = false
    ): Long = ensureSharedEngineSync(ctx, projectName)

    /**
     * Rebuilds the single shared `Engine*` instance and universal patch cache from `stdlib.mathc` +
     * all current `.math` patches in `filesDir/math_patches/`.
     */
    @Synchronized
    internal fun rebuildSharedEngineAndPatchesSync(
        ctx: Context,
        onStatus: ((String) -> Unit)? = null
    ): Boolean {
        val oldHandle = engineHandle
        MathPatchDownloader.invalidateCompiledCache(MathPatchDownloader.getPatchesDir(ctx))
        val newHandle = createFreshStdlibEngine(ctx, activeProjectName)
        if (newHandle == 0L) {
            onStatus?.invoke("Engine rebuild failed: could not load $BUNDLED_STDLIB_ASSET")
            return false
        }

        if (oldHandle != 0L) {
            runCatching { MathEngineBridge.nativeDestroy(oldHandle) }
        }
        engineHandle = newHandle
        linkedModuleSourcesByName.clear()
        linkedProjectName = null
        linkedProject1Signature = ""
        isProjectModeActive = false
        registerHostExternsOnHandle(newHandle, activeProjectName)
        MathPatchLoader.resetTracking()

        MathPatchLoader.loadUnloadedPatches(
            ctx,
            newHandle,
            { src -> compileAndLoadPatchOnHandleSync(newHandle, src) },
            onStatus
        )
        loadedBaseHash = runCatching { getUniversalPatchedStdlibBytes(ctx).contentHashCode() }.getOrDefault(0)
        refreshLiveAutocompleteCatalogSync(engineHandle)
        return true
    }

    /**
     * Ensures the single shared `Engine*` instance ([engineHandle]) and the global patch cache
     * (`compiled_state.mathc`) are booted and up to date.
     */
    @Synchronized
    fun ensureEngine(onStatus: ((String) -> Unit)? = null): Boolean {
        val ctx = appContext ?: return false
        ensureProjectSetup(ctx, activeProjectName)

        if (engineHandle != 0L) {
            registerHostExternsOnHandle(engineHandle, activeProjectName)
            if (MathPatchLoader.needsEngineRebuild(ctx, engineHandle)) {
                return rebuildSharedEngineAndPatchesSync(ctx, onStatus)
            }
            // If user project modules are loaded, flush them before applying any newly downloaded global patches
            val patchesDir = MathPatchDownloader.getPatchesDir(ctx)
            val hasPatchFiles = patchesDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.isNotEmpty() == true
            if (hasPatchFiles && isProjectModeActive) {
                // Only flush if unloaded patches actually exist
                val newlyLoaded = MathPatchLoader.loadUnloadedPatches(
                    ctx,
                    engineHandle,
                    { src ->
                        flushUserModulesSync(ctx)
                        compileAndLoadPatchOnHandleSync(engineHandle, src)
                    },
                    onStatus
                )
                if (newlyLoaded > 0) {
                    loadedBaseHash = runCatching { getUniversalPatchedStdlibBytes(ctx).contentHashCode() }.getOrDefault(0)
                }
            } else {
                val newlyLoaded = MathPatchLoader.loadUnloadedPatches(
                    ctx,
                    engineHandle,
                    { src -> compileAndLoadPatchOnHandleSync(engineHandle, src) },
                    onStatus
                )
                if (newlyLoaded > 0) {
                    loadedBaseHash = runCatching { getUniversalPatchedStdlibBytes(ctx).contentHashCode() }.getOrDefault(0)
                }
            }
            return true
        }

        return try {
            val cachedHandle = MathPatchLoader.tryBootFromCompiledCache(ctx, onStatus)
            if (cachedHandle != 0L) {
                engineHandle = cachedHandle
                linkedModuleSourcesByName.clear()
                linkedProjectName = null
                isProjectModeActive = false
                registerHostExternsOnHandle(cachedHandle, activeProjectName)
                if (onStatus == null) {
                    MathPatchLoader.loadUnloadedPatches(
                        ctx,
                        cachedHandle,
                        { src -> compileAndLoadPatchOnHandleSync(cachedHandle, src) },
                        null
                    )
                }
                loadedBaseHash = runCatching { getUniversalPatchedStdlibBytes(ctx).contentHashCode() }.getOrDefault(0)
                return true
            }

            val handle = createFreshStdlibEngine(ctx, activeProjectName)
            engineHandle = handle
            linkedModuleSourcesByName.clear()
            linkedProjectName = null
            isProjectModeActive = false
            if (handle != 0L) {
                registerHostExternsOnHandle(handle, activeProjectName)
                MathPatchLoader.loadUnloadedPatches(
                    ctx,
                    handle,
                    { src -> compileAndLoadPatchOnHandleSync(handle, src) },
                    onStatus
                )
                loadedBaseHash = runCatching { getUniversalPatchedStdlibBytes(ctx).contentHashCode() }.getOrDefault(0)
                refreshLiveAutocompleteCatalogSync(handle)
                true
            } else {
                Log.e(TAG, "Failed to initialize shared engine with $BUNDLED_STDLIB_ASSET")
                onStatus?.invoke("Engine init failed: could not load $BUNDLED_STDLIB_ASSET")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception while initializing shared engine from $BUNDLED_STDLIB_ASSET", t)
            onStatus?.invoke("Engine init exception: ${t.message}")
            false
        }
    }

    /** Scans `filesDir/math_patches/` on the engine worker thread and links any unloaded `.math` patches into the shared engine. */
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
                    if (engineHandle == 0L) {
                        val ok = ensureEngine(onStatus = onStatus)
                        if (!ok) {
                            onStatus?.invoke("Patch load skipped: engine not ready")
                        }
                        onDone?.invoke(0)
                        return@execute
                    }
                    registerHostExternsOnHandle(engineHandle, activeProjectName)
                    if (MathPatchLoader.needsEngineRebuild(ctx, engineHandle)) {
                        rebuildSharedEngineAndPatchesSync(ctx, onStatus)
                        onDone?.invoke(1)
                        return@execute
                    }
                    val count = MathPatchLoader.loadUnloadedPatches(
                        ctx,
                        engineHandle,
                        { src ->
                            if (isProjectModeActive) flushUserModulesSync(ctx)
                            compileAndLoadPatchOnHandleSync(engineHandle, src)
                        },
                        onStatus
                    )
                    if (count > 0) {
                        loadedBaseHash = runCatching { getUniversalPatchedStdlibBytes(ctx).contentHashCode() }.getOrDefault(0)
                        refreshLiveAutocompleteCatalogSync(engineHandle)
                    }
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
