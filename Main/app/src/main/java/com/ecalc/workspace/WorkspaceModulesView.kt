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
                    "📋 Output & Vars — inspect bound module variables & constants, formatted tables (\$map / JSON / CSV / \$mat / \$vec), console output, and the statement log."
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

            // Bottom action bar in Editor: [▶ Run / Link] | [📈 Plot] | [⚙ CAS]
            // (Redundant "📋 Out" and individual file reset "↺" removed)
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
                layoutParams = LinearLayout.LayoutParams(dp(76f), dp(38f)).apply { marginEnd = dp(4f) }
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
                layoutParams = LinearLayout.LayoutParams(dp(72f), dp(38f))
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

            btnResetActiveModule = null
            btnModuleViewLog = null

            execRow.addView(btnCompileModule)
            execRow.addView(btnPlotModule)
            execRow.addView(btnBytecodeModule)
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

            // Bottom action bar in Output & Vars: [📈 Plot] | [⚙ CAS] | [Clear]
            // (Redundant "📝 Edit" button removed)
            val mLogActions = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            btnModuleBackToEditor = null
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
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.0f).apply { marginEnd = dp(4f) }
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
                layoutParams = LinearLayout.LayoutParams(dp(84f), dp(38f))
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
    MathEngineManager.ensureDefaultProject1Seeded(ctx)

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
