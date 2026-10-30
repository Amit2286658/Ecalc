package com.ecalc

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatTextView

/**
 * Custom View that implements realistic 3D Neumorphic styling for calculator keys.
 * Supports dual soft shadows (highlight and shade), pressed-in depth inversion,
 * and adaptive dark/light theme palettes.
 *
 * Shadow technique: each shadow is an explicitly blurred round-rect
 * ([BlurMaskFilter], rendered on this view's software layer) drawn inside a
 * rounded clipping contour slightly larger than the key pill. Because the surrounding
 * glow is always bounded by a rounded silhouette, no renderer path (hardware layer
 * drop-outs, clip variants, shadow-layer quirks) can ever paint an opaque square
 * block around the key.
 */
class NeumorphicKeyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var isDarkThemeMode: Boolean = true
        set(value) {
            field = value
            updateColors()
            invalidate()
        }

    var isAccentKey: Boolean = false
        set(value) {
            field = value
            updateColors()
            invalidate()
        }

    var cornerRadiusDp: Float = 8f
        set(value) {
            field = value
            invalidate()
        }

    private var isKeyPressed: Boolean = false
    private var longPressConsumed: Boolean = false
    /** Notified with `true` when a hold turns into a long-press, `false` when
     *  that gesture ends (finger up / cancelled) — used for hold-lived popups. */
    var holdListener: ((Boolean) -> Unit)? = null
    private val longPressChecker = Runnable {
        if (isKeyPressed && performLongClick()) {
            longPressConsumed = true
            holdListener?.invoke(true)
        }
    }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        // Software layer is required for BlurMaskFilter shadows to rasterize.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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
            applyShadowColors(surface, lightShadow, darkShadow,
                if (isAccentKey) Color.parseColor("#2E8B90") else Color.parseColor("#CCCCCC"))
        } else {
            val surface = if (isKeyPressed) Color.parseColor("#EAEAEA") else Color.parseColor("#F3F3F3")
            val lightShadow = Color.parseColor("#FFFFFF")
            val darkShadow = Color.parseColor("#D5D5D5")
            applyShadowColors(surface, lightShadow, darkShadow,
                if (isAccentKey) Color.parseColor("#E58A7B") else Color.parseColor("#444444"))
        }

        if (isKeyPressed) {
            // Pressed-in: tighter shadow with INVERTED offsets (light moves
            // down-right, dark up-left → inward depth inversion)
            shadowBlurPx = blur * 0.5f
            val o = offset * 0.4f
            lightDx = o; lightDy = o
            darkDx = -o; darkDy = -o
        } else {
            // Raised convex neumorphic look: light up-left, dark down-right
            shadowBlurPx = blur
            lightDx = -offset; lightDy = -offset
            darkDx = offset; darkDy = offset
        }

        lightShadowPaint.maskFilter = BlurMaskFilter(shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
        darkShadowPaint.maskFilter = BlurMaskFilter(shadowBlurPx, BlurMaskFilter.Blur.NORMAL)
    }

    private fun applyShadowColors(surface: Int, lightShadow: Int, darkShadow: Int, textColor: Int) {
        surfacePaint.color = surface
        // Shadow paints carry the shadow COLOR itself; the blurred halo they
        // produce is later over-painted in the middle by the opaque surface pill.
        lightShadowPaint.color = lightShadow
        darkShadowPaint.color = darkShadow
        setTextColor(textColor)
    }

    var shadowPadDp: Float = 5f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val pad = shadowPadDp * density
        val radius = cornerRadiusDp * density

        rectF.set(pad, pad, width - pad, height - pad)

        // Rounded clip boundary: the glow may only exist inside a rounded
        // contour ~pad-extended around the pill — this is what makes an
        // opaque square halo geometrically impossible on any pipeline.
        val grow = pad * 0.9f
        outerRect.set(rectF.left - grow, rectF.top - grow, rectF.right + grow, rectF.bottom + grow)
        clipPath.rewind()
        clipPath.addRoundRect(outerRect, radius + grow, radius + grow, Path.Direction.CW)
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        // Soft shadows: blurred pill drawn offset — dark down-right / light
        // up-left when raised; inverted when pressed.
        val saveShadow = canvas.save()
        canvas.translate(darkDx, darkDy)
        canvas.drawRoundRect(rectF, radius, radius, darkShadowPaint)
        canvas.restoreToCount(saveShadow)

        val saveLight = canvas.save()
        canvas.translate(lightDx, lightDy)
        canvas.drawRoundRect(rectF, radius, radius, lightShadowPaint)
        canvas.restoreToCount(saveLight)

        // Key surface on top (opaque pill hides the halo interiors)
        surfacePaint.maskFilter = null
        canvas.drawRoundRect(rectF, radius, radius, surfacePaint)
        canvas.restoreToCount(saveCount)

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
