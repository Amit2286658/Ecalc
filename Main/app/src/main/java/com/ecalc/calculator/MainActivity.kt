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

    internal lateinit var binding: ActivityMainBinding
    internal val prefs by lazy { getSharedPreferences("ecalc_prefs", MODE_PRIVATE) }
    var isDarkMode: Boolean = true
    internal var selectedTabIndex: Int = 1 // Default to Basic keypad
    var isDegreeMode: Boolean = true
    internal var currentWorkspacePage: Int = 0 // 0 = Calculator, 1 = .math Studio
    internal var currentToast: Toast? = null
    internal var patchToast: Toast? = null
    internal var launchRevealStarted: Boolean = false
    internal var isExitingApp: Boolean = false

    internal var currentPrimaryInput: String = "0.0"
    internal var currentSecondaryFormula: String = "0.0"
    internal var lastAnswer: String = "0.0"
    /** Accumulated expression statement (display dialect, engine-translated
     *  at evaluate time): operators and function heads chain here while the
     *  active operand lives in currentPrimaryInput. */
    internal var exprStatement: String = ""
    /** Simple one-slot memory: accumulates via M+/M−, cleared by MR button. */
    internal var memoryValue: Double = 0.0
    val calculationHistory = mutableListOf<String>()

    internal val wobbleAnimators = mutableListOf<ValueAnimator>()
    internal val displayPulseAnimators = mutableListOf<Pair<View, ValueAnimator>>()
    /** Animate the MR cluster sliding — same damped-spring pulse used everywhere
     *  else (e^-7.5 t · sin(22 t)) instead of DecelerateInterpolator / LinearInterpolator. */
    internal val mrSlideAnimators = mutableListOf<ValueAnimator>()
    internal val tabPhysicsAnimators = mutableListOf<ValueAnimator>()
    internal val pagerSpringAnimators = mutableListOf<ValueAnimator>()
    internal val workspaceSlideAnimators = mutableListOf<ValueAnimator>()
    internal var workspaceFragment: MathWorkspaceFragment? = null

    /** Distance-dependent tab physics tracking (1 = adjacent tab, 4 = farthest tab). */
    internal var lastTabDistance: Int = 1
    internal var lastTabDirection: Int = 1
    internal var pendingTabPhysicsPage: Int = -1

    /** Blurred-background About info window overlay. */
    internal var aboutOverlay: FrameLayout? = null
    internal var aboutBlurredBitmap: Bitmap? = null

    internal var swipeStartX: Float = 0f
    internal var swipeStartY: Float = 0f
    internal var isWorkspaceSwiping: Boolean = false
    internal var swipeBlockedByChild: Boolean = false

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

        // Coordinate engine boot, remote .math patch sync, and worker-thread patch
        // compilation/linking so status feedback is only toasted AFTER all stages
        // finish (preventing the race where statusLines was checked before the
        // async worker executed loadPendingPatches, or where ensureEngine()
        // consumed unloaded patches with a null status callback).
        val statusLines = java.util.Collections.synchronizedList(ArrayList<String>())
        val stageToasts: (String) -> Unit = { msg -> statusLines.add(msg) }
        MathEngineManager.syncAndLoadPatches(
            context = applicationContext,
            onStatus = stageToasts,
            onDone = { _, _ ->
                val summary = synchronized(statusLines) {
                    statusLines.distinct().joinToString("\n")
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (summary.isNotBlank()) {
                            showPatchStatusToast(summary)
                        }
                        refreshFunctionCatalogUI()
                    }
                }
            }
        )

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

    internal fun styleSplashDots(dark: Boolean) {
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

    internal fun triggerLaunchSplashIfReady() {
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

    /** Dedicated long-duration toast for startup/patch pipeline status so keypad or
     *  studio button toasts do not immediately cancel patch diagnostic messages. */
    fun showPatchStatusToast(summary: String) {
        patchToast?.cancel()
        patchToast = Toast.makeText(this, summary, Toast.LENGTH_LONG).also { it.show() }
    }

    internal fun setupWorkspaceFragment() {
        val existing = supportFragmentManager.findFragmentByTag("math_workspace") as? MathWorkspaceFragment
        if (existing != null) {
            workspaceFragment = existing
        }
        binding.mathWorkspaceContainer.visibility = View.INVISIBLE
    }

    internal fun ensureWorkspaceFragment(): MathWorkspaceFragment? {
        workspaceFragment?.let { return it }
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return null
        val existing = supportFragmentManager.findFragmentByTag("math_workspace") as? MathWorkspaceFragment
        if (existing != null) {
            workspaceFragment = existing
            return existing
        }
        val frag = MathWorkspaceFragment()
        workspaceFragment = frag
        supportFragmentManager.beginTransaction()
            .replace(binding.mathWorkspaceContainer.id, frag, "math_workspace")
            .commitNowAllowingStateLoss()
        return frag
    }

    /** Switch between Calculator (page 0) and Free-form .math Studio (page 1)
     *  using the signature damped-spring physics (e^-7.5t * sin(22t)). */
    fun switchWorkspacePage(targetPage: Int, initialVelocityPx: Float = 0f) {
        val clamped = targetPage.coerceIn(0, 1)
        workspaceSlideAnimators.forEach { it.cancel() }
        workspaceSlideAnimators.clear()

        if (clamped == 1) {
            ensureWorkspaceFragment()?.applyTheme(isDarkMode)
        } else {
            workspaceFragment?.saveCurrentModuleEditorState(showToast = false)
            refreshFunctionCatalogUI()
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

    internal fun isTouchInsideInteractiveWorkspaceView(ev: MotionEvent): Boolean {
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
        if (currentWorkspacePage == 1 && (workspaceFragment?.isFileManagerDrawerOpen == true || workspaceFragment?.isModalDialogOpen == true)) {
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
                // Page-swipe is calculator→workspace only. Swiping back from
                // the freeform workspace was removed: it steals horizontal
                // moves from scrollable content (e.g. multiple open editor
                // tabs). Return to the calculator via the back gesture instead.
                swipeBlockedByChild = if (currentWorkspacePage == 0) {
                    !fromRightEdge && isTouchInsideInteractiveWorkspaceView(ev)
                } else {
                    true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeBlockedByChild) {
                    val dx = ev.rawX - swipeStartX
                    val dy = ev.rawY - swipeStartY
                    if (!isWorkspaceSwiping && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.35f) {
                        val validLeftSwipe = currentWorkspacePage == 0 && dx < 0
                        if (validLeftSwipe) {
                            isWorkspaceSwiping = true
                            workspaceSlideAnimators.forEach { it.cancel() }
                            workspaceSlideAnimators.clear()
                            ensureWorkspaceFragment()?.applyTheme(isDarkMode)
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
                    // Only the calculator→workspace direction can still be
                    // mid-swipe here (workspace→calculator swipes were removed).
                    val target = if (currentWorkspacePage == 0 && dx < -threshold) {
                        1
                    } else {
                        currentWorkspacePage
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
    internal fun setupViewPager() {
        binding.viewPager.adapter = EcalcPagerAdapter(this)
        binding.viewPager.setCurrentItem(selectedTabIndex, false)
        // Keep offscreenPageLimit = 1 during cold start so only the visible keypad inflates before
        // the first frame; playLaunchSplashReveal() expands it to 5 once the splash reveal ends.
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.setPageTransformer { page, position ->
            if (position <= -1f || position >= 1f || position == 0f) {
                page.scaleX = 1f
                page.scaleY = 1f
                page.rotation = 0f
                page.alpha = 1f
            } else {
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

                val tabs = listOf(
                    binding.tabHistory, binding.tabBasic, binding.tabExtended,
                    binding.tabTrig, binding.tabFx, binding.tabCatalog
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

    internal fun triggerTabPagePhysics(position: Int, direction: Int, distance: Int) {
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
                is FunctionCatalogFragment -> frag.springSettle(direction, clampedDist)
                else -> if (attempt < 4) {
                    binding.viewPager.postDelayed({ invokeFragmentSpring(attempt + 1) }, 24L)
                }
            }
        }
        binding.viewPager.post { invokeFragmentSpring(0) }
    }

    internal fun tabSpringPulse(view: View, direction: Int, distance: Int) {
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
        override fun getItemCount(): Int = 6
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> HistoryFragment()
            5 -> FunctionCatalogFragment()
            else -> KeyGridFragment.newInstance(position)
        }
    }

    fun refreshFunctionCatalogUI() {
        (fragmentAt(5) as? FunctionCatalogFragment)?.reloadCatalogFromEngine()
    }

    fun onCatalogItemClicked(item: CalculatorCatalogItem) {
        when (item.kind) {
            "op" -> {
                if (currentPrimaryInput != "0.0") {
                    exprStatement += currentPrimaryInput
                    currentPrimaryInput = "0.0"
                }
                val opIns = if (item.name.firstOrNull()?.isLetterOrDigit() == true) {
                    " ${item.name} "
                } else {
                    item.name
                }
                exprStatement = exprStatement.trimEnd() + opIns
            }
            "const" -> {
                if (currentPrimaryInput != "0.0") {
                    exprStatement += currentPrimaryInput
                    currentPrimaryInput = "0.0"
                    if (exprStatement.isNotEmpty() && exprStatement.trimEnd().last() !in "+-×÷/*^,(") {
                        exprStatement += "×"
                    }
                }
                exprStatement += item.name
            }
            else -> {
                if (currentPrimaryInput != "0.0") {
                    exprStatement += currentPrimaryInput
                    currentPrimaryInput = "0.0"
                    if (exprStatement.isNotEmpty() && exprStatement.trimEnd().last() !in "+-×÷/*^,(") {
                        exprStatement += "×"
                    }
                }
                val isZeroArg = item.signature.contains("${item.name}()")
                exprStatement += if (isZeroArg) "${item.name}()" else "${item.name}("
            }
        }
        binding.tvPrimaryDisplay.text =
            if (currentPrimaryInput == "0.0") exprStatement.trimEnd().ifBlank { "0.0" }
            else (exprStatement + currentPrimaryInput).ifBlank { "0.0" }
        displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
    }

    internal fun fragmentAt(position: Int): Fragment? =
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

    internal fun setupInteractions() {
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
            binding.tabTrig, binding.tabFx, binding.tabCatalog
        )
        val tabNames = listOf("History", "Basic keypad", "Extended keypad", "Trig", "Fx", "Catalog")

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


    internal var keyPopupOverlay: FrameLayout? = null
}
