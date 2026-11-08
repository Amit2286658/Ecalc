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

internal fun MathWorkspaceFragment.buildModulesSection(
    ctx: Context,
    activity: MainActivity,
    dp: (Float) -> Int
): LinearLayout {
    val dark = activity.isDarkMode
    val primaryText = Color.parseColor(if (dark) "#E0E0E0" else "#333333")
    val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")
    val accentText = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        // Row 1: Compact Horizontal scroll bar for Open Module chips
        val modScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34f)
            ).apply { bottomMargin = dp(4f) }
        }
        moduleChipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), 0, dp(2f), 0)
        }
        modScroll.addView(moduleChipsRow)
        addView(modScroll)

        // Row 2: Inner Sub-Tab Bar: [📝 Editor] | [📋 Output & Vars]
        val subRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4f) }
        }
        moduleSubTabBar = subRow

        btnModuleSubEditor = NeumorphicKeyView(ctx).apply {
            text = "📝 Editor"
            textSize = 12f
            cornerRadiusDp = 12f
            shadowPadDp = 2.8f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginEnd = dp(4f) }
            setOnClickListener {
                if (!isModuleEditorSubTab) {
                    isModuleEditorSubTab = true
                    updateModuleSubTabVisibility(animate = true)
                } else {
                    springPulse(this, 0.06f)
                }
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(
                    this,
                    "📝 Editor — edit the active .math module with live syntax highlighting, autocomplete suggestions, and error line markers."
                ) else activity.dismissButtonPopup()
            }
        }
        btnModuleSubLog = NeumorphicKeyView(ctx).apply {
            text = "📋 Output & Vars"
            textSize = 12f
            cornerRadiusDp = 12f
            shadowPadDp = 2.8f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginStart = dp(4f) }
            setOnClickListener {
                if (isModuleEditorSubTab) {
                    saveCurrentModuleEditorState(showToast = false)
                    isModuleEditorSubTab = false
                    updateModuleSubTabVisibility(animate = true)
                } else {
                    springPulse(this, 0.06f)
                }
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(
                    this,
                    "📋 Output & Vars — inspect bound module variables & constants, formatted tables ($map / JSON / CSV / $mat / $vec), console output, and the statement log."
                ) else activity.dismissButtonPopup()
            }
        }
        subRow.addView(btnModuleSubEditor)
        subRow.addView(btnModuleSubLog)
        addView(subRow)

        // --- Sub-Pane A: Maximized .math Module Editor Pane (with Gutter, Syntax Highlighting & Symbol Chips) ---
        moduleEditorPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            tvActiveModuleName = TextView(ctx).apply {
                text = "Active Module: ${activeModuleFileName ?: "None open"}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(accentText)
                setPadding(dp(4f), 0, dp(4f), dp(3f))
            }
            addView(tvActiveModuleName)

            etModuleEditor = MathCodeEditText(ctx).apply {
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply { bottomMargin = dp(4f) }
                setTextColor(primaryText)
                setHintTextColor(secondaryText)
                hint = "# Top-to-bottom executable .math module ([mod], <[dep], pub/priv vars & fns, or @entry/main for Project Run)..."
            }
            addView(etModuleEditor)

            // Quick-Insert .math Symbol, Operator & Snippet Chips Bar
            val symScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(31f)
                ).apply { bottomMargin = dp(4f) }
            }
            val sRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(2f), 0)
            }
            symbolChipsRow = sRow
            symbolChipViews.clear()
            val snippets = listOf(
                Triple("pub main", "pub main() = {\n    \n}", 2),
                Triple("@entry", "@entry(main)\n", 0),
                Triple(":=", " := ", 0),
                Triple("=>", " => ", 0),
                Triple("->", " -> ", 0),
                Triple("::", "::", 0),
                Triple("..<", "..<", 0),
                Triple("...", "...", 0),
                Triple("??", " ?? {\n    \n}", 2),
                Triple("|>", " |> ", 0),
                Triple("\"{}\"", "\"{}\"", 2),
                Triple("\$\"\"", "\$\"{}\"", 2),
                Triple("struct", "struct Point(x: real, y: real)", 0),
                Triple("into", " into ", 0),
                Triple("switch", "switch (x) {\n    case 0: 0\n    else: 1\n}", 0),
                Triple("try/catch", "try {\n    \n} catch (e) {\n    e\n}", 18),
                Triple("println", "println(\"\")", 2),
                Triple("read_json", "read_json(\"config.json\")", 0),
                Triple("csv_maps", "csv_to_maps(read_file(\"sensor_data.csv\"))", 0),
                Triple("π", "π", 0),
                Triple("√(", "√()", 1)
            )
            for ((label, insText, backOff) in snippets) {
                val chipW = (label.length * 7.5f + 22f).coerceAtLeast(42f)
                val sChip = NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = 10.5f
                    cornerRadiusDp = 9.5f
                    shadowPadDp = 2.0f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(chipW), dp(27f)).apply {
                        marginEnd = dp(4f)
                    }
                    setOnClickListener {
                        springPulse(this, 0.06f)
                        etModuleEditor?.insertSnippet(insText, backOff)
                    }
                }
                symbolChipViews.add(sChip)
                sRow.addView(sChip)
            }
            symScroll.addView(sRow)
            addView(symScroll)

            val execRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4f) }
            }

            btnCompileModule = NeumorphicKeyView(ctx).apply {
                text = "▶ Run / Link"
                textSize = 11.5f
                cornerRadiusDp = 13f
                shadowPadDp = 3.0f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.35f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    runCurrentModuleFile(switchToLogAfterRun = false)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "▶ Run / Link — executes this individual .math module file strictly top-to-bottom on its own " +
                            "(resolving any <[dep] sibling project modules). " +
                            "To run a project's main() / @entry function across files, use '▶ Run' in ☰ Project Options."
                    ) else activity.dismissButtonPopup()
                }
            }

            btnPlotModule = NeumorphicKeyView(ctx).apply {
                text = "📈 Plot"
                textSize = 11f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(64f), dp(38f)).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    showFrostedPlotterModal()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "📈 Plot — opens the Interactive 2D Plotter for functions f(x), vectors, and parametric paths in the active module."
                    ) else activity.dismissButtonPopup()
                }
            }

            btnBytecodeModule = NeumorphicKeyView(ctx).apply {
                text = "⚙ CAS"
                textSize = 11f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(56f), dp(38f)).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    showFrostedBytecodeDisasmModal()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "⚙ CAS — opens the Symbolic CAS & Expression Inspector (sym_simplify, sym_diff, sym_integ, sym_taylor, sym_solve) for functions or expressions in the active module."
                    ) else activity.dismissButtonPopup()
                }
            }

            btnResetActiveModule = NeumorphicKeyView(ctx).apply {
                text = "↺"
                textSize = 12f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(42f), dp(38f)).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    resetActiveModuleState()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "↺ Reset Module — clears runtime variables and constants bound in the active module."
                    ) else activity.dismissButtonPopup()
                }
            }

            btnModuleViewLog = NeumorphicKeyView(ctx).apply {
                text = "📋 Out"
                textSize = 11.2f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(66f), dp(38f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    saveCurrentModuleEditorState(showToast = false)
                    isModuleEditorSubTab = false
                    updateModuleSubTabVisibility(animate = true)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "📋 Out — switches to the Output, Variables & Table Inspector tab."
                    ) else activity.dismissButtonPopup()
                }
            }

            execRow.addView(btnCompileModule)
            execRow.addView(btnPlotModule)
            execRow.addView(btnBytecodeModule)
            execRow.addView(btnResetActiveModule)
            execRow.addView(btnModuleViewLog)
            addView(execRow)

            moduleQuickStatusCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val modFile = activeModuleFileName
                    val errInfo = modFile?.let { moduleErrorLines[projectScopedKey(it)] }
                    if (errInfo != null) {
                        etModuleEditor?.highlightErrorLine(errInfo.first, errInfo.second)
                    } else {
                        saveCurrentModuleEditorState(showToast = false)
                        isModuleEditorSubTab = false
                        updateModuleSubTabVisibility(animate = true)
                    }
                }
            }
            tvModuleQuickStatus = TextView(ctx).apply {
                text = "Tap '▶ Run / Link' to run this file top-to-bottom, or '▶ Run' in ☰ Project Options to run main/@entry."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                setTextColor(secondaryText)
                maxLines = 2
            }
            moduleQuickStatusCard?.addView(tvModuleQuickStatus)
            addView(moduleQuickStatusCard)
        }
        addView(moduleEditorPane)

        // --- Sub-Pane B: Structured Output, Console Stream, Table Inspector & Variables ---
        moduleLogPane = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            val outOuterScroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply { bottomMargin = dp(6f) }
            }
            val outStack = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            // 1. Bound Module Variables & Constants Card
            val vCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            }
            varsCard = vCard

            tvVarsHeader = TextView(ctx).apply {
                text = "Module Variables & Constants (pub/priv • mut/const)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accentText)
            }
            tvVarsContent = TextView(ctx).apply {
                text = "No variables bound yet. Tap '▶ Run / Link' in the Editor tab."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.MONOSPACE
                setTextColor(primaryText)
                setPadding(0, dp(4f), 0, 0)
                setTextIsSelectable(true)
            }
            vCard.addView(tvVarsHeader)
            vCard.addView(tvVarsContent)
            outStack.addView(vCard)

            // 2. Interactive $map / JSON / 2D $mat / vec<map> Table & Tree Inspector Card
            val inspCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            }
            inspectorCard = inspCard

            tvInspectorHeader = TextView(ctx).apply {
                text = "🔍 Structured Data Inspector (\$map • JSON • CSV • \$mat • \$vec)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accentText)
            }
            inspCard.addView(tvInspectorHeader)

            val inspChipsScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4f); bottomMargin = dp(4f) }
            }
            inspectorChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            inspChipsScroll.addView(inspectorChipsRow)
            inspCard.addView(inspChipsScroll)

            inspectorTableContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val tvInspPlaceholder = TextView(ctx).apply {
                text = "Evaluate a \$map, JSON object/array, csv_to_maps(...), \$mat, or \$vec to inspect it as a formatted table."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
                typeface = Typeface.MONOSPACE
                setTextColor(secondaryText)
            }
            inspectorTableContainer?.addView(tvInspPlaceholder)
            inspCard.addView(inspectorTableContainer)
            // inspCard added after mLogCard

            // 3. Dedicated Console Stream (print / println) Card
            val cCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            }
            consoleCard = cCard

            val cHdrRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            tvConsoleHeader = TextView(ctx).apply {
                text = "🖥 Console Stream (print / println)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accentText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            btnClearConsole = NeumorphicKeyView(ctx).apply {
                text = "Clear"
                textSize = 10f
                cornerRadiusDp = 9f
                shadowPadDp = 2.0f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(56f), dp(26f))
                setOnClickListener {
                    springPulse(this, 0.06f)
                    val key = projectScopedKey(activeModuleFileName ?: "default")
                    moduleConsoleLogs[key] = "(Console cleared)"
                    tvConsoleOutput?.text = "(Console cleared)"
                }
            }
            cHdrRow.addView(tvConsoleHeader)
            cHdrRow.addView(btnClearConsole)
            cCard.addView(cHdrRow)

            tvConsoleOutput = TextView(ctx).apply {
                text = "(No print / println output yet)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
                typeface = Typeface.MONOSPACE
                setTextColor(primaryText)
                setPadding(0, dp(4f), 0, 0)
                setTextIsSelectable(true)
            }
            cCard.addView(tvConsoleOutput)
            outStack.addView(cCard)

            // 4. Statement Return Values & Link Log Card
            val mLogCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            }
            moduleLogCard = mLogCard

            tvModuleLogOutput = TextView(ctx).apply {
                text = "=== Module Execution & Link Log ===\nTap '▶ Run / Link' in the Editor tab to execute the active .math module top-to-bottom."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
                typeface = Typeface.MONOSPACE
                setTextColor(primaryText)
                setTextIsSelectable(true)
            }
            mLogCard.addView(tvModuleLogOutput)
            outStack.addView(mLogCard)
            outStack.addView(inspCard)

            outOuterScroll.addView(outStack)
            addView(outOuterScroll)

            val mLogActions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            btnModuleBackToEditor = NeumorphicKeyView(ctx).apply {
                text = "📝 Edit"
                textSize = 11.2f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.0f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    isModuleEditorSubTab = true
                    updateModuleSubTabVisibility(animate = true)
                }
            }
            btnOutputPlot = NeumorphicKeyView(ctx).apply {
                text = "📈 Plot"
                textSize = 11.2f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.0f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    showFrostedPlotterModal()
                }
            }
            btnOutputBytecode = NeumorphicKeyView(ctx).apply {
                text = "⚙ CAS"
                textSize = 11.2f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 0.9f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    showFrostedBytecodeDisasmModal()
                }
            }
            btnClearModuleLog = NeumorphicKeyView(ctx).apply {
                text = "Clear"
                textSize = 11.2f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(72f), dp(38f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    val fileLabel = activeModuleFileName ?: "default"
                    val key = projectScopedKey(fileLabel)
                    val cleared = "=== Module Execution & Link Log ($activeProjectName/$fileLabel) ===\n(Log cleared)"
                    moduleLogs[key] = cleared
                    tvModuleLogOutput?.text = cleared
                    moduleLogCard?.let { springPulse(it, 0.04f) }
                }
            }
            mLogActions.addView(btnModuleBackToEditor)
            mLogActions.addView(btnOutputPlot)
            mLogActions.addView(btnOutputBytecode)
            mLogActions.addView(btnClearModuleLog)
            addView(mLogActions)
        }
        addView(moduleLogPane)
    }
}

// =========================================================================
//  Unified Project .math Module File System & Migration Helpers
// =========================================================================

internal fun MathWorkspaceFragment.legacyMathDir(ctx: Context): File =
    File(ctx.filesDir, "math_programs").also { if (!it.exists()) it.mkdirs() }

internal fun MathWorkspaceFragment.legacySessionsRootDir(ctx: Context): File =
    File(ctx.filesDir, "math_sessions")

internal fun MathWorkspaceFragment.legacyModulesDir(ctx: Context): File =
    File(ctx.filesDir, "math_modules")

internal fun MathWorkspaceFragment.modulesDir(ctx: Context, projectName: String = activeProjectName): File =
    MathEngineManager.ensureProjectSetup(ctx, projectName)

internal fun MathWorkspaceFragment.listModuleFiles(ctx: Context, projectName: String = activeProjectName): List<File> =
    modulesDir(ctx, projectName).listFiles { f -> f.isFile && f.name.endsWith(".math") }
        ?.sortedBy { it.name }
        ?: emptyList()

internal fun MathWorkspaceFragment.projectScopedKey(fileName: String, projectName: String = activeProjectName): String =
    "$projectName::$fileName"

internal fun MathWorkspaceFragment.collectAllProjectModuleSources(
    ctx: Context,
    activeFileOverride: String?,
    activeCodeOverride: String?,
    projectName: String = activeProjectName
): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    listModuleFiles(ctx, projectName).forEach { file ->
        val src = if (projectName == activeProjectName && file.name == activeFileOverride && activeCodeOverride != null) {
            activeCodeOverride
        } else {
            runCatching { file.readText() }.getOrDefault("")
        }
        map[file.name] = src
    }
    if (projectName == activeProjectName && activeFileOverride != null && activeCodeOverride != null && !map.containsKey(activeFileOverride)) {
        map[activeFileOverride] = activeCodeOverride
    }
    return map
}

internal fun MathWorkspaceFragment.ensureSampleAndMigratedModuleFiles(ctx: Context) {
    activeProjectName = MathEngineManager.DEFAULT_PROJECT_NAME
    MathEngineManager.activeProjectName = activeProjectName

    val legDir = legacyMathDir(ctx)
    val legModDir = legacyModulesDir(ctx)
    val modDir = modulesDir(ctx, MathEngineManager.DEFAULT_PROJECT_NAME)
    val sessRoot = legacySessionsRootDir(ctx)

    File(legDir, "trig_ext.math").takeIf { it.exists() }?.delete()
    File(legModDir, "trig_ext.math").takeIf { it.exists() }?.delete()
    File(modDir, "trig_ext.math").takeIf { it.exists() }?.delete()

    // Ensure project0 is reserved cleanly for Basic Calculator; migrate any legacy studio .math files from project0 into project1
    val calcProj0Dir = File(File(ctx.filesDir, "math_projects"), MathEngineManager.CALC_PROJECT_NAME)
    if (calcProj0Dir.exists() && calcProj0Dir.isDirectory) {
        calcProj0Dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".math") || f.name.endsWith(".json") || f.name.endsWith(".csv"))
        }?.forEach { file ->
            val dest = File(modDir, file.name)
            if (!dest.exists()) {
                runCatching { file.copyTo(dest, overwrite = false) }
            }
            file.delete()
        }
    }

    // Migrate any legacy files from math_modules/, math_programs/, or math_sessions/ into default studio project1
    if (legModDir.exists() && legModDir.isDirectory) {
        legModDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".math") || f.name.endsWith(".json") || f.name.endsWith(".csv"))
        }?.forEach { file ->
            val dest = File(modDir, file.name)
            if (!dest.exists()) {
                runCatching { file.copyTo(dest, overwrite = false) }
            }
            file.delete()
        }
    }
    legDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { file ->
        val dest = File(modDir, file.name)
        if (!dest.exists()) {
            runCatching { dest.writeText(file.readText()) }
        }
        file.delete()
    }
    if (sessRoot.exists() && sessRoot.isDirectory) {
        sessRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".math") }
            .forEach { file ->
                val dest = File(modDir, file.name)
                if (!dest.exists()) {
                    runCatching { dest.writeText(file.readText()) }
                }
                file.delete()
            }
    }

    val seedMarker = File(modDir, ".seeded_unified_modules_v9")
    if (!seedMarker.exists()) {
        File(modDir, "config.json").writeText(
            "{\n  \"project\": \"MEP Telemetry\",\n  \"sample_hz\": 50,\n  \"active\": true\n}\n"
        )
        File(modDir, "sensor_data.csv").writeText(
            "sensor, temp_c, status\nCore_A, 42.5, nominal\nCore_B, 48.0, warm\nCore_C, 39.2, nominal\n"
        )
        File(modDir, "data_io_json.math").writeText(
            "# Sandboxed File I/O, RFC 8259 JSON, CSV & Console Stream (.math)\n\n" +
            "[data_io_json]\n" +
            "<[string]\n" +
            "<[vec]\n\n" +
            "pub cfg := read_json(\"config.json\")\n" +
            "pub records := csv_to_maps(read_file(\"sensor_data.csv\"))\n\n" +
            "println(\$\"Loaded project: {cfg[\"project\"]} @ {cfg[\"sample_hz\"]} Hz\")\n" +
            "println(\$\"CSV rows loaded: {records.len()} (first={records[0][\"sensor\"]})\")\n\n" +
            "pub avg_temp := (json_parse(records[0][\"temp_c\"]) + json_parse(records[1][\"temp_c\"]) + json_parse(records[2][\"temp_c\"])) / 3\n" +
            "records\n"
        )
        File(modDir, "custom_ops.math").writeText(
            "# Unified Library & Executable Module: custom_ops.math\n" +
            "# Imported via <[custom_ops] in declaration-only mode (top-level expressions skipped on import).\n\n" +
            "[custom_ops]\n" +
            "<[arith]\n" +
            "<[trig]\n\n" +
            "priv const DEG_SCALE = π / 180\n" +
            "priv deg_to_r(x: real) = x * DEG_SCALE\n\n" +
            "pub const G_STD = 9.80665\n" +
            "pub hypot3(a: real, b: real, c: real) = √(a^2 + b^2 + c^2)\n" +
            "pub kinetic_e(m: real, v: real) = 0.5 * m * v^2\n" +
            "pub deg_sin(x: real) = sin(deg_to_r(x))\n\n" +
            "# Top-level self-test expression (runs when custom_ops.math is executed top-to-bottom):\n" +
            "hypot3(3, 4, 12)\n"
        )
        File(modDir, "physics_sim.math").writeText(
            "# Multi-File Project Module with Project Entry Point: physics_sim.math\n" +
            "# • Running this file ('▶ Run / Link') executes declarations top-to-bottom.\n" +
            "# • Running the Project ('▶ Run' in ☰ Project Options) executes pub main().\n\n" +
            "[physics_sim]\n" +
            "<[custom_ops]\n" +
            "<[units]\n" +
            "<[conversions]\n\n" +
            "pub v0 := 25.0\n" +
            "pub angle := 45.0\n" +
            "priv const g = G_STD\n\n" +
            "pub range_m := (v0^2 * deg_sin(2 * angle)) / g\n" +
            "pub range_km := (range_m * 1m) into km\n" +
            "pub max_h := (v0 * deg_sin(angle))^2 / (2 * g)\n" +
            "pub flight_t := (2 * v0 * deg_sin(angle)) / g\n\n" +
            "# Reusable module function:\n" +
            "pub height_at(t: real) = v0 * deg_sin(angle) * t - 0.5 * g * t^2\n\n" +
            "# Project entry point (invoked via '▶ Run' in ☰ Project Options):\n" +
            "pub main() = {\n" +
            "    println(\$\"Projectile range={range_m} m, max_h={max_h} m\")\n" +
            "    height_at(1.8)\n" +
            "}\n"
        )
        File(modDir, "vectors_stats.math").writeText(
            "# Vectors, Scoping, Immutability & Symbolic Math (.math)\n\n" +
            "[vectors_stats]\n" +
            "<[vec]\n" +
            "<[calculus]\n\n" +
            "const squares = [x^2 for x in 1...8]\n" +
            "pub total := sum(squares)\n" +
            "pub avg := mean(squares)\n" +
            "pub std_val := stddev(squares)\n\n" +
            "# 3-tier lexical scoping: local { ... } := shadows module 'total'\n" +
            "local_check := { total := 999 ; total } + total\n\n" +
            "# Symbolic differentiation & equation solving:\n" +
            "pub deriv_expr = sym_diff(x, x^3 - 6*x^2 + 11*x - 6)\n" +
            "pub roots = sym_solve(x, x^2 - 5*x + 6 == 0)\n" +
            "roots\n"
        )
        File(modDir, "custom_module.math").takeIf { it.exists() }?.delete()
        runCatching { seedMarker.writeText("ok") }
    } else {
        // Auto-upgrade any unmodified legacy data_io_json.math that divided raw CSV strings
        val dataIoFile = File(modDir, "data_io_json.math")
        if (dataIoFile.isFile) {
            val txt = runCatching { dataIoFile.readText() }.getOrDefault("")
            if (txt.contains("(records[0][\"temp_c\"] + records[1][\"temp_c\"] + records[2][\"temp_c\"]) / 3")) {
                runCatching {
                    dataIoFile.writeText(
                        txt.replace(
                            "(records[0][\"temp_c\"] + records[1][\"temp_c\"] + records[2][\"temp_c\"]) / 3",
                            "(json_parse(records[0][\"temp_c\"]) + json_parse(records[1][\"temp_c\"]) + json_parse(records[2][\"temp_c\"])) / 3"
                        )
                    )
                }
            }
        }
    }

    val availableMods = listModuleFiles(ctx)
    if (openModuleFiles.isEmpty() && availableMods.isNotEmpty()) {
        availableMods.forEach { f ->
            if (f.name !in openModuleFiles) openModuleFiles.add(f.name)
        }
        activeModuleFileName = if ("physics_sim.math" in openModuleFiles) {
            "physics_sim.math"
        } else {
            openModuleFiles.first()
        }
    } else if (availableMods.isEmpty()) {
        activeModuleFileName = null
    }
}

// =========================================================================
//  Module Tabs, Editor, Individual Top-to-Bottom Run & Project Entry Run
// =========================================================================
