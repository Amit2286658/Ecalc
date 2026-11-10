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
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * Tab 5 (6th tab) in the Basic Calculator:
 * Displays a recycled, zero-jank list of numerical functions and custom/overloaded operators
 * from `stdlib + patches + project1`.
 * - Uses [RecyclerView] with recycled ViewHolders and cached drawables so swiping between tabs
 *   and scrolling the list run at full frame rate with zero main-thread view inflation spikes.
 * - Tapping any item inserts it into the Basic Calculator's expression input.
 * - Holding any item displays a frosted-glass popup info window (`showButtonPopup`) with its
 *   full signature, source module (`project1`, `patch`, or `stdlib`), parameters, and return info.
 */
class FunctionCatalogFragment : Fragment() {

    private var outerLayout: LinearLayout? = null
    private var tvHeaderSubtitle: TextView? = null
    private var recyclerView: RecyclerView? = null
    private var adapter: CatalogAdapter? = null

    private var allItems: List<CalculatorCatalogItem> = emptyList()
    private var activeFilter: String = "ALL" // "ALL", "PROJECT1", "PATCH", "STDLIB", "OP"
    private var lastRenderedFilter: String = ""
    private var lastRenderedDark: Boolean? = null
    private var lastRenderedItemsRef: List<CalculatorCatalogItem>? = null

    private val filterChipViews = mutableListOf<Pair<String, NeumorphicKeyView>>()
    private val helperKeyViews = mutableListOf<NeumorphicKeyView>()
    private val wobbleAnimators = mutableListOf<ValueAnimator>()
    private val boldMonoTypeface: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

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
                    if (activeFilter != filterKey) {
                        activeFilter = filterKey
                        val curDark = (this@FunctionCatalogFragment.activity as? MainActivity)?.isDarkMode ?: true
                        updateFilterChipSelection(curDark)
                        renderFilteredItems(curDark, force = true)
                    }
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
            text = "Numerical functions & operators • Tap to insert, Hold for info"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
            setPadding(dp(2f), 0, dp(2f), dp(4f))
        }
        root.addView(tvHeaderSubtitle)

        // 3. Recycled List (RecyclerView) — only ~6 visible rows inflated at any time
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setHasFixedSize(true)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val catAdapter = CatalogAdapter(density, dark)
        adapter = catAdapter
        rv.adapter = catAdapter
        recyclerView = rv
        root.addView(rv)

        // Populate immediately with handpicked stdlib items, then sync project1 + patches on worker
        if (allItems.isEmpty()) {
            allItems = HANDPICKED_NUMERICAL_STDLIB_CATALOG
            renderFilteredItems(dark, force = true)
        }
        reloadCatalogFromEngine()
        return root
    }

    private fun updateFilterChipSelection(dark: Boolean) {
        for ((key, chip) in filterChipViews) {
            chip.isDarkThemeMode = dark
            chip.isAccentKey = (key == activeFilter)
        }
        helperKeyViews.forEach { it.isDarkThemeMode = dark }
    }

    /** Asynchronously links `project1` and reloads numerical functions & operators if changed. */
    fun reloadCatalogFromEngine() {
        val ctx = context?.applicationContext ?: return
        MathEngineManager.worker.execute {
            val items = runCatching {
                MathEngineManager.collectCalculatorFunctionCatalog(ctx)
            }.getOrDefault(HANDPICKED_NUMERICAL_STDLIB_CATALOG)
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val changed = (items !== allItems && items != allItems)
                allItems = items
                val dark = (activity as? MainActivity)?.isDarkMode ?: true
                if (changed || lastRenderedItemsRef == null) {
                    updateFilterChipSelection(dark)
                    renderFilteredItems(dark, force = true)
                }
            }
        }
    }

    fun onThemeChanged(isDark: Boolean) {
        if (lastRenderedDark == isDark) return
        tvHeaderSubtitle?.setTextColor(Color.parseColor(if (isDark) "#8E8E8E" else "#666666"))
        updateFilterChipSelection(isDark)
        renderFilteredItems(isDark, force = true)
    }

    private fun renderFilteredItems(isDark: Boolean, force: Boolean = false) {
        if (!force &&
            lastRenderedFilter == activeFilter &&
            lastRenderedDark == isDark &&
            lastRenderedItemsRef === allItems
        ) {
            return
        }
        lastRenderedFilter = activeFilter
        lastRenderedDark = isDark
        lastRenderedItemsRef = allItems

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
            "${filtered.size} numerical items (project1: $p1Count • patches: $patchCount • stdlib: $stdCount) — Tap to insert, Hold for info"

        adapter?.submitList(filtered, isDark)
    }

    private inner class CatalogViewHolder(
        val rootCard: LinearLayout,
        val tvSig: TextView,
        val tvBadge: TextView,
        val tvDesc: TextView,
        val bgDrawable: GradientDrawable
    ) : RecyclerView.ViewHolder(rootCard) {
        var boundItem: CalculatorCatalogItem? = null
        var isHoldActive: Boolean = false
    }

    private inner class CatalogAdapter(
        private val density: Float,
        private var isDark: Boolean
    ) : RecyclerView.Adapter<CatalogViewHolder>() {

        private var items: List<CalculatorCatalogItem> = emptyList()

        fun submitList(newItems: List<CalculatorCatalogItem>, dark: Boolean) {
            this.items = newItems
            this.isDark = dark
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CatalogViewHolder {
            val ctx = parent.context
            val dp = { v: Float -> (v * density).toInt() }

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 11f * density
            }
            val rowCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5f) }
                setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
                background = bg
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = boldMonoTypeface
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvBadge = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = boldMonoTypeface
                setPadding(dp(6f), dp(1f), 0, dp(1f))
            }

            topRow.addView(tvSig)
            topRow.addView(tvBadge)
            rowCard.addView(topRow)

            val tvDesc = TextView(ctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                maxLines = 2
                setPadding(0, dp(2f), 0, 0)
            }
            rowCard.addView(tvDesc)

            val holder = CatalogViewHolder(rowCard, tvSig, tvBadge, tvDesc, bg)

            rowCard.setOnClickListener {
                val item = holder.boundItem ?: return@setOnClickListener
                pulseRow(rowCard)
                (activity as? MainActivity)?.onCatalogItemClicked(item)
            }

            rowCard.setOnLongClickListener {
                val item = holder.boundItem ?: return@setOnLongClickListener false
                holder.isHoldActive = true
                rowCard.parent?.requestDisallowInterceptTouchEvent(true)
                (activity as? MainActivity)?.showButtonPopup(rowCard, item.toPopupInfoText())
                true
            }

            rowCard.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        holder.isHoldActive = false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val act = activity as? MainActivity
                        if (holder.isHoldActive || act?.keyPopupOverlay != null) {
                            holder.isHoldActive = false
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            act?.dismissButtonPopup()
                        }
                    }
                }
                false
            }

            return holder
        }

        override fun onBindViewHolder(holder: CatalogViewHolder, position: Int) {
            val item = items[position]
            holder.boundItem = item
            val isProj1 = (item.originGroup == "project1")

            val rowBg = if (isDark) 0xFF292929.toInt() else 0xFFECECEC.toInt()
            val proj1RowBg = if (isDark) 0xFF1F3033.toInt() else 0xFFF7EAE7.toInt()
            val strokeColor = if (isDark) 0xFF3C3C3C.toInt() else 0xFFD6D6D6.toInt()
            val proj1Stroke = if (isDark) 0xFF13959B.toInt() else 0xFFE58A7B.toInt()
            val primaryText = if (isDark) 0xFFEAEAEA.toInt() else 0xFF2B2B2B.toInt()
            val secondaryText = if (isDark) 0xFF9A9A9A.toInt() else 0xFF626262.toInt()

            val badgeColor = when {
                item.originGroup == "project1" -> if (isDark) 0xFF13959B.toInt() else 0xFFD96B59.toInt()
                item.originGroup == "patch" -> if (isDark) 0xFFDCDCAA.toInt() else 0xFF9A6700.toInt()
                item.kind == "op" -> if (isDark) 0xFFC586C0.toInt() else 0xFF8250DF.toInt()
                else -> if (isDark) 0xFF569CD6.toInt() else 0xFF0969DA.toInt()
            }

            holder.bgDrawable.setColor(if (isProj1) proj1RowBg else rowBg)
            holder.bgDrawable.setStroke(
                ((if (isProj1) 1.3f else 1f) * density).toInt().coerceAtLeast(1),
                if (isProj1) proj1Stroke else strokeColor
            )

            holder.tvSig.text = item.signature
            holder.tvSig.setTextColor(primaryText)

            holder.tvBadge.text = when (item.originGroup) {
                "project1" -> if (item.kind == "op") "project1 • op [${item.moduleName}]" else "project1 [${item.moduleName}]"
                "patch" -> "patch [${item.moduleName}]"
                else -> "stdlib [${item.moduleName}]"
            }
            holder.tvBadge.setTextColor(badgeColor)

            holder.tvDesc.text = item.shortDescription
            holder.tvDesc.setTextColor(secondaryText)
        }
    }

    private fun pulseRow(view: View) {
        if (view.width > 0) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val t = f * 0.45
                val pulse = (Math.exp(-7.5 * t) * Math.sin(t * 22.0)).toFloat()
                view.scaleX = 1f + 0.03f * pulse
                view.scaleY = 1f + 0.03f * pulse
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
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()

        val clampedDist = distance.coerceIn(1, 4)
        val d = clampedDist.toFloat()
        val density = resources.displayMetrics.density
        val slidePx = (8f + (d - 1f) * 8f) * density * direction
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L + (clampedDist - 1) * 40L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                root.translationX = slidePx * springFactor * 0.35f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.translationX = 0f
                    root.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
            start()
            wobbleAnimators.add(this)
        }
    }

    fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        val root = outerLayout ?: return
        if (!root.isShown || root.width == 0 || root.height == 0) return
        val speed = maxRadius / revealDurationMs
        val loc = IntArray(2)
        root.getLocationInWindow(loc)
        val vx = loc[0] + root.width / 2f
        val vy = loc[1] + root.height / 2f
        val dist = hypot((vx - cx).toDouble(), (vy - cy).toDouble()).toFloat()
        val proximity = 1f - (dist / maxRadius).coerceIn(0f, 1f)
        val bump = 0.012f + 0.02f * proximity
        val delay = (dist / speed).toLong().coerceAtMost(revealDurationMs - 40)
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 440L
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
                    root.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
            start()
            wobbleAnimators.add(this)
        }
    }

    override fun onDestroyView() {
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        recyclerView?.adapter = null
        recyclerView = null
        adapter = null
        outerLayout = null
        tvHeaderSubtitle = null
        super.onDestroyView()
    }
}
