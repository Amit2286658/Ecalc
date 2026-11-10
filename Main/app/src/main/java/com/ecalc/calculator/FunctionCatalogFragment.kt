package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlin.math.hypot

/**
 * Tab 5 (6th tab) in the Basic Calculator:
 * Displays a live-synced list of all functions, operators (including operator overloads), and
 * constants from `stdlib + patches + project1`.
 * - Tapping any item inserts it into the Basic Calculator's expression input.
 * - Holding any item displays a frosted-glass popup info window (`showButtonPopup`) with its
 *   full signature, source module (`project1`, `patch`, or `stdlib`), parameters, and return info.
 */
class FunctionCatalogFragment : Fragment() {

    private var outerLayout: LinearLayout? = null
    private var filterChipsRow: LinearLayout? = null
    private var helperKeysRow: LinearLayout? = null
    private var tvHeaderSubtitle: TextView? = null
    private var listContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null

    private var allItems: List<CalculatorCatalogItem> = emptyList()
    private var activeFilter: String = "ALL" // "ALL", "PROJECT1", "PATCH", "STDLIB", "OP"
    private val filterChipViews = mutableListOf<Pair<String, NeumorphicKeyView>>()
    private val helperKeyViews = mutableListOf<NeumorphicKeyView>()
    private val wobbleAnimators = mutableListOf<ValueAnimator>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val activity = activity as? MainActivity
        val dark = activity?.isDarkMode ?: true
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(12f), dp(6f), dp(12f), dp(8f))
        }
        outerLayout = root

        // 1. Filter Chips + Quick Expression Helper Row
        val topControlsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4f) }
        }

        val chipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(0, dp(32f), 1f)
        }
        val chipsInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterChipsRow = chipsInner
        filterChipViews.clear()

        val filters = listOf(
            "ALL" to "All",
            "PROJECT1" to "project1",
            "PATCH" to "Patches",
            "STDLIB" to "Stdlib",
            "OP" to "Operators"
        )
        for ((filterKey, label) in filters) {
            val chipW = (label.length * 7.5f + 24f).coerceAtLeast(52f)
            val chip = NeumorphicKeyView(ctx).apply {
                text = label
                textSize = 10.8f
                cornerRadiusDp = 10f
                shadowPadDp = 2.2f
                isAccentKey = (filterKey == activeFilter)
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(chipW), dp(28f)).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    activeFilter = filterKey
                    updateFilterChipSelection(dark)
                    renderFilteredItems(dark)
                }
            }
            filterChipViews.add(filterKey to chip)
            chipsInner.addView(chip)
        }
        chipsScroll.addView(chipsInner)
        topControlsRow.addView(chipsScroll)

        // Quick helper keys `(`, `,`, `)`, `⌫`, `=` for completing multi-arg function calls
        val helpersContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(32f)
            ).apply { marginStart = dp(4f) }
        }
        helperKeysRow = helpersContainer
        helperKeyViews.clear()
        val helperSpecs = listOf(
            "(" to false,
            "," to false,
            ")" to false,
            "⌫" to false,
            "=" to true
        )
        for ((keyLabel, isAccent) in helperSpecs) {
            val hBtn = NeumorphicKeyView(ctx).apply {
                text = keyLabel
                textSize = 11.5f
                cornerRadiusDp = 9.5f
                shadowPadDp = 2.0f
                isAccentKey = isAccent
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(30f), dp(28f)).apply {
                    marginStart = dp(2f)
                }
                setOnClickListener {
                    (this@FunctionCatalogFragment.activity as? MainActivity)?.onKeyClicked(keyLabel)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    val act = this@FunctionCatalogFragment.activity as? MainActivity
                    if (act != null) {
                        if (held) act.showButtonPopup(this, act.keyDescriptionFor(keyLabel))
                        else act.dismissButtonPopup()
                    }
                }
            }
            helperKeyViews.add(hBtn)
            helpersContainer.addView(hBtn)
        }
        topControlsRow.addView(helpersContainer)
        root.addView(topControlsRow)

        // 2. Subtitle / Count & Hint Bar
        tvHeaderSubtitle = TextView(ctx).apply {
            text = "Tap any function/operator to insert • Hold any item for popup info window"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
            setPadding(dp(2f), 0, dp(2f), dp(4f))
        }
        root.addView(tvHeaderSubtitle)

        // 3. Scrollable Catalog List
        val sv = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        scrollView = sv

        val containerLinear = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        listContainer = containerLinear
        sv.addView(containerLinear)
        root.addView(sv)

        reloadCatalogFromEngine()
        return root
    }

    override fun onResume() {
        super.onResume()
        reloadCatalogFromEngine()
    }

    private fun updateFilterChipSelection(dark: Boolean) {
        for ((key, chip) in filterChipViews) {
            chip.isDarkThemeMode = dark
            chip.isAccentKey = (key == activeFilter)
        }
        helperKeyViews.forEach { it.isDarkThemeMode = dark }
    }

    /** Asynchronously links `project1` and reloads all functions & operators from `stdlib + patches + project1`. */
    fun reloadCatalogFromEngine() {
        val ctx = context?.applicationContext ?: return
        MathEngineManager.worker.execute {
            val items = runCatching {
                MathEngineManager.collectCalculatorFunctionCatalog(ctx)
            }.getOrDefault(emptyList())
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                allItems = items
                val dark = (activity as? MainActivity)?.isDarkMode ?: true
                updateFilterChipSelection(dark)
                renderFilteredItems(dark)
            }
        }
    }

    fun onThemeChanged(isDark: Boolean) {
        tvHeaderSubtitle?.setTextColor(Color.parseColor(if (isDark) "#8E8E8E" else "#666666"))
        updateFilterChipSelection(isDark)
        renderFilteredItems(isDark)
    }

    private fun renderFilteredItems(isDark: Boolean) {
        val ctx = context ?: return
        val container = listContainer ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        container.removeAllViews()

        val filtered = when (activeFilter) {
            "PROJECT1" -> allItems.filter { it.originGroup == "project1" }
            "PATCH" -> allItems.filter { it.originGroup == "patch" }
            "STDLIB" -> allItems.filter { it.originGroup == "stdlib" }
            "OP" -> allItems.filter { it.kind == "op" }
            else -> allItems
        }

        val p1Count = allItems.count { it.originGroup == "project1" }
        val patchCount = allItems.count { it.originGroup == "patch" }
        val stdCount = allItems.count { it.originGroup == "stdlib" }
        tvHeaderSubtitle?.text =
            "Showing ${filtered.size} items (project1: $p1Count • patches: $patchCount • stdlib: $stdCount) — Tap to insert, Hold for info"

        val rowBg = Color.parseColor(if (isDark) "#292929" else "#ECECEC")
        val proj1RowBg = Color.parseColor(if (isDark) "#1F3033" else "#F7EAE7")
        val strokeColor = Color.parseColor(if (isDark) "#3C3C3C" else "#D6D6D6")
        val proj1Stroke = Color.parseColor(if (isDark) "#13959B" else "#E58A7B")
        val primaryText = Color.parseColor(if (isDark) "#EAEAEA" else "#2B2B2B")
        val secondaryText = Color.parseColor(if (isDark) "#9A9A9A" else "#626262")

        if (filtered.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                text = "No functions or operators in this category."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(secondaryText)
                gravity = Gravity.CENTER
                setPadding(dp(12f), dp(24f), dp(12f), dp(24f))
            }
            container.addView(emptyTv)
            return
        }

        for (item in filtered) {
            val isProj1 = (item.originGroup == "project1")
            val badgeColor = when {
                item.originGroup == "project1" -> Color.parseColor(if (isDark) "#13959B" else "#D96B59")
                item.originGroup == "patch" -> Color.parseColor(if (isDark) "#DCDCAA" else "#9A6700")
                item.kind == "op" -> Color.parseColor(if (isDark) "#C586C0" else "#8250DF")
                else -> Color.parseColor(if (isDark) "#569CD6" else "#0969DA")
            }

            val rowCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5f) }
                setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 11f * density
                    setColor(if (isProj1) proj1RowBg else rowBg)
                    setStroke(
                        ((if (isProj1) 1.3f else 1f) * density).toInt().coerceAtLeast(1),
                        if (isProj1) proj1Stroke else strokeColor
                    )
                }
                isClickable = true
                isFocusable = true
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvSig = TextView(ctx).apply {
                text = item.signature
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(primaryText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val badgeLabel = when (item.originGroup) {
                "project1" -> if (item.kind == "op") "project1 • op [${item.moduleName}]" else "project1 [${item.moduleName}]"
                "patch" -> "patch [${item.moduleName}]"
                else -> "stdlib [${item.moduleName}]"
            }
            val tvBadge = TextView(ctx).apply {
                text = badgeLabel
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(badgeColor)
                setPadding(dp(6f), dp(1f), 0, dp(1f))
            }

            topRow.addView(tvSig)
            topRow.addView(tvBadge)
            rowCard.addView(topRow)

            val tvDesc = TextView(ctx).apply {
                text = item.shortDescription
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTextColor(secondaryText)
                maxLines = 2
                setPadding(0, dp(2f), 0, 0)
            }
            rowCard.addView(tvDesc)

            // Tap to insert function/operator/constant into Basic Calculator expression
            rowCard.setOnClickListener {
                pulseRow(rowCard)
                (activity as? MainActivity)?.onCatalogItemClicked(item)
            }

            // Hold to display frosted popup info window
            var isHoldActive = false
            rowCard.setOnLongClickListener {
                isHoldActive = true
                rowCard.parent?.requestDisallowInterceptTouchEvent(true)
                (activity as? MainActivity)?.showButtonPopup(rowCard, item.toPopupInfoText())
                true
            }
            rowCard.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isHoldActive = false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val act = activity as? MainActivity
                        if (isHoldActive || act?.keyPopupOverlay != null) {
                            isHoldActive = false
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            act?.dismissButtonPopup()
                        }
                    }
                }
                false
            }

            container.addView(rowCard)
        }
    }

    private fun pulseRow(view: View) {
        if (view.width > 0) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val t = f * 0.45
                val pulse = (Math.exp(-7.5 * t) * Math.sin(t * 22.0)).toFloat()
                view.scaleX = 1f + 0.035f * pulse
                view.scaleY = 1f + 0.035f * pulse
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            })
            start()
        }
    }

    fun springSettle(direction: Int, distance: Int = 1) {
        val root = outerLayout ?: return
        val clampedDist = distance.coerceIn(1, 4)
        val d = clampedDist.toFloat()
        val density = resources.displayMetrics.density
        val slidePx = (8f + (d - 1f) * 10f) * density * direction
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460L + (clampedDist - 1) * 55L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                root.translationX = slidePx * springFactor * 0.4f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.translationX = 0f
                }
            })
            start()
        }
    }

    fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        val root = outerLayout ?: return
        if (!root.isShown || root.width == 0 || root.height == 0) return
        val speed = maxRadius / revealDurationMs
        val density = resources.displayMetrics.density
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val vx = loc[0] + root.width / 2f
        val vy = loc[1] + root.height / 2f
        val dist = hypot((vx - cx).toDouble(), (vy - cy).toDouble()).toFloat()
        val proximity = 1f - (dist / maxRadius).coerceIn(0f, 1f)
        val bump = 0.015f + 0.025f * proximity
        val delay = (dist / speed).toLong().coerceAtMost(revealDurationMs - 40)
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 480L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val pulse = (Math.exp(-7.5 * t) * Math.sin(t * 22.0)).toFloat()
                root.scaleX = 1f + bump * pulse
                root.scaleY = 1f + bump * pulse
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.scaleX = 1f
                    root.scaleY = 1f
                }
            })
            start()
            wobbleAnimators.add(this)
        }
    }
}
