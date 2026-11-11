package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.hypot

/**
 * Tab 5 (6th tab) in the Basic Calculator:
 * Displays a recycled, zero-jank list of numerical functions and custom/overloaded operators
 * dynamically discovered from `stdlib + patches + project1`.
 * - Top bar is split 50/50 between two neumorphic dropdown buttons:
 *   1. Left 50%: Source/kind filter dropdown (`▾ Filter: All`, `project1`, `Patches`, `Stdlib`, `Operators`).
 *   2. Right 50%: Quick numeric keypad dropdown (`▾ Keypad: 1–9  ( )  =`).
 * - Both dropdowns and all filter transitions use the app's signature damped-spring physics.
 * - Tapping any item inserts it into the Basic Calculator's expression input.
 * - Holding any item displays a frosted-glass popup info window (`showButtonPopup`) populated
 *   directly from the `.math` file's `# @desc`, `# @param`, and `# @return` documentation.
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

    private var btnFilterDropdown: NeumorphicKeyView? = null
    private var btnPadToggle: NeumorphicKeyView? = null
    private val wobbleAnimators = mutableListOf<ValueAnimator>()
    private val filterListAnimators = mutableListOf<ValueAnimator>()
    private val boldMonoTypeface: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    /** Frost-blurred filter dropdown overlay shown from the left 50% filter toggle button. */
    private var filterDropdownOverlay: FrameLayout? = null
    private val filterDropdownAnimators = mutableListOf<ValueAnimator>()

    /** Frost-blurred quick numeric-pad dropdown overlay shown from the right 50% keypad toggle button. */
    private var helperPadOverlay: FrameLayout? = null
    private val padAnimators = mutableListOf<ValueAnimator>()

    private val filterOptions = listOf(
        "ALL" to "All",
        "PROJECT1" to "project1",
        "PATCH" to "Patches",
        "STDLIB" to "Stdlib",
        "OP" to "Operators"
    )

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

        // 1. 50/50 Top Controls Row: [▾ Filter: All (...)] (50%) + [▾ Keypad: 1–9 ( ) =] (50%)
        val topControlsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5f) }
        }

        val filterToggle = NeumorphicKeyView(ctx).apply {
            text = formatFilterButtonLabel()
            textSize = 11.2f
            cornerRadiusDp = 10.5f
            shadowPadDp = 2.2f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply {
                marginEnd = dp(4f)
            }
            setOnClickListener {
                pulseRow(this)
                toggleFilterDropdown(this)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (filterDropdownOverlay == null && helperPadOverlay == null) {
                    val act = this@FunctionCatalogFragment.activity as? MainActivity
                    if (act != null) {
                        if (held) {
                            act.showButtonPopup(
                                this,
                                "Filter dropdown — filters the dynamic function catalog by source or kind: " +
                                    "All, project1 (.math Studio modules), Patches (bundled & OTA .math patches), " +
                                    "Stdlib (core MEP standard library), or Operators. Selecting a filter animates the list with damped-spring physics."
                            )
                        } else {
                            act.dismissButtonPopup()
                        }
                    }
                }
            }
        }
        btnFilterDropdown = filterToggle
        topControlsRow.addView(filterToggle)

        val padToggle = NeumorphicKeyView(ctx).apply {
            text = "▾ Keypad: 1–9  ( )  ="
            textSize = 11.2f
            cornerRadiusDp = 10.5f
            shadowPadDp = 2.2f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply {
                marginStart = dp(4f)
            }
            setOnClickListener {
                pulseRow(this)
                toggleHelperPad(this)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (helperPadOverlay == null && filterDropdownOverlay == null) {
                    val act = this@FunctionCatalogFragment.activity as? MainActivity
                    if (act != null) {
                        if (held) {
                            act.showButtonPopup(
                                this,
                                "1–9 Keypad — Quick numeric insert pad: drops down a frost-blurred keypad with digits 1–9, " +
                                    "decimal dot, parameter comma, a smart bracket that opens or closes ( ) automatically, " +
                                    "backspace, and equals. Tap outside or the button again to dismiss."
                            )
                        } else {
                            act.dismissButtonPopup()
                        }
                    }
                }
            }
        }
        btnPadToggle = padToggle
        topControlsRow.addView(padToggle)
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

        // Populate from dynamically parsed stdlib .math assets and sync project1 + patches on worker
        if (allItems.isEmpty()) {
            allItems = runCatching { ensureBundledStdlibDocsLoaded(ctx.applicationContext) }.getOrDefault(emptyList())
            renderFilteredItems(dark, force = true)
        }
        reloadCatalogFromEngine()
        return root
    }

    private fun countForFilter(filterKey: String): Int = when (filterKey) {
        "PROJECT1" -> allItems.count { it.originGroup == "project1" }
        "PATCH" -> allItems.count { it.originGroup == "patch" }
        "STDLIB" -> allItems.count { it.originGroup == "stdlib" }
        "OP" -> allItems.count { it.kind == "op" }
        else -> allItems.size
    }

    private fun formatFilterButtonLabel(): String {
        val label = filterOptions.firstOrNull { it.first == activeFilter }?.second ?: "All"
        val cnt = countForFilter(activeFilter)
        return if (allItems.isNotEmpty()) "▾ Filter: $label ($cnt)" else "▾ Filter: $label"
    }

    private fun updateTopButtonsAppearance(dark: Boolean) {
        btnFilterDropdown?.apply {
            isDarkThemeMode = dark
            isAccentKey = true
            text = formatFilterButtonLabel()
        }
        btnPadToggle?.apply {
            isDarkThemeMode = dark
            isAccentKey = (helperPadOverlay != null)
        }
    }

    /** Creates the frosted backdrop ImageView with a live display cutout over tvPrimaryDisplay + tvSecondaryDisplay. */
    private fun createFrostedBackdropWithDisplayCutout(act: MainActivity, rootFrame: FrameLayout, dp: (Float) -> Int): ImageView? {
        val frostBmp = Frost.frozenBackdrop(rootFrame, downscale = 6, radius = 6) ?: return null
        val clipPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
        val cutoutRect = RectF()
        return object : ImageView(act) {
            override fun onDraw(canvas: Canvas) {
                val dispPri = act.binding.tvPrimaryDisplay
                val dispSec = act.binding.tvSecondaryDisplay
                if (dispPri.width > 0 && dispSec.width > 0 && width > 0 && height > 0) {
                    val priLoc = IntArray(2)
                    val secLoc = IntArray(2)
                    val selfLoc = IntArray(2)
                    dispPri.getLocationInWindow(priLoc)
                    dispSec.getLocationInWindow(secLoc)
                    getLocationInWindow(selfLoc)
                    val leftB = (minOf(priLoc[0], secLoc[0]) - selfLoc[0] - dp(10f)).toFloat()
                    val topB = (minOf(priLoc[1], secLoc[1]) - selfLoc[1] - dp(8f)).toFloat()
                    val rightB = (maxOf(priLoc[0] + dispPri.width, secLoc[0] + dispSec.width) - selfLoc[0] + dp(10f)).toFloat()
                    val bottomB = (maxOf(priLoc[1] + dispPri.height, secLoc[1] + dispSec.height) - selfLoc[1] + dp(8f)).toFloat()
                    cutoutRect.set(
                        leftB.coerceIn(0f, width.toFloat()),
                        topB.coerceIn(0f, height.toFloat()),
                        rightB.coerceIn(0f, width.toFloat()),
                        bottomB.coerceIn(0f, height.toFloat())
                    )
                    clipPath.reset()
                    clipPath.fillType = Path.FillType.EVEN_ODD
                    clipPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                    val r = dp(10f).toFloat()
                    clipPath.addRoundRect(cutoutRect, r, r, Path.Direction.CW)
                    val saveCount = canvas.save()
                    canvas.clipPath(clipPath)
                    super.onDraw(canvas)
                    canvas.restoreToCount(saveCount)
                } else {
                    super.onDraw(canvas)
                }
            }
        }.apply {
            setImageBitmap(frostBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
        }
    }

    /** Toggles the frosted-glass filter dropdown menu with damped-spring physics. */
    private fun toggleFilterDropdown(anchor: View) {
        if (filterDropdownOverlay != null) {
            dismissFilterDropdown()
        } else {
            if (helperPadOverlay != null) dismissHelperPad()
            showFilterDropdown(anchor)
        }
    }

    /**
     * Drops down a frost-blurred filter selection menu anchored to [anchor] (`btnFilterDropdown`)
     * with damped-spring entrance (`e⁻⁴ᶠ · cos(3πf)`) and exit animations. Selecting a filter
     * triggers a directional damped-spring transition on the function list.
     */
    private fun showFilterDropdown(anchor: View) {
        val act = activity as? MainActivity ?: return
        val rootFrame = act.binding.rootFrame
        act.dismissButtonPopup()
        filterDropdownAnimators.forEach { it.cancel() }
        filterDropdownAnimators.clear()
        filterDropdownOverlay?.let { (it.parent as? FrameLayout)?.removeView(it) }
        filterDropdownOverlay = null

        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }
        val dark = act.isDarkMode
        val panelBg = if (dark) "#F8F8F8" else "#2E2E2E"
        val strokeCol = if (dark) "#13959B" else "#E58A7B"

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 13f * density
                setColor(Color.parseColor(panelBg))
                setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.parseColor(strokeCol))
            }
            elevation = 12f * density
            setPadding(dp(10f), dp(9f), dp(10f), dp(8f))
            isClickable = true
            isFocusable = true
            visibility = View.INVISIBLE
        }

        val itemWidthDp = maxOf(176f, (anchor.width / density) - 16f)
        for ((idx, pair) in filterOptions.withIndex()) {
            val (filterKey, label) = pair
            val count = countForFilter(filterKey)
            val isSelected = (filterKey == activeFilter)
            val optionBtn = NeumorphicKeyView(act).apply {
                text = if ( isSelected ) "✓  $label  ($count)" else "$label  ($count)"
                textSize = 11.4f
                cornerRadiusDp = 9.5f
                shadowPadDp = 2.0f
                isAccentKey = isSelected
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(itemWidthDp), dp(32f)).apply {
                    if (idx < filterOptions.lastIndex) {
                        bottomMargin = dp(4f)
                    }
                }
                setOnClickListener {
                    pulseRow(this)
                    val prevIdx = filterOptions.indexOfFirst { it.first == activeFilter }.coerceAtLeast(0)
                    val newIdx = idx
                    val dist = kotlin.math.abs(newIdx - prevIdx).coerceIn(1, 4)
                    val dir = if (newIdx >= prevIdx) 1 else -1
                    activeFilter = filterKey
                    val curDark = (this@FunctionCatalogFragment.activity as? MainActivity)?.isDarkMode ?: true
                    updateTopButtonsAppearance(curDark)
                    btnFilterDropdown?.let { pulseRow(it) }
                    renderFilteredItems(
                        isDark = curDark,
                        force = true,
                        animateTransition = true,
                        transitionDir = dir,
                        transitionDist = dist
                    )
                    dismissFilterDropdown()
                }
            }
            panel.addView(optionBtn)
        }

        val overlay = FrameLayout(act).apply {
            elevation = 48f * density
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissFilterDropdown() }
        }
        // Filter dropdown frosts the whole screen — expression/answer stay blurred here
        // (no display cutout; the sharp cutout is reserved for the helper key pad).
        val frostView = ImageView(act).apply {
            setImageBitmap(Frost.frozenBackdrop(rootFrame, downscale = 6, radius = 6))
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
        }
        if (frostView != null) {
            overlay.addView(
                frostView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        rootFrame.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        filterDropdownOverlay = overlay

        fun positionAndAnimateDropdown() {
            if (filterDropdownOverlay !== overlay) return
            val rootW = if (rootFrame.width > 0) rootFrame.width else resources.displayMetrics.widthPixels
            val rootH = if (rootFrame.height > 0) rootFrame.height else resources.displayMetrics.heightPixels
            val rootLoc = IntArray(2)
            rootFrame.getLocationInWindow(rootLoc)
            val anchorLoc = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            val layerPad = dp(12f)
            panel.measure(
                View.MeasureSpec.makeMeasureSpec((rootW - 2 * layerPad).coerceAtLeast(1), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec((rootH - 2 * layerPad).coerceAtLeast(1), View.MeasureSpec.AT_MOST)
            )
            val bw = panel.measuredWidth
            val bh = panel.measuredHeight
            val anchorCx = anchorLoc[0] + anchor.width / 2 - rootLoc[0]
            val anchorTop = anchorLoc[1] - rootLoc[1]
            val anchorBottom = anchorTop + anchor.height
            val showBelow = anchorBottom + bh + layerPad <= rootH - layerPad
            val rawY = if (showBelow) anchorBottom + dp(6f) else anchorTop - bh - dp(6f)
            val maxTop = (rootH - bh - layerPad).coerceAtLeast(layerPad)
            val y = rawY.coerceIn(layerPad, maxTop)
            val maxLeft = (rootW - bw - layerPad).coerceAtLeast(layerPad)
            val x = (anchorCx - bw / 2).coerceIn(layerPad, maxLeft)
            (panel.layoutParams as FrameLayout.LayoutParams).apply {
                width = bw
                leftMargin = x
                topMargin = y
            }
            panel.requestLayout()
            panel.visibility = View.VISIBLE
            panel.pivotX = bw / 2f
            panel.pivotY = if (showBelow) 0f else bh.toFloat()
            panel.translationY = if (showBelow) -12f * density else 12f * density
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 430L
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    val decay = Math.exp(-4.0 * f)
                    val scale = (1.0 - 0.45 * decay * Math.cos(f * 3.0 * Math.PI)).toFloat()
                    panel.scaleX = scale
                    panel.scaleY = scale
                    panel.alpha = (f / 0.22f).coerceIn(0f, 1f)
                    panel.translationY = (if (showBelow) -12f * density else 12f * density) * (1f - f)
                    frostView?.alpha = 0.92f * (f / 0.3f).coerceIn(0f, 1f)
                }
                start()
                filterDropdownAnimators.add(this)
            }
        }
        if (rootFrame.width > 0 && anchor.width > 0) {
            positionAndAnimateDropdown()
        } else {
            overlay.post { positionAndAnimateDropdown() }
        }
    }

    /** Collapses the filter dropdown menu with damped-spring shrink and frost fade-out. */
    private fun dismissFilterDropdown() {
        filterDropdownAnimators.forEach { it.cancel() }
        filterDropdownAnimators.clear()
        filterDropdownOverlay?.let { overlay ->
            filterDropdownOverlay = null
            for (i in 0 until overlay.childCount - 1) {
                overlay.getChildAt(i).animate().alpha(0f).setDuration(170L).start()
            }
            val panel = overlay.getChildAt(overlay.childCount - 1)
            if (panel != null) {
                panel.animate()
                    .scaleX(0.62f).scaleY(0.62f).alpha(0f)
                    .setDuration(170L)
                    .setInterpolator(AccelerateInterpolator(0.8f))
                    .withEndAction { (overlay.parent as? FrameLayout)?.removeView(overlay) }
                    .start()
            } else {
                (overlay.parent as? FrameLayout)?.removeView(overlay)
            }
        }
    }

    /** Opens the quick numeric pad if closed, closes it if open. */
    private fun toggleHelperPad(anchor: View) {
        if (helperPadOverlay != null) {
            dismissHelperPad()
        } else {
            if (filterDropdownOverlay != null) dismissFilterDropdown()
            showHelperPad(anchor)
        }
    }

    /**
     * Drops down a small frost-blurred button window anchored to [anchor] (the `1-9` toggle):
     * digits 1–9, decimal dot, parameter comma, a smart `( )` bracket key and an accent equals
     * key. Entrance runs the app's signature damped-spring animation (`e⁻⁴ᶠ · cos(3πf)` scale
     * with a slide + fade, identical to the hold-info popups). Tapping the overlay anywhere
     * outside the panel — or the toggle again — dismisses it.
     */
    private fun showHelperPad(anchor: View) {
        val act = activity as? MainActivity ?: return
        val rootFrame = act.binding.rootFrame
        act.dismissButtonPopup()
        padAnimators.forEach { it.cancel() }
        padAnimators.clear()
        helperPadOverlay?.let { (it.parent as? FrameLayout)?.removeView(it) }
        helperPadOverlay = null

        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }
        val dark = act.isDarkMode
        val panelBg = if (dark) "#F8F8F8" else "#2E2E2E"
        val strokeCol = if (dark) "#13959B" else "#E58A7B"

        fun makeKey(label: String, isAccent: Boolean, widthDp: Float, onClick: () -> Unit): NeumorphicKeyView {
            val key = NeumorphicKeyView(act)
            key.text = label
            key.textSize = if (label.length > 1) 10.5f else 13f
            key.cornerRadiusDp = 8.5f
            key.shadowPadDp = 2.0f
            key.isAccentKey = isAccent
            key.isDarkThemeMode = dark
            key.layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(30f)).apply {
                marginStart = dp(3f)
                marginEnd = dp(3f)
            }
            key.setOnClickListener {
                pulseRow(key)
                onClick()
            }
            key.setOnLongClickListener { true }
            key.holdListener = { held ->
                if (held) act.showButtonPopup(key, act.keyDescriptionFor(label))
                else act.dismissButtonPopup()
            }
            return key
        }

        val panel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 13f * density
                setColor(Color.parseColor(panelBg))
                setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.parseColor(strokeCol))
            }
            elevation = 12f * density
            setPadding(dp(9f), dp(9f), dp(9f), dp(8f))
            isClickable = true
            isFocusable = true
            visibility = View.INVISIBLE
        }

        val rowsGrid = listOf(
            listOf("1" to false, "2" to false, "3" to false),
            listOf("4" to false, "5" to false, "6" to false),
            listOf("7" to false, "8" to false, "9" to false),
            listOf("( )" to false, "," to false, "." to false)
        )
        for (row in rowsGrid) {
            val rowLayout = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((label, accent) in row) {
                rowLayout.addView(makeKey(label, accent, 34f) {
                    when (label) {
                        "( )" -> act.onSmartBracketClicked()
                        else -> act.onKeyClicked(label)
                    }
                })
            }
            panel.addView(
                rowLayout,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2f) }
            )
        }
        panel.addView(
            LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(makeKey("⌫", false, 54f) { act.onKeyClicked("⌫") })
                addView(makeKey("=", true, 54f) {
                    act.onKeyClicked("=")
                    dismissHelperPad()
                })
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val overlay = FrameLayout(act).apply {
            elevation = 48f * density
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissHelperPad() }
        }
        val frostView = createFrostedBackdropWithDisplayCutout(act, rootFrame, dp)
        if (frostView != null) {
            overlay.addView(
                frostView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        rootFrame.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        helperPadOverlay = overlay
        btnPadToggle?.isAccentKey = true

        fun positionAndAnimatePanel() {
            if (helperPadOverlay !== overlay) return
            val rootW = if (rootFrame.width > 0) rootFrame.width else resources.displayMetrics.widthPixels
            val rootH = if (rootFrame.height > 0) rootFrame.height else resources.displayMetrics.heightPixels
            val rootLoc = IntArray(2)
            rootFrame.getLocationInWindow(rootLoc)
            val anchorLoc = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            val layerPad = dp(12f)
            panel.measure(
                View.MeasureSpec.makeMeasureSpec((rootW - 2 * layerPad).coerceAtLeast(1), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec((rootH - 2 * layerPad).coerceAtLeast(1), View.MeasureSpec.AT_MOST)
            )
            val bw = panel.measuredWidth
            val bh = panel.measuredHeight
            val anchorCx = anchorLoc[0] + anchor.width / 2 - rootLoc[0]
            val anchorTop = anchorLoc[1] - rootLoc[1]
            val anchorBottom = anchorTop + anchor.height
            val showAbove = anchorTop - bh - layerPad >= layerPad
            val rawY = if (showAbove) anchorTop - bh - layerPad else anchorBottom + layerPad
            val maxTop = (rootH - bh - layerPad).coerceAtLeast(layerPad)
            val y = rawY.coerceIn(layerPad, maxTop)
            val maxLeft = (rootW - bw - layerPad).coerceAtLeast(layerPad)
            val x = (anchorCx - bw / 2).coerceIn(layerPad, maxLeft)
            (panel.layoutParams as FrameLayout.LayoutParams).apply {
                width = bw
                leftMargin = x
                topMargin = y
            }
            panel.requestLayout()
            panel.visibility = View.VISIBLE
            panel.pivotX = bw / 2f
            panel.pivotY = if (showAbove) bh.toFloat() else 0f
            panel.translationY = if (showAbove) 12f * density else -12f * density
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 430L
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    val decay = Math.exp(-4.0 * f)
                    val scale = (1.0 - 0.45 * decay * Math.cos(f * 3.0 * Math.PI)).toFloat()
                    panel.scaleX = scale
                    panel.scaleY = scale
                    panel.alpha = (f / 0.22f).coerceIn(0f, 1f)
                    panel.translationY = (if (showAbove) 12f * density else -12f * density) * (1f - f)
                    frostView?.alpha = 0.92f * (f / 0.3f).coerceIn(0f, 1f)
                }
                start()
                padAnimators.add(this)
            }
        }
        if (rootFrame.width > 0 && anchor.width > 0) {
            positionAndAnimatePanel()
        } else {
            overlay.post { positionAndAnimatePanel() }
        }
    }

    /** Collapses the quick numeric pad: frost fades + panel shrink, same recipe as the hold popups. */
    private fun dismissHelperPad() {
        padAnimators.forEach { it.cancel() }
        padAnimators.clear()
        btnPadToggle?.isAccentKey = false
        helperPadOverlay?.let { overlay ->
            helperPadOverlay = null
            for (i in 0 until overlay.childCount - 1) {
                overlay.getChildAt(i).animate().alpha(0f).setDuration(170L).start()
            }
            val panel = overlay.getChildAt(overlay.childCount - 1)
            if (panel != null) {
                panel.animate()
                    .scaleX(0.62f).scaleY(0.62f).alpha(0f)
                    .setDuration(170L)
                    .setInterpolator(AccelerateInterpolator(0.8f))
                    .withEndAction { (overlay.parent as? FrameLayout)?.removeView(overlay) }
                    .start()
            } else {
                (overlay.parent as? FrameLayout)?.removeView(overlay)
            }
        }
    }

    fun reloadCatalogFromEngine() {
        val ctx = context?.applicationContext ?: return
        MathEngineManager.worker.execute {
            val items = runCatching {
                MathEngineManager.collectCalculatorFunctionCatalog(ctx)
            }.getOrElse {
                runCatching { ensureBundledStdlibDocsLoaded(ctx) }.getOrDefault(emptyList())
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val changed = (items !== allItems && items != allItems)
                allItems = items
                val dark = (activity as? MainActivity)?.isDarkMode ?: true
                updateTopButtonsAppearance(dark)
                if (changed || lastRenderedItemsRef == null) {
                    renderFilteredItems(dark, force = true)
                }
            }
        }
    }

    fun onThemeChanged(isDark: Boolean) {
        if (lastRenderedDark == isDark) return
        dismissFilterDropdown()
        dismissHelperPad()
        tvHeaderSubtitle?.setTextColor(Color.parseColor(if (isDark) "#8E8E8E" else "#666666"))
        updateTopButtonsAppearance(isDark)
        renderFilteredItems(isDark, force = true)
    }

    private fun renderFilteredItems(
        isDark: Boolean,
        force: Boolean = false,
        animateTransition: Boolean = false,
        transitionDir: Int = 1,
        transitionDist: Int = 1
    ) {
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
        updateTopButtonsAppearance(isDark)

        adapter?.submitList(filtered, isDark)
        if (animateTransition) {
            recyclerView?.scrollToPosition(0)
            animateFilterListPhysics(transitionDir, transitionDist)
        }
    }

    /**
     * Animates the function list and subtitle bar with directional damped-spring physics
     * (`e^-7.5t · sin(22t)`) whenever a filter is applied from the filter dropdown menu.
     */
    private fun animateFilterListPhysics(direction: Int, distance: Int) {
        val rv = recyclerView ?: return
        val subtitle = tvHeaderSubtitle
        filterListAnimators.forEach { it.cancel() }
        filterListAnimators.clear()

        val clampedDist = distance.coerceIn(1, 4)
        val d = clampedDist.toFloat()
        val density = resources.displayMetrics.density
        val slideX = (22f + (d - 1f) * 10f) * density * direction
        val slideY = (14f + (d - 1f) * 4f) * density
        val scaleAmp = (0.026f + (d - 1f) * 0.008f)
        val dur = 460L + (clampedDist - 1) * 45L

        if (rv.width > 0) {
            rv.pivotX = rv.width / 2f
            rv.pivotY = rv.height * 0.25f
        }
        rv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()

                rv.translationX = slideX * springFactor * 0.65f
                rv.translationY = slideY * springFactor
                val sc = 1f + scaleAmp * pulse
                rv.scaleX = sc
                rv.scaleY = sc
                rv.alpha = (0.35f + 0.65f * (f / 0.25f)).coerceIn(0f, 1f)

                subtitle?.translationX = slideX * springFactor * 0.4f
                subtitle?.alpha = (0.4f + 0.6f * (f / 0.22f)).coerceIn(0f, 1f)

                // Cascade damped-spring wave across currently visible row cards
                val childCount = rv.childCount
                for (i in 0 until childCount) {
                    val child = rv.getChildAt(i) ?: continue
                    val staggerF = ((f - i * 0.035f) / (1f - (i * 0.035f).coerceAtMost(0.35f))).coerceIn(0f, 1f)
                    val ct = staggerF * 0.48
                    val cDecay = Math.exp(-7.5 * ct)
                    val cSpring = (cDecay * (Math.cos(ct * 22.0) + (7.5 / 22.0) * Math.sin(ct * 22.0))).toFloat()
                    val cPulse = (cDecay * Math.sin(ct * 22.0)).toFloat()
                    child.translationX = slideX * 0.55f * cSpring
                    child.translationY = (10f * density) * cSpring
                    val cScale = 1f + 0.022f * cPulse
                    child.scaleX = cScale
                    child.scaleY = cScale
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rv.translationX = 0f
                    rv.translationY = 0f
                    rv.scaleX = 1f
                    rv.scaleY = 1f
                    rv.alpha = 1f
                    rv.setLayerType(View.LAYER_TYPE_NONE, null)
                    subtitle?.translationX = 0f
                    subtitle?.alpha = 1f
                    for (i in 0 until rv.childCount) {
                        rv.getChildAt(i)?.apply {
                            translationX = 0f
                            translationY = 0f
                            scaleX = 1f
                            scaleY = 1f
                        }
                    }
                }
            })
            start()
        }
        filterListAnimators.add(anim)
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
                (activity as? MainActivity)?.showButtonPopup(rowCard, item.richPopupText())
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
        filterListAnimators.forEach { it.cancel() }
        filterListAnimators.clear()
        dismissFilterDropdown()
        dismissHelperPad()
        btnFilterDropdown = null
        btnPadToggle = null
        recyclerView?.adapter = null
        recyclerView = null
        adapter = null
        outerLayout = null
        tvHeaderSubtitle = null
        super.onDestroyView()
    }
}
