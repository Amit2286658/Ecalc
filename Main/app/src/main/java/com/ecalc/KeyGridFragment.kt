package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import androidx.fragment.app.Fragment
import kotlin.math.hypot

data class KeySpec(val label: String, val isAccent: Boolean)

class KeyGridFragment : Fragment() {

    companion object {
        private const val ARG_KEY_SET = "key_set"

        fun newInstance(keySet: Int): KeyGridFragment = KeyGridFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KEY_SET, keySet) }
        }

        private val BASIC_KEYS = listOf(
            KeySpec("AC", true), KeySpec("⌫", true), KeySpec("M+", true), KeySpec("M-", true),
            KeySpec("7", false), KeySpec("8", false), KeySpec("9", false), KeySpec("x", true),
            KeySpec("4", false), KeySpec("5", false), KeySpec("6", false), KeySpec("÷", true),
            KeySpec("1", false), KeySpec("2", false), KeySpec("3", false), KeySpec("+", true),
            KeySpec("0", false), KeySpec(".", false), KeySpec("-", true), KeySpec("=", true)
        )

        private val EXTENDED_KEYS = listOf(
            KeySpec("(", true), KeySpec(")", true), KeySpec("%", true), KeySpec("^", true),
            KeySpec("7", false), KeySpec("8", false), KeySpec("9", false), KeySpec("√", true),
            KeySpec("4", false), KeySpec("5", false), KeySpec("6", false), KeySpec("ⁿ√", true),
            KeySpec("1", false), KeySpec("2", false), KeySpec("3", false), KeySpec("!", true),
            KeySpec("0", false), KeySpec(".", false), KeySpec("EE", true), KeySpec("=", true)
        )

        private val TRIG_KEYS = listOf(
            KeySpec("sin", true), KeySpec("cos", true), KeySpec("tan", true), KeySpec("π", true),
            KeySpec("asin", true), KeySpec("acos", true), KeySpec("atan", true), KeySpec("e", true),
            KeySpec("sec", true), KeySpec("cosec", true), KeySpec("cot", true), KeySpec("ln", true),
            KeySpec("asec", true), KeySpec("acosec", true), KeySpec("acot", true), KeySpec("1/x", true),
            KeySpec("sinh", true), KeySpec("cosh", true), KeySpec("tanh", true), KeySpec("=", true)
        )

        private val FX_KEYS = listOf(
            KeySpec("abs", true), KeySpec("log", true), KeySpec("log₂", true), KeySpec("round", true),
            KeySpec("x²", true), KeySpec("x³", true), KeySpec("xʸ", true), KeySpec("10ˣ", true),
            KeySpec("eˣ", true), KeySpec("nPr", true), KeySpec("nCr", true), KeySpec("cbrt", true),
            KeySpec("ceil", true), KeySpec("floor", true), KeySpec("rand", true), KeySpec("d/dx", true),
            KeySpec("gcd", true), KeySpec("lcm", true), KeySpec("Ans", true), KeySpec("=", true)
        )
    }

    private val keyViews = mutableListOf<NeumorphicKeyView>()
    private val wobbleAnimators = mutableListOf<ValueAnimator>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val specs = when (requireArguments().getInt(ARG_KEY_SET)) {
            1 -> BASIC_KEYS
            2 -> EXTENDED_KEYS
            3 -> TRIG_KEYS
            else -> FX_KEYS          // position 4 → fx (units removed)
        }
        val activity = requireActivity() as MainActivity

        val root = FrameLayout(requireContext())
        val grid = GridLayout(requireContext()).apply {
            columnCount = 4; rowCount = 5
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(grid)

        specs.forEachIndexed { index, spec ->
            val keyView = NeumorphicKeyView(requireContext()).apply {
                text = spec.label; contentDescription = spec.label
                isAccentKey = spec.isAccent; isDarkThemeMode = activity.isDarkMode
                cornerRadiusDp = 8f
                setOnClickListener {
                    activity.showButtonToast(spec.label); activity.onKeyClicked(spec.label)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, activity.keyDescriptionFor(spec.label))
                    else activity.dismissButtonPopup()
                }
            }
            val params = GridLayout.LayoutParams(
                GridLayout.spec(index / 4, 1f), GridLayout.spec(index % 4, 1f)
            ).apply { width = 0; height = 0; setGravity(Gravity.FILL) }
            grid.addView(keyView, params); keyViews.add(keyView)
        }
        return root
    }

    fun applyTheme(dark: Boolean) { if (view != null) keyViews.forEach { it.isDarkThemeMode = dark } }

    fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
        if (view == null) return
        wobbleAnimators.forEach { it.cancel() }; wobbleAnimators.clear()
        val speed = maxRadius / revealDurationMs
        val density = resources.displayMetrics.density
        keyViews.forEach { view ->
            if (view.width == 0 || view.height == 0) return@forEach
            val loc = IntArray(2); view.getLocationInWindow(loc)
            val vx = loc[0] + view.width/2f; val vy = loc[1] + view.height/2f
            val dx = vx - cx; val dy = vy - cy; val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val proximity = 1f - (dist/maxRadius).coerceIn(0f, 1f)
            val bump = (0.015f + 0.035f*proximity)*1.9f; val rotAmp = 6f*proximity
            val delay = (dist/speed).toLong().coerceAtMost(revealDurationMs-40)
            val invLen = if (dist > 0.5f) 1f/dist else 0f
            val pushX = dx*invLen; val pushY = dy*invLen
            val pushDist = 7f*density*(0.5f+proximity)*1.9f

            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = delay; duration = 480L; interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float; val t = f*0.48
                    val decay = Math.exp(-7.5*t); val pulse = decay*Math.sin(t*22.0)
                    view.scaleX = (1.0+bump*pulse).toFloat(); view.scaleY = (1.0+bump*pulse).toFloat()
                    view.translationX = (pushDist*pulse).toFloat()*pushX; view.translationY = (pushDist*pulse).toFloat()*pushY
                    view.rotation = (rotAmp*decay*Math.sin(t*22.0+0.6)).toFloat()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { view.scaleX=1f;view.scaleY=1f;view.translationX=0f;view.translationY=0f;view.rotation=0f }
                })
                start(); wobbleAnimators.add(this)
            }
        }
    }

    /** Damped-spring entrance pulse when tab scrolling settles on this keypad. */
    fun springSettle(fromDirection: Int) {
        if (view == null) return
        wobbleAnimators.forEach { it.cancel() }; wobbleAnimators.clear()
        val density = resources.displayMetrics.density
        val slidePx = 22f * density * fromDirection
        keyViews.forEachIndexed { idx, kv ->
            if (kv.width == 0 || kv.height == 0) return@forEachIndexed
            val row = idx / 4
            val col = idx % 4
            val delay = ((row * 14) + (col * 10)).toLong()
            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = delay; duration = 480L; interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float; val t = f * 0.48
                    val decay = Math.exp(-7.5 * t); val pulse = decay * Math.sin(t * 22.0)
                    val springX = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                    kv.translationX = slidePx * springX * 0.45f
                    kv.scaleX = (1.0 + 0.045 * pulse).toFloat()
                    kv.scaleY = (1.0 + 0.045 * pulse).toFloat()
                    kv.rotation = (2.2 * fromDirection * decay * Math.sin(t * 22.0 + 0.6)).toFloat()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        kv.scaleX = 1f; kv.scaleY = 1f; kv.translationX = 0f; kv.rotation = 0f
                    }
                })
                start(); wobbleAnimators.add(this)
            }
        }
    }

    override fun onDestroyView() { wobbleAnimators.forEach { it.cancel() }; wobbleAnimators.clear(); keyViews.clear(); super.onDestroyView() }
}
