package com.ecalc

import android.content.Context
import android.util.Log
import com.mathengine.MathEngineBridge
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Engine Hot-Update — lets Math-Engine-MEP evolve independently of the APK.
 *
 * Flow at startup (background thread, before the engine is first used):
 *
 *  1. Ask GitHub what the engine repo's `Prebuilt/arm64-v8a/libmathengine.so`
 *     currently is (contents API returns its git blob SHA).
 *  2. If that SHA differs from the cached copy, download it, verify the bytes
 *     against the git blob SHA (cheap integrity check — the SHA comes over
 *     HTTPS from GitHub, so a MITM cannot serve a hijacked binary), then
 *     replace the cached library atomically (tmp file + rename).
 *  3. If a cached library exists, hand its path to [MathEngineBridge.libOverridePath];
 *     the bridge then `System.load`s it INSTEAD of the copy bundled in the APK.
 *     Otherwise (first run, offline, rate-limited, bad network) the bundled
 *     jniLibs binary is used — the app always works.
 *
 * Notes:
 *  - A newly pulled engine takes effect on the NEXT app launch: a .so loaded
 *    into a running process cannot be swapped mid-session.
 *  - The clickable contract: whenever you rebuild the engine, commit the new
 *    .so to `Prebuilt/arm64-v8a/libmathengine.so` in Math-Engine-MEP. Never
 *    push an older build there — the app would regress (volatile builds from
 *    various machines are NOT version-checked end-to-end).
 *  - Unauthenticated GitHub API limit is 60 req/h per IP; subscribe to your
 *    own IP's cap by not spamming relaunches. Falls back gracefully at the cap.
 */
object MathEngineUpdater {

    private const val TAG = "MathEngineUpdater"
    private const val META_URL =
        "https://api.github.com/repos/Amit2286658/Math-Engine-MEP/contents/Prebuilt/arm64-v8a/libmathengine.so"
    private const val CONNECT_TIMEOUT_MS = 4_000L
    private const val DOWNLOAD_TIMEOUT_MS = 30_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    fun initialize(context: Context) {
        try {
            val dir = File(context.filesDir, "native-engine/arm64-v8a").apply { mkdirs() }
            val libFile = File(dir, "libmathengine.so")
            val metaFile = File(dir, "meta.json")

            val remote = fetchRemoteMeta()
            if (remote != null && remote.second != readBlobSha(metaFile)) {
                val bytes = fetchBytes(remote.first)
                if (verifyGitBlobSha(bytes, remote.second)) {
                    val tmp = File(dir, ".libmathengine.so.tmp")
                    tmp.writeBytes(bytes)
                    tmp.setReadable(true, false)
                    tmp.setExecutable(false, false)
                    if (tmp.renameTo(libFile)) {
                        metaFile.writeText(remote.second)
                        Log.i(TAG, "Engine library updated to blob ${remote.second.take(10)}")
                    } else {
                        tmp.delete()
                    }
                } else {
                    Log.e(TAG, "Downloaded engine failed blob-SHA check; keeping cached version")
                }
            }

            if (libFile.canRead()) {
                // Must be set BEFORE any MathEngineBridge static-init (first use)
                MathEngineBridge.libOverridePath = libFile.absolutePath
                Log.d(TAG, "Using hot-updated engine: ${libFile.path}")
            } else {
                Log.d(TAG, "No cached engine update; using bundled libmathengine.so")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Engine update check failed; falling back to bundled library", t)
        }
    }

    /** @return Pair(downloadUrl, gitBlobSha) or null when unavailable. */
    private fun fetchRemoteMeta(): Pair<String, String>? = try {
        val json = client.newCall(
            Request.Builder()
                .url(META_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body!!.string()
        }
        val obj = JSONObject(json)
        val url = obj.optString("download_url", "").takeIf { it.isNotBlank() }
        url?.let { it to obj.getString("sha") }
    } catch (t: Throwable) {
        Log.d(TAG, "Metadata check unavailable", t)
        null
    }

    private fun fetchBytes(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).build())
            .execute().use { resp ->
                require(resp.isSuccessful) { "engine download HTTP ${resp.code}" }
                resp.body!!.bytes()
            }

    private fun readBlobSha(metaFile: File): String? =
        try { metaFile.readText().trim() } catch (_: Throwable) { null }

    /** Git blog object SHA1 = sha1("blob <size>\0" + content) — matches the API `sha`. */
    private fun verifyGitBlobSha(bytes: ByteArray, expectedSha: String): Boolean {
        val header = "blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-1").apply {
            update(header); update(bytes)
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(expectedSha, ignoreCase = true)
    }
}
