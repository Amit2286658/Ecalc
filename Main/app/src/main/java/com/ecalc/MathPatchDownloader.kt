package com.ecalc

import android.content.Context
import android.util.Log
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Incremental `.math` Patch Downloader.
 *
 * Checks the remote `manifest.json` index and downloads any `.math` extension
 * files that are not already present (or whose SHA-256 changed) into the
 * dedicated local folder `context.filesDir/math_patches/`.
 *
 * Note:
 * - Does NOT load downloaded `.math` files into the native engine immediately.
 * - Loading from `context.filesDir/math_patches/` is handled separately by
 *   [MathPatchLoader] so downloads and engine state mutations stay decoupled.
 * - Because `.math` files are plain-text scripts interpreted by the bundled
 *   C11 engine (never `.so` or `.dex` executables), this workflow is fully
 *   compliant with Google Play and F-Droid distribution policies.
 */
object MathPatchDownloader {

    private const val TAG = "MathPatchDownloader"
    const val PATCHES_FOLDER_NAME = "math_patches"
    internal const val CACHE_MATHC_FILE = "compiled_state.mathc"
    internal const val CACHE_SIG_FILE = "compiled_state.sig"

    /**
     * Points to the static `manifest.json` hosted via jsDelivr's GitHub CDN.
     * jsDelivr serves `@main` branches through a long-lived CDN cache and
     * can serve stale responses for files added or updated recently, so
     * [fallbackBaseUrl] (GitHub raw, uncached) is checked first for the
     * lightweight manifest index and verified per-mirror by SHA-256 for patch files.
     */
    var manifestBaseUrl: String =
        "https://cdn.jsdelivr.net/gh/Amit2286658/Math-Engine-MEP@main/Main/patches"

    /** Fresh, uncached mirror used so recently published patches are never hidden by CDN caching. */
    var fallbackBaseUrl: String =
        "https://raw.githubusercontent.com/Amit2286658/Math-Engine-MEP/main/Main/patches"

    private const val CONNECT_TIMEOUT_MS = 4_000L
    private const val DOWNLOAD_TIMEOUT_MS = 15_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private enum class PatchFileSyncResult {
        DOWNLOADED,
        UP_TO_DATE,
        FAILED
    }

    /** Returns the dedicated local directory where incremental `.math` patches live. */
    fun getPatchesDir(context: Context): File =
        File(context.filesDir, PATCHES_FOLDER_NAME).apply { mkdirs() }

    /** Removes precompiled `.mathc` cache files whenever on-disk `.math` patches change. */
    internal fun invalidateCompiledCache(patchesDir: File) {
        runCatching { File(patchesDir, CACHE_MATHC_FILE).delete() }
        runCatching { File(patchesDir, CACHE_SIG_FILE).delete() }
        runCatching { File(patchesDir, ".$CACHE_MATHC_FILE.tmp").delete() }
    }

    /**
     * Inspects `manifest.json` and downloads any `.math` files not already
     * present with a matching SHA-256 in `filesDir/math_patches/`.
     *
     * @param onStatus Optional one-line status reporting (mirrors of the CDN
     *   are retried transparently); lets the caller surface pull results on
     *   screen instead of only in logcat.
     * @return Number of newly downloaded `.math` patch files.
     */
    @Synchronized
    fun syncMissingPatches(context: Context, onStatus: ((String) -> Unit)? = null): Int {
        return try {
            val patchesDir = getPatchesDir(context)
            val manifestText = fetchManifestWithFallback("/manifest.json")
            if (manifestText == null) {
                onStatus?.invoke("Patch sync: no reachable source (offline or CDN blocked)")
                return 0
            }
            val root = JSONObject(manifestText)
            val patchesArray = root.optJSONArray("patches")
            if (patchesArray == null) {
                onStatus?.invoke("Patch sync: manifest.json has no patches[] array")
                return 0
            }

            var downloadedCount = 0
            var upToDateCount = 0
            var failedCount = 0

            for (i in 0 until patchesArray.length()) {
                val entry = patchesArray.optJSONObject(i) ?: continue
                val fileName = entry.optString("file", "").trim()
                val expectedSha = entry.optString("sha256", "").trim()
                if (!fileName.endsWith(".math") || fileName.contains("/") || fileName.contains("..")) {
                    continue
                }
                when (downloadPatchIfNeeded(patchesDir, fileName, expectedSha, onStatus)) {
                    PatchFileSyncResult.DOWNLOADED -> {
                        downloadedCount++
                        onStatus?.invoke("Downloaded patch '$fileName' (SHA-256 verified)")
                    }
                    PatchFileSyncResult.UP_TO_DATE -> {
                        upToDateCount++
                    }
                    PatchFileSyncResult.FAILED -> {
                        failedCount++
                    }
                }
            }
            if (downloadedCount > 0) {
                invalidateCompiledCache(patchesDir)
                Log.i(TAG, "Downloaded $downloadedCount new .math patch file(s) into ${patchesDir.path}")
            } else if (failedCount == 0) {
                if (upToDateCount > 0) {
                    onStatus?.invoke("Patch sync: $upToDateCount remote patch(es) up to date")
                } else {
                    onStatus?.invoke("Patch sync: manifest up to date (0 remote patches)")
                }
            }
            downloadedCount
        } catch (t: Throwable) {
            Log.d(TAG, "Incremental .math patch check skipped or offline", t)
            onStatus?.invoke("Patch sync error: ${t.message ?: t.javaClass.simpleName}")
            0
        }
    }

    private fun downloadPatchIfNeeded(
        dir: File,
        fileName: String,
        expectedSha256: String,
        onStatus: ((String) -> Unit)? = null
    ): PatchFileSyncResult {
        val targetFile = File(dir, fileName)
        if (targetFile.isFile && targetFile.canRead() && targetFile.length() > 0L) {
            if (expectedSha256.isEmpty()) return PatchFileSyncResult.UP_TO_DATE
            val currentSha = runCatching { sha256Hex(targetFile.readBytes()) }.getOrNull()
            if (currentSha != null && currentSha.equals(expectedSha256, ignoreCase = true)) {
                return PatchFileSyncResult.UP_TO_DATE // Already downloaded and up to date
            }
        }

        val mirrors = listOf(fallbackBaseUrl, manifestBaseUrl).distinct()
        var verifiedBytes: ByteArray? = null
        var anyMirrorResponded = false
        var sawShaMismatch = false

        for (baseUrl in mirrors) {
            val candidate = fetchBytes("$baseUrl/$fileName") ?: continue
            if (candidate.isEmpty()) continue
            anyMirrorResponded = true
            if (expectedSha256.isEmpty() || sha256Hex(candidate).equals(expectedSha256, ignoreCase = true)) {
                verifiedBytes = candidate
                break
            } else {
                sawShaMismatch = true
                Log.w(TAG, "Mirror $baseUrl/$fileName had stale SHA-256; trying next mirror")
            }
        }

        val bytes = verifiedBytes ?: run {
            if (sawShaMismatch && anyMirrorResponded) {
                Log.w(TAG, "Patch $fileName failed SHA-256 verification on all mirrors; discarding")
                onStatus?.invoke("Patch '$fileName' failed SHA-256 verification; discarded")
            } else {
                onStatus?.invoke("Patch download failed: '$fileName' unreachable on all mirrors")
            }
            return PatchFileSyncResult.FAILED
        }

        dir.mkdirs()
        val tmpFile = File(dir, ".$fileName.tmp")
        return try {
            tmpFile.writeBytes(bytes)
            val replaced = if (tmpFile.renameTo(targetFile)) {
                true
            } else {
                // Fallback for filesystems where renameTo fails over an existing destination
                runCatching {
                    if (targetFile.exists()) targetFile.delete()
                    if (tmpFile.renameTo(targetFile)) {
                        true
                    } else {
                        targetFile.writeBytes(bytes)
                        tmpFile.delete()
                        true
                    }
                }.getOrDefault(false)
            }
            if (replaced) {
                invalidateCompiledCache(dir)
                PatchFileSyncResult.DOWNLOADED
            } else {
                tmpFile.delete()
                onStatus?.invoke("Patch save failed: could not write '$fileName'")
                PatchFileSyncResult.FAILED
            }
        } catch (t: Throwable) {
            tmpFile.delete()
            onStatus?.invoke("Patch save failed for '$fileName': ${t.message}")
            PatchFileSyncResult.FAILED
        }
    }

    /**
     * Fetches `manifest.json` from the uncached raw mirror first so newly published
     * patches are never hidden by jsDelivr's multi-hour `@main` branch cache,
     * falling back to [manifestBaseUrl] if the primary mirror is unreachable or
     * if [manifestBaseUrl] was customized (e.g. in tests).
     */
    private fun fetchManifestWithFallback(tailPath: String): String? {
        val urls = if (manifestBaseUrl.contains("cdn.jsdelivr.net")) {
            listOf(fallbackBaseUrl, manifestBaseUrl).distinct()
        } else {
            listOf(manifestBaseUrl, fallbackBaseUrl).distinct()
        }
        for (base in urls) {
            fetchString("$base$tailPath")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun fetchString(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Throwable) {
        null
    }

    private fun fetchBytes(url: String): ByteArray? = try {
        val req = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (_: Throwable) {
        null
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
