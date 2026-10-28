package com.ecalc

import android.content.Context
import android.util.Log
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

    /**
     * Points to the static `manifest.json` hosted via jsDelivr's GitHub CDN
     * (or switch `@main/Main/patches` to `@patches` if using an orphan branch).
     */
    var manifestBaseUrl: String =
        "https://cdn.jsdelivr.net/gh/Amit2286658/Math-Engine-MEP@main/Main/patches"

    private const val CONNECT_TIMEOUT_MS = 4_000L
    private const val DOWNLOAD_TIMEOUT_MS = 15_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Returns the dedicated local directory where incremental `.math` patches live. */
    fun getPatchesDir(context: Context): File =
        File(context.filesDir, PATCHES_FOLDER_NAME).apply { mkdirs() }

    /**
     * Inspects `manifest.json` at [manifestBaseUrl] and downloads any `.math`
     * files not already present with a matching SHA-256 in `filesDir/math_patches/`.
     *
     * @return Number of newly downloaded `.math` patch files.
     */
    fun syncMissingPatches(context: Context): Int {
        return try {
            val patchesDir = getPatchesDir(context)
            val manifestText = fetchString("$manifestBaseUrl/manifest.json") ?: return 0
            val root = JSONObject(manifestText)
            val patchesArray = root.optJSONArray("patches") ?: return 0

            var downloadedCount = 0
            for (i in 0 until patchesArray.length()) {
                val entry = patchesArray.optJSONObject(i) ?: continue
                val fileName = entry.optString("file", "").trim()
                val expectedSha = entry.optString("sha256", "").trim()
                if (!fileName.endsWith(".math") || fileName.contains("/") || fileName.contains("..")) {
                    continue
                }
                if (downloadPatchIfNeeded(patchesDir, fileName, expectedSha)) {
                    downloadedCount++
                }
            }
            if (downloadedCount > 0) {
                Log.i(TAG, "Downloaded $downloadedCount new .math patch file(s) into ${patchesDir.path}")
            }
            downloadedCount
        } catch (t: Throwable) {
            Log.d(TAG, "Incremental .math patch check skipped or offline", t)
            0
        }
    }

    private fun downloadPatchIfNeeded(dir: File, fileName: String, expectedSha256: String): Boolean {
        val targetFile = File(dir, fileName)
        if (targetFile.isFile && targetFile.canRead()) {
            if (expectedSha256.isEmpty()) return false
            val currentSha = sha256Hex(targetFile.readBytes())
            if (currentSha.equals(expectedSha256, ignoreCase = true)) {
                return false // Already downloaded and up to date
            }
        }

        val bytes = fetchBytes("$manifestBaseUrl/$fileName") ?: return false
        if (expectedSha256.isNotEmpty() && !sha256Hex(bytes).equals(expectedSha256, ignoreCase = true)) {
            Log.w(TAG, "Patch $fileName failed SHA-256 verification; discarding")
            return false
        }

        val tmpFile = File(dir, ".$fileName.tmp")
        return try {
            tmpFile.writeBytes(bytes)
            if (tmpFile.renameTo(targetFile)) {
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (t: Throwable) {
            tmpFile.delete()
            false
        }
    }

    private fun fetchString(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Throwable) {
        null
    }

    private fun fetchBytes(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
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
