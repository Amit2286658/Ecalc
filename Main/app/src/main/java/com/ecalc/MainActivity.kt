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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("ecalc_prefs", MODE_PRIVATE) }
    var isDarkMode: Boolean = true
    private var selectedTabIndex: Int = 1 // Default to Basic keypad
    var isDegreeMode: Boolean = true
    private var currentWorkspacePage: Int = 0 // 0 = Calculator, 1 = .math Studio
    private var currentToast: Toast? = null
    private var launchRevealStarted: Boolean = false
    private var isExitingApp: Boolean = false

    private var currentPrimaryInput: String = "0.0"
    private var currentSecondaryFormula: String = "0.0"
    private var lastAnswer: String = "0.0"
    /** Accumulated expression statement (display dialect, engine-translated
     *  at evaluate time): operators and function heads chain here while the
     *  active operand lives in currentPrimaryInput. */
    private var exprStatement: String = ""
    /** Simple one-slot memory: accumulates via M+/M−, cleared by MR button. */
    private var memoryValue: Double = 0.0
    val calculationHistory = mutableListOf<String>()

    private val wobbleAnimators = mutableListOf<ValueAnimator>()
    private val displayPulseAnimators = mutableListOf<Pair<View, ValueAnimator>>()
    /** Animate the MR cluster sliding — same damped-spring pulse used everywhere
     *  else (e^-7.5 t · sin(22 t)) instead of DecelerateInterpolator / LinearInterpolator. */
    private val mrSlideAnimators = mutableListOf<ValueAnimator>()
    private val tabPhysicsAnimators = mutableListOf<ValueAnimator>()
    private val pagerSpringAnimators = mutableListOf<ValueAnimator>()
    private val workspaceSlideAnimators = mutableListOf<ValueAnimator>()
    private var workspaceFragment: MathWorkspaceFragment? = null

    /** Distance-dependent tab physics tracking (1 = adjacent tab, 4 = farthest tab). */
    private var lastTabDistance: Int = 1
    private var lastTabDirection: Int = 1
    private var pendingTabPhysicsPage: Int = -1

    /** Blurred-background About info window overlay. */
    private var aboutOverlay: FrameLayout? = null
    private var aboutBlurredBitmap: Bitmap? = null

    private var swipeStartX: Float = 0f
    private var swipeStartY: Float = 0f
    private var isWorkspaceSwiping: Boolean = false
    private var swipeBlockedByChild: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore persisted theme before super.onCreate so recreated fragments read the right mode
        isDarkMode = prefs.getBoolean("dark_mode", isDarkMode)
        isDegreeMode = prefs.getBoolean("degree_mode", isDegreeMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvAngleMode.text = if (isDegreeMode) "deg(Degree)" else "rad(Radian)"
        styleSplashDots(isDarkMode)

        // Immediately hand off from the Android 12+ (API 31+) system splash screen
        // into our in-app ripple reveal so the static icon never lingers on cold start.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                splashScreen.setOnExitAnimationListener { splashView ->
                    splashView.remove()
                    triggerLaunchSplashIfReady()
                }
            } catch (_: Throwable) { /* fall back */ }
        }

        // Initialize context asynchronously (no main-thread asset I/O on cold start)
        MathEngineManager.initialize(applicationContext)

        setupWorkspaceFragment()
        setupViewPager()
        setupInteractions()
        applyThemeColors(isDarkMode)
        applyThemeIconScales(isDarkMode, animate = false)

        Thread {
            MathEngineManager.ensureEngine()
            // Download any new incremental .math extension patches in the background
            // and load them if new patches arrived
            if (MathPatchDownloader.syncMissingPatches(applicationContext) > 0) {
                MathEngineManager.loadPendingPatches()
            }
        }.start()

        // Start the in-app ripple animation on the very first pre-draw frame
        binding.rootFrame.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (binding.rootFrame.viewTreeObserver.isAlive) {
                        binding.rootFrame.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    triggerLaunchSplashIfReady()
                    return true
                }
            }
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    aboutOverlay != null -> dismissAboutInfoWindow()
                    currentWorkspacePage == 1 && workspaceFragment?.handleBackPressed() == true -> Unit
                    currentWorkspacePage == 1 -> switchWorkspacePage(0)
                    else -> playExitSplashCollapse()
                }
            }
        })
    }

    private fun styleSplashDots(dark: Boolean) {
        val outerColor = if (dark) "#3A3E43" else "#E2E5E9"
        val innerColor = if (dark) "#F8F8F8" else "#2D2D2D"
        binding.splashOuterDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(outerColor))
        }
        binding.splashInnerDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(innerColor))
        }
    }

    private fun triggerLaunchSplashIfReady() {
        if (launchRevealStarted) return
        if (binding.rootFrame.width > 0 && binding.rootFrame.height > 0) {
            launchRevealStarted = true
            playLaunchSplashReveal()
        } else {
            binding.rootFrame.post {
                if (!launchRevealStarted) {
                    launchRevealStarted = true
                    playLaunchSplashReveal()
                }
            }
        }
    }

    fun showButtonToast(label: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, label, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun setupWorkspaceFragment() {
        val existing = supportFragmentManager.findFragmentByTag("math_workspace") as? MathWorkspaceFragment
        if (existing != null) {
            workspaceFragment = existing
        } else {
            val frag = MathWorkspaceFragment()
            workspaceFragment = frag
            supportFragmentManager.beginTransaction()
                .replace(binding.mathWorkspaceContainer.id, frag, "math_workspace")
                .commitNowAllowingStateLoss()
        }
        binding.mathWorkspaceContainer.visibility = View.INVISIBLE
    }

    /** Switch between Calculator (page 0) and Free-form .math Studio (page 1)
     *  using the signature damped-spring physics (e^-7.5t * sin(22t)). */
    fun switchWorkspacePage(targetPage: Int, initialVelocityPx: Float = 0f) {
        val clamped = targetPage.coerceIn(0, 1)
        workspaceSlideAnimators.forEach { it.cancel() }
        workspaceSlideAnimators.clear()

        if (clamped == 1) {
            workspaceFragment?.applyTheme(isDarkMode)
        }

        val width = binding.rootFrame.width.toFloat().coerceAtLeast(1f)
        val calcView = binding.calculatorContainer
        val studioView = binding.mathWorkspaceContainer

        calcView.visibility = View.VISIBLE
        studioView.visibility = View.VISIBLE

        val startCalcTx = calcView.translationX
        val endCalcTx = if (clamped == 0) 0f else -width
        val deltaCalc = endCalcTx - startCalcTx

        val pageChanged = (clamped != currentWorkspacePage)
        currentWorkspacePage = clamped

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()

                val curCalcTx = endCalcTx - deltaCalc * springFactor
                calcView.translationX = curCalcTx
                studioView.translationX = curCalcTx + width

                val scaleBump = 1f + 0.025f * pulse
                if (clamped == 1) {
                    studioView.scaleX = scaleBump
                    studioView.scaleY = scaleBump
                    calcView.scaleX = 1f
                    calcView.scaleY = 1f
                } else {
                    calcView.scaleX = scaleBump
                    calcView.scaleY = scaleBump
                    studioView.scaleX = 1f
                    studioView.scaleY = 1f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    calcView.translationX = endCalcTx
                    studioView.translationX = endCalcTx + width
                    calcView.scaleX = 1f; calcView.scaleY = 1f
                    studioView.scaleX = 1f; studioView.scaleY = 1f
                    if (clamped == 0) {
                        studioView.visibility = View.INVISIBLE
                    } else {
                        calcView.visibility = View.INVISIBLE
                    }
                }
            })
            start()
            workspaceSlideAnimators.add(this)
        }

        if (pageChanged) {
            val cx = if (clamped == 1) width * 0.85f else width * 0.15f
            val cy = binding.rootFrame.height * 0.25f
            val maxR = hypot(width.toDouble(), binding.rootFrame.height.toDouble()).toFloat()
            if (clamped == 1) {
                workspaceFragment?.wobbleRound(cx, cy, maxR, 380L)
            } else {
                wobbleRound(cx, cy, maxR, 380L)
            }
        }
    }

    private fun isTouchInsideInteractiveWorkspaceView(ev: MotionEvent): Boolean {
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        fun hit(v: View?): Boolean {
            if (v == null || !v.isShown) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return x in loc[0]..(loc[0] + v.width) && y in loc[1]..(loc[1] + v.height)
        }
        if (currentWorkspacePage == 0) {
            // On calculator page, swiping on the top display section switches to .math Studio,
            // while swiping on the bottom keypad sheet scrolls between the 5 keypad tabs.
            return hit(binding.keypadSheet)
        }
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (aboutOverlay != null) {
            return super.dispatchTouchEvent(ev)
        }
        if (currentWorkspacePage == 1 && workspaceFragment?.isFileManagerDrawerOpen == true) {
            return super.dispatchTouchEvent(ev)
        }
        val density = resources.displayMetrics.density
        val touchSlop = 18f * density
        val width = binding.rootFrame.width.toFloat().coerceAtLeast(1f)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.rawX
                swipeStartY = ev.rawY
                isWorkspaceSwiping = false
                val fromRightEdge = ev.rawX >= width - 28f * density
                val fromLeftEdge = ev.rawX <= 36f * density
                swipeBlockedByChild = if (currentWorkspacePage == 0) {
                    !fromRightEdge && isTouchInsideInteractiveWorkspaceView(ev)
                } else {
                    !fromLeftEdge && ev.rawY > 150f * density
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeBlockedByChild) {
                    val dx = ev.rawX - swipeStartX
                    val dy = ev.rawY - swipeStartY
                    if (!isWorkspaceSwiping && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        val validLeftSwipe = currentWorkspacePage == 0 && dx < 0
                        val validRightSwipe = currentWorkspacePage == 1 && dx > 0
                        if (validLeftSwipe || validRightSwipe) {
                            isWorkspaceSwiping = true
                            workspaceSlideAnimators.forEach { it.cancel() }
                            workspaceSlideAnimators.clear()
                            if (validLeftSwipe) {
                                workspaceFragment?.applyTheme(isDarkMode)
                            }
                            binding.calculatorContainer.visibility = View.VISIBLE
                            binding.mathWorkspaceContainer.visibility = View.VISIBLE
                            val cancelEvent = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                    }
                    if (isWorkspaceSwiping) {
                        val baseCalcTx = if (currentWorkspacePage == 0) 0f else -width
                        val newCalcTx = (baseCalcTx + dx).coerceIn(-width, 0f)
                        binding.calculatorContainer.translationX = newCalcTx
                        binding.mathWorkspaceContainer.translationX = newCalcTx + width
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isWorkspaceSwiping) {
                    val dx = ev.rawX - swipeStartX
                    val threshold = width * 0.22f
                    val target = when {
                        currentWorkspacePage == 0 && dx < -threshold -> 1
                        currentWorkspacePage == 1 && dx > threshold -> 0
                        else -> currentWorkspacePage
                    }
                    isWorkspaceSwiping = false
                    switchWorkspacePage(target)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Five tabs (history + 4 keypads) live inside a ViewPager2 with distance-dependent damped-spring physics. */
    private fun setupViewPager() {
        binding.viewPager.adapter = EcalcPagerAdapter(this)
        binding.viewPager.setCurrentItem(selectedTabIndex, false)
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.setPageTransformer { page, position ->
            val absPos = Math.abs(position).coerceIn(0f, 1f)
            val distScale = 0.75f + 0.35f * lastTabDistance.coerceIn(1, 4)
            val t = (1f - absPos) * 0.48
            val pulse = (Math.exp(-7.5 * t) * Math.sin(t * 22.0)).toFloat() * absPos * distScale
            val scale = (0.92f + 0.08f * (1f - absPos)) + 0.03f * pulse
            page.scaleX = scale
            page.scaleY = scale
            val clampedPos = position.coerceIn(-1.5f, 1.5f)
            page.rotation = -clampedPos * (2.6f + 0.9f * lastTabDistance.coerceIn(1, 4)) * (1f - absPos * 0.5f)
            page.alpha = 0.65f + 0.35f * (1f - absPos)
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val prevIndex = selectedTabIndex
                val rawDistance = kotlin.math.abs(position - prevIndex)
                val distance = rawDistance.coerceIn(1, 4)
                val direction = if (position >= prevIndex) 1 else -1

                lastTabDistance = distance
                lastTabDirection = direction
                selectedTabIndex = position

                updateTabIndicators(animate = true, prevIndex = prevIndex, distance = distance)
                if (position == 0) refreshHistoryUI()

                val tabs = listOf(
                    binding.tabHistory, binding.tabBasic, binding.tabExtended,
                    binding.tabTrig, binding.tabFx
                )
                val tab = tabs[position]
                tabSpringPulse(tab, direction, distance)

                binding.tabScroll.post {
                    val density = resources.displayMetrics.density
                    val target = (tab.left - (binding.tabScroll.width - tab.width) / 2)
                        .coerceAtLeast(0)
                    val startScroll = binding.tabScroll.scrollX
                    val delta = target - startScroll
                    val barKickPx = -(6f + 5.5f * (distance - 1)) * density * direction
                    val dur = 460L + (distance - 1) * 55L

                    tabPhysicsAnimators.forEach { it.cancel() }
                    tabPhysicsAnimators.clear()
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = dur
                        interpolator = LinearInterpolator()
                        addUpdateListener { anim ->
                            val f = anim.animatedValue as Float
                            val t = f * 0.48
                            val decay = Math.exp(-7.5 * t)
                            val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                            if (delta != 0) {
                                val curScroll = (target - delta * springFactor).toInt()
                                binding.tabScroll.scrollTo(curScroll, 0)
                            }
                            binding.tabBarRow.translationX = barKickPx * springFactor
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (delta != 0) binding.tabScroll.scrollTo(target, 0)
                                binding.tabBarRow.translationX = 0f
                            }
                        })
                        start()
                        tabPhysicsAnimators.add(this)
                    }
                }

                if (rawDistance <= 1) {
                    pendingTabPhysicsPage = -1
                    triggerTabPagePhysics(position, direction, distance)
                } else {
                    pendingTabPhysicsPage = position
                }
            }

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                if (pendingTabPhysicsPage != -1 && pendingTabPhysicsPage == selectedTabIndex) {
                    val currentFloatPos = position + positionOffset
                    if (kotlin.math.abs(currentFloatPos - selectedTabIndex) <= 0.65f) {
                        val targetPos = pendingTabPhysicsPage
                        pendingTabPhysicsPage = -1
                        triggerTabPagePhysics(targetPos, lastTabDirection, lastTabDistance)
                    }
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE && pendingTabPhysicsPage != -1) {
                    val targetPos = pendingTabPhysicsPage
                    pendingTabPhysicsPage = -1
                    triggerTabPagePhysics(targetPos, lastTabDirection, lastTabDistance)
                }
            }
        })
    }

    private fun triggerTabPagePhysics(position: Int, direction: Int, distance: Int) {
        val clampedDist = distance.coerceIn(1, 4)
        val d = clampedDist.toFloat()
        val density = resources.displayMetrics.density

        pagerSpringAnimators.forEach { it.cancel() }
        pagerSpringAnimators.clear()

        val pagerSlidePx = (10f + (d - 1f) * 14f) * density * direction
        val pagerScaleBump = 0.018f + (d - 1f) * 0.022f
        val pagerRotAmp = (0.8f + (d - 1f) * 1.1f) * direction
        val settleDuration = 460L + (clampedDist - 1) * 60L

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = settleDuration
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()

                binding.viewPager.translationX = pagerSlidePx * springFactor * 0.5f
                binding.viewPager.scaleX = 1f + pagerScaleBump * pulse
                binding.viewPager.scaleY = 1f + pagerScaleBump * pulse
                binding.viewPager.rotation = (pagerRotAmp * decay * Math.sin(t * 22.0 + 0.6)).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.viewPager.translationX = 0f
                    binding.viewPager.scaleX = 1f
                    binding.viewPager.scaleY = 1f
                    binding.viewPager.rotation = 0f
                }
            })
            start()
            pagerSpringAnimators.add(this)
        }

        fun invokeFragmentSpring(attempt: Int) {
            val frag = fragmentAt(position)
            when (frag) {
                is KeyGridFragment -> frag.springSettle(direction, clampedDist)
                is HistoryFragment -> frag.springSettle(direction, clampedDist)
                else -> if (attempt < 4) {
                    binding.viewPager.postDelayed({ invokeFragmentSpring(attempt + 1) }, 24L)
                }
            }
        }
        binding.viewPager.post { invokeFragmentSpring(0) }
    }

    private fun tabSpringPulse(view: View, direction: Int, distance: Int) {
        displayPulseAnimators.removeAll { (v, a) ->
            if (v === view) {
                a.cancel()
                true
            } else false
        }
        val clampedDist = distance.coerceIn(1, 4)
        val d = clampedDist.toFloat()
        val density = resources.displayMetrics.density
        val bump = 0.09f + (d - 1f) * 0.055f
        val pushPx = (4f + (d - 1f) * 3.5f) * density * direction
        val rotAmp = (2.5f + (d - 1f) * 2.2f) * direction
        val dur = 460L + (clampedDist - 1) * 55L

        if (view.width > 0) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                view.scaleX = 1f + bump * pulse
                view.scaleY = 1f + bump * pulse
                view.translationX = pushPx * pulse
                view.rotation = (rotAmp * decay * Math.sin(t * 22.0 + 0.6)).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.translationX = 0f
                    view.rotation = 0f
                }
            })
            start()
        }
        displayPulseAnimators.add(view to anim)
    }

    inner class EcalcPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 5
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> HistoryFragment()
            else -> KeyGridFragment.newInstance(position)
        }
    }

    private fun fragmentAt(position: Int): Fragment? =
        supportFragmentManager.findFragmentByTag("f$position")

    fun clearHistory() {
        val frag = fragmentAt(0) as? HistoryFragment
        if (frag == null) {
            calculationHistory.clear()
            refreshHistoryUI()
            return
        }
        frag.animateClearThenReset {
            calculationHistory.clear()
            refreshHistoryUI(fadeInPlaceholder = true)
        }
    }

    fun onHistoryItemClicked(item: String) {
        val trimmed = item.trim()
        if (trimmed.isEmpty()) return
        val value = if (trimmed.contains(" = ")) {
            trimmed.substringAfterLast(" = ").substringBefore("[").trim()
        } else trimmed
        if (value.isEmpty()) return

        currentPrimaryInput =
            if (currentPrimaryInput == "0.0" || currentPrimaryInput.isBlank()) value
            else currentPrimaryInput + value

        binding.tvPrimaryDisplay.text =
            if (currentPrimaryInput == "0.0") exprStatement.trimEnd().ifBlank { "0.0" }
            else (exprStatement + currentPrimaryInput).ifBlank { "0.0" }
        displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
    }

    private fun setupInteractions() {
        binding.btnMrClear.apply {
            isAccentKey = false
            isDarkThemeMode = isDarkMode
            cornerRadiusDp = 24f
            contentDescription = "Clear Memory"
            setOnClickListener {
                memoryValue = 0.0
                binding.tvMrValue.text = formatValue(0.0)
                displaySpringPulse(binding.tvMrValue, 0.05f)
                showButtonToast("Memory cleared")
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) showButtonPopup(this, "Clear memory — zeroes the single memory slot (M) behind the MR " +
                    "cluster. The recalled value shown under the display resets to 0.0; the number currently on the " +
                    "keypad is untouched.")
                else dismissButtonPopup()
            }
        }

        bindHoldPopup(binding.themeTogglePill, "Theme toggle — switches the whole app between the dark palette " +
            "(graphite surfaces, teal accents) and the light palette (porcelain surfaces, coral accents) with a " +
            "circular reveal from the toggle. Yours is remembered across restarts.")
        bindHoldPopup(binding.tvAngleMode, "Angle mode — taps switch how trigonometric functions interpret angles: " +
            "Degrees (sin 90° = 1) or Radians (sin π/2 = 1). The choice is saved persistently; the label below the " +
            "display always shows the active mode.")
        bindHoldPopup(binding.btnAboutInfo, "About & Docs — opens a blurred-glass modal with Ecalc & developer info " +
            "(Amit kumar), GitHub links, MIT License T&C, and the complete embedded CMath Engine (MEP) documentation.")

        binding.themeTogglePill.setOnClickListener {
            toggleThemeWithCircularReveal()
        }

        binding.cbMrToggle.setOnCheckedChangeListener { _, isChecked ->
            animateMrClusterPhysics(isChecked)
        }

        binding.btnAboutInfo.setOnClickListener {
            displaySpringPulse(binding.btnAboutInfo, 0.10f)
            showAboutInfoWindow()
        }

        binding.tvAngleMode.setOnClickListener {
            isDegreeMode = !isDegreeMode
            prefs.edit().putBoolean("degree_mode", isDegreeMode).apply()
            binding.tvAngleMode.text = if (isDegreeMode) "deg(Degree)" else "rad(Radian)"
            displaySpringPulse(binding.tvAngleMode, 0.05f)
        }

        binding.tvMrValue.setOnClickListener {
            val recalled = formatValue(memoryValue)
            currentPrimaryInput = recalled
            binding.tvPrimaryDisplay.text =
                if (currentPrimaryInput == "0.0") exprStatement.trimEnd().ifBlank { "0.0" }
                else (exprStatement + currentPrimaryInput).ifBlank { "0.0" }
            displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
            displaySpringPulse(binding.tvMrValue, 0.05f)
        }

        val tabViews = listOf(
            binding.tabHistory, binding.tabBasic, binding.tabExtended,
            binding.tabTrig, binding.tabFx
        )
        val tabNames = listOf("History", "Basic keypad", "Extended keypad", "Trig", "Fx")

        tabViews.forEachIndexed { idx, tab ->
            tab.setOnClickListener {
                if (idx == selectedTabIndex) {
                    tabSpringPulse(tab, 1, 1)
                    triggerTabPagePhysics(idx, 1, 1)
                } else {
                    binding.viewPager.setCurrentItem(idx, true)
                }
            }
            bindHoldPopup(tab, tabDescriptionFor(tabNames[idx]))
        }
    }

    // =========================================================================
    //  About Info Modal with Blurred Background, Developer Info, MIT License &
    //  In-Project Embedded CMath Engine Documentation
    // =========================================================================

    private fun createBlurredBackdropBitmap(sourceView: View, radius: Int = 8): Bitmap? {
        val w = sourceView.width
        val h = sourceView.height
        if (w <= 0 || h <= 0) return null
        return try {
            val scale = 6
            val sw = (w / scale).coerceAtLeast(16)
            val sh = (h / scale).coerceAtLeast(16)
            val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(1f / scale, 1f / scale)
            sourceView.draw(canvas)

            val pixels = IntArray(sw * sh)
            val temp = IntArray(sw * sh)
            bmp.getPixels(pixels, 0, sw, 0, 0, sw, sh)

            val r = radius.coerceIn(2, 14)
            val div = 2 * r + 1
            repeat(2) {
                for (y in 0 until sh) {
                    val rowOffset = y * sw
                    for (x in 0 until sw) {
                        var sumA = 0
                        var sumR = 0
                        var sumG = 0
                        var sumB = 0
                        for (k in -r..r) {
                            val px = (x + k).coerceIn(0, sw - 1)
                            val c = pixels[rowOffset + px]
                            sumA += (c ushr 24) and 0xFF
                            sumR += (c ushr 16) and 0xFF
                            sumG += (c ushr 8) and 0xFF
                            sumB += c and 0xFF
                        }
                        temp[rowOffset + x] =
                            ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                    }
                }
                for (x in 0 until sw) {
                    for (y in 0 until sh) {
                        var sumA = 0
                        var sumR = 0
                        var sumG = 0
                        var sumB = 0
                        for (k in -r..r) {
                            val py = (y + k).coerceIn(0, sh - 1)
                            val c = temp[py * sw + x]
                            sumA += (c ushr 24) and 0xFF
                            sumR += (c ushr 16) and 0xFF
                            sumG += (c ushr 8) and 0xFF
                            sumB += c and 0xFF
                        }
                        pixels[y * sw + x] =
                            ((sumA / div) shl 24) or ((sumR / div) shl 16) or ((sumG / div) shl 8) or (sumB / div)
                    }
                }
            }
            bmp.setPixels(pixels, 0, sw, 0, 0, sw, sh)
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun readAssetTextOrFallback(assetPath: String, fallback: String): String {
        return try {
            assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
                .ifEmpty { fallback }
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun openExternalUri(uriString: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
            startActivity(intent)
        } catch (_: Throwable) {
            showButtonToast("Link: $uriString")
        }
    }

    fun showAboutInfoWindow() {
        if (aboutOverlay != null) {
            dismissAboutInfoWindow()
            return
        }
        val root = binding.rootFrame
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }
        val dark = isDarkMode

        val cardBg = Color.parseColor(if (dark) "#2E2E2E" else "#F5F5F5")
        val sectionBg = Color.parseColor(if (dark) "#252525" else "#EAEAEA")
        val codeBg = Color.parseColor(if (dark) "#1D1D1D" else "#FFFFFF")
        val strokeColor = Color.parseColor(if (dark) "#444444" else "#D5D5D5")
        val primaryText = Color.parseColor(if (dark) "#E8E8E8" else "#2A2A2A")
        val secondaryText = Color.parseColor(if (dark) "#9E9E9E" else "#606060")
        val accentColor = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
        val scrimColor = Color.parseColor(if (dark) "#96141414" else "#88E6E6E6")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blurEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
            binding.calculatorContainer.setRenderEffect(blurEffect)
            binding.mathWorkspaceContainer.setRenderEffect(blurEffect)
        }
        val activeContainer = if (currentWorkspacePage == 1) binding.mathWorkspaceContainer else binding.calculatorContainer
        val blurredBmp = createBlurredBackdropBitmap(activeContainer, radius = 8)
        aboutBlurredBitmap = blurredBmp

        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { dismissAboutInfoWindow() }
        }

        if (blurredBmp != null) {
            val bgImage = ImageView(this).apply {
                setImageBitmap(blurredBmp)
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.35f else 0.92f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            overlay.addView(bgImage)
        }

        val scrimView = View(this).apply {
            setBackgroundColor(scrimColor)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlay.addView(scrimView)

        val cardWidth = (root.width - dp(28f)).coerceIn(dp(280f), dp(420f))
        val cardHeight = (root.height * 0.84f).toInt().coerceAtLeast(dp(420f))
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                cardWidth,
                cardHeight,
                Gravity.CENTER
            )
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            elevation = 16f * density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * density
                setColor(cardBg)
                setStroke(dp(1.2f).coerceAtLeast(1), strokeColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        // 1. Header row: App title + version badge + top-right '✕' close button
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(this).apply {
            text = "Ecalc"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
        }
        val tvVersion = TextView(this).apply {
            text = "Version 1.0 • Native C11 Math Engine (MEP)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(accentColor)
            setTypeface(typeface, Typeface.BOLD)
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvVersion)

        val btnCloseTop = NeumorphicKeyView(this).apply {
            text = "✕"
            textSize = 13f
            cornerRadiusDp = 18f
            shadowPadDp = 3f
            isAccentKey = true
            isDarkThemeMode = dark
            contentDescription = "Close About Window"
            layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
            setOnClickListener {
                dismissAboutInfoWindow()
            }
        }
        headerRow.addView(titleCol)
        headerRow.addView(btnCloseTop)
        infoCard.addView(headerRow)

        // 2. Scrollable content container holding Overview, Author/Contact/Links, License T&C, and Engine Docs
        val bodyScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { bottomMargin = dp(10f) }
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun makeSectionBox(bgColor: Int = sectionBg): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * density
                    setColor(bgColor)
                    setStroke(1, strokeColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10f) }
            }
        }

        fun makeSectionTitle(titleText: String): TextView {
            return TextView(this).apply {
                text = titleText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accentColor)
                setPadding(0, 0, 0, dp(4f))
            }
        }

        val tvDesc = TextView(this).apply {
            text = "A scientific Android calculator featuring 3D neumorphic keys, a precompiled C11 symbolic & numeric math engine, and damped-spring wavefront physics."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(secondaryText)
            setLineSpacing(0f, 1.18f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10f) }
        }
        scrollContent.addView(tvDesc)

        // Section A: Highlights box
        val detailsBox = makeSectionBox()
        detailsBox.addView(makeSectionTitle("Core Highlights"))
        fun addInfoBullet(target: LinearLayout, label: String, body: String, onClick: (() -> Unit)? = null) {
            val item = TextView(this).apply {
                text = "▸ $label : $body"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
                setTextColor(primaryText)
                setPadding(0, dp(2.5f), 0, dp(2.5f))
                setTextIsSelectable(onClick == null)
                if (onClick != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
            }
            target.addView(item)
        }
        addInfoBullet(detailsBox, "Math Engine", "O(1) stdlib.mathc boot, symbolic diff/solve & units")
        addInfoBullet(detailsBox, "UI Physics", "Damped-spring oscillations (e⁻⁷·⁵ᵗ · sin 22t)")
        addInfoBullet(detailsBox, ".math Studio", "Swipe left on the display for Modules & live Sessions")
        addInfoBullet(detailsBox, "Tooltips", "Press and hold any button or tab to inspect its action")
        scrollContent.addView(detailsBox)

        // Section B: Developer, Contact & Repository Info
        val authorBox = makeSectionBox()
        authorBox.addView(makeSectionTitle("Developer & Project Information"))
        addInfoBullet(authorBox, "Author", "Amit kumar")
        addInfoBullet(authorBox, "Email", "amitkumar13234353@gmail.com") {
            openExternalUri("mailto:amitkumar13234353@gmail.com?subject=Ecalc%20Query%20/%20Feedback")
        }
        addInfoBullet(authorBox, "Project GitHub", "GitHub.com/Ecalc") {
            openExternalUri("https://github.com/Ecalc")
        }
        addInfoBullet(authorBox, "Cmath engine", "GitHub.com/Math-Engine-MEP") {
            openExternalUri("https://github.com/Amit2286658/Math-Engine-MEP")
        }
        addInfoBullet(authorBox, "Engine Docs Guide", "GitHub.com/Math-Engine-MEP (Bundled in-project below)") {
            openExternalUri("https://github.com/Amit2286658/Math-Engine-MEP")
        }

        val tvContactNote = TextView(this).apply {
            text = "Contact the developer on the email address (amitkumar13234353@gmail.com) for any query/bug report/feature request."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(secondaryText)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(6f), 0, dp(6f))
            setTextIsSelectable(true)
        }
        authorBox.addView(tvContactNote)

        val quickLinksRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun makeQuickLinkBtn(label: String, uri: String): NeumorphicKeyView {
            return NeumorphicKeyView(this).apply {
                text = label
                textSize = 10.2f
                cornerRadiusDp = 10f
                shadowPadDp = 2.2f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(32f), 1f).apply {
                    marginEnd = dp(3f)
                }
                setOnClickListener {
                    displaySpringPulse(this, 0.06f)
                    openExternalUri(uri)
                }
            }
        }
        quickLinksRow.addView(makeQuickLinkBtn("✉ Email", "mailto:amitkumar13234353@gmail.com?subject=Ecalc%20Query%20/%20Feedback"))
        quickLinksRow.addView(makeQuickLinkBtn("🌐 Ecalc Repo", "https://github.com/Ecalc"))
        quickLinksRow.addView(makeQuickLinkBtn("⚙ MEP Engine", "https://github.com/Amit2286658/Math-Engine-MEP").apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        })
        authorBox.addView(quickLinksRow)
        scrollContent.addView(authorBox)

        // Section C: License & Terms & Conditions (MIT License)
        val licenseBox = makeSectionBox(codeBg)
        licenseBox.addView(makeSectionTitle("License & Terms & Conditions (MIT License)"))
        val licenseFallback = """
            MIT License

            Copyright (c) 2026 Amit kumar (amitkumar13234353@gmail.com)

            Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
        """.trimIndent()
        val licenseText = readAssetTextOrFallback("docs/LICENSE.txt", licenseFallback)
        val tvLicense = TextView(this).apply {
            text = licenseText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(primaryText)
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
        }
        licenseBox.addView(tvLicense)
        scrollContent.addView(licenseBox)

        // Section D: Embedded CMath Engine (Math-Engine-MEP) Documentation & Guides (Below License & T&C)
        val docsBox = makeSectionBox(codeBg)
        docsBox.addView(makeSectionTitle("CMath Engine (Math-Engine-MEP) — Documentation & Guides"))
        val tvDocsSub = TextView(this).apply {
            text = "Bundled in-project (docs/CMATH_ENGINE_DOCS.md) • Source: GitHub.com/Math-Engine-MEP"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f)
            setTextColor(secondaryText)
            setPadding(0, 0, 0, dp(6f))
        }
        docsBox.addView(tvDocsSub)

        val docsFallback = """
            # CMath Engine (Math-Engine-MEP) Reference Guide
            • Architecture: Self-contained C11 symbolic & numerical runtime with 8B NaN-boxed Values, O(1) stdlib.mathc binary boot ("MTHC" v1), topological <[dep] extension linker, and up to 8 isolated MSP1 sessions.
            • Expressions & Sessions: Implicit multiplication (2x, 2π), dynamic bindings (x := 10, f(t) := ...), ranges (1...10, 1..<10, by), list comprehensions ([x^2 for x in 1...8]), lambdas ((x) => x^2), pipes (x |> f), matrices ([1, 2; 3, 4]), and SI unit conversions (25 km in m).
            • Symbolic & Numerical Calculus: sym_diff, sym_integ, sym_solve, sym_simplify, sym_taylor, diff, integrate, lim, sigma, prod, solve, fmin, fmax, ode_rk4.
            • Writing [module] Extensions: Declare [module_name], dependencies <[arith], constants (c = 1.0;), typed functions (fn(x: real) = expr;), custom operators, and $type structs.
            • Standard Library (18 Modules): [boot_syntax], [boot_types], [boot_control], [arith], [trig], [user_trig], [complex_ext], [vec], [mat], [algebra], [calculus], [units], [conversions], [physics], [chemistry], [biology], [geom_ext], [string].
        """.trimIndent()
        val engineDocsText = readAssetTextOrFallback("docs/CMATH_ENGINE_DOCS.md", docsFallback)
        val tvEngineDocs = TextView(this).apply {
            text = engineDocsText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(primaryText)
            setLineSpacing(0f, 1.16f)
            setTextIsSelectable(true)
        }
        docsBox.addView(tvEngineDocs)
        scrollContent.addView(docsBox)

        bodyScroll.addView(scrollContent)
        infoCard.addView(bodyScroll)

        // 3. Bottom Close Button
        val btnCloseBottom = NeumorphicKeyView(this).apply {
            text = "Close"
            textSize = 13.5f
            cornerRadiusDp = 14f
            shadowPadDp = 3.5f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40f)
            )
            setOnClickListener {
                dismissAboutInfoWindow()
            }
        }
        infoCard.addView(btnCloseBottom)

        overlay.addView(infoCard)
        root.addView(overlay)
        aboutOverlay = overlay

        // Animate backdrop fade + damped-spring entrance of the info card
        overlay.alpha = 0f
        infoCard.scaleX = 0.78f
        infoCard.scaleY = 0.78f
        infoCard.translationY = 18f * density
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 440L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.44
                val decay = Math.exp(-7.5 * t)
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                overlay.alpha = (f / 0.28f).coerceIn(0f, 1f)
                val s = 1f - 0.22f * springFactor + 0.04f * pulse
                infoCard.scaleX = s
                infoCard.scaleY = s
                infoCard.translationY = (18f * density) * springFactor
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    overlay.alpha = 1f
                    infoCard.scaleX = 1f
                    infoCard.scaleY = 1f
                    infoCard.translationY = 0f
                }
            })
            start()
        }
    }

    fun dismissAboutInfoWindow() {
        val overlay = aboutOverlay ?: return
        aboutOverlay = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.calculatorContainer.setRenderEffect(null)
            binding.mathWorkspaceContainer.setRenderEffect(null)
        }

        val card = overlay.getChildAt(overlay.childCount - 1)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = AccelerateInterpolator(0.9f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                overlay.alpha = 1f - f
                if (card != null) {
                    val s = 1f - 0.18f * f
                    card.scaleX = s
                    card.scaleY = s
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (overlay.parent as? FrameLayout)?.removeView(overlay)
                    aboutBlurredBitmap?.recycle()
                    aboutBlurredBitmap = null
                }
            })
            start()
        }
    }

    fun onKeyClicked(label: String) {
        fun flushOperand() {
            if (currentPrimaryInput != "0.0") {
                exprStatement += currentPrimaryInput
                currentPrimaryInput = "0.0"
            }
        }

        fun withOperator(opDisplay: String, spaced: Boolean = false) {
            val tail = if (spaced) "$opDisplay " else opDisplay
            val trimmed = exprStatement.trimEnd()
            exprStatement = if (trimmed.isNotEmpty() && trimmed.last() in "+-×÷^") {
                "${trimmed.dropLast(1)}$tail"
            } else { "$trimmed$tail" }
        }

        when (label) {
            "AC" -> {
                exprStatement = ""; currentPrimaryInput = "0.0"; currentSecondaryFormula = "0.0"
                binding.tvPrimaryDisplay.text = "0.0"; binding.tvSecondaryDisplay.text = "0.0"
                displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
                displaySpringPulse(binding.tvSecondaryDisplay, 0.035f)
                return
            }
            "⌫" -> {
                when {
                    currentPrimaryInput.length > 1 && currentPrimaryInput != "0.0" ->
                        currentPrimaryInput = currentPrimaryInput.dropLast(1)
                    currentPrimaryInput != "0.0" -> currentPrimaryInput = "0.0"
                    else -> {
                        var s = exprStatement.trimEnd()
                        if (s.isNotEmpty()) s = s.dropLast(1).trimEnd()
                        val heads = listOf(
                            "acosec","cosec","asec","acot","sec","cot",
                            "sin","cos","tan","asin","acos","atan","sinh","cosh","tanh",
                            "round","cbrt","root","log2","log₂","log","ln","abs","ceil","floor","exp",
                            "gcd","lcm","npr","ncr","ⁿ√","√","1/(", "10^("
                        ).sortedByDescending { it.length }
                        for (h in heads) { if (s.endsWith(h)) { s = s.removeSuffix(h).trimEnd(); break } }
                        exprStatement = s
                    }
                }
            }
            "M+" -> {
                val value = currentPrimaryInput.toDoubleOrNull() ?: 0.0
                memoryValue += value
                val displayStr = formatValue(memoryValue)
                binding.tvMrValue.text = displayStr
                displaySpringPulse(binding.tvMrValue, 0.05f)
                return
            }
            "M-" -> {
                val value = currentPrimaryInput.toDoubleOrNull() ?: 0.0
                memoryValue -= value
                val displayStr = formatValue(memoryValue)
                binding.tvMrValue.text = displayStr
                displaySpringPulse(binding.tvMrValue, 0.05f)
                return
            }
            "=" -> { evaluateCurrent(); return }
            "Ans" -> {
                if (currentPrimaryInput == "0.0") currentPrimaryInput = "ans"
                else currentPrimaryInput += "ans"
            }
            "π", "e" -> {
                if (currentPrimaryInput == "0.0") currentPrimaryInput = label
                else currentPrimaryInput += label
            }
            "rand" -> {
                val r = formatValue(Math.random())
                if (currentPrimaryInput != "0.0") {
                    flushOperand()
                    if (exprStatement.isNotEmpty() && exprStatement.trimEnd().last() !in "+-×÷/*^,(") {
                        exprStatement += "×"
                    }
                }
                currentPrimaryInput = r
            }
            "ⁿ√" -> {
                val hasActiveOperand = currentPrimaryInput != "0.0" || exprStatement.trimEnd().endsWith(")")
                if (hasActiveOperand) {
                    flushOperand()
                    exprStatement += "√("
                } else {
                    exprStatement += "ⁿ√("
                }
            }
            "!" -> {
                if (currentPrimaryInput != "0.0") currentPrimaryInput += "!"
                else if (exprStatement.trimEnd().endsWith(")")) exprStatement += "!"
            }
            "x²" -> {
                if (currentPrimaryInput != "0.0") currentPrimaryInput += "^2"
                else if (exprStatement.trimEnd().endsWith(")")) exprStatement += "^2"
            }
            "x³" -> {
                if (currentPrimaryInput != "0.0") currentPrimaryInput += "^3"
                else if (exprStatement.trimEnd().endsWith(")")) exprStatement += "^3"
            }
            "xʸ" -> { flushOperand(); withOperator("^") }
            "10ˣ" -> { flushOperand(); exprStatement += "10^(" }
            "eˣ" -> { flushOperand(); exprStatement += "exp(" }
            "1/x" -> { flushOperand(); exprStatement += "1/(" }
            "%" -> {
                if (currentPrimaryInput != "0.0" && !currentPrimaryInput.endsWith("%")) {
                    currentPrimaryInput += "%"
                } else if (currentPrimaryInput == "0.0" && exprStatement.trimEnd().endsWith(")")) {
                    exprStatement += "%"
                }
            }
            "+" -> { flushOperand(); withOperator("+") }
            "-" -> { flushOperand(); withOperator("-") }
            "x" -> { flushOperand(); withOperator("×") }
            "÷" -> { flushOperand(); withOperator("/") }
            "^" -> { flushOperand(); withOperator("^") }
            "." -> {
                if (!currentPrimaryInput.contains(".")) {
                    currentPrimaryInput = if (currentPrimaryInput == "0.0") "0." else currentPrimaryInput + "."
                }
            }
            "EE" -> {
                if (!currentPrimaryInput.contains('E') && !currentPrimaryInput.contains("ans")) {
                    currentPrimaryInput = if (currentPrimaryInput == "0.0") "1EE" else currentPrimaryInput + "EE"
                }
            }
            "0","1","2","3","4","5","6","7","8","9" -> {
                currentPrimaryInput = if (currentPrimaryInput == "0.0" || currentPrimaryInput == "0") label else currentPrimaryInput + label
            }
            "(" -> { flushOperand(); exprStatement += "(" }
            ")" -> {
                flushOperand()
                val openParens = exprStatement.count { it == '(' } - exprStatement.count { it == ')' }
                if (openParens > 0) {
                    val lastOpen = exprStatement.lastIndexOf('(')
                    val prefix = if (lastOpen >= 0) exprStatement.substring(0, lastOpen) else ""
                    val inside = if (lastOpen >= 0) exprStatement.substring(lastOpen + 1) else ""
                    val twoArgHeads = listOf("npr", "ncr", "gcd", "lcm", "root", "ⁿ√")
                    if (twoArgHeads.any { prefix.endsWith(it) } && !inside.contains(',')) {
                        exprStatement += ","
                    } else {
                        exprStatement += ")"
                    }
                }
            }
            else -> {
                if (label.isNotEmpty() && (label[0].isLetterOrDigit() || label[0] == '√') && label != "d/dx") {
                    if (currentPrimaryInput != "0.0") {
                        flushOperand()
                        if (exprStatement.isNotEmpty() && exprStatement.trimEnd().last() !in "+-×÷/*^,(") {
                            exprStatement += "×"
                        }
                    }
                    val fn = when (label) {
                        "nPr" -> "npr"
                        "nCr" -> "ncr"
                        "log₂" -> "log2"
                        else -> label
                    }
                    exprStatement += "$fn("
                }
            }
        }
        binding.tvPrimaryDisplay.text =
            if (currentPrimaryInput == "0.0") exprStatement.trimEnd().ifBlank { "0.0" }
            else (exprStatement + currentPrimaryInput).ifBlank { "0.0" }
        displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
    }

    private fun evaluateCurrent() {
        val fullDisplay = if (currentPrimaryInput == "0.0") exprStatement
                          else exprStatement + currentPrimaryInput
        if (fullDisplay.isBlank()) return

        binding.tvSecondaryDisplay.text = "$fullDisplay ="

        MathEngineManager.evaluate(fullDisplay, isDegreeMode, lastAnswer) { outcome ->
            runOnUiThread {
                if (outcome.ok) {
                    lastAnswer = outcome.value
                    currentPrimaryInput = outcome.value
                    exprStatement = ""
                    val unitTag = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                    val resultLine = "$fullDisplay = ${outcome.value}$unitTag"
                    calculationHistory.add(0, resultLine)
                    refreshHistoryUI()
                    currentSecondaryFormula = resultLine
                    binding.tvPrimaryDisplay.text = outcome.value
                    binding.tvSecondaryDisplay.text = resultLine
                    displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
                    displaySpringPulse(binding.tvSecondaryDisplay, 0.035f)
                } else {
                    val msg = outcome.error ?: "Unknown error"
                    val errLine = if (msg.length > 40) "⚠ ${msg.take(37)}…" else "Error: $msg"
                    currentSecondaryFormula = errLine
                    binding.tvSecondaryDisplay.text = errLine
                    displaySpringPulse(binding.tvSecondaryDisplay, 0.04f)
                }
            }
        }
    }

    private fun displaySpringPulse(view: View, bump: Float) {
        displayPulseAnimators.removeAll { (v, a) -> if (v === view) { a.cancel(); true } else false }
        if (view.width > 0) {
            view.pivotX = view.width.toFloat(); view.pivotY = view.height / 2f
        }
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L; interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = decay * Math.sin(t * 22.0)
                view.scaleX = (1.0 + bump * pulse).toFloat()
                view.scaleY = (1.0 + bump * pulse).toFloat()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.scaleX = 1f; view.scaleY = 1f
                }
            })
            start()
        }
        displayPulseAnimators.add(view to anim)
    }

    private fun formatValue(v: Double): String {
        if (v % 1.0 == 0.0) return "${v.toLong()}.0"
        var s = String.format("%.4f", v)
        val dotIdx = s.indexOf('.')
        if (dotIdx >= 0) {
            s = s.take(dotIdx + 1) + s.substring(dotIdx + 1).trimEnd('0')
            if (s.endsWith('.')) s = s.dropLast(1)
        }
        return s
    }

    fun refreshHistoryUI(fadeInPlaceholder: Boolean = false) {
        (fragmentAt(0) as? HistoryFragment)?.refresh(calculationHistory, isDarkMode, fadeInPlaceholder)
    }

    private fun applyThemeColors(dark: Boolean) {
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

    private fun updateTabIndicators(
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

    private fun applyThemeIconScales(dark: Boolean, animate: Boolean) {
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

    private fun animateMrClusterPhysics(show: Boolean) {
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

    private fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long, inward: Boolean = false) {
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

    private fun spawnRippleRing(cx: Int, cy: Int, maxRadius: Float, delayMs: Long,
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

    private fun toggleThemeWithCircularReveal() {
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

    private fun persistTheme() { prefs.edit().putBoolean("dark_mode", isDarkMode).apply() }

    private fun playLaunchSplashReveal() {
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

    private fun playExitSplashCollapse() {
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

    private var keyPopupOverlay: FrameLayout? = null

    fun showButtonPopup(anchor: View, text: String) {
        keyPopupOverlay?.let { (it.parent as? FrameLayout)?.removeView(it) }; keyPopupOverlay = null
        val root = binding.rootFrame; val density = resources.displayMetrics.density
        val popupBg = if (isDarkMode) "#F8F8F8" else "#2E2E2E"
        val popupText = if (isDarkMode) "#2D2D2D" else "#F2F2F2"
        val bubble = TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(Color.parseColor(popupText))
            background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 12f*density; setColor(Color.parseColor(popupBg)) }
            elevation = 5f*density
            setPadding((14*density).toInt(), (9*density).toInt(), (14*density).toInt(), (9*density).toInt())
            isClickable = true; isFocusable = true; setOnClickListener { }
            visibility = View.INVISIBLE
        }
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT); setOnClickListener { dismissButtonPopup() } }
        overlay.addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        keyPopupOverlay = overlay
        overlay.post {
            if (keyPopupOverlay !== overlay) return@post
            val rootLoc = IntArray(2); root.getLocationInWindow(rootLoc)
            val anchorLoc = IntArray(2); anchor.getLocationInWindow(anchorLoc)
            val bw = bubble.measuredWidth; val bh = bubble.measuredHeight
            val layerPad = (12*density).toInt()
            val anchorCx = anchorLoc[0] + anchor.width/2 - rootLoc[0]
            val anchorTop = anchorLoc[1] - rootLoc[1]
            val anchorBottom = anchorTop + anchor.height
            val showAbove = anchorTop - bh - layerPad >= layerPad
            val y = if (showAbove) anchorTop - bh - layerPad else anchorBottom + layerPad
            val x = (anchorCx - bw/2).coerceIn(layerPad, root.width - bw - layerPad)
            (bubble.layoutParams as FrameLayout.LayoutParams).apply { leftMargin = x; topMargin = y }
            bubble.requestLayout()
            bubble.visibility = View.VISIBLE
            bubble.pivotX = bw/2f; bubble.pivotY = if (showAbove) bh.toFloat() else 0f
            bubble.translationY = if (showAbove) 12f*density else -12f*density
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 430L; interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedValue as Float
                    val decay = Math.exp(-4.0*f); val s = (1.0 - 0.45*decay*Math.cos(f*3.0*Math.PI)).toFloat()
                    bubble.scaleX = s; bubble.scaleY = s
                    bubble.alpha = (f/0.22f).coerceIn(0f, 1f)
                    bubble.translationY = (if (showAbove) 12f*density else -12f*density)*(1f-f)
                }; start()
            }
        }
    }

    fun dismissButtonPopup() {
        keyPopupOverlay?.let { overlay ->
            keyPopupOverlay = null; val bubble = overlay.getChildAt(0)
            if (bubble != null) bubble.animate().scaleX(0.62f).scaleY(0.62f).alpha(0f)
                .setDuration(170L).setInterpolator(AccelerateInterpolator(0.8f))
                .withEndAction { (overlay.parent as? FrameLayout)?.removeView(overlay) }.start()
            else (overlay.parent as? FrameLayout)?.removeView(overlay)
        }
    }

    private fun bindHoldPopup(view: View, text: String) {
        view.setOnLongClickListener { showButtonPopup(view, text); true }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) { MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (v.isPressed) dismissButtonPopup() }
            false
        }
    }

    private fun tabDescriptionFor(name: String): String = when (name) {
        "History" -> "History — your last 15 calculations, newest first. Tap an entry to place its result onto the " +
            "current keypad input; hold 'Clear History' to wipe the whole list with a slide-out animation."
        "Basic keypad" -> "Basic keypad — the everyday grid: digits, decimal point, AC / ⌫, M+ / M− and the four " +
            "core operations (×  ÷  +  −). Hold any key for a usage hint."
        "Extended keypad" -> "Extended keypad — parentheses, %, powers, √, ⁿ√ (nth root), factorial (!), and " +
            "scientific-notation EE. Hold a key for examples of how it enters the expression."
        "Trig" -> "Trig keypad — sin / cos / tan with their inverses and reciprocals, the hyperbolic family, and the " +
            "constants π and e. Angles follow the deg/rad mode shown under the display."
        "Fx" -> "Fx keypad — general functions: abs, log / log₂, powers (x², x³, xʸ, 10ˣ, eˣ), roots (cbrt), " +
            "combinatorics (nPr, nCr), gcd / lcm, round / ceil / floor, rand, the derivative d/dx, and Ans recall."
        else -> "$name — open this tab"
    }

    fun keyDescriptionFor(label: String): String = when (label) {
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
        "rand" -> "rand() — a fresh uniform random number in [0, 1) each time it is evaluated in a session."
        "d/dx" -> "d/dx — symbolic derivative: d/dx(x^2+1) → 2·x. In sessions the derivative rule set of the Math-Engine is applied."
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
}
