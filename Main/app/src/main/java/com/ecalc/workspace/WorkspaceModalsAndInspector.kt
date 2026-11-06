package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.File
import kotlin.math.hypot

/**
 * Unified Top-to-Bottom Executable `.math` Modules & Multi-File Project Workspace
 * with Left-Drawer Project & File Manager.

internal fun MathWorkspaceFragment.updateModuleSubTabVisibility(animate: Boolean) {
    if (!isModuleEditorSubTab) {
        etModuleEditor?.dismissSuggestionsPopup()
    }
    btnModuleSubEditor?.isAccentKey = isModuleEditorSubTab
    btnModuleSubLog?.isAccentKey = !isModuleEditorSubTab
    if (isModuleEditorSubTab) {
        moduleLogPane?.visibility = View.GONE
        moduleEditorPane?.visibility = View.VISIBLE
        if (animate) {
            btnModuleSubEditor?.let { springPulse(it, 0.06f) }
            moduleEditorPane?.let { springSlideIn(it, fromDirection = -1) }
        }
    } else {
        moduleEditorPane?.visibility = View.GONE
        moduleLogPane?.visibility = View.VISIBLE
        if (animate) {
            btnModuleSubLog?.let { springPulse(it, 0.06f) }
            moduleLogPane?.let { springSlideIn(it, fromDirection = 1) }
        }
    }
}

fun MathWorkspaceFragment.applyTheme(dark: Boolean) {
    val root = rootLayout ?: return
    val density = resources.displayMetrics.density
    val bg = Color.parseColor(if (dark) "#2D2D2D" else "#F8F8F8")
    val drawerBg = Color.parseColor(if (dark) "#282828" else "#F4F4F4")
    val cardBg = Color.parseColor(if (dark) "#252525" else "#ECECEC")
    val editorBg = Color.parseColor(if (dark) "#1E1E1E" else "#FFFFFF")
    val strokeColor = Color.parseColor(if (dark) "#3E3E3E" else "#D6D6D6")
    val primaryText = Color.parseColor(if (dark) "#E0E0E0" else "#333333")
    val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")
    val accentText = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

    root.setBackgroundColor(bg)
    fileManagerDrawerPanel?.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(drawerBg)
        setStroke((1.2f * density).toInt(), strokeColor)
    }

    tvWorkspaceTitle?.setTextColor(primaryText)
    tvDrawerTitle?.setTextColor(primaryText)
    tvDrawerSubtitle?.setTextColor(secondaryText)
    tvDrawerFilesSectionLabel?.setTextColor(secondaryText)
    tvActiveModuleName?.setTextColor(accentText)

    fun styleCard(v: View?, fill: Int) {
        v?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(fill)
            setStroke((1f * density).toInt(), strokeColor)
        }
    }

    styleCard(etModuleEditor, editorBg)
    styleCard(moduleQuickStatusCard, cardBg)
    styleCard(varsCard, cardBg)
    styleCard(inspectorCard, cardBg)
    styleCard(consoleCard, cardBg)
    styleCard(moduleLogCard, cardBg)

    etModuleEditor?.isDarkThemeMode = dark
    etModuleEditor?.setTextColor(primaryText)
    etModuleEditor?.setHintTextColor(secondaryText)
    tvModuleQuickStatus?.setTextColor(secondaryText)
    tvVarsHeader?.setTextColor(accentText)
    tvVarsContent?.setTextColor(primaryText)
    tvInspectorHeader?.setTextColor(accentText)
    tvConsoleHeader?.setTextColor(accentText)
    tvConsoleOutput?.setTextColor(primaryText)
    tvModuleLogOutput?.setTextColor(primaryText)

    symbolChipViews.forEach { it.isDarkThemeMode = dark }
    tvDrawerProjectsSectionLabel?.setTextColor(secondaryText)
    listOf(
        btnHamburgerMenu, btnBackCalc, btnActiveProjectPill,
        btnDrawerCloseIcon, btnDrawerRunProject, btnDrawerCreateProject, btnDrawerResetProjectEngine,
        btnDrawerCreateNewFile, btnDrawerImportFile,
        btnModuleSubEditor, btnModuleSubLog,
        btnCompileModule, btnPlotModule, btnBytecodeModule, btnResetActiveModule, btnModuleViewLog,
        btnClearConsole,
        btnModuleBackToEditor, btnOutputPlot, btnOutputBytecode, btnClearModuleLog
    ).forEach { it?.isDarkThemeMode = dark }
    activeModuleFileName?.let { refreshStructuredInspector(it) }

    refreshModuleChips()
    refreshFileManagerDrawer()
}

// =========================================================================
//  Damped-Spring Physics Helpers
// =========================================================================

internal fun MathWorkspaceFragment.springPulse(target: View, bump: Float) {
    springAnimators.removeAll { (v, a) -> if (v === target) { a.cancel(); true } else false }
    if (target.width > 0) {
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
    }
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 480L
        interpolator = LinearInterpolator()
        addUpdateListener { a ->
            val f = a.animatedValue as Float
            val t = f * 0.48
            val decay = Math.exp(-7.5 * t)
            val pulse = decay * Math.sin(t * 22.0)
            target.scaleX = (1.0 + bump * pulse).toFloat()
            target.scaleY = (1.0 + bump * pulse).toFloat()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                target.scaleX = 1f
                target.scaleY = 1f
            }
        })
        start()
    }
    springAnimators.add(target to anim)
}

internal fun MathWorkspaceFragment.springSlideIn(target: View, fromDirection: Int = 1, distance: Int = 1) {
    springAnimators.removeAll { (v, a) -> if (v === target) { a.cancel(); true } else false }
    val clampedDist = distance.coerceIn(1, 4)
    val d = clampedDist.toFloat()
    val dist = fromDirection * (36f + (d - 1f) * 14f) * resources.displayMetrics.density
    val scaleAmp = 0.025 + (d - 1.0) * 0.01
    val dur = 480L + (clampedDist - 1) * 45L
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = dur
        interpolator = LinearInterpolator()
        addUpdateListener { a ->
            val f = a.animatedValue as Float
            val t = f * 0.48
            val decay = Math.exp(-7.5 * t)
            val pulse = decay * Math.sin(t * 22.0)
            val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
            target.translationX = dist * springFactor
            target.scaleX = (1.0 + scaleAmp * pulse).toFloat()
            target.scaleY = (1.0 + scaleAmp * pulse).toFloat()
            target.alpha = (f / 0.22f).coerceIn(0f, 1f)
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                target.translationX = 0f
                target.scaleX = 1f
                target.scaleY = 1f
                target.alpha = 1f
            }
        })
        start()
    }
    springAnimators.add(target to anim)
}

// =========================================================================
//  Frosted-Glass Neumorphic Modal Dialogs (New File, Rename/Copy, Confirm)
// =========================================================================

internal fun MathWorkspaceFragment.hideSoftKeyboardFrom(view: View?) {
    val ctx = context ?: return
    val target = view ?: return
    runCatching {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(target.windowToken, 0)
    }
}

internal fun MathWorkspaceFragment.animateModalCardEntrance(overlay: View, dialogCard: View, density: Float, onEnd: (() -> Unit)? = null) {
    overlay.alpha = 0f
    dialogCard.scaleX = 0.78f
    dialogCard.scaleY = 0.78f
    dialogCard.translationY = 18f * density
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 420L
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            val f = anim.animatedValue as Float
            val t = f * 0.44
            val decay = Math.exp(-7.5 * t)
            val pulse = (decay * Math.sin(t * 22.0)).toFloat()
            val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
            overlay.alpha = (f / 0.28f).coerceIn(0f, 1f)
            val s = 1f - 0.22f * springFactor + 0.04f * pulse
            dialogCard.scaleX = s
            dialogCard.scaleY = s
            dialogCard.translationY = (18f * density) * springFactor
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                overlay.alpha = 1f
                dialogCard.scaleX = 1f
                dialogCard.scaleY = 1f
                dialogCard.translationY = 0f
                onEnd?.invoke()
            }
        })
        start()
    }
}

fun MathWorkspaceFragment.dismissWorkspaceModalDialog(onEnd: (() -> Unit)? = null) {
    val overlay = activeModalOverlay
    if (overlay == null) {
        isModalDialogOpen = false
        onEnd?.invoke()
        return
    }
    activeModalOverlay = null
    isModalDialogOpen = false
    hideSoftKeyboardFrom(overlay)

    Frost.clearLiveBlur(fileManagerDrawerPanel)
    if (!isFileManagerDrawerOpen) {
        Frost.clearLiveBlur(rootLayout)
    }

    val card = overlay.getChildAt(overlay.childCount - 1)
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 190L
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
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                activeModalBlurBitmap?.recycle()
                activeModalBlurBitmap = null
                onEnd?.invoke()
            }
        })
        start()
    }
}

internal fun MathWorkspaceFragment.showFrostedFileNamingModal(
    badgeIcon: String,
    title: String,
    subtitle: String,
    description: String,
    initialText: String,
    hintText: String,
    suggestions: List<String>,
    primaryButtonLabel: String,
    onPrimary: (String) -> Unit,
    secondaryButtonLabel: String? = null,
    onSecondary: ((String) -> Unit)? = null
) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val outerFrame = workspaceRootFrame ?: return

    activeModalOverlay?.let { existing ->
        (existing.parent as? ViewGroup)?.removeView(existing)
        activeModalOverlay = null
    }
    activeModalBlurBitmap?.recycle()
    activeModalBlurBitmap = null

    val dark = activity.isDarkMode
    val density = resources.displayMetrics.density
    val dp = { v: Float -> (v * density).toInt() }

    val cardBg = Color.parseColor(if (dark) "#2E2E2E" else "#F5F5F5")
    val sectionBg = Color.parseColor(if (dark) "#252525" else "#EAEAEA")
    val inputBg = Color.parseColor(if (dark) "#1D1D1D" else "#FFFFFF")
    val strokeColor = Color.parseColor(if (dark) "#444444" else "#D5D5D5")
    val primaryText = Color.parseColor(if (dark) "#E8E8E8" else "#2A2A2A")
    val secondaryText = Color.parseColor(if (dark) "#9E9E9E" else "#606060")
    val accentColor = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
    val badgeTintBg = Color.parseColor(if (dark) "#1D3638" else "#FCEAE6")
    val scrimColor = Color.parseColor(if (dark) "#96141414" else "#88E6E6E6")

    val frostBmp = Frost.frozenBackdrop(outerFrame, downscale = 6, radius = 8)
    activeModalBlurBitmap = frostBmp
    Frost.tryLiveBlur(rootLayout, 18f * density)
    if (isFileManagerDrawerOpen) {
        Frost.tryLiveBlur(fileManagerDrawerPanel, 18f * density)
    }

    val overlay = FrameLayout(ctx).apply {
        elevation = dp(28f).toFloat()
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { dismissWorkspaceModalDialog() }
    }

    if (frostBmp != null) {
        val bgImage = ImageView(ctx).apply {
            setImageBitmap(frostBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.55f else 0.94f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlay.addView(bgImage)
    }

    val scrimView = View(ctx).apply {
        setBackgroundColor(scrimColor)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    overlay.addView(scrimView)

    val availWidth = if (outerFrame.width > 0) outerFrame.width else resources.displayMetrics.widthPixels
    val cardWidth = minOf(availWidth - dp(28f), dp(388f)).coerceAtLeast(dp(272f))

    val dialogCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        elevation = dp(32f).toFloat()
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

    val headerRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) }
    }

    val iconBadge = TextView(ctx).apply {
        text = badgeIcon
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(badgeTintBg)
            setStroke(dp(1f).coerceAtLeast(1), accentColor)
        }
        layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply {
            marginEnd = dp(10f)
        }
    }
    headerRow.addView(iconBadge)

    val titleCol = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvTitle = TextView(ctx).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primaryText)
    }
    val tvSub = TextView(ctx).apply {
        text = subtitle
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accentColor)
    }
    titleCol.addView(tvTitle)
    titleCol.addView(tvSub)
    headerRow.addView(titleCol)

    val btnCloseTop = NeumorphicKeyView(ctx).apply {
        text = "✕"
        textSize = 13f
        cornerRadiusDp = 16f
        shadowPadDp = 2.8f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
        setOnClickListener {
            springPulse(this, 0.07f)
            dismissWorkspaceModalDialog()
        }
    }
    headerRow.addView(btnCloseTop)
    dialogCard.addView(headerRow)

    val descBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(sectionBg)
            setStroke(1, strokeColor)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12f) }
    }
    val tvDesc = TextView(ctx).apply {
        text = description
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
        setTextColor(secondaryText)
        setLineSpacing(0f, 1.15f)
    }
    descBox.addView(tvDesc)
    dialogCard.addView(descBox)

    val tvInputLabel = TextView(ctx).apply {
        text = "NAME"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(secondaryText)
        setPadding(dp(2f), 0, dp(2f), dp(4f))
    }
    dialogCard.addView(tvInputLabel)

    val inputFrameBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 14f * density
        setColor(inputBg)
        setStroke(dp(1.4f).coerceAtLeast(1), accentColor)
    }
    val inputRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = inputFrameBg
        setPadding(dp(12f), dp(4f), dp(8f), dp(4f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) }
    }

    val inputEdit = EditText(ctx).apply {
        setText(initialText)
        hint = hintText
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.MONOSPACE
        setTextColor(primaryText)
        setHintTextColor(secondaryText)
        background = null
        setPadding(0, dp(8f), dp(8f), dp(8f))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        if (initialText.isNotEmpty()) {
            val dotIdx = initialText.lastIndexOf(".math")
            setSelection(if (dotIdx > 0) dotIdx else initialText.length)
        }
        setOnFocusChangeListener { _, hasFocus ->
            inputFrameBg.setStroke(
                dp(if (hasFocus) 1.6f else 1.1f).coerceAtLeast(1),
                if (hasFocus) accentColor else strokeColor
            )
        }
    }

    val extBadge = TextView(ctx).apply {
        text = ".math"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accentColor)
        setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor(badgeTintBg)
            setStroke(1, accentColor)
        }
    }

    inputRow.addView(inputEdit)
    inputRow.addView(extBadge)
    dialogCard.addView(inputRow)

    if (suggestions.isNotEmpty()) {
        val tvSuggLabel = TextView(ctx).apply {
            text = "QUICK SUGGESTIONS (TAP TO FILL)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(secondaryText)
            setPadding(dp(2f), 0, dp(2f), dp(3f))
        }
        dialogCard.addView(tvSuggLabel)

        val suggScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32f)
            ).apply { bottomMargin = dp(12f) }
        }
        val suggRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        suggestions.forEach { suggestion ->
            val chipWidth = (suggestion.length * 7.4f + 26f).coerceAtLeast(88f)
            val chip = NeumorphicKeyView(ctx).apply {
                text = suggestion
                textSize = 10.5f
                cornerRadiusDp = 10f
                shadowPadDp = 2.2f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(chipWidth), dp(28f)).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    springPulse(this, 0.06f)
                    inputEdit.setText(suggestion)
                    val dotIdx = suggestion.lastIndexOf(".math")
                    inputEdit.setSelection(if (dotIdx > 0) dotIdx else suggestion.length)
                }
            }
            suggRow.addView(chip)
        }
        suggScroll.addView(suggRow)
        dialogCard.addView(suggScroll)
    }

    fun submitPrimary() {
        val entered = inputEdit.text?.toString().orEmpty()
        dismissWorkspaceModalDialog {
            onPrimary(entered)
        }
    }

    inputEdit.setOnEditorActionListener { _, actionId, event ->
        val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
        if (actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
            submitPrimary()
            true
        } else {
            false
        }
    }

    val actionsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val btnCancel = NeumorphicKeyView(ctx).apply {
        text = "Cancel"
        textSize = 12.5f
        cornerRadiusDp = 14f
        shadowPadDp = 3f
        isAccentKey = false
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(0, dp(40f), 0.9f).apply {
            marginEnd = dp(6f)
        }
        setOnClickListener {
            springPulse(this, 0.06f)
            dismissWorkspaceModalDialog()
        }
    }
    actionsRow.addView(btnCancel)

    if (secondaryButtonLabel != null && onSecondary != null) {
        val btnSecondary = NeumorphicKeyView(ctx).apply {
            text = secondaryButtonLabel
            textSize = 12f
            cornerRadiusDp = 14f
            shadowPadDp = 3f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1.05f).apply {
                marginEnd = dp(6f)
            }
            setOnClickListener {
                springPulse(this, 0.06f)
                val entered = inputEdit.text?.toString().orEmpty()
                dismissWorkspaceModalDialog {
                    onSecondary(entered)
                }
            }
        }
        actionsRow.addView(btnSecondary)
    }

    val btnPrimary = NeumorphicKeyView(ctx).apply {
        text = primaryButtonLabel
        textSize = 12.8f
        cornerRadiusDp = 14f
        shadowPadDp = 3.2f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1.3f)
        setOnClickListener {
            springPulse(this, 0.07f)
            submitPrimary()
        }
    }
    actionsRow.addView(btnPrimary)
    dialogCard.addView(actionsRow)

    overlay.addView(dialogCard)
    outerFrame.addView(overlay)
    activeModalOverlay = overlay
    isModalDialogOpen = true

    animateModalCardEntrance(overlay, dialogCard, density) {
        inputEdit.requestFocus()
        runCatching {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(inputEdit, InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

internal fun MathWorkspaceFragment.showFrostedConfirmModal(
    badgeIcon: String,
    title: String,
    subtitle: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit
) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val outerFrame = workspaceRootFrame ?: return

    activeModalOverlay?.let { existing ->
        (existing.parent as? ViewGroup)?.removeView(existing)
        activeModalOverlay = null
    }
    activeModalBlurBitmap?.recycle()
    activeModalBlurBitmap = null

    val dark = activity.isDarkMode
    val density = resources.displayMetrics.density
    val dp = { v: Float -> (v * density).toInt() }

    val cardBg = Color.parseColor(if (dark) "#2E2E2E" else "#F5F5F5")
    val sectionBg = Color.parseColor(if (dark) "#252525" else "#EAEAEA")
    val strokeColor = Color.parseColor(if (dark) "#444444" else "#D5D5D5")
    val primaryText = Color.parseColor(if (dark) "#E8E8E8" else "#2A2A2A")
    val secondaryText = Color.parseColor(if (dark) "#9E9E9E" else "#606060")
    val accentColor = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
    val badgeTintBg = Color.parseColor(if (dark) "#1D3638" else "#FCEAE6")
    val scrimColor = Color.parseColor(if (dark) "#96141414" else "#88E6E6E6")

    val frostBmp = Frost.frozenBackdrop(outerFrame, downscale = 6, radius = 8)
    activeModalBlurBitmap = frostBmp
    Frost.tryLiveBlur(rootLayout, 18f * density)
    if (isFileManagerDrawerOpen) {
        Frost.tryLiveBlur(fileManagerDrawerPanel, 18f * density)
    }

    val overlay = FrameLayout(ctx).apply {
        elevation = dp(28f).toFloat()
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { dismissWorkspaceModalDialog() }
    }

    if (frostBmp != null) {
        val bgImage = ImageView(ctx).apply {
            setImageBitmap(frostBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.55f else 0.94f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlay.addView(bgImage)
    }

    val scrimView = View(ctx).apply {
        setBackgroundColor(scrimColor)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    overlay.addView(scrimView)

    val availWidth = if (outerFrame.width > 0) outerFrame.width else resources.displayMetrics.widthPixels
    val cardWidth = minOf(availWidth - dp(28f), dp(370f)).coerceAtLeast(dp(268f))

    val dialogCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        elevation = dp(32f).toFloat()
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

    val headerRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10f) }
    }
    val iconBadge = TextView(ctx).apply {
        text = badgeIcon
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(badgeTintBg)
            setStroke(dp(1f).coerceAtLeast(1), accentColor)
        }
        layoutParams = LinearLayout.LayoutParams(dp(38f), dp(38f)).apply {
            marginEnd = dp(10f)
        }
    }
    headerRow.addView(iconBadge)

    val titleCol = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    titleCol.addView(TextView(ctx).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primaryText)
    })
    titleCol.addView(TextView(ctx).apply {
        text = subtitle
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accentColor)
    })
    headerRow.addView(titleCol)

    val btnCloseTop = NeumorphicKeyView(ctx).apply {
        text = "✕"
        textSize = 13f
        cornerRadiusDp = 16f
        shadowPadDp = 2.8f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(dp(36f), dp(36f))
        setOnClickListener {
            springPulse(this, 0.07f)
            dismissWorkspaceModalDialog()
        }
    }
    headerRow.addView(btnCloseTop)
    dialogCard.addView(headerRow)

    val msgBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(sectionBg)
            setStroke(1, strokeColor)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14f) }
    }
    msgBox.addView(TextView(ctx).apply {
        text = message
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(secondaryText)
        setLineSpacing(0f, 1.18f)
    })
    dialogCard.addView(msgBox)

    val actionsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val btnCancel = NeumorphicKeyView(ctx).apply {
        text = "Cancel"
        textSize = 12.5f
        cornerRadiusDp = 14f
        shadowPadDp = 3f
        isAccentKey = false
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1f).apply {
            marginEnd = dp(6f)
        }
        setOnClickListener {
            springPulse(this, 0.06f)
            dismissWorkspaceModalDialog()
        }
    }
    val btnConfirm = NeumorphicKeyView(ctx).apply {
        text = confirmLabel
        textSize = 12.8f
        cornerRadiusDp = 14f
        shadowPadDp = 3.2f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1.2f)
        setOnClickListener {
            springPulse(this, 0.07f)
            dismissWorkspaceModalDialog {
                onConfirm()
            }
        }
    }
    actionsRow.addView(btnCancel)
    actionsRow.addView(btnConfirm)
    dialogCard.addView(actionsRow)

    overlay.addView(dialogCard)
    outerFrame.addView(overlay)
    activeModalOverlay = overlay
    isModalDialogOpen = true

    animateModalCardEntrance(overlay, dialogCard, density)
}

fun MathWorkspaceFragment.wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
    wobbleAnimators.forEach { it.cancel() }
    wobbleAnimators.clear()
    val speed = maxRadius / revealDurationMs
    val density = resources.displayMetrics.density
    val views = listOfNotNull(
        topHeaderBar, moduleSubTabBar, etModuleEditor,
        moduleQuickStatusCard, varsCard, moduleLogCard
    )
    views.forEach { v ->
        if (!v.isShown || v.width == 0 || v.height == 0) return@forEach
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val vx = loc[0] + v.width / 2f
        val vy = loc[1] + v.height / 2f
        val dx = vx - cx
        val dy = vy - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val proximity = 1f - (dist / maxRadius).coerceIn(0f, 1f)
        val bump = 0.015f + 0.03f * proximity
        val delay = (dist / speed).toLong().coerceAtMost(revealDurationMs - 40)
        val invLen = if (dist > 0.5f) 1f / dist else 0f
        val pushX = dx * invLen
        val pushY = dy * invLen
        val pushDist = 6f * density * (0.5f + proximity)

        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 480L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = decay * Math.sin(t * 22.0)
                v.scaleX = (1.0 + bump * pulse).toFloat()
                v.scaleY = (1.0 + bump * pulse).toFloat()
                v.translationX = (pushDist * pulse).toFloat() * pushX
                v.translationY = (pushDist * pulse).toFloat() * pushY
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.translationX = 0f
                    v.translationY = 0f
                }
            })
            start()
            wobbleAnimators.add(this)
        }
    }
}


// =========================================================================
//  Interactive $map / JSON / CSV / 2D $mat / $vec Table & Tree Inspector
// =========================================================================


internal fun MathWorkspaceFragment.refreshStructuredInspector(modFileName: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val dark = activity.isDarkMode
    val chipsContainer = inspectorChipsRow ?: return
    val tableContainer = inspectorTableContainer ?: return
    val density = resources.displayMetrics.density
    val dp = { v: Float -> (v * density).toInt() }
    val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")

    chipsContainer.removeAllViews()
    tableContainer.removeAllViews()

    val items = moduleInspectableItems[projectScopedKey(modFileName)].orEmpty()
    if (items.isEmpty()) {
        val tvEmpty = TextView(ctx).apply {
            text = "Run a module with a \$map, JSON object, csv_to_maps(...), \$mat, or \$vec to inspect it as a formatted table."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
            typeface = Typeface.MONOSPACE
            setTextColor(secondaryText)
        }
        tableContainer.addView(tvEmpty)
        return
    }

    if (selectedInspectorIdx >= items.size) selectedInspectorIdx = 0

    items.forEachIndexed { idx, (label, vType, _) ->
        val isSel = (idx == selectedInspectorIdx)
        val chipLabel = "$label ($vType)"
        val chipW = (chipLabel.length * 7.2f + 24f).coerceAtLeast(78f)
        val chip = NeumorphicKeyView(ctx).apply {
            text = chipLabel
            textSize = 10.2f
            cornerRadiusDp = 9.5f
            shadowPadDp = 2.0f
            isAccentKey = isSel
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(chipW), dp(27f)).apply {
                marginEnd = dp(4f)
            }
            setOnClickListener {
                selectedInspectorIdx = idx
                refreshStructuredInspector(modFileName)
            }
        }
        chipsContainer.addView(chip)
    }

    val (_, selType, selVal) = items[selectedInspectorIdx]
    tableContainer.addView(buildStructuredTableView(ctx, dark, selType, selVal))
}

internal fun MathWorkspaceFragment.parseMapEntriesFromFormatted(raw: String): List<Pair<String, String>> {
    val trimmed = raw.trim()
    val inner = when {
        trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed.substring(1, trimmed.length - 1).trim()
        trimmed.startsWith("map(") && trimmed.endsWith(")") -> trimmed.substring(4, trimmed.length - 1).trim()
        trimmed.startsWith("dict(") && trimmed.endsWith(")") -> trimmed.substring(5, trimmed.length - 1).trim()
        else -> return emptyList()
    }
    if (inner.isEmpty()) return emptyList()
    val pairs = mutableListOf<Pair<String, String>>()
    val tokens = splitTopLevelCommas(inner)
    for (tok in tokens) {
        val colonIdx = findTopLevelColon(tok)
        if (colonIdx > 0) {
            val k = tok.substring(0, colonIdx).trim().removeSurrounding("\"")
            val v = tok.substring(colonIdx + 1).trim()
            pairs.add(k to v)
        }
    }
    return pairs
}

internal fun MathWorkspaceFragment.splitTopLevelCommas(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var depth = 0
    var inQuotes = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && inQuotes && i + 1 < s.length) {
            cur.append(c).append(s[i + 1])
            i += 2
            continue
        }
        if (c == '"') {
            inQuotes = !inQuotes
            cur.append(c)
        } else if (!inQuotes) {
            when (c) {
                '{', '[', '(' -> { depth++; cur.append(c) }
                '}', ']', ')' -> { if (depth > 0) depth--; cur.append(c) }
                ',' -> if (depth == 0) {
                    out.add(cur.toString().trim())
                    cur.setLength(0)
                } else {
                    cur.append(c)
                }
                else -> cur.append(c)
            }
        } else {
            cur.append(c)
        }
        i++
    }
    if (cur.isNotEmpty()) out.add(cur.toString().trim())
    return out
}

internal fun MathWorkspaceFragment.findTopLevelColon(s: String): Int {
    var depth = 0
    var inQuotes = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && inQuotes && i + 1 < s.length) {
            i += 2
            continue
        }
        if (c == '"') inQuotes = !inQuotes
        else if (!inQuotes) {
            when (c) {
                '{', '[', '(' -> depth++
                '}', ']', ')' -> if (depth > 0) depth--
                ':' -> if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

internal fun MathWorkspaceFragment.buildStructuredTableView(
    ctx: Context,
    dark: Boolean,
    vType: String,
    rawValue: String
): View {
    val density = resources.displayMetrics.density
    val dp = { v: Float -> (v * density).toInt() }
    val hdrBg = Color.parseColor(if (dark) "#1D3638" else "#FCEAE6")
    val rowEvenBg = Color.parseColor(if (dark) "#1F1F1F" else "#FFFFFF")
    val rowOddBg = Color.parseColor(if (dark) "#262626" else "#F4F5F7")
    val borderCol = Color.parseColor(if (dark) "#3A3A3A" else "#D5D5D5")
    val primaryText = Color.parseColor(if (dark) "#E6E6E6" else "#2A2A2A")
    val accentText = Color.parseColor(if (dark) "#26C6DA" else "#D85A4A")

    fun makeTable(headers: List<String>, rows: List<List<String>>): View {
        val hScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tableCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setStroke(1, borderCol)
            }
        }

        val colCount = headers.size
        val colChars = IntArray(colCount) { c -> headers[c].length.coerceAtLeast(6) }
        for (r in rows) {
            for (c in 0 until minOf(colCount, r.size)) {
                colChars[c] = maxOf(colChars[c], r[c].length.coerceAtMost(28))
            }
        }

        fun makeRowView(cells: List<String>, isHeader: Boolean, rowIdx: Int): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(
                    when {
                        isHeader -> hdrBg
                        rowIdx % 2 == 0 -> rowEvenBg
                        else -> rowOddBg
                    }
                )
                for (c in 0 until colCount) {
                    val cellText = cells.getOrNull(c).orEmpty()
                    val cellW = dp((colChars[c] * 7.8f + 20f).coerceAtLeast(64f))
                    val tv = TextView(ctx).apply {
                        text = cellText
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 11.2f else 11f)
                        typeface = if (isHeader) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
                        setTextColor(if (isHeader) accentText else primaryText)
                        setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
                        layoutParams = LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    addView(tv)
                }
            }
        }

        tableCol.addView(makeRowView(headers, isHeader = true, rowIdx = -1))
        rows.take(50).forEachIndexed { idx, r ->
            tableCol.addView(makeRowView(r, isHeader = false, rowIdx = idx))
        }
        hScroll.addView(tableCol)
        return hScroll
    }

    if (vType == "map") {
        val entries = parseMapEntriesFromFormatted(rawValue)
        if (entries.isNotEmpty()) {
            return makeTable(
                listOf("Key", "Value"),
                entries.map { (k, v) -> listOf(k, v) }
            )
        }
    }

    val trimmed = rawValue.trim()
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.contains(";")) {
            val rawRows = inner.split(";").map { r ->
                r.trim().removePrefix("[").removeSuffix("]").split(",").map { it.trim() }
            }
            val cols = rawRows.maxOfOrNull { it.size } ?: 1
            val headers = listOf("Row") + (0 until cols).map { "Col $it" }
            val rows = rawRows.mapIndexed { rIdx, rCells -> listOf("[$rIdx]") + rCells }
            return makeTable(headers, rows)
        }
        val topElems = splitTopLevelCommas(inner)
        if (topElems.isNotEmpty() && topElems.first().trim().startsWith("{")) {
            val parsedMaps = topElems.map { parseMapEntriesFromFormatted(it) }.filter { it.isNotEmpty() }
            if (parsedMaps.isNotEmpty()) {
                val allKeys = LinkedHashSet<String>()
                parsedMaps.forEach { m -> m.forEach { (k, _) -> allKeys.add(k) } }
                val keyList = allKeys.toList()
                val headers = listOf("#") + keyList
                val rows = parsedMaps.mapIndexed { idx, m ->
                    val lookup = m.toMap()
                    listOf(idx.toString()) + keyList.map { k -> lookup[k] ?: "" }
                }
                return makeTable(headers, rows)
            }
        }
        if (topElems.isNotEmpty() && topElems.first().trim().startsWith("[")) {
            val rawRows = topElems.map { r ->
                val ri = r.trim().removePrefix("[").removeSuffix("]")
                splitTopLevelCommas(ri)
            }
            val cols = rawRows.maxOfOrNull { it.size } ?: 1
            val headers = listOf("Row") + (0 until cols).map { "Col $it" }
            val rows = rawRows.mapIndexed { rIdx, rCells -> listOf("[$rIdx]") + rCells }
            return makeTable(headers, rows)
        }
        if (topElems.isNotEmpty()) {
            val headers = listOf("Index", "Value")
            val rows = topElems.mapIndexed { idx, v -> listOf("[$idx]", v) }
            return makeTable(headers, rows)
        }
    }

    return TextView(ctx).apply {
        text = rawValue
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
        typeface = Typeface.MONOSPACE
        setTextColor(primaryText)
    }
}

// =========================================================================
//  Interactive 2D Function & Vector Plotter Modal (`MathPlotView`)
// =========================================================================
