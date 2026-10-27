package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlin.math.hypot

/**
 * History tab as its own fragment. Built entirely in code: header, scrollable
 * item list, and the "Clear History" pill. Press = click, hold = tooltip.
 */
class HistoryFragment : Fragment() {

    private var itemsLayout: LinearLayout? = null
    private var header: TextView? = null
    private var clearButton: NeumorphicKeyView? = null
    private val wobbleAnimators = mutableListOf<ValueAnimator>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val activity = requireActivity() as MainActivity
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        header = TextView(requireContext()).apply {
            text = "Recent Calculations"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        root.addView(header)

        val scroll = ScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        itemsLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(itemsLayout)
        root.addView(scroll)

        clearButton = NeumorphicKeyView(requireContext()).apply {
            text = "Clear History"
            textSize = 13f
            cornerRadiusDp = 20f
            isDarkThemeMode = activity.isDarkMode
            layoutParams = LinearLayout.LayoutParams(dp(122), dp(38)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            }
            setOnClickListener { activity.clearHistory() }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(
                    this, "Clear History — remove all saved calculations"
                ) else activity.dismissButtonPopup()
            }
        }
        root.addView(clearButton)

        // Fill with current data once attached (theme set by activity too)
        root.post { refresh(activity.calculationHistory, activity.isDarkMode) }
        return root
    }

    /** Rebuild the item list from the activity's history data. */
    fun refresh(items: List<String>, dark: Boolean) {
        if (view == null) return
        header?.setTextColor(if (dark) Color.parseColor("#E0E0E0") else Color.parseColor("#333333"))
        clearButton?.isDarkThemeMode = dark

        val textColor = if (dark) Color.parseColor("#E0E0E0") else Color.parseColor("#333333")
        val subColor = if (dark) Color.parseColor("#8E8E8E") else Color.parseColor("#666666")

        val lay = itemsLayout ?: return
        lay.removeAllViews()
        val activity = activity as? MainActivity ?: return

        if (items.isEmpty()) {
            lay.addView(TextView(requireContext()).apply {
                text = "No calculations in history yet"
                setTextColor(subColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 40)
            })
        } else {
            items.take(15).forEach { item ->
                lay.addView(TextView(requireContext()).apply {
                    text = item
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(12, 14, 12, 14)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { activity.showButtonToast("History item: $item") }
                })
            }
        }
    }

    /** Theme flip: retint everything in the history list. */
    fun onThemeChanged(dark: Boolean) {
        (activity as? MainActivity)?.let { refresh(it.calculationHistory, dark) }
    }

    /** The whole history view gets kicked by the ripple wavefront: outward
     *  push, spring-back, quick settle. */
    fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
        val view = view ?: return
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()

        val speed = maxRadius / revealDurationMs
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val vx = loc[0] + view.width / 2f
        val vy = loc[1] + view.height / 2f
        val dx = vx - cx
        val dy = vy - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val proximity = 1f - (dist / maxRadius).coerceIn(0f, 1f)
        val bump = 0.015f + 0.035f * proximity
        val delay = (dist / speed).toLong().coerceAtMost(revealDurationMs - 40)
        // unit vector pointing away from the ripple origin
        val invLen = if (dist > 0.5f) 1f / dist else 0f
        val pushX = dx * invLen
        val pushY = dy * invLen
        val pushDist = 7f * resources.displayMetrics.density * (0.5f + proximity)

        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 480L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48 // seconds
                val decay = Math.exp(-7.5 * t)
                val pulse = decay * Math.sin(t * 22.0) // outward-first damped spring
                view.scaleX = (1.0 + bump * pulse).toFloat()
                view.scaleY = (1.0 + bump * pulse).toFloat()
                view.translationX = (pushDist * pulse).toFloat() * pushX
                view.translationY = (pushDist * pulse).toFloat() * pushY
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.translationX = 0f
                    view.translationY = 0f
                }
            })
            start()
            wobbleAnimators.add(this)
        }
    }

    /** Damped-spring entrance pulse when tab scrolling settles on History. */
    fun springSettle(fromDirection: Int) {
        val v = view ?: return
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        val slidePx = 24f * resources.displayMetrics.density * fromDirection
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = decay * Math.sin(t * 22.0)
                val springX = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                v.translationX = slidePx * springX * 0.5f
                v.scaleX = (1.0 + 0.03 * pulse).toFloat()
                v.scaleY = (1.0 + 0.03 * pulse).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.translationX = 0f
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
            })
            start()
            wobbleAnimators.add(this)
        }
    }

    override fun onDestroyView() {
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        itemsLayout = null
        header = null
        clearButton = null
        super.onDestroyView()
    }
}
