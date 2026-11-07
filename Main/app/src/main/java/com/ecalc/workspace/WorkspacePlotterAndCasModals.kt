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
 */

internal fun MathWorkspaceFragment.extractModuleFunctionAndVectorCandidates(code: String): List<String> {
    val out = LinkedHashSet<String>()
    val fnDeclRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()\"]*)\\)\\s*(?:->[^=]+)?=")
    val vecVarDeclRegex = Regex("(?m)^\\s*(?:(?:pub|priv|const|mut)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::[^=]+)?(?::=|=)(?![=>])\\s*\\[")
    for (m in fnDeclRegex.findAll(code)) {
        val name = m.groupValues[1]
        val params = m.groupValues[2].trim()
        val paramCount = if (params.isEmpty()) 0 else params.split(",").size
        if (paramCount == 1 && name !in setOf("if", "while", "switch", "for", "catch", "main")) {
            out.add(name)
        }
    }
    for (m in vecVarDeclRegex.findAll(code)) {
        val name = m.groupValues[1]
        out.add(name)
    }
    out.add("sin(x) * cos(2*x)")
    out.add("x^2 - 2*x - 3")
    return out.take(10).toList()
}

internal fun MathWorkspaceFragment.showFrostedPlotterModal(initialExpr: String? = null) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val outerFrame = workspaceRootFrame ?: return

    etModuleEditor?.dismissSuggestionsPopup()
    activeModalOverlay?.let { existing ->
        (existing.parent as? ViewGroup)?.removeView(existing)
        activeModalOverlay = null
    }
    activeModalBlurBitmap?.recycle()
    activeModalBlurBitmap = null

    val dark = activity.isDarkMode
    val density = resources.displayMetrics.density
    val dp = { v: Float -> (v * density).toInt() }

    val cardBg = Color.parseColor(if (dark) "#2A2D32" else "#F5F7FA")
    val inputBg = Color.parseColor(if (dark) "#1C1E22" else "#FFFFFF")
    val strokeColor = Color.parseColor(if (dark) "#444850" else "#D0D7DE")
    val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#24292F")
    val secondaryText = Color.parseColor(if (dark) "#9BA4B0" else "#57606A")
    val accentColor = Color.parseColor(if (dark) "#26C6DA" else "#E58A7B")
    val scrimColor = Color.parseColor(if (dark) "#96101216" else "#88E6E6E6")

    val code = etModuleEditor?.text?.toString().orEmpty()
    val modFile = activeModuleFileName
    val modId = MathEngineManager.deriveModuleName(modFile, code)
    val projectSources = collectAllProjectModuleSources(ctx, modFile, code)
    val candidates = extractModuleFunctionAndVectorCandidates(code)
    val defaultTarget = initialExpr ?: candidates.firstOrNull() ?: "sin(x)"

    val frostBmp = Frost.frozenBackdrop(outerFrame, downscale = 6, radius = 8)
    activeModalBlurBitmap = frostBmp
    Frost.tryLiveBlur(rootLayout, 18f * density)

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
        overlay.addView(ImageView(ctx).apply {
            setImageBitmap(frostBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.55f else 0.94f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
    }
    overlay.addView(View(ctx).apply {
        setBackgroundColor(scrimColor)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    })

    val availWidth = if (outerFrame.width > 0) outerFrame.width else resources.displayMetrics.widthPixels
    val cardWidth = minOf(availWidth - dp(20f), dp(410f)).coerceAtLeast(dp(280f))

    val dialogCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        elevation = dp(32f).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(cardBg)
            setStroke(dp(1.2f).coerceAtLeast(1), strokeColor)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { }
    }

    val hdrRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6f) }
    }
    val titleCol = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    titleCol.addView(TextView(ctx).apply {
        text = "📈 Interactive 2D Plotter"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primaryText)
    })
    titleCol.addView(TextView(ctx).apply {
        text = "Module [$modId] • Pinch to zoom, drag to pan, tap curve for roots/extrema"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTextColor(accentColor)
    })
    hdrRow.addView(titleCol)
    hdrRow.addView(NeumorphicKeyView(ctx).apply {
        text = "✕"
        textSize = 13f
        cornerRadiusDp = 14f
        shadowPadDp = 2.5f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f))
        setOnClickListener { dismissWorkspaceModalDialog() }
    })
    dialogCard.addView(hdrRow)

    val controlsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6f) }
    }

    fun styleEditBox(et: EditText) {
        et.setSingleLine(true)
        et.typeface = Typeface.MONOSPACE
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
        et.setTextColor(primaryText)
        et.setHintTextColor(secondaryText)
        et.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        et.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(inputBg)
            setStroke(1, strokeColor)
        }
    }

    val etExpr = EditText(ctx).apply {
        setText(defaultTarget)
        hint = "f(x) or vec"
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f).apply {
            marginEnd = dp(4f)
        }
        styleEditBox(this)
    }
    val etXMin = EditText(ctx).apply {
        setText("-5")
        hint = "xMin"
        layoutParams = LinearLayout.LayoutParams(dp(52f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(4f)
        }
        styleEditBox(this)
    }
    val etXMax = EditText(ctx).apply {
        setText("5")
        hint = "xMax"
        layoutParams = LinearLayout.LayoutParams(dp(52f), LinearLayout.LayoutParams.WRAP_CONTENT)
        styleEditBox(this)
    }
    controlsRow.addView(etExpr)
    controlsRow.addView(etXMin)
    controlsRow.addView(etXMax)
    dialogCard.addView(controlsRow)

    val candScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(30f)
        ).apply { bottomMargin = dp(6f) }
    }
    val candRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    candScroll.addView(candRow)
    dialogCard.addView(candScroll)

    val plotView = MathPlotView(ctx).apply {
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(230f)
        ).apply { bottomMargin = dp(6f) }
    }
    dialogCard.addView(plotView)

    val tvReadout = TextView(ctx).apply {
        text = "Tap or drag on the curve to inspect (x, y), roots, and local extrema."
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        setTextColor(secondaryText)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8f) }
    }
    plotView.onInspectPointChanged = { ix, iy, tag ->
        val badge = tag?.let { "[$it] " } ?: ""
        tvReadout.text = String.format("📍 %sx = %.5g,  y = %.5g", badge, ix, iy)
    }
    dialogCard.addView(tvReadout)

    fun triggerPlot() {
        val target = etExpr.text?.toString().orEmpty().trim()
        val xMin = etXMin.text?.toString()?.toDoubleOrNull() ?: -5.0
        val xMax = etXMax.text?.toString()?.toDoubleOrNull() ?: 5.0
        tvReadout.text = "⏳ Sampling '$target' in [$modId]..."
        MathEngineManager.samplePlotPoints(
            modName = modId,
            exprOrFn = target,
            xMin = xMin,
            xMax = xMax,
            samples = 180,
            projectName = activeProjectName,
            moduleSource = code,
            projectSourcesByFileName = projectSources
        ) { ok, label, pts, err ->
            activity.runOnUiThread {
                if (ok && pts.isNotEmpty()) {
                    plotView.setSeriesData(label, pts)
                    tvReadout.text = "✅ Plotted $label (${pts.size} pts). Tap curve to inspect."
                } else {
                    plotView.setSeriesData(label, emptyList())
                    tvReadout.text = "⚠ ${err ?: "Could not plot '$target'"}"
                }
            }
        }
    }

    candidates.forEach { cand ->
        val w = (cand.length * 7.2f + 22f).coerceAtLeast(62f)
        candRow.addView(NeumorphicKeyView(ctx).apply {
            text = cand
            textSize = 10.2f
            cornerRadiusDp = 9f
            shadowPadDp = 2f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(w), dp(26f)).apply { marginEnd = dp(4f) }
            setOnClickListener {
                etExpr.setText(cand)
                triggerPlot()
            }
        })
    }

    val btnRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    btnRow.addView(NeumorphicKeyView(ctx).apply {
        text = "📈 Plot Curve"
        textSize = 12f
        cornerRadiusDp = 13f
        shadowPadDp = 2.8f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.2f).apply { marginEnd = dp(5f) }
        setOnClickListener {
            springPulse(this, 0.06f)
            triggerPlot()
        }
    })
    btnRow.addView(NeumorphicKeyView(ctx).apply {
        text = "Reset Zoom"
        textSize = 11.5f
        cornerRadiusDp = 13f
        shadowPadDp = 2.8f
        isAccentKey = false
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.0f).apply { marginEnd = dp(5f) }
        setOnClickListener {
            springPulse(this, 0.06f)
            plotView.resetViewport()
        }
    })
    btnRow.addView(NeumorphicKeyView(ctx).apply {
        text = "Close"
        textSize = 11.5f
        cornerRadiusDp = 13f
        shadowPadDp = 2.8f
        isAccentKey = false
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(dp(74f), dp(38f))
        setOnClickListener { dismissWorkspaceModalDialog() }
    })
    dialogCard.addView(btnRow)

    overlay.addView(dialogCard)
    outerFrame.addView(overlay)
    activeModalOverlay = overlay
    isModalDialogOpen = true
    animateModalCardEntrance(overlay, dialogCard, density)
    triggerPlot()
}

// =========================================================================
//  Stack Bytecode VM Disassembler Modal (`bc_disasm`)
// =========================================================================

internal fun MathWorkspaceFragment.showFrostedBytecodeDisasmModal(initialFn: String? = null) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val outerFrame = workspaceRootFrame ?: return

    etModuleEditor?.dismissSuggestionsPopup()
    activeModalOverlay?.let { existing ->
        (existing.parent as? ViewGroup)?.removeView(existing)
        activeModalOverlay = null
    }
    activeModalBlurBitmap?.recycle()
    activeModalBlurBitmap = null

    val dark = activity.isDarkMode
    val density = resources.displayMetrics.density
    val dp = { v: Float -> (v * density).toInt() }

    val cardBg = Color.parseColor(if (dark) "#2A2D32" else "#F5F7FA")
    val inputBg = Color.parseColor(if (dark) "#181A1F" else "#FFFFFF")
    val strokeColor = Color.parseColor(if (dark) "#444850" else "#D0D7DE")
    val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#24292F")
    val secondaryText = Color.parseColor(if (dark) "#9BA4B0" else "#57606A")
    val accentColor = Color.parseColor(if (dark) "#26C6DA" else "#E58A7B")
    val scrimColor = Color.parseColor(if (dark) "#96101216" else "#88E6E6E6")

    val code = etModuleEditor?.text?.toString().orEmpty()
    val modFile = activeModuleFileName
    val modId = MathEngineManager.deriveModuleName(modFile, code)
    val projectSources = collectAllProjectModuleSources(ctx, modFile, code)

    val fnRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*(?:::[a-zA-Z_][a-zA-Z0-9_]*)?)\\s*\\([^()\"]*\\)\\s*(?:->[^=]+)?=")
    val fnCandidates = LinkedHashSet<String>()
    for (m in fnRegex.findAll(code)) {
        val fnName = m.groupValues[1].substringAfter("::")
        if (fnName !in setOf("if", "while", "switch", "for", "catch")) {
            fnCandidates.add(fnName)
        }
    }
    fnCandidates.add("x => x^2 + 2*x + 1")
    val defaultTarget = initialFn ?: fnCandidates.first()

    val frostBmp = Frost.frozenBackdrop(outerFrame, downscale = 6, radius = 8)
    activeModalBlurBitmap = frostBmp
    Frost.tryLiveBlur(rootLayout, 18f * density)

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
        overlay.addView(ImageView(ctx).apply {
            setImageBitmap(frostBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.55f else 0.94f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
    }
    overlay.addView(View(ctx).apply {
        setBackgroundColor(scrimColor)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    })

    val availWidth = if (outerFrame.width > 0) outerFrame.width else resources.displayMetrics.widthPixels
    val cardWidth = minOf(availWidth - dp(20f), dp(400f)).coerceAtLeast(dp(276f))

    val dialogCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(
            cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        elevation = dp(32f).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(cardBg)
            setStroke(dp(1.2f).coerceAtLeast(1), strokeColor)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { }
    }

    val hdrRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6f) }
    }
    val titleCol = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    titleCol.addView(TextView(ctx).apply {
        text = "⚙ Symbolic CAS & Expression Inspector"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primaryText)
    })
    titleCol.addView(TextView(ctx).apply {
        text = "Module [$modId] • sym_simplify / sym_diff / sym_integ / sym_solve"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTextColor(accentColor)
    })
    hdrRow.addView(titleCol)
    hdrRow.addView(NeumorphicKeyView(ctx).apply {
        text = "✕"
        textSize = 13f
        cornerRadiusDp = 14f
        shadowPadDp = 2.5f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f))
        setOnClickListener { dismissWorkspaceModalDialog() }
    })
    dialogCard.addView(hdrRow)

    val etTarget = EditText(ctx).apply {
        setText(defaultTarget)
        hint = "Function name or lambda (e.g. height_at)"
        setSingleLine(true)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(primaryText)
        setHintTextColor(secondaryText)
        setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            setColor(inputBg)
            setStroke(1, strokeColor)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6f) }
    }
    dialogCard.addView(etTarget)

    val candScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(30f)
        ).apply { bottomMargin = dp(6f) }
    }
    val candRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    candScroll.addView(candRow)
    dialogCard.addView(candScroll)

    val disasmScroll = ScrollView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(210f)
        ).apply { bottomMargin = dp(8f) }
        setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(inputBg)
            setStroke(1, strokeColor)
        }
    }
    val tvDisasm = TextView(ctx).apply {
        text = "Select a function above or tap 'Analyze'..."
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.MONOSPACE
        setTextColor(primaryText)
        setTextIsSelectable(true)
    }
    disasmScroll.addView(tvDisasm)
    dialogCard.addView(disasmScroll)

    fun runDisasm() {
        val target = etTarget.text?.toString().orEmpty().trim()
        tvDisasm.text = "Analyzing '$target' in [$modId]..."
        MathEngineManager.analyzeSymbolicInModule(
            modName = modId,
            targetExpr = target,
            projectName = activeProjectName,
            moduleSource = code,
            projectSourcesByFileName = projectSources
        ) { ok, outText ->
            activity.runOnUiThread {
                tvDisasm.text = if (ok) {
                    outText
                } else {
                    "⚠ $outText\n\nTip: Enter a function name in [$modId] (e.g. height_at) or an inline lambda such as: x => x^2 + 2*x + 1"
                }
            }
        }
    }

    fnCandidates.forEach { fnName ->
        val w = (fnName.length * 7.2f + 22f).coerceAtLeast(64f)
        candRow.addView(NeumorphicKeyView(ctx).apply {
            text = fnName
            textSize = 10.2f
            cornerRadiusDp = 9f
            shadowPadDp = 2f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(w), dp(26f)).apply { marginEnd = dp(4f) }
            setOnClickListener {
                etTarget.setText(fnName)
                runDisasm()
            }
        })
    }

    val btnRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    btnRow.addView(NeumorphicKeyView(ctx).apply {
        text = "⚙ Analyze"
        textSize = 12f
        cornerRadiusDp = 13f
        shadowPadDp = 2.8f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.3f).apply { marginEnd = dp(6f) }
        setOnClickListener {
            springPulse(this, 0.06f)
            runDisasm()
        }
    })
    btnRow.addView(NeumorphicKeyView(ctx).apply {
        text = "Close"
        textSize = 12f
        cornerRadiusDp = 13f
        shadowPadDp = 2.8f
        isAccentKey = false
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(dp(84f), dp(38f))
        setOnClickListener { dismissWorkspaceModalDialog() }
    })
    dialogCard.addView(btnRow)

    overlay.addView(dialogCard)
    outerFrame.addView(overlay)
    activeModalOverlay = overlay
    isModalDialogOpen = true
    animateModalCardEntrance(overlay, dialogCard, density)
    runDisasm()
}
