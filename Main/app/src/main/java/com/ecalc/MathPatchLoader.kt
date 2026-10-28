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
 *    files and updates `compiled_state.mathc` with the newest returned image.
 */
object MathPatchLoader {

    private const val TAG = "MathPatchLoader"
    private const val CACHE_MATHC_FILE = "compiled_state.mathc"
    private const val CACHE_SIG_FILE = "compiled_state.sig"

    /**
     * Tracks `<fileName>:<sha256>` signatures already linked into the active
     * engine handle during the current process lifetime.
     */
    private val loadedPatchSignatures = mutableSetOf<String>()
    private var trackedEngineHandle: Long = 0L

    @Synchronized
    fun resetTracking() {
        loadedPatchSignatures.clear()
        trackedEngineHandle = 0L
    }

    /**
     * Computes a deterministic composite signature of all `.math` patch files
     * currently stored in `filesDir/math_patches/`.
     */
    private fun listPatchFilesWithSignatures(patchesDir: File): List<Pair<File, String>> {
        if (!patchesDir.isDirectory) return emptyList()
        val files = patchesDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }
            ?.sortedBy { it.name }
            ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val sha = MathPatchDownloader.sha256Hex(file.readBytes())
                file to "${file.name}:$sha"
            }.getOrNull()
        }
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
    fun tryBootFromCompiledCache(context: Context): Long {
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
            trackedEngineHandle = blankHandle
            loadedPatchSignatures.clear()
            entries.forEach { loadedPatchSignatures.add(it.second) }
            Log.i(TAG, "Booted directly from cached $CACHE_MATHC_FILE (${cachedBytes.size} bytes, ${entries.size} patch(es))")
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
     * Scans `filesDir/math_patches/` in lexicographical order, compiles & links
     * any `.math` file not yet loaded into [engineHandle], and saves the final
     * compiled `.mathc` `ByteArray` returned by the engine to
     * `compiled_state.mathc` so future launches don't recompile it.
     */
    @Synchronized
    fun loadUnloadedPatches(
        context: Context,
        engineHandle: Long,
        compilerLinker: (mathSource: String) -> Triple<Boolean, String, ByteArray?>
    ): Int {
        if (engineHandle == 0L) return 0
        if (trackedEngineHandle != engineHandle) {
            loadedPatchSignatures.clear()
            trackedEngineHandle = engineHandle
        }

        val patchesDir = File(context.filesDir, MathPatchDownloader.PATCHES_FOLDER_NAME)
        val entries = listPatchFilesWithSignatures(patchesDir)
        if (entries.isEmpty()) return 0

        var newlyLoaded = 0
        var latestCompiledMathc: ByteArray? = null

        for ((file, sig) in entries) {
            if (sig in loadedPatchSignatures) continue
            try {
                val rawSource = file.readText(Charsets.UTF_8)
                val (ok, msg, mathcBytes) = compilerLinker(rawSource)
                if (ok) {
                    loadedPatchSignatures.add(sig)
                    newlyLoaded++
                    if (mathcBytes != null && mathcBytes.isNotEmpty()) {
                        latestCompiledMathc = mathcBytes
                    }
                    Log.i(TAG, "Compiled & linked patch '${file.name}': $msg")
                } else {
                    Log.w(TAG, "Failed to link patch '${file.name}': $msg")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error reading patch file '${file.name}'", t)
            }
        }

        // Persist the cumulative .mathc image returned by nativeCompileExt()
        // so the next cold start loads it in O(1) memcpy time via nativeLoadMathc()
        if (latestCompiledMathc != null && entries.all { it.second in loadedPatchSignatures }) {
            runCatching {
                val cacheTmp = File(patchesDir, ".$CACHE_MATHC_FILE.tmp")
                val cacheTarget = File(patchesDir, CACHE_MATHC_FILE)
                val sigTarget = File(patchesDir, CACHE_SIG_FILE)
                cacheTmp.writeBytes(latestCompiledMathc)
                if (cacheTmp.renameTo(cacheTarget)) {
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
