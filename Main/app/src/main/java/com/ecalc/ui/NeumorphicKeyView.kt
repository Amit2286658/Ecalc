package com.ecalc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatTextView

/**
 * Custom View that implements realistic 3D Neumorphic styling for calculator keys.
 * Supports dual soft shadows (highlight and shade), pressed-in depth inversion,
 * and adaptive dark/light theme palettes.
 *
 * Performance architecture:
 * Instead of putting the View itself in `LAYER_TYPE_SOFTWARE` (which forces the CPU to
 * re-run `BlurMaskFilter` on every animation frame whenever `ViewPager2` or `springSettle`
 * scales/rotates the view), the blurred neumorphic shadow + pill surface for a given
 * `(width, height, dark, accent, pressed, radius, pad)` tuple is rendered once into a
 * shared software [Bitmap] cache ([shadowBitmapCache]) and drawn onto the hardware-accelerated
 * Canvas in O(1) GPU time.
 */
class NeumorphicKeyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    companion object {
        private val shadowBitmapCache = object : LruCache<String, Bitmap>(64) {
            override fun sizeOf(key: String, value: Bitmap): Int = 1
        }
    }

    var isDarkThemeMode: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                updateColors()
                invalidate()
            }
        }

    var isAccentKey: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateColors()
                invalidate()
            }
        }

    var cornerRadiusDp: Float = 8f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var isKeyPressed: Boolean = false
    private var longPressConsumed: Boolean = false
    /** Notified with `true` when a hold turns into a long-press, `false` when
     *  that gesture ends (finger up / cancelled) — used for hold-lived popups. */
    var holdListener: ((Boolean) -> Unit)? = null
    private val longPressChecker = Runnable {
        if (isKeyPressed) {
            val handled = performLongClick() || holdListener != null
            if (handled) {
                longPressConsumed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                holdListener?.invoke(true)
            }
        }
    }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rectF = RectF()
    private val outerRect = RectF()
    private val clipPath = Path()

    // Per-state shadow geometry, recomputed by updateColors() (pressed = inverted, tighter)
    private var shadowBlurPx: Float = 0f
    private var lightDx: Float = 0f
    private var lightDy: Float = 0f
    private var darkDx: Float = 0f
    private var darkDy: Float = 0f

    init {
        gravity = Gravity.CENTER
        textSize = 17f
        isClickable = true
        isFocusable = true
        updateColors()
    }

    fun updateColors() {
        val density = resources.displayMetrics.density
        val blur = 6f * density
        val offset = 3f * density

        if (isDarkThemeMode) {
            val surface = if (isKeyPressed) Color.parseColor("#2E2E2E") else Color.parseColor("#333333")
            val lightShadow = Color.parseColor("#3E3E3E")
            val darkShadow = Color.parseColor("#212121")
            applyShadowColors(
                surface, lightShadow, darkShadow,
                if (isAccentKey) Color.parseColor("#2E8B90") else Color.parseColor("#CCCCCC")
            )
        } else {
            val surface = if (isKeyPressed) Color.parseColor("#EAEAEA") else Color.parseColor("#F3F3F3")
            val lightShadow = Color.parseColor("#FFFFFF")
            val darkShadow = Color.parseColor("#D5D5D5")
            applyShadowColors(
                surface, lightShadow, darkShadow,
                if (isAccentKey) Color.parseColor("#E58A7B") else Color.parseColor("#444444")
            )
        }

        if (isKeyPressed) {
            shadowBlurPx = blur * 0.5f
            val o = offset * 0.4f
            lightDx = o; lightDy = o
            darkDx = -o; darkDy = -o
        } else {
            shadowBlurPx = blur
            lightDx = -offset; lightDy = -offset
            darkDx = offset; darkDy = offset
        }

        lightShadowPaint.maskFilter = BlurMaskFilter(shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
        darkShadowPaint.maskFilter = BlurMaskFilter(shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
    }

    private fun applyShadowColors(surface: Int, lightShadow: Int, darkShadow: Int, textColor: Int) {
        surfacePaint.color = surface
        lightShadowPaint.color = lightShadow
        darkShadowPaint.color = darkShadow
        setTextColor(textColor)
    }

    var shadowPadDp: Float = 5f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private fun getOrBuildShadowBitmap(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val cacheKey = "${w}x${h}:${if (isDarkThemeMode) 1 else 0}:${if (isAccentKey) 1 else 0}:${if (isKeyPressed) 1 else 0}:$cornerRadiusDp:$shadowPadDp"
        shadowBitmapCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val offCanvas = Canvas(bmp)
            val density = resources.displayMetrics.density
            val pad = shadowPadDp * density
            val radius = cornerRadiusDp * density

            rectF.set(pad, pad, w - pad, h - pad)
            val grow = pad * 0.9f
            outerRect.set(rectF.left - grow, rectF.top - grow, rectF.right + grow, rectF.bottom + grow)
            clipPath.rewind()
            clipPath.addRoundRect(outerRect, radius + grow, radius + grow, Path.Direction.CW)

            val saveCount = offCanvas.save()
            offCanvas.clipPath(clipPath)

            val saveShadow = offCanvas.save()
            offCanvas.translate(darkDx, darkDy)
            offCanvas.drawRoundRect(rectF, radius, radius, darkShadowPaint)
            offCanvas.restoreToCount(saveShadow)

            val saveLight = offCanvas.save()
            offCanvas.translate(lightDx, lightDy)
            offCanvas.drawRoundRect(rectF, radius, radius, lightShadowPaint)
            offCanvas.restoreToCount(saveLight)

            surfacePaint.maskFilter = null
            offCanvas.drawRoundRect(rectF, radius, radius, surfacePaint)
            offCanvas.restoreToCount(saveCount)

            shadowBitmapCache.put(cacheKey, bmp)
            bmp
        }.getOrNull()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = getOrBuildShadowBitmap(width, height)
        if (bmp != null) {
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        }
        super.onDraw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isKeyPressed = true
                longPressConsumed = false
                updateColors()
                invalidate()
                postDelayed(longPressChecker, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_UP -> {
                isKeyPressed = false
                removeCallbacks(longPressChecker)
                updateColors()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (longPressConsumed) {
                    longPressConsumed = false
                    holdListener?.invoke(false)
                } else performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                isKeyPressed = false
                removeCallbacks(longPressChecker)
                updateColors()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (longPressConsumed) {
                    longPressConsumed = false
                    holdListener?.invoke(false)
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }
}
