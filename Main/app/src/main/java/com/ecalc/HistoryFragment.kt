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
import android.view.animation.DecelerateInterpolator
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
            setTextColor(if (activity.isDarkMode) Color.parseColor("#E0E0E0") else Color.parseColor("#333333"))
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
                    this, "Clear History — every saved calculation slides off-screen and the list resets.\n" +
                        "Cleared results cannot be recovered; recent answers remain reusable via the Ans key."
                ) else activity.dismissButtonPopup()
            }
        }
        root.addView(clearButton)

        // Populate immediately and refresh once attached
        refresh(activity.calculationHistory, activity.isDarkMode)
        return root
    }

    /** Rebuild the item list from the activity's history data. */
    fun refresh(items: List<String>, dark: Boolean, fadeInPlaceholder: Boolean = false) {
        if (header == null) return
        header?.setTextColor(if (dark) Color.parseColor("#E0E0E0") else Color.parseColor("#333333"))
        clearButton?.isDarkThemeMode = dark

        val textColor = if (dark) Color.parseColor("#E0E0E0") else Color.parseColor("#333333")
        val subColor = if (dark) Color.parseColor("#8E8E8E") else Color.parseColor("#666666")

        val lay = itemsLayout ?: return
        val ctx = context ?: return
        lay.removeAllViews()
        val activity = activity as? MainActivity ?: return

        if (items.isEmpty()) {
            val placeholder = TextView(ctx).apply {
                text = "No calculations in history yet"
                setTextColor(subColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 40)
            }
            lay.addView(placeholder)
            if (fadeInPlaceholder) springFadeInView(placeholder)
        } else {
            items.take(15).forEach { item ->
                lay.addView(TextView(ctx).apply {
                    text = item
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(12, 14, 12, 14)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        activity.onHistoryItemClicked(item)
                        // Acknowledge the placement on the item itself (no toast)
                        alpha = 0.45f
                        animate().alpha(1f).setDuration(240L).start()
                    }
                })
            }
        }
    }

    /**
     * Cascading clear: every history entry slides left and out of the screen,
     * one after another (top first), then [onCleared] fires so the caller can
     * wipe the data and rebuild the list — the placeholder then fades in with
     * the damped-spring pulse.
     */
    fun animateClearThenReset(onCleared: () -> Unit) {
        val lay = itemsLayout
        if (lay == null || lay.childCount == 0) { onCleared(); return }
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()

        val count = lay.childCount
        var remaining = count
        for (idx in 0 until count) {
            val item = lay.getChildAt(idx)
            val exitDist = item.width.coerceAtLeast(lay.width).coerceAtLeast(1) + item.paddingRight
            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = idx * 45L
                duration = 300L
                interpolator = DecelerateInterpolator(1.1f)
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    item.translationX = -exitDist * f
                    item.alpha = 1f - (Math.pow(f.toDouble(), 1.6)).toFloat()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        item.translationX = -exitDist
                        item.alpha = 0f
                        remaining--
                        if (remaining == 0) {
                            lay.removeAllViews()
                            onCleared()
                        }
                    }
                })
                start()
                wobbleAnimators.add(this)
            }
        }
    }

    /** Damped-spring fade-in for the empty-history placeholder
     *  (e^(-7.5t)·sin(22t) pulse, same family as the app's other springs). */
    private fun springFadeInView(target: View) {
        target.alpha = 0f
        target.pivotX = target.width * 0.5f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val pulse = (Math.exp(-7.5 * t) * Math.sin(t * 22.0)).toFloat()
                target.alpha = (f / 0.28f).coerceIn(0f, 1f)
                target.scaleX = (1.0 + 0.045 * pulse).toFloat()
                target.scaleY = (1.0 + 0.045 * pulse).toFloat()
                target.translationX = (-10f * resources.displayMetrics.density * pulse).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    target.alpha = 1f
                    target.scaleX = 1f
                    target.scaleY = 1f
                    target.translationX = 0f
                }
            })
            start()
            wobbleAnimators.add(this)
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

    /**
     * Distance-dependent damped-spring entrance pulse when tab scrolling
     * settles on History (`distance` ranges from 1 for adjacent tab up to 4).
     */
    fun springSettle(fromDirection: Int, distance: Int = 1) {
        val v = view ?: return
        if (v.width == 0 || v.height == 0) {
            v.post {
                if (isAdded && view != null) {
                    springSettle(fromDirection, distance)
                }
            }
            return
        }
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()

        val clampedDist = distance.coerceIn(1, 4)
        val d = clampedDist.toFloat()
        val density = resources.displayMetrics.density
        val slidePx = (18f + (d - 1f) * 20f) * density * fromDirection
        val vertPx = (3f + (d - 1f) * 3f) * density
        val scaleAmp = 0.03f + (d - 1f) * 0.035f
        val rotAmp = (1.4f + (d - 1f) * 1.6f) * fromDirection
        val settleDuration = 460L + (clampedDist - 1) * 60L

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = settleDuration
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = decay * Math.sin(t * 22.0)
                val springX = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                v.translationX = slidePx * springX * 0.55f
                v.translationY = (vertPx * pulse).toFloat()
                v.scaleX = (1.0 + scaleAmp * pulse).toFloat()
                v.scaleY = (1.0 + scaleAmp * pulse).toFloat()
                v.rotation = (rotAmp * decay * Math.sin(t * 22.0 + 0.6)).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.translationX = 0f
                    v.translationY = 0f
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.rotation = 0f
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
