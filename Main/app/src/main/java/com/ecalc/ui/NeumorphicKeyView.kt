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
 * disabled/greyed-out state, custom vector lens icon rendering, and adaptive dark/light theme palettes.
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

    var isDarkThemeMode: Boolean = false
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

    /**
     * When true, draws a crisp geometric magnifying-glass lens icon centered inside the
     * neumorphic key using the current theme/accent text color.
     */
    var showLensIcon: Boolean = false
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
        if (isKeyPressed && isEnabled) {
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
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
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

    override fun setEnabled(enabled: Boolean) {
        val changed = (isEnabled != enabled)
        super.setEnabled(enabled)
        if (!enabled) {
            isKeyPressed = false
            removeCallbacks(longPressChecker)
        }
        if (changed) {
            updateColors()
            invalidate()
        }
    }

    fun updateColors() {
        val density = resources.displayMetrics.density
        val blur = 6f * density
        val offset = 3f * density

        if (isDarkThemeMode) {
            val surface = when {
                !isEnabled -> Color.parseColor("#2A2A2A")
                isKeyPressed -> Color.parseColor("#2E2E2E")
                else -> Color.parseColor("#333333")
            }
            val lightShadow = Color.parseColor("#3E3E3E")
            val darkShadow = Color.parseColor("#212121")
            val txtColor = when {
                !isEnabled -> Color.parseColor("#666666")
                isAccentKey -> Color.parseColor("#2E8B90")
                else -> Color.parseColor("#CCCCCC")
            }
            applyShadowColors(surface, lightShadow, darkShadow, txtColor)
        } else {
            val surface = when {
                !isEnabled -> Color.parseColor("#E6E6E6")
                isKeyPressed -> Color.parseColor("#EAEAEA")
                else -> Color.parseColor("#F3F3F3")
            }
            val lightShadow = Color.parseColor("#FFFFFF")
            val darkShadow = Color.parseColor("#D5D5D5")
            val txtColor = when {
                !isEnabled -> Color.parseColor("#9E9E9E")
                isAccentKey -> Color.parseColor("#E58A7B")
                else -> Color.parseColor("#444444")
            }
            applyShadowColors(surface, lightShadow, darkShadow, txtColor)
        }

        if (isKeyPressed && isEnabled) {
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
        val cacheKey = "${w}x${h}:${if (isDarkThemeMode) 1 else 0}:${if (isAccentKey) 1 else 0}:${if (isKeyPressed) 1 else 0}:${if (isEnabled) 1 else 0}:$cornerRadiusDp:$shadowPadDp"
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

            if (isEnabled) {
                val saveShadow = offCanvas.save()
                offCanvas.translate(darkDx, darkDy)
                offCanvas.drawRoundRect(rectF, radius, radius, darkShadowPaint)
                offCanvas.restoreToCount(saveShadow)

                val saveLight = offCanvas.save()
                offCanvas.translate(lightDx, lightDy)
                offCanvas.drawRoundRect(rectF, radius, radius, lightShadowPaint)
                offCanvas.restoreToCount(saveLight)
            }

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
        if (showLensIcon && width > 0 && height > 0) {
            val density = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val minDim = minOf(width, height).toFloat()
            val ringRadius = (minDim * 0.19f).coerceIn(4.2f * density, 6.8f * density)
            val lensCx = cx - ringRadius * 0.28f
            val lensCy = cy - ringRadius * 0.28f

            iconPaint.color = currentTextColor
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = (1.85f * density).coerceAtLeast(2f)
            canvas.drawCircle(lensCx, lensCy, ringRadius, iconPaint)

            val diag = 0.70710677f
            val handleStartX = lensCx + ringRadius * diag
            val handleStartY = lensCy + ringRadius * diag
            val handleEndX = lensCx + ringRadius * 1.68f
            val handleEndY = lensCy + ringRadius * 1.68f
            iconPaint.strokeWidth = (2.15f * density).coerceAtLeast(2.2f)
            canvas.drawLine(handleStartX, handleStartY, handleEndX, handleEndY, iconPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !isClickable) {
            // Consume touch on disabled button without firing click or letting parent card intercept
            return true
        }
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
        if (!isEnabled || !isClickable) return false
        return super.performClick()
    }
}
