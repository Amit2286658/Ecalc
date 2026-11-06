package com.ecalc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * Shared frosted-glass helper used by every modal layer in the app:
 * the File-Manager drawer backdrop, the About/Info window, and the
 * hold-tooltips (each at its own opacity). Renders a view tree into a
 * downsampled bitmap and runs the app's signature two-pass box blur so all
 * frosted layers share the exact same texture.
 */
object Frost {

    /**
     * Renders [sourceView] at 1/[downscale] resolution, blurs it with a
     * two-pass box ([radius] px at that low resolution — plenty strong after
     * the 6× upscale with bilinear filtering) and returns the SMALL bitmap.
     * Display it in an `ImageView` with `ScaleType.FIT_XY`.
     *
     * NOTE: children that are `View.INVISIBLE`/`GONE` are skipped by
     * [View.draw], so overlays whose visibility is managed around the call
     * are naturally excluded from the snapshot.
     */
    fun frozenBackdrop(sourceView: View, downscale: Int = 6, radius: Int = 8): Bitmap? {
        val w = sourceView.width
        val h = sourceView.height
        if (w <= 0 || h <= 0) return null
        return try {
            val sw = (w / downscale).coerceAtLeast(16)
            val sh = (h / downscale).coerceAtLeast(16)
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(1f / downscale, 1f / downscale)
            sourceView.draw(canvas)

            val pixels = IntArray(sw * sh)
            val temp = IntArray(sw * sh)
            bmp.getPixels(pixels, 0, sw, 0, 0, sw, sh)

            val r = radius.coerceIn(2, 14)
            val div = 2 * r + 1
            repeat(2) {
                for (y in 0 until sh) {
                    val rowOffset = y * sw
                    for (x in 0 until sw) {
                        var sumA = 0
                        var sumR = 0
                        var sumG = 0
                        var sumB = 0
                        for (k in -r..r) {
                            val px = (x + k).coerceIn(0, sw - 1)
                            val c = pixels[rowOffset + px]
                            sumA += (c ushr 24) and 0xFF
                            sumR += (c ushr 16) and 0xFF
                            sumG += (c ushr 8) and 0xFF
                            sumB += c and 0xFF
                        }
                        temp[rowOffset + x] =
                            ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                    }
                }
                for (x in 0 until sw) {
                    for (y in 0 until sh) {
                        var sumA = 0
                        var sumR = 0
                        var sumG = 0
                        var sumB = 0
                        for (k in -r..r) {
                            val py = (y + k).coerceIn(0, sh - 1)
                            val c = temp[py * sw + x]
                            sumA += (c ushr 24) and 0xFF
                            sumR += (c ushr 16) and 0xFF
                            sumG += (c ushr 8) and 0xFF
                            sumB += c and 0xFF
                        }
                        pixels[y * sw + x] =
                            ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                    }
                }
            }
            bmp.setPixels(pixels, 0, sw, 0, 0, sw, sh)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * LIVE frosted blur for Android 12+ (API 31): applies a GPU
     * [RenderEffect] blur to [target] so everything behind the overlay stays
     * live-animating through the frost. Returns `false` on older versions
     * (and on any GPU failure) so the caller can fall back to the static
     * [frozenBackdrop] snapshot.
     */
    fun tryLiveBlur(target: View?, radiusPx: Float): Boolean {
        if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val effect = RenderEffect.createBlurEffect(
                radiusPx, radiusPx, Shader.TileMode.CLAMP
            )
            target.setRenderEffect(effect)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Removes a blur applied by [tryLiveBlur] (no-op where unsupported). */
    fun clearLiveBlur(target: View?) {
        if (target == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            target.setRenderEffect(null)
        } catch (_: Throwable) {
            // ignore
        }
    }
}
