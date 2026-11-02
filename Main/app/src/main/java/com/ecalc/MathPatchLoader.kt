package com.ecalc

import android.content.Context
import android.util.Log
import com.mathengine.MathEngineBridge
import java.io.File

/**
 * Dedicated `.math` Patch Folder Loader with `.mathc` Binary Cache Persistence.
 *
 * How `.math` -> `.mathc` compilation caching works:
 * 1. Whenever `MathEngineBridge.nativeCompileExt(engineHandle, mathSource)`
 *    compiles and links a `.math` file into the engine, the C11 engine
 *    returns a `ByteArray` containing the **complete cumulative `.mathc` binary
 *    image** (`stdlib.mathc` + all linked `.math` patches).
 * 2. [MathPatchLoader] saves that returned `ByteArray` to
 *    `filesDir/math_patches/compiled_state.mathc` alongside a composite
 *    fingerprint (`compiled_state.sig`) of all `.math` files in the folder.
 * 3. On the next app launch, if no `.math` files in `filesDir/math_patches/`
 *    have changed, [tryBootFromCompiledCache] loads `compiled_state.mathc`
 *    directly via `nativeCreateBlank()` + `nativeLoadMathc()` in ~0.4 ms
 *    (`memcpy`), skipping `.math` text compilation completely!
 * 4. If a new `.math` patch was downloaded while the app was running (or since
 *    the last launch), [loadUnloadedPatches] compiles only the unloaded `.math`
 *    files (ordered topologically by `<[dep]`) and updates `compiled_state.mathc`
 *    with the newest returned image.
 */
object MathPatchLoader {

    private const val TAG = "MathPatchLoader"
    private const val CACHE_MATHC_FILE = MathPatchDownloader.CACHE_MATHC_FILE
    private const val CACHE_SIG_FILE = MathPatchDownloader.CACHE_SIG_FILE

    /**
     * Tracks `<fileName>:<sha256>` signatures already linked into the active
     * engine handle during the current process lifetime.
     */
    private val loadedPatchSignatures = mutableSetOf<String>()
    private val loadedPatchShaByFileName = mutableMapOf<String, String>()
    private val unreportedCompiledFiles = linkedSetOf<String>()
    private val unreportedCachedFiles = linkedSetOf<String>()
    private var trackedEngineHandle: Long = 0L

    @Synchronized
    fun resetTracking() {
        loadedPatchSignatures.clear()
        loadedPatchShaByFileName.clear()
        unreportedCompiledFiles.clear()
        unreportedCachedFiles.clear()
        trackedEngineHandle = 0L
    }

    /**
     * Computes a deterministic composite signature of all `.math` patch files
     * currently stored in `filesDir/math_patches/`.
     */
    private fun listPatchFilesWithSignatures(patchesDir: File): List<Pair<File, String>> {
        if (!patchesDir.isDirectory) return emptyList()
        val files = patchesDir.listFiles { f ->
            f.isFile && f.name.endsWith(".math") && !f.name.startsWith(".") && f.length() > 0L
        }?.sortedBy { it.name } ?: return emptyList()

        return files.mapNotNull { file ->
            runCatching {
                val bytes = file.readBytes()
                if (bytes.isEmpty()) return@runCatching null
                val sha = MathPatchDownloader.sha256Hex(bytes)
                file to "${file.name}:$sha"
            }.getOrNull()
        }
    }

    /**
     * Extracts `[module]` declarations and `<[dep]` dependencies from a `.math` source string
     * so multiple patch files can be compiled in topological dependency order.
     */
    private fun parsePatchModulesAndDeps(rawSource: String): Pair<Set<String>, Set<String>> {
        val mods = mutableSetOf<String>()
        val deps = mutableSetOf<String>()
        val modRegex = Regex("^\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")
        val depRegex = Regex("^<\\[([a-zA-Z_][a-zA-Z0-9_]*)]$")

        for (rawLine in rawSource.lineSequence()) {
            val trimmed = stripCommentOutsideQuotes(rawLine).trim()
            if (trimmed.isEmpty()) continue
            modRegex.matchEntire(trimmed)?.let { m ->
                val name = m.groupValues[1]
                if (name != "default") mods.add(name)
            }
            depRegex.matchEntire(trimmed)?.let { m ->
                deps.add(m.groupValues[1])
            }
        }
        return mods to deps
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
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    /**
     * Orders `.math` patch entries topologically so that if patch A has `<[dep]`
     * provided by patch B, patch B is compiled and linked before patch A.
     */
    private fun orderEntriesTopologically(entries: List<Pair<File, String>>): List<Pair<File, String>> {
        if (entries.size <= 1) return entries

        val n = entries.size
        val modsByIdx = Array(n) { emptySet<String>() }
        val depsByIdx = Array(n) { emptySet<String>() }
        val providerByMod = mutableMapOf<String, Int>()

        for (i in 0 until n) {
            val src = runCatching { entries[i].first.readText(Charsets.UTF_8) }.getOrDefault("")
            val (mods, deps) = parsePatchModulesAndDeps(src)
            modsByIdx[i] = mods
            depsByIdx[i] = deps
            for (m in mods) {
                providerByMod.putIfAbsent(m, i)
            }
        }

        val adj = Array(n) { mutableSetOf<Int>() }
        val inDegree = IntArray(n)
        for (i in 0 until n) {
            for (dep in depsByIdx[i]) {
                val provider = providerByMod[dep]
                if (provider != null && provider != i && adj[provider].add(i)) {
                    inDegree[i]++
                }
            }
        }

        val scheduled = BooleanArray(n)
        val result = ArrayList<Pair<File, String>>(n)
        while (result.size < n) {
            var pick = -1
            for (i in 0 until n) {
                if (!scheduled[i] && inDegree[i] == 0) {
                    pick = i
                    break
                }
            }
            if (pick < 0) {
                for (i in 0 until n) {
                    if (!scheduled[i]) {
                        pick = i
                        break
                    }
                }
            }
            scheduled[pick] = true
            result.add(entries[pick])
            for (target in adj[pick]) {
                if (inDegree[target] > 0) inDegree[target]--
            }
        }
        return result
    }

    /**
     * Returns `true` when an already-loaded `.math` patch file in `filesDir/math_patches/`
     * has been modified (different SHA-256) or deleted since [engineHandle] loaded it.
     */
    @Synchronized
    fun needsEngineRebuild(context: Context, engineHandle: Long): Boolean {
        if (engineHandle == 0L || trackedEngineHandle != engineHandle) return false
        if (loadedPatchShaByFileName.isEmpty()) return false

        val patchesDir = File(context.filesDir, MathPatchDownloader.PATCHES_FOLDER_NAME)
        val entries = listPatchFilesWithSignatures(patchesDir)
        val currentShaByFile = entries.associate { (file, sig) ->
            file.name to sig.substringAfter(':')
        }

        for ((loadedFile, loadedSha) in loadedPatchShaByFileName) {
            val currentSha = currentShaByFile[loadedFile]
            if (currentSha == null || !currentSha.equals(loadedSha, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * Fast O(1) boot path: if `compiled_state.mathc` exists and its signature
     * matches the exact set of `.math` files in `filesDir/math_patches/`,
     * boots a blank engine and loads `compiled_state.mathc` directly without
     * recompiling any `.math` source files.
     *
     * @return Non-zero engine handle on cache hit, or `0L` on cache miss/stale.
     */
    @Synchronized
    fun tryBootFromCompiledCache(
        context: Context,
        onStatus: ((String) -> Unit)? = null
    ): Long {
        val patchesDir = File(context.filesDir, MathPatchDownloader.PATCHES_FOLDER_NAME)
        val entries = listPatchFilesWithSignatures(patchesDir)
        if (entries.isEmpty()) return 0L

        val cacheFile = File(patchesDir, CACHE_MATHC_FILE)
        val sigFile = File(patchesDir, CACHE_SIG_FILE)
        if (!cacheFile.canRead() || !sigFile.canRead()) return 0L

        val expectedCompositeSig = entries.joinToString("\n") { it.second }
        val cachedSig = runCatching { sigFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (cachedSig != expectedCompositeSig) return 0L

        val cachedBytes = runCatching { cacheFile.readBytes() }.getOrNull() ?: return 0L
        if (cachedBytes.isEmpty()) return 0L

        val blankHandle = MathEngineBridge.nativeCreateBlank()
        if (blankHandle == 0L) return 0L

        return if (MathEngineBridge.nativeLoadMathc(blankHandle, cachedBytes)) {
            CurrencyConverter.registerWithEngine(blankHandle)
            trackedEngineHandle = blankHandle
            loadedPatchSignatures.clear()
            loadedPatchShaByFileName.clear()
            unreportedCompiledFiles.clear()
            unreportedCachedFiles.clear()
            entries.forEach { (file, sig) ->
                loadedPatchSignatures.add(sig)
                loadedPatchShaByFileName[file.name] = sig.substringAfter(':')
            }
            val patchNames = entries.joinToString(", ") { it.first.name }
            Log.i(TAG, "Booted directly from cached $CACHE_MATHC_FILE (${cachedBytes.size} bytes, ${entries.size} patch(es): $patchNames)")
            if (onStatus != null) {
                onStatus.invoke("Booted ${entries.size} cached patch(es) ($patchNames)")
            } else {
                entries.forEach { unreportedCachedFiles.add(it.first.name) }
            }
            blankHandle
        } else {
            // Stale intr_hash (e.g. after an app update) or corrupt cache — delete and recompile
            MathEngineBridge.nativeDestroy(blankHandle)
            cacheFile.delete()
            sigFile.delete()
            0L
        }
    }

    /**
     * Scans `filesDir/math_patches/` in topological dependency order, compiles & links
     * any `.math` file not yet loaded into [engineHandle], and saves the final
     * compiled `.mathc` `ByteArray` returned by the engine to
     * `compiled_state.mathc` so future launches don't recompile it.
     */
    @Synchronized
    fun loadUnloadedPatches(
        context: Context,
        engineHandle: Long,
        compilerLinker: (mathSource: String) -> Triple<Boolean, String, ByteArray?>,
        onStatus: ((String) -> Unit)? = null
    ): Int {
        if (engineHandle == 0L) {
            onStatus?.invoke("Patch load skipped: engine handle is 0")
            return 0
        }
        CurrencyConverter.registerWithEngine(engineHandle)
        if (trackedEngineHandle != engineHandle) {
            loadedPatchSignatures.clear()
            loadedPatchShaByFileName.clear()
            unreportedCompiledFiles.clear()
            unreportedCachedFiles.clear()
            trackedEngineHandle = engineHandle
        }

        val patchesDir = MathPatchDownloader.getPatchesDir(context)
        val entries = listPatchFilesWithSignatures(patchesDir)
        if (entries.isEmpty()) {
            onStatus?.invoke("Patch loader: no .math files found in math_patches/")
            return 0
        }

        // Flush any silent early-boot notifications (e.g. when openSession triggered
        // ensureEngine(onStatus = null) milliseconds before syncAndLoadPatches finished)
        var flushedSilentBoot = false
        if (onStatus != null) {
            if (unreportedCachedFiles.isNotEmpty()) {
                val cachedList = unreportedCachedFiles.joinToString(", ")
                onStatus.invoke("Booted ${unreportedCachedFiles.size} cached patch(es) ($cachedList)")
                unreportedCachedFiles.clear()
                flushedSilentBoot = true
            }
            if (unreportedCompiledFiles.isNotEmpty()) {
                for (name in unreportedCompiledFiles) {
                    onStatus.invoke("Loaded patch '$name' into engine")
                }
                unreportedCompiledFiles.clear()
                flushedSilentBoot = true
            }
        }

        val orderedEntries = orderEntriesTopologically(entries)
        val pending = orderedEntries.filter { (_, sig) -> sig !in loadedPatchSignatures }.toMutableList()

        if (pending.isEmpty()) {
            if (onStatus != null && !flushedSilentBoot) {
                val names = entries.joinToString(", ") { it.first.name }
                onStatus.invoke("Engine ready: ${entries.size} patch(es) active ($names)")
            }
            return 0
        }

        var newlyLoaded = 0
        var latestCompiledMathc: ByteArray? = null
        val failureMessages = linkedMapOf<String, String>()

        // Multi-pass compilation in case patches have cross-file dependencies
        var madeProgress = true
        while (pending.isNotEmpty() && madeProgress) {
            madeProgress = false
            val iter = pending.iterator()
            while (iter.hasNext()) {
                val (file, sig) = iter.next()
                try {
                    val rawSource = file.readText(Charsets.UTF_8)
                    val (ok, msg, mathcBytes) = compilerLinker(rawSource)
                    if (ok) {
                        loadedPatchSignatures.add(sig)
                        loadedPatchShaByFileName[file.name] = sig.substringAfter(':')
                        failureMessages.remove(file.name)
                        iter.remove()
                        newlyLoaded++
                        madeProgress = true
                        if (mathcBytes != null && mathcBytes.isNotEmpty()) {
                            latestCompiledMathc = mathcBytes
                        }
                        Log.i(TAG, "Compiled & linked patch '${file.name}': $msg")
                        if (onStatus != null) {
                            onStatus.invoke("Loaded patch '${file.name}' into engine")
                        } else {
                            unreportedCompiledFiles.add(file.name)
                        }
                    } else {
                        failureMessages[file.name] = msg
                    }
                } catch (t: Throwable) {
                    failureMessages[file.name] = t.message ?: t.javaClass.simpleName
                }
            }
        }

        // Report any patches that still failed after all dependency passes settled
        for ((fileName, errMsg) in failureMessages) {
            Log.w(TAG, "Failed to link patch '$fileName': $errMsg")
            onStatus?.invoke("FAILED to load patch '$fileName': $errMsg")
        }

        // Persist the cumulative .mathc image returned by nativeCompileExt()
        // so the next cold start loads it in O(1) memcpy time via nativeLoadMathc()
        if (latestCompiledMathc != null && entries.all { it.second in loadedPatchSignatures }) {
            runCatching {
                patchesDir.mkdirs()
                val cacheTmp = File(patchesDir, ".$CACHE_MATHC_FILE.tmp")
                val cacheTarget = File(patchesDir, CACHE_MATHC_FILE)
                val sigTarget = File(patchesDir, CACHE_SIG_FILE)
                cacheTmp.writeBytes(latestCompiledMathc)
                val saved = if (cacheTmp.renameTo(cacheTarget)) {
                    true
                } else {
                    if (cacheTarget.exists()) cacheTarget.delete()
                    if (cacheTmp.renameTo(cacheTarget)) {
                        true
                    } else {
                        cacheTarget.writeBytes(latestCompiledMathc)
                        cacheTmp.delete()
                        true
                    }
                }
                if (saved) {
                    sigTarget.writeText(entries.joinToString("\n") { it.second }, Charsets.UTF_8)
                    Log.i(TAG, "Saved precompiled patch cache $CACHE_MATHC_FILE (${latestCompiledMathc.size} bytes)")
                } else {
                    cacheTmp.delete()
                }
            }
        }
        return newlyLoaded
    }
}
