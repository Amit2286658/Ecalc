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


internal fun MainActivity.createBlurredBackdropBitmap(sourceView: View, radius: Int = 8): Bitmap? {
    // Delegated to the shared frosted-glass helper so the Info window, the
    // File-Manager drawer and hold-tooltips all share one frost texture.
    return Frost.frozenBackdrop(sourceView, downscale = 6, radius = radius)
}

internal fun MainActivity.readAssetTextOrFallback(assetPath: String, fallback: String): String {
    return try {
        assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
            .ifEmpty { fallback }
    } catch (_: Throwable) {
        fallback
    }
}

internal fun MainActivity.openExternalUri(uriString: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
        startActivity(intent)
    } catch (_: Throwable) {
        showButtonToast("Link: $uriString")
    }
}

fun MainActivity.showAboutInfoWindow() {
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

    // Never let the clamp range invert on narrow windows (a live max in
    // coerceIn would throw on tiny/split-screen widths).
    val cardWidth = minOf(root.width - dp(28f), dp(420f)).coerceAtLeast(dp(280f))
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
        text = "MEP Calc"
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
    addInfoBullet(detailsBox, ".math Studio", "Swipe left on the display for executable .math Modules & multi-file projects")
    addInfoBullet(detailsBox, "Tooltips", "Press and hold any button or tab to inspect its action")
    scrollContent.addView(detailsBox)

    // Section B: Developer, Contact & Repository Info
    val authorBox = makeSectionBox()
    authorBox.addView(makeSectionTitle("Developer & Project Information"))
    addInfoBullet(authorBox, "Author", "Amit kumar")
    addInfoBullet(authorBox, "Email", "amitkumar13234353@gmail.com") {
        openExternalUri("mailto:amitkumar13234353@gmail.com?subject=MEP%20Calc%20Query%20/%20Feedback")
    }
    addInfoBullet(authorBox, "Project GitHub", "GitHub.com/Amit2286658/Ecalc") {
        openExternalUri("https://github.com/Amit2286658/Ecalc")
    }
    addInfoBullet(authorBox, "Cmath engine", "GitHub.com/Amit2286658/Math-Engine-MEP") {
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
    quickLinksRow.addView(makeQuickLinkBtn("✉ Email", "mailto:amitkumar13234353@gmail.com?subject=MEP%20Calc%20Query%20/%20Feedback"))
    quickLinksRow.addView(makeQuickLinkBtn("🌐 MEP Calc Repo", "https://github.com/Amit2286658/Ecalc"))
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
        • Architecture: Self-contained C11 symbolic & numerical runtime with 8B NaN-boxed Values, O(1) stdlib.mathc binary boot ("MTHC" v1), and a unified, Python-style top-to-bottom executable .math module architecture with topological <[dep] multi-file linking.
        • Executable Modules & Expressions: Every .math file runs top-to-bottom on its own with no entry point required—mixing ${'$'}type structs, @dim units, overloaded functions/operators, module variables, and top-level expressions. Supports implicit multiplication (2x, 2π), ranges (1...10, 1..<10, by), comprehensions ([x^2 for x in 1...8]), lambdas ((x) => x^2), pipes (x |> f), matrices ([1, 2; 3, 4]), and SI unit conversions (25 km into m).
        • Privacy, Scoping & Mutability: Declarations default to pub; prefixing with priv encapsulates variables, constants, functions, or types within the declaring module. 3-tier lexical scoping: local { ... } blocks/params shadow current-module variables, which shadow imported public variables. Mutability: ':=' or 'mut' declares mutable variables, while 'const' (or '=' on a new module binding) creates immutable constants that reject reassignment and in-place mutation.
        • Symbolic & Numerical Calculus: sym_diff, sym_integ, sym_solve, sym_simplify, sym_taylor, diff, integrate, lim, sigma, prod, solve, fmin, fmax, ode_rk4.
        • Standard Library (18 Modules): [boot_syntax], [boot_types], [boot_control], [arith], [trig], [complex_ext], [vec], [mat], [algebra], [calculus], [units], [conversions], [physics], [chemistry], [biology], [geom_ext], [string].
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

fun MainActivity.dismissAboutInfoWindow() {
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
