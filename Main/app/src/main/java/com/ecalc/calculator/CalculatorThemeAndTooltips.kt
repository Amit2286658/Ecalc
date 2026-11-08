package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.CompoundButtonCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.ecalc.databinding.ActivityMainBinding
import kotlin.math.hypot


internal fun MainActivity.applyThemeColors(dark: Boolean) {
    val displayBg   = if (dark) "#2D2D2D" else "#F8F8F8"
    val sheetBg     = if (dark) "#333333" else "#F3F3F3"
    val pillBg      = if (dark) "#262626" else "#EFEFEF"
    val primaryText = if (dark) "#E0E0E0" else "#333333"
    val secondaryText = if (dark) "#8E8E8E" else "#888888"
    val accentColor = if (dark) "#13959B" else "#F18977"

    window.statusBarColor = Color.parseColor(displayBg)
    window.navigationBarColor = Color.parseColor(sheetBg)
    window.setBackgroundDrawable(ColorDrawable(Color.parseColor(displayBg)))
    WindowCompat.getInsetsController(window, window.decorView)
        .isAppearanceLightStatusBars = !dark

    binding.calculatorContainer.setBackgroundColor(Color.parseColor(displayBg))

    binding.themeTogglePill.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 24f * resources.displayMetrics.density
        setColor(Color.parseColor(pillBg))
    }

    binding.btnAboutInfo.background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(pillBg))
    }
    binding.ivAboutInfo.setColorFilter(Color.parseColor(if (dark) "#CCCCCC" else "#444444"))

    binding.ivSun.setColorFilter(Color.parseColor(if (dark) "#757575" else "#222222"))
    binding.ivMoon.setColorFilter(Color.parseColor(if (dark) "#FFFFFF" else "#A0A0A0"))

    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
    CompoundButtonCompat.setButtonTintList(binding.cbMrToggle, ColorStateList(states, intArrayOf(Color.parseColor(accentColor), Color.parseColor(secondaryText))))

    binding.tvMrHeaderLabel.setTextColor(Color.parseColor(if (dark) "#CCCCCC" else "#444444"))
    binding.tvAngleMode.setTextColor(Color.parseColor(secondaryText))
    binding.tvPrimaryDisplay.setTextColor(Color.parseColor(primaryText))
    binding.tvSecondaryDisplay.setTextColor(Color.parseColor(secondaryText))
    binding.tvMrClusterTitle.setTextColor(Color.parseColor(secondaryText))
    binding.tvMrValue.setTextColor(Color.parseColor(secondaryText))
    binding.btnMrClear.isDarkThemeMode = dark

    val topRadius = 28f * resources.displayMetrics.density
    binding.keypadSheet.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(topRadius, topRadius, topRadius, topRadius, 0f, 0f, 0f, 0f)
        setColor(Color.parseColor(sheetBg))
    }

    val tabIcons = listOf(
        binding.ivTabHistory, binding.ivTabBasic,
        binding.ivTabExtended, binding.ivTabTrig, binding.ivTabFx
    )
    tabIcons.forEach { it.setColorFilter(Color.parseColor(primaryText)) }

    updateTabIndicators()

    for (pos in 0 until 5) {
        when (val frag = fragmentAt(pos)) {
            is KeyGridFragment -> frag.applyTheme(dark)
            is HistoryFragment -> frag.onThemeChanged(dark)
            else -> {}
        }
    }
    workspaceFragment?.applyTheme(dark)
}

internal fun MainActivity.updateTabIndicators(
    animate: Boolean = false,
    prevIndex: Int = selectedTabIndex,
    distance: Int = 1
) {
    val accentColor = if (isDarkMode) "#13959B" else "#E58A7B"
    val indicators = listOf(
        binding.indTabHistory, binding.indTabBasic,
        binding.indTabExtended, binding.indTabTrig, binding.indTabFx
    )
    val clampedDist = distance.coerceIn(1, 4)
    val d = clampedDist.toFloat()
    val density = resources.displayMetrics.density

    indicators.forEachIndexed { idx, view ->
        view.setBackgroundColor(Color.parseColor(accentColor))
        if (idx == selectedTabIndex) {
            view.visibility = View.VISIBLE
            if (animate) {
                view.pivotX = view.width / 2f
                view.pivotY = view.height / 2f
                val stepSlotPx = if (view.width > 0) view.width.toFloat() else 60f * density
                val startOffsetPx = (prevIndex - idx) * stepSlotPx
                val scaleAmp = 0.45f + (d - 1f) * 0.22f
                val dur = 460L + (clampedDist - 1) * 55L
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = dur
                    interpolator = LinearInterpolator()
                    addUpdateListener { anim ->
                        val f = anim.animatedValue as Float
                        val t = f * 0.48
                        val decay = Math.exp(-7.5 * t)
                        val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                        val springScale = (1.0 - scaleAmp * decay * Math.cos(t * 22.0)).toFloat().coerceAtLeast(0.18f)
                        view.scaleX = springScale
                        view.translationX = startOffsetPx * springFactor * 0.42f
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            view.scaleX = 1f
                            view.translationX = 0f
                        }
                    })
                    start()
                }
            }
        } else {
            view.visibility = View.INVISIBLE
            view.translationX = 0f
            view.scaleX = 1f
        }
    }
}

internal fun MainActivity.applyThemeIconScales(dark: Boolean, animate: Boolean) {
    val activeScale = 1.3f; val inactiveScale = 0.7f
    val sunTarget = if (dark) inactiveScale else activeScale
    val moonTarget = if (dark) activeScale else inactiveScale
    val sunAlpha = if (dark) 0.55f else 1f
    val moonAlpha = if (dark) 1f else 0.55f
    if (animate) {
        binding.ivSun.animate().scaleX(sunTarget).scaleY(sunTarget).alpha(sunAlpha)
            .setDuration(320L).setInterpolator(OvershootInterpolator(1.5f)).start()
        binding.ivMoon.animate().scaleX(moonTarget).scaleY(moonTarget).alpha(moonAlpha)
            .setDuration(320L).setInterpolator(OvershootInterpolator(1.5f)).start()
    } else {
        binding.ivSun.scaleX = sunTarget; binding.ivSun.scaleY = sunTarget; binding.ivSun.alpha = sunAlpha
        binding.ivMoon.scaleX = moonTarget; binding.ivMoon.scaleY = moonTarget; binding.ivMoon.alpha = moonAlpha
    }
}

internal fun MainActivity.animateMrClusterPhysics(show: Boolean) {
    mrSlideAnimators.forEach { it.cancel() }; mrSlideAnimators.clear()
    val cluster = binding.mrDisplayCluster
    val density = resources.displayMetrics.density
    val baseWidth = if (cluster.width > 0) cluster.width.toFloat() else 140f * density
    val distance = baseWidth + 48f * density
    if (cluster.width > 0) {
        cluster.pivotX = cluster.width.toFloat()
        cluster.pivotY = cluster.height / 2f
    }
    if (show) {
        cluster.visibility = View.VISIBLE
        cluster.translationX = distance; cluster.alpha = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L; interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = decay * Math.sin(t * 22.0)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                cluster.translationX = distance * springFactor
                cluster.scaleX = (1.0 + 0.06 * pulse).toFloat()
                cluster.scaleY = (1.0 + 0.06 * pulse).toFloat()
                cluster.rotation = (3.5 * decay * Math.sin(t * 22.0 + 0.6)).toFloat()
                cluster.alpha = (f / 0.22f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cluster.translationX = 0f; cluster.alpha = 1f
                    cluster.scaleX = 1f; cluster.scaleY = 1f; cluster.rotation = 0f
                }
            })
            start()
            mrSlideAnimators.add(this)
        }
    } else {
        val startTx = cluster.translationX
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L; interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = decay * Math.sin(t * 22.0)
                val springFactor = (1.0 - decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                cluster.translationX = startTx + (distance - startTx) * springFactor
                cluster.scaleX = (1.0 - 0.06 * pulse).toFloat()
                cluster.scaleY = (1.0 - 0.06 * pulse).toFloat()
                cluster.rotation = (-3.5 * decay * Math.sin(t * 22.0 + 0.6)).toFloat()
                cluster.alpha = ((1f - f) / 0.65f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cluster.visibility = View.INVISIBLE
                    cluster.translationX = 0f; cluster.alpha = 1f
                    cluster.scaleX = 1f; cluster.scaleY = 1f; cluster.rotation = 0f
                }
            })
            start()
            mrSlideAnimators.add(this)
        }
    }
}

internal fun MainActivity.wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long, inward: Boolean = false) {
    wobbleAnimators.forEach { it.cancel() }; wobbleAnimators.clear()
    val speed = maxRadius / revealDurationMs
    val density = resources.displayMetrics.density
    val targets = buildList<Triple<View, Float, Float>> {
        add(Triple(binding.tvPrimaryDisplay, 0f, 1f))
        add(Triple(binding.tvSecondaryDisplay, 0f, 1f))
        add(Triple(binding.tvAngleMode, 0f, 1f))
        add(Triple(binding.mrToggleRow, 0f, 1f))
        if (binding.cbMrToggle.isChecked) add(Triple(binding.mrDisplayCluster, 0f, 1f))
        listOf(binding.tabHistory, binding.tabBasic, binding.tabExtended,
               binding.tabTrig, binding.tabFx).forEach { add(Triple(it, 5f, 1.9f)) }
    }

    targets.forEach { (view, rotBase, ampMul) ->
        if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) return@forEach
        val loc = IntArray(2); view.getLocationInWindow(loc)
        val vx = loc[0] + view.width / 2f; val vy = loc[1] + view.height / 2f
        val dx = vx - cx; val dy = vy - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val proximity = 1f - (dist / maxRadius).coerceIn(0f, 1f)
        val bump = (0.015f + 0.035f * proximity) * ampMul
        val rotAmp = rotBase * proximity
        val delay = if (inward) {
            ((maxRadius - dist) / speed).toLong().coerceIn(0, revealDurationMs - 40)
        } else { (dist / speed).toLong().coerceAtMost(revealDurationMs - 40) }
        val invLen = if (dist > 0.5f) 1f / dist else 0f
        val pushX = dx * invLen; val pushY = dy * invLen
        val pushDist = 7f * density * (0.5f + proximity) * ampMul

        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay; duration = 480L; interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float; val t = f * 0.48
                val decay = Math.exp(-7.5 * t); val pulse = decay * Math.sin(t * 22.0)
                view.scaleX = (1.0 + bump * pulse).toFloat()
                view.scaleY = (1.0 + bump * pulse).toFloat()
                view.translationX = (pushDist * pulse).toFloat() * pushX
                view.translationY = (pushDist * pulse).toFloat() * pushY
                if (rotAmp > 0f) view.rotation = (rotAmp * decay * Math.sin(t * 22.0 + 0.6)).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f; view.scaleY = 1f
                    view.translationX = 0f; view.translationY = 0f
                    if (rotAmp > 0f) view.rotation = 0f
                }
            })
            start(); wobbleAnimators.add(this)
        }
    }

    if (!inward) {
        for (pos in 0 until 5) {
            val frag = fragmentAt(pos) ?: continue
            when (frag) {
                is KeyGridFragment -> frag.wobbleRound(cx, cy, maxRadius, revealDurationMs)
                is HistoryFragment -> frag.wobbleRound(cx, cy, maxRadius, revealDurationMs)
                else -> {}
            }
        }
    }
}

internal fun MainActivity.spawnRippleRing(cx: Int, cy: Int, maxRadius: Float, delayMs: Long,
                             ringAlpha: Float, ringDuration: Long, inward: Boolean = false) {
    if (binding.rootFrame.width == 0) return
    val density = resources.displayMetrics.density
    val accent = if (isDarkMode) "#13959B" else "#F18977"
    val base = (36f * density).toInt()
    val ring = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke((2f*density).toInt(), Color.parseColor(accent)) }
        alpha = 0f
    }
    binding.rootFrame.addView(ring, FrameLayout.LayoutParams(base, base).apply { leftMargin = cx - base/2; topMargin = cy - base/2 })
    ValueAnimator.ofFloat(0f, 1f).apply {
        startDelay = delayMs; duration = ringDuration; interpolator = DecelerateInterpolator()
        addUpdateListener { anim ->
            val f = anim.animatedValue as Float
            val scale = if (inward) 1f + (1f-f) * (maxRadius*2f/base) else 1f + f*(maxRadius*2f/base)
            ring.scaleX = scale; ring.scaleY = scale; ring.alpha = (1f-f)*ringAlpha
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { (ring.parent as? FrameLayout)?.removeView(ring) }
        })
        start()
    }
}

internal fun MainActivity.toggleThemeWithCircularReveal() {
    val root = binding.rootFrame; val w = root.width; val h = root.height
    if (w == 0 || h == 0) { isDarkMode = !isDarkMode; persistTheme(); applyThemeColors(isDarkMode); applyThemeIconScales(isDarkMode, false); return }
    val activeContainer: View = if (currentWorkspacePage == 1) binding.mathWorkspaceContainer else binding.calculatorContainer
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).let { activeContainer.draw(it) }
    val overlay = ImageView(this).apply { setImageBitmap(bitmap); layoutParams = FrameLayout.LayoutParams(w, h) }
    root.addView(overlay, 0)
    isDarkMode = !isDarkMode; persistTheme(); applyThemeColors(isDarkMode); applyThemeIconScales(isDarkMode, true)
    val revealIcon = if (isDarkMode) binding.ivMoon else binding.ivSun
    val iconLoc = IntArray(2); revealIcon.getLocationInWindow(iconLoc)
    val cx = iconLoc[0] + revealIcon.width/2; val cy = iconLoc[1] + revealIcon.height/2
    val maxRadius = hypot(w.toDouble(), h.toDouble()).toFloat()
    ViewAnimationUtils.createCircularReveal(activeContainer, cx, cy, 0f, maxRadius).apply {
        duration = 380L; interpolator = AccelerateDecelerateInterpolator()
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { root.removeView(overlay); bitmap.recycle() }
        }); start()
    }
    spawnRippleRing(cx, cy, maxRadius, 60L, 0.35f, 760L)
    spawnRippleRing(cx, cy, maxRadius, 240L, 0.35f, 760L)
    if (currentWorkspacePage == 1) {
        workspaceFragment?.wobbleRound(cx.toFloat(), cy.toFloat(), maxRadius, 380L)
    } else {
        wobbleRound(cx.toFloat(), cy.toFloat(), maxRadius, 380L)
    }
}

internal fun MainActivity.persistTheme() { prefs.edit().putBoolean("dark_mode", isDarkMode).apply() }

internal fun MainActivity.playLaunchSplashReveal() {
    styleSplashDots(isDarkMode)
    binding.splashCircle.visibility = View.VISIBLE
    binding.splashInnerDot.alpha = 1f
    binding.calculatorContainer.visibility = View.INVISIBLE

    val cx = binding.rootFrame.width / 2
    val cy = binding.rootFrame.height / 2
    val splashRadius = hypot(cx.toDouble(), cy.toDouble()).toFloat() * 0.45f
    listOf(0L, 120L).forEach { spawnRippleRing(cx, cy, splashRadius, it, 0.26f, 440L) }

    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 240L
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            val f = anim.animatedValue as Float
            val t = f * 0.24
            val kick = Math.exp(-7.5 * t) * Math.sin(t * 24.0)
            val innerScale = (1.0 + 0.35 * kick - 0.45 * f * f).toFloat().coerceAtLeast(0.25f)
            val outerScale = (1.0 + 0.14 * kick).toFloat()
            binding.splashInnerDot.scaleX = innerScale
            binding.splashInnerDot.scaleY = innerScale
            binding.splashInnerDot.alpha = (1f - 0.65f * f * f).coerceIn(0f, 1f)
            binding.splashOuterDot.scaleX = outerScale
            binding.splashOuterDot.scaleY = outerScale
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.splashCircle.visibility = View.GONE
                binding.splashOuterDot.scaleX = 1f
                binding.splashOuterDot.scaleY = 1f
                binding.splashInnerDot.scaleX = 1f
                binding.splashInnerDot.scaleY = 1f
                binding.calculatorContainer.visibility = View.VISIBLE
                val rcx = binding.calculatorContainer.width / 2
                val rcy = binding.calculatorContainer.height / 2
                val maxR = hypot(rcx.toDouble(), rcy.toDouble()).toFloat()
                ViewAnimationUtils.createCircularReveal(
                    binding.calculatorContainer,
                    rcx,
                    rcy,
                    38f * resources.displayMetrics.density,
                    maxR
                ).apply {
                    duration = 340L
                    interpolator = AccelerateDecelerateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            binding.viewPager.offscreenPageLimit = 4
                        }
                    })
                    start()
                }
                spawnRippleRing(rcx, rcy, maxR, 40L, 0.35f, 700L)
                spawnRippleRing(rcx, rcy, maxR, 190L, 0.35f, 700L)
                wobbleRound(rcx.toFloat(), rcy.toFloat(), maxR, 340L)
            }
        })
        start()
    }
}

internal fun MainActivity.playExitSplashCollapse() {
    if (isExitingApp) return
    isExitingApp = true
    binding.splashCircle.visibility = View.GONE
    val cx = binding.calculatorContainer.width / 2
    val cy = binding.calculatorContainer.height / 2
    val maxRadius = hypot(cx.toDouble(), cy.toDouble()).toFloat()
    spawnRippleRing(cx, cy, maxRadius, 0L, 0.24f, 280L, inward = true)
    spawnRippleRing(cx, cy, maxRadius, 80L, 0.18f, 220L, inward = true)
    ViewAnimationUtils.createCircularReveal(binding.calculatorContainer, cx, cy, maxRadius, 0f).apply {
        duration = 280L
        interpolator = AccelerateDecelerateInterpolator()
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                binding.calculatorContainer.visibility = View.INVISIBLE
                binding.splashCircle.visibility = View.GONE
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                }
            }
        })
        start()
    }
    wobbleRound(cx.toFloat(), cy.toFloat(), maxRadius, 280L, inward = true)
}


fun MainActivity.showButtonPopup(anchor: View, text: String) {
    keyPopupOverlay?.let { (it.parent as? FrameLayout)?.removeView(it) }; keyPopupOverlay = null
    val root = binding.rootFrame; val density = resources.displayMetrics.density
    val popupBg = if (isDarkMode) "#F8F8F8" else "#2E2E2E"
    val popupText = if (isDarkMode) "#2D2D2D" else "#F2F2F2"
    val strokeCol = if (isDarkMode) "#13959B" else "#E58A7B"
    val bubble = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(Color.parseColor(popupText))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(Color.parseColor(popupBg))
            setStroke((1.2f * density).toInt().coerceAtLeast(1), Color.parseColor(strokeCol))
        }
        elevation = 12f * density
        setPadding((14 * density).toInt(), (9 * density).toInt(), (14 * density).toInt(), (9 * density).toInt())
        isClickable = true; isFocusable = true; setOnClickListener { }
        visibility = View.INVISIBLE
    }
    val overlay = FrameLayout(this).apply {
        elevation = 48f * density
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { dismissButtonPopup() }
    }
    // Full-strength frosted backdrop behind the bubble captured BEFORE overlay joins the tree
    val frostBmp = Frost.frozenBackdrop(root, downscale = 6, radius = 6)
    if (frostBmp != null) {
        overlay.addView(ImageView(this).apply {
            setImageBitmap(frostBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    overlay.addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    keyPopupOverlay = overlay

    fun positionAndAnimateBubble() {
        if (keyPopupOverlay !== overlay) return
        val rootW = if (root.width > 0) root.width else resources.displayMetrics.widthPixels
        val rootH = if (root.height > 0) root.height else resources.displayMetrics.heightPixels
        val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
        val anchorLoc = IntArray(2); anchor.getLocationInWindow(anchorLoc)
        val layerPad = (12 * density).toInt()
        val maxW = (rootW - 2 * layerPad).coerceAtLeast((160 * density).toInt())
        val maxH = (rootH - 2 * layerPad).coerceAtLeast((120 * density).toInt())

        // Always measure explicitly with AT_MOST so bw and bh are never 0 even before first layout pass
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
        )
        val bw = bubble.measuredWidth.coerceIn((80 * density).toInt(), maxW)
        val bh = bubble.measuredHeight.coerceIn((36 * density).toInt(), maxH)

        val anchorCx = anchorLoc[0] + anchor.width / 2 - rootLoc[0]
        val anchorTop = anchorLoc[1] - rootLoc[1]
        val anchorBottom = anchorTop + anchor.height
        val showAbove = anchorTop - bh - layerPad >= layerPad
        val rawY = if (showAbove) anchorTop - bh - layerPad else anchorBottom + layerPad
        val maxTop = (rootH - bh - layerPad).coerceAtLeast(layerPad)
        val y = rawY.coerceIn(layerPad, maxTop)
        val maxLeft = (rootW - bw - layerPad).coerceAtLeast(layerPad)
        val x = (anchorCx - bw / 2).coerceIn(layerPad, maxLeft)

        (bubble.layoutParams as FrameLayout.LayoutParams).apply {
            width = bw
            leftMargin = x
            topMargin = y
        }
        bubble.requestLayout()
        bubble.visibility = View.VISIBLE
        bubble.pivotX = bw / 2f
        bubble.pivotY = if (showAbove) bh.toFloat() else 0f
        bubble.translationY = if (showAbove) 12f * density else -12f * density
        val frostChild = overlay.getChildAt(0).takeIf { it !== bubble }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 430L; interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val decay = Math.exp(-4.0 * f); val s = (1.0 - 0.45 * decay * Math.cos(f * 3.0 * Math.PI)).toFloat()
                bubble.scaleX = s; bubble.scaleY = s
                bubble.alpha = (f / 0.22f).coerceIn(0f, 1f)
                bubble.translationY = (if (showAbove) 12f * density else -12f * density) * (1f - f)
                frostChild?.alpha = 0.92f * (f / 0.3f).coerceIn(0f, 1f)
            }; start()
        }
    }

    if (root.width > 0 && anchor.width > 0) {
        positionAndAnimateBubble()
    } else {
        overlay.post { positionAndAnimateBubble() }
    }
}

fun MainActivity.dismissButtonPopup() {
    keyPopupOverlay?.let { overlay ->
        keyPopupOverlay = null
        // The bubble is the LAST child (frost backdrop sits below it)
        val bubble = overlay.getChildAt(overlay.childCount - 1)
        // Frosted backdrop fades out in parallel with the bubble shrink
        if (overlay.childCount > 1) {
            overlay.getChildAt(0).animate().alpha(0f).setDuration(170L).start()
        }
        if (bubble != null) bubble.animate().scaleX(0.62f).scaleY(0.62f).alpha(0f)
            .setDuration(170L).setInterpolator(AccelerateInterpolator(0.8f))
            .withEndAction { (overlay.parent as? FrameLayout)?.removeView(overlay) }.start()
        else (overlay.parent as? FrameLayout)?.removeView(overlay)
    }
}

internal fun MainActivity.bindHoldPopup(view: View, text: String) {
    var isHoldActive = false
    view.isLongClickable = true
    view.setOnLongClickListener {
        isHoldActive = true
        view.parent?.requestDisallowInterceptTouchEvent(true)
        showButtonPopup(view, text)
        true
    }
    view.setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isHoldActive = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isHoldActive || keyPopupOverlay != null) {
                    isHoldActive = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    dismissButtonPopup()
                }
            }
        }
        false
    }
}

internal fun MainActivity.tabDescriptionFor(name: String): String = when (name) {
    "History" -> "History — your last 15 calculations, newest first. Tap an entry to place its result onto the " +
        "current keypad input; hold 'Clear History' to wipe the whole list with a slide-out animation."
    "Basic keypad" -> "Basic keypad — the everyday grid: digits, decimal point, AC / ⌫, M+ / M− and the four " +
        "core operations (×  ÷  +  −). Hold any key for a usage hint."
    "Extended keypad" -> "Extended keypad — parentheses, %, powers, √, ⁿ√ (nth root), factorial (!), and " +
        "scientific-notation EE. Hold a key for examples of how it enters the expression."
    "Trig" -> "Trig keypad — sin / cos / tan with their inverses and reciprocals, the hyperbolic family, and the " +
        "constants π and e. Angles follow the deg/rad mode shown under the display."
    "Fx" -> "Fx keypad — general functions: abs, log / log₂, powers (x², x³, xʸ, 10ˣ, eˣ), roots (cbrt), " +
        "combinatorics (nPr, nCr), gcd / lcm, round / ceil / floor, rand, the parameter separator comma (,), and Ans recall."
    else -> "$name — open this tab"
}

fun MainActivity.keyDescriptionFor(label: String): String = when (label) {
    "AC" -> "AC — All Clear: wipes the whole pending expression, the current operand and resets the display to 0.0. Memory (M) is NOT touched."
    "⌫" -> "⌫ — Backspace: deletes the last typed character of the current operand; if the operand is empty it trims the trailing token of the expression."
    "M+" -> "M+ — adds the value currently on the display to the memory slot. The MR value under the display updates immediately."
    "M-" -> "M− — subtracts the value currently on the display from the memory slot. Useful for running totals without a paper trail."
    "=" -> "= — evaluates the full expression with the native Math-Engine and records 'expr = result' into History (tap a History entry to reuse a result)."
    "Ans" -> "Ans — inserts the last computed answer into the expression: tap initially to start with it, tap again to append. chains like Ans+1, Ans×2 work."
    "x" -> "× — multiplication"
    "÷" -> "÷ — division (0 ÷ anything stays 0; nonzero ÷ 0 reports a division error)"
    "+" -> "+ — addition"
    "-" -> "− — subtraction; also works as a negative sign when it is the first token, e.g. −5 + 3"
    "%" -> "% — percentage: 200 + 10% = 220 · 200 × 10% = 20 · 10% = 0.1"
    "^" -> "^ — power operator: enter the base, tap ^, then the exponent — 2 ^ 10 = 1024. Non-integer exponents use the engine's fp math."
    "EE" -> "EE — scientific-notation exponent: 3 EE 8 enters 3×10⁸. Use ± keys to sign the exponent."
    "π" -> "π — pi (3.14159…): taps insert the constant directly; π/2, 2π r, sin(π) are all valid expressions."
    "e" -> "e — Euler's number (2.71828…): e^x via xʸ, or use the dedicated eˣ key on the fx grid."
    "√" -> "√ — square root: √(x). Entering a number first auto-multiplies (√(9…) becomes √(9×…) — use parentheses for multi-term radicands."
    "ⁿ√" -> "ⁿ√ — nth root: ⁿ√(x, n) or type n then tap ⁿ√ — e.g. ⁿ√(27, 3) = 3 (cube root). n may be fractional for exotic roots."
    "!" -> "! — factorial: appends ! to the value on the display — 5! = 120. Non-integers fall back to the engine's gamma-based factorial."
    "1/x" -> "1/x — reciprocal: turns the current operand into its multiplicative inverse; 0 is rejected with a division error."
    "x²" -> "x² — squares the current operand: 1.5 x² = 2.25. Applied after a ')' it squares the parenthesised term."
    "x³" -> "x³ — cubes the current operand: 4 x³ = 64."
    "xʸ" -> "xʸ — exponentiate: type the base, tap xʸ, then type the exponent and press = — 2 xʸ 0.5 = √2."
    "10ˣ" -> "10ˣ — power of ten: inserts 10^( so you complete the exponent — 10^(3) = 1000. Handy with EE-style magnitudes."
    "eˣ" -> "eˣ — natural exponential: inserts e^( — e^(1) ≈ 2.71828. Pairs with ln for shift/undo style math."
    "ln" -> "ln — natural logarithm, base e — ln(e) = 1, ln(e²) = 2. Input ≤ 0 reports a domain error."
    "log" -> "log — logarithm, base 10 — log(1000) = 3. Input ≤ 0 reports a domain error."
    "log₂" -> "log₂ — logarithm, base 2 — log₂(256) = 8. Bit-count style estimates, input ≤ 0 is a domain error."
    "round" -> "round(x) — rounds to the nearest integer, ties upward: round(2.5) = 3, round(−2.5) = −2 (banker's rounding is NOT applied)."
    "gcd" -> "gcd(a, b) — greatest common divisor of two integers: gcd(48, 18) = 6. Negative inputs use absolute value."
    "lcm" -> "lcm(a, b) — least common multiple: lcm(4, 6) = 12."
    "cbrt" -> "cbrt(x) — real cube root: cbrt(27) = 3 and cbrt(−27) = −3 (sign-preserving, unlike fractional powers)."
    "nPr" -> "nPr(n, r) — permutations: ORDER matters — nPr(5, 2) = 20. r > n reports a combinatorics error."
    "nCr" -> "nCr(n, r) — combinations: ORDER does not matter — nCr(5, 2) = 10 (binomial coefficient)."
    "ceil" -> "ceil(x) — rounds up: ceil(2.001) = 3, ceil(−2.1) = −2."
    "floor" -> "floor(x) — rounds down: floor(2.999) = 2, floor(−2.001) = −3."
    "rand" -> "rand() — a fresh uniform random number in [0, 1) each time it is evaluated."
    "," -> ", — parameter separator for multi-argument functions such as gcd(a, b), lcm(a, b), nPr(n, r), nCr(n, r), and ⁿ√(x, n)."
    "(" -> "( — opening parenthesis; also starts a function's argument list, e.g. log(100)"
    ")" -> ") — closing parenthesis (or comma in 2-arg functions)"
    "sin" -> "sin — sine of the angle in the active deg/rad mode"
    "cos" -> "cos — cosine of the angle in the active deg/rad mode"
    "tan" -> "tan — tangent; ±90°/±π/2 reports a tangent asymptote instead of a huge number"
    "sec" -> "sec — secant = 1/cos(x); undefined where cos(x) = 0"
    "cosec" -> "cosec — cosecant = 1/sin(x); undefined where sin(x) = 0"
    "cot" -> "cot — cotangent = 1/tan(x); undefined at multiples of π"
    "asin" -> "sin⁻¹ — inverse sine (arcsine): domain [−1, 1], result in deg/rad mode. asin(0.5) = 30° in degrees"
    "acos" -> "cos⁻¹ — inverse cosine (arccosine): domain [−1, 1], result in deg/rad mode. acos(0.5) = 60° in degrees"
    "atan" -> "tan⁻¹ — inverse tangent (arctangent): accepts any real, result in deg/rad mode"
    "asec" -> "sec⁻¹ — inverse secant: domain |x| ≥ 1, result in deg/rad mode"
    "acosec" -> "csc⁻¹ — inverse cosecant: domain |x| ≥ 1, result in deg/rad mode"
    "acot" -> "cot⁻¹ — inverse cotangent: accepts any real, result in deg/rad mode"
    "sinh" -> "sinh — hyperbolic sine: (eˣ − e^(−x))/2"
    "cosh" -> "cosh — hyperbolic cosine: (eˣ + e^(−x))/2, always ≥ 1"
    "tanh" -> "tanh — hyperbolic tangent: asymptotically approaches ±1"
    else -> "$label"
}
