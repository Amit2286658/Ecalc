package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewAnimationUtils
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatActivity
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
    private val workspaceSlideAnimators = mutableListOf<ValueAnimator>()
    private var workspaceFragment: MathWorkspaceFragment? = null
    private var swipeStartX: Float = 0f
    private var swipeStartY: Float = 0f
    private var isWorkspaceSwiping: Boolean = false
    private var swipeBlockedByChild: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore the persisted theme and angle mode before building any themed UI
        isDarkMode = prefs.getBoolean("dark_mode", isDarkMode)
        isDegreeMode = prefs.getBoolean("degree_mode", isDegreeMode)
        binding.tvAngleMode.text = if (isDegreeMode) "deg(Degree)" else "rad(Radian)" 

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            try {
                splashScreen.setOnExitAnimationListener { splashView ->
                    splashView.setBackgroundColor(
                        Color.parseColor(if (isDarkMode) "#2D2D2D" else "#F8F8F8")
                    )
                    splashView.animate()
                        .alpha(0f)
                        .setDuration(160L)
                        .setInterpolator(AccelerateInterpolator())
                        .withEndAction { splashView.remove() }
                        .start()
                }
            } catch (_: Throwable) { /* fall back */ }
        }

        setupWorkspaceFragment()
        setupViewPager()
        setupInteractions()
        applyThemeColors(isDarkMode)
        applyThemeIconScales(isDarkMode, animate = false)

        Thread {
            MathEngineManager.initialize(applicationContext)
            MathEngineManager.ensureEngine()
            // Download any new incremental .math extension patches in the background
            // (saved to filesDir/math_patches/ and loaded by MathPatchLoader when needed)
            MathPatchDownloader.syncMissingPatches(applicationContext)
        }.start()

        binding.root.post { playLaunchSplashReveal() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentWorkspacePage == 1) {
                    switchWorkspacePage(0)
                } else {
                    playExitSplashCollapse()
                }
            }
        })
    }

    fun showButtonToast(label: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, "Button: $label", Toast.LENGTH_SHORT).also { it.show() }
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
                showButtonToast(".math Studio & Sessions")
            } else {
                wobbleRound(cx, cy, maxR, 380L)
                showButtonToast("Calculator")
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
        val density = resources.displayMetrics.density
        val touchSlop = 18f * density
        val width = binding.rootFrame.width.toFloat().coerceAtLeast(1f)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = ev.rawX
                swipeStartY = ev.rawY
                isWorkspaceSwiping = false
                // On page 0 (Calculator), swiping left anywhere on the display section (or from the right edge)
                // reveals the .math Studio. On page 1 (.math Studio), swiping right from the left edge or header
                // reveals the Calculator again.
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

    /** Five tabs (history + 4 keypads) live inside a ViewPager2 with damped-spring physics. */
    private fun setupViewPager() {
        binding.viewPager.adapter = EcalcPagerAdapter(this)
        binding.viewPager.setCurrentItem(selectedTabIndex, false)
        binding.viewPager.offscreenPageLimit = 4
        binding.viewPager.setPageTransformer { page, position ->
            val absPos = Math.abs(position).coerceIn(0f, 1f)
            val t = (1f - absPos) * 0.48
            val pulse = (Math.exp(-7.5 * t) * Math.sin(t * 22.0)).toFloat() * absPos
            val scale = (0.92f + 0.08f * (1f - absPos)) + 0.03f * pulse
            page.scaleX = scale
            page.scaleY = scale
            page.rotation = -position * 3.2f * (1f - absPos * 0.5f)
            page.alpha = 0.65f + 0.35f * (1f - absPos)
        }
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val prevIndex = selectedTabIndex
                val direction = if (position >= prevIndex) 1 else -1
                selectedTabIndex = position
                updateTabIndicators(animate = true)
                if (position == 0) refreshHistoryUI()

                val tabs = listOf(
                    binding.tabHistory, binding.tabBasic, binding.tabExtended,
                    binding.tabTrig, binding.tabFx
                )
                val tab = tabs[position]
                displaySpringPulse(tab, 0.12f)

                // Apply damped-spring horizontal scroll to the tab bar
                binding.tabScroll.post {
                    val target = (tab.left - (binding.tabScroll.width - tab.width) / 2)
                        .coerceAtLeast(0)
                    val startScroll = binding.tabScroll.scrollX
                    val delta = target - startScroll
                    if (delta != 0) {
                        tabPhysicsAnimators.forEach { it.cancel() }
                        tabPhysicsAnimators.clear()
                        ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 480L
                            interpolator = LinearInterpolator()
                            addUpdateListener { anim ->
                                val f = anim.animatedValue as Float
                                val t = f * 0.48
                                val decay = Math.exp(-7.5 * t)
                                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                                val curScroll = (target - delta * springFactor).toInt()
                                binding.tabScroll.scrollTo(curScroll, 0)
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    binding.tabScroll.scrollTo(target, 0)
                                }
                            })
                            start()
                            tabPhysicsAnimators.add(this)
                        }
                    }
                }

                // Trigger springSettle on the newly selected page's keys/content
                binding.viewPager.post {
                    when (val frag = fragmentAt(position)) {
                        is KeyGridFragment -> frag.springSettle(direction)
                        is HistoryFragment -> frag.springSettle(direction)
                    }
                }
            }
        })
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
        showButtonToast("Clear History")
        calculationHistory.clear()
        refreshHistoryUI()
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
                if (held) showButtonPopup(this, "Clear — zero the memory counter")
                else dismissButtonPopup()
            }
        }

        bindHoldPopup(binding.themeTogglePill, "Theme — toggle between light and dark")
        bindHoldPopup(binding.tvAngleMode, "Angle mode — switch between degrees and radians")

        binding.themeTogglePill.setOnClickListener {
            toggleThemeWithCircularReveal()
            showButtonToast(if (isDarkMode) "Dark Theme" else "Light Theme")
        }

        binding.cbMrToggle.setOnCheckedChangeListener { _, isChecked ->
            animateMrClusterPhysics(isChecked)
            showButtonToast(if (isChecked) "MR Enabled" else "MR Disabled")
        }

        binding.tvAngleMode.setOnClickListener {
            isDegreeMode = !isDegreeMode
            prefs.edit().putBoolean("degree_mode", isDegreeMode).apply()
            binding.tvAngleMode.text = if (isDegreeMode) "deg(Degree)" else "rad(Radian)"
            displaySpringPulse(binding.tvAngleMode, 0.05f)
            showButtonToast("Mode: ${binding.tvAngleMode.text}")
        }

        // Tap the memory value to recall it into the current operand
        binding.tvMrValue.setOnClickListener {
            val recalled = formatValue(memoryValue)
            currentPrimaryInput = recalled
            binding.tvPrimaryDisplay.text =
                if (currentPrimaryInput == "0.0") exprStatement.trimEnd().ifBlank { "0.0" }
                else (exprStatement + currentPrimaryInput).ifBlank { "0.0" }
            displaySpringPulse(binding.tvPrimaryDisplay, 0.05f)
            displaySpringPulse(binding.tvMrValue, 0.05f)
            showButtonToast("MR Recall: $recalled")
        }


        val tabViews = listOf(
            binding.tabHistory, binding.tabBasic, binding.tabExtended,
            binding.tabTrig, binding.tabFx
        )
        val tabNames = listOf("History", "Basic keypad", "Extended keypad", "Trig", "Fx")

        tabViews.forEachIndexed { idx, tab ->
            tab.setOnClickListener {
                binding.viewPager.setCurrentItem(idx, true)
                showButtonToast("Tab: ${tabNames[idx]}")
            }
            bindHoldPopup(tab, "${tabNames[idx]} — open this tab")
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
                // Add the displayed value to memory
                val value = currentPrimaryInput.toDoubleOrNull() ?: 0.0
                memoryValue += value
                val displayStr = formatValue(memoryValue)
                binding.tvMrValue.text = displayStr
                displaySpringPulse(binding.tvMrValue, 0.05f)
                return
            }
            "M-" -> {
                // Subtract the displayed value from memory
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

    /** Stateless single-shot evaluation via eng_eval (no session needed).
     *  Each call compiles and runs independently — perfect for a calculator. */
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

    /** Damped-spring kick identical to the water-ripple wavefront:
     *  decay e⁻⁷·⁵ᵗ · sin(22 t), 480 ms settle, right-edge pivot. */
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
        // Strip trailing zeros after the decimal point
        val dotIdx = s.indexOf('.')
        if (dotIdx >= 0) {
            s = s.take(dotIdx + 1) + s.substring(dotIdx + 1).trimEnd('0')
            if (s.endsWith('.')) s = s.dropLast(1)
        }
        return s
    }

    fun refreshHistoryUI() {
        (fragmentAt(0) as? HistoryFragment)?.refresh(calculationHistory, isDarkMode)
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

        // Four keys + history icon — no Units tab anymore
        val tabIcons = listOf(
            binding.ivTabHistory, binding.ivTabBasic,
            binding.ivTabExtended, binding.ivTabTrig, binding.ivTabFx
        )
        tabIcons.forEach { it.setColorFilter(Color.parseColor(primaryText)) }

        updateTabIndicators()

        // Propagate theme to every live tab (only 5 now) and the .math studio workspace
        for (pos in 0 until 5) {
            when (val frag = fragmentAt(pos)) {
                is KeyGridFragment -> frag.applyTheme(dark)
                is HistoryFragment -> frag.onThemeChanged(dark)
                else -> {}
            }
        }
        workspaceFragment?.applyTheme(dark)
    }

    private fun updateTabIndicators(animate: Boolean = false) {
        val accentColor = if (isDarkMode) "#13959B" else "#E58A7B"
        val indicators = listOf(
            binding.indTabHistory, binding.indTabBasic,
            binding.indTabExtended, binding.indTabTrig, binding.indTabFx
        )
        indicators.forEachIndexed { idx, view ->
            view.setBackgroundColor(Color.parseColor(accentColor))
            if (idx == selectedTabIndex) {
                view.visibility = View.VISIBLE
                if (animate) {
                    view.pivotX = view.width / 2f
                    view.pivotY = view.height / 2f
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 480L
                        interpolator = LinearInterpolator()
                        addUpdateListener { anim ->
                            val f = anim.animatedValue as Float
                            val t = f * 0.48
                            val decay = Math.exp(-7.5 * t)
                            val spring = (1.0 - 0.65 * decay * Math.cos(t * 22.0)).toFloat()
                            view.scaleX = spring
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                view.scaleX = 1f
                            }
                        })
                        start()
                    }
                }
            } else {
                view.visibility = View.INVISIBLE
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

    /** Slide-in/out the MR memory cluster with the same damped-spring pulse
     *  as everything else in the app (e⁻⁷·⁵ᵗ · sin(22 t)).  No generic
     *  Interpolator — just raw physics so it feels alive. */
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
                    // Underdamped spring displacement: slides in from right, overshoots, and settles
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
                    // Same damped-spring physics when sliding out: spring recoil then slide to distance
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
            // Only the four keypad tabs — no Units
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
        wobbleRound(cx.toFloat(), cy.toFloat(), maxRadius, 380L)
    }

    private fun persistTheme() { prefs.edit().putBoolean("dark_mode", isDarkMode).apply() }

    private fun playLaunchSplashReveal() {
        val outerColor = if (isDarkMode) "#2D2D2D" else "#F8F8F8"
        val innerColor = if (isDarkMode) "#F8F8F8" else "#2D2D2D"
        binding.splashOuterDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(outerColor)) }
        binding.splashInnerDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(innerColor)) }
        binding.splashCircle.visibility = View.VISIBLE
        binding.calculatorContainer.visibility = View.INVISIBLE

        val cx = binding.rootFrame.width / 2; val cy = binding.rootFrame.height / 2
        val splashRadius = hypot(cx.toDouble(), cy.toDouble()).toFloat() * 0.45f
        listOf(0L, 300L, 600L).forEach { spawnRippleRing(cx, cy, splashRadius, it, 0.22f, 620L) }

        val pulses = listOf(0.2125, 0.6375)
        ValueAnimator.ofFloat(0f, 1f, 0f, 1f, 0f).apply {
            duration = 850L; interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float; val t = anim.animatedFraction * 0.85
                var kick = 0.0
                pulses.forEach { tk -> if (t >= tk) { val dt = t - tk; kick += Math.exp(-7.5*dt)*Math.sin(dt*22.0) } }
                binding.splashInnerDot.alpha = v
                val s = ((0.28f + 0.72f * v) * (1.0 + 0.12 * kick)).toFloat()
                binding.splashInnerDot.scaleX = s; binding.splashInnerDot.scaleY = s
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.splashCircle.visibility = View.GONE
                    binding.calculatorContainer.visibility = View.VISIBLE
                    val rcx = binding.calculatorContainer.width/2; val rcy = binding.calculatorContainer.height/2
                    val maxR = hypot(rcx.toDouble(), rcy.toDouble()).toFloat()
                    ViewAnimationUtils.createCircularReveal(binding.calculatorContainer, rcx, rcy, 38f*resources.displayMetrics.density, maxR).apply {
                        duration = 360L; interpolator = AccelerateDecelerateInterpolator(); start()
                    }
                    spawnRippleRing(rcx, rcy, maxR, 60L, 0.35f, 760L)
                    spawnRippleRing(rcx, rcy, maxR, 240L, 0.35f, 760L)
                    wobbleRound(rcx.toFloat(), rcy.toFloat(), maxR, 360L)
                }
            }); start()
        }
    }

    private fun playExitSplashCollapse() {
        val cx = binding.calculatorContainer.width/2; val cy = binding.calculatorContainer.height/2
        val maxRadius = hypot(cx.toDouble(), cy.toDouble()).toFloat()
        val outerColor = if (isDarkMode) "#2D2D2D" else "#F8F8F8"
        val innerColor = if (isDarkMode) "#F8F8F8" else "#2D2D2D"
        binding.splashOuterDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(outerColor)) }
        binding.splashInnerDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(innerColor)) }
        spawnRippleRing(cx, cy, maxRadius, 0L, 0.22f, 620L, inward=true)
        spawnRippleRing(cx, cy, maxRadius, 140L, 0.18f, 620L, inward=true)
        ViewAnimationUtils.createCircularReveal(binding.calculatorContainer, cx, cy, maxRadius, 0f).apply {
            duration = 320L; interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.calculatorContainer.visibility = View.INVISIBLE
                    binding.splashCircle.visibility = View.VISIBLE
                    binding.root.postDelayed(::finish, 250L)
                }
            }); start()
        }
        wobbleRound(cx.toFloat(), cy.toFloat(), maxRadius, 320L, inward=true)
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

    fun keyDescriptionFor(label: String): String = when (label) {
        "AC" -> "AC — clear everything and reset the display"
        "⌫" -> "⌫ — delete the last character"
        "M+" -> "M+ — add the displayed value to memory"
        "M-" -> "M− — subtract the displayed value from memory"
        "=" -> "= — evaluate the expression"
        "Ans" -> "Ans — reuse the last answer"
        "x" -> "× — multiplication"
        "÷" -> "÷ — division"
        "+" -> "+ — addition"
        "-" -> "− — subtraction"
        "%" -> "% — percentage: 200 + 10% = 220 · 200 × 10% = 20 · 10% = 0.1"
        "^" -> "^ — power"
        "EE" -> "EE — scientific-notation exponent"
        "π" -> "π — pi (3.14159…)"
        "e" -> "e — Euler's number (2.71828…)"
        "√" -> "√ — square root"
        "ⁿ√" -> "ⁿ√ — nth root: enter n then ⁿ√(x), or ⁿ√(x, n)"
        "!" -> "! — factorial"
        "1/x" -> "1/x — reciprocal"
        "x²" -> "x² — square"
        "x³" -> "x³ — cube"
        "xʸ" -> "xʸ — exponentiate"
        "10ˣ" -> "10ˣ — power of ten"
        "eˣ" -> "eˣ — exponential (natural)"
        "ln" -> "ln — natural logarithm"
        "log" -> "log — logarithm, base 10"
        "log₂" -> "log₂ — logarithm, base 2"
        "round" -> "round — round to nearest integer"
        "gcd" -> "gcd — greatest common divisor"
        "lcm" -> "lcm — least common multiple"
        "cbrt" -> "cbrt — cube root"
        "nPr" -> "nPr — permutations"
        "nCr" -> "nCr — combinations"
        "ceil" -> "ceil — round up to integer"
        "floor" -> "floor — round down to integer"
        "rand" -> "rand — random number in [0, 1)"
        "d/dx" -> "d/dx — derivative"
        "(" -> "( — opening parenthesis"
        ")" -> ") — closing parenthesis (or comma in 2-arg functions)"
        "sin" -> "sin — sine"
        "cos" -> "cos — cosine"
        "tan" -> "tan — tangent"
        "sec" -> "sec — secant"
        "cosec" -> "cosec — cosecant"
        "cot" -> "cot — cotangent"
        "asin" -> "sin⁻¹ — inverse sine (arcsine)"
        "acos" -> "cos⁻¹ — inverse cosine (arccosine)"
        "atan" -> "tan⁻¹ — inverse tangent (arctangent)"
        "asec" -> "sec⁻¹ — inverse secant (arcsecant)"
        "acosec" -> "csc⁻¹ — inverse cosecant (arccosecant)"
        "acot" -> "cot⁻¹ — inverse cotangent (arccotangent)"
        "sinh" -> "sinh — hyperbolic sine"
        "cosh" -> "cosh — hyperbolic cosine"
        "tanh" -> "tanh — hyperbolic tangent"
        else -> "$label"
    }
}
