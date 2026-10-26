package com.ecalc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    init {
        gravity = Gravity.CENTER
        textSize = 17f
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

            surfacePaint.color = surface
            lightShadowPaint.color = surface
            darkShadowPaint.color = surface

            if (isKeyPressed) {
                // Inverted shadow for pressed inward effect
                lightShadowPaint.setShadowLayer(blur * 0.5f, offset * 0.4f, offset * 0.4f, lightShadow)
                darkShadowPaint.setShadowLayer(blur * 0.5f, -offset * 0.4f, -offset * 0.4f, darkShadow)
            } else {
                // Raised convex neumorphic look
                lightShadowPaint.setShadowLayer(blur, -offset, -offset, lightShadow)
                darkShadowPaint.setShadowLayer(blur, offset, offset, darkShadow)
            }

            val textColor = if (isAccentKey) Color.parseColor("#2E8B90") else Color.parseColor("#CCCCCC")
            setTextColor(textColor)
        } else {
            val surface = if (isKeyPressed) Color.parseColor("#EAEAEA") else Color.parseColor("#F3F3F3")
            val lightShadow = Color.parseColor("#FFFFFF")
            val darkShadow = Color.parseColor("#D5D5D5")

            surfacePaint.color = surface
            lightShadowPaint.color = surface
            darkShadowPaint.color = surface

            if (isKeyPressed) {
                lightShadowPaint.setShadowLayer(blur * 0.5f, offset * 0.4f, offset * 0.4f, lightShadow)
                darkShadowPaint.setShadowLayer(blur * 0.5f, -offset * 0.4f, -offset * 0.4f, darkShadow)
            } else {
                lightShadowPaint.setShadowLayer(blur, -offset, -offset, lightShadow)
                darkShadowPaint.setShadowLayer(blur, offset, offset, darkShadow)
            }

            val textColor = if (isAccentKey) Color.parseColor("#E58A7B") else Color.parseColor("#444444")
            setTextColor(textColor)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val pad = 5f * density
        val radius = cornerRadiusDp * density

        rectF.set(pad, pad, width - pad, height - pad)

        // Draw shadows and background surface
        canvas.drawRoundRect(rectF, radius, radius, darkShadowPaint)
        canvas.drawRoundRect(rectF, radius, radius, lightShadowPaint)
        canvas.drawRoundRect(rectF, radius, radius, surfacePaint)

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
