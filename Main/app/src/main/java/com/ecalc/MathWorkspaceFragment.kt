package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.File
import kotlin.math.hypot

/**
 * Free-form `.math` programming & interactive session workspace.
 * Revealed by swiping left on the main calculator UI; swiping right returns
 * to the calculator. Uses the same damped-spring physics (e^-7.5t * sin(22t))
 * across all buttons, sub-tabs, session chips, and output views.
 */
class MathWorkspaceFragment : Fragment() {

    private var rootLayout: LinearLayout? = null
    private var topHeaderBar: LinearLayout? = null
    private var tvWorkspaceTitle: TextView? = null
    private var btnBackCalc: NeumorphicKeyView? = null

    private var modeToggleBar: LinearLayout? = null
    private var btnModeFiles: NeumorphicKeyView? = null
    private var btnModeSessions: NeumorphicKeyView? = null

    // --- File Manager & Program Editor section ---
    private var filesSection: LinearLayout? = null
    private var fileChipsRow: LinearLayout? = null
    private var btnNewFile: NeumorphicKeyView? = null
    private var btnSaveFile: NeumorphicKeyView? = null
    private var btnDeleteFile: NeumorphicKeyView? = null
    private var btnImportFile: NeumorphicKeyView? = null
    private var btnExportFile: NeumorphicKeyView? = null
    private var tvActiveFileName: TextView? = null
    private var etCodeEditor: EditText? = null
    private var btnRunScript: NeumorphicKeyView? = null
    private var btnLoadModule: NeumorphicKeyView? = null
    private var btnClearOutput: NeumorphicKeyView? = null
    private var outputCard: LinearLayout? = null
    private var tvProgramOutput: TextView? = null

    // --- Interactive Sessions section ---
    private var sessionsSection: LinearLayout? = null
    private var sessionChipsRow: LinearLayout? = null
    private var btnNewSession: NeumorphicKeyView? = null
    private var btnResetSession: NeumorphicKeyView? = null
    private var btnCloseSession: NeumorphicKeyView? = null
    private var btnExportSnap: NeumorphicKeyView? = null
    private var btnRestoreSnap: NeumorphicKeyView? = null
    private var varsCard: LinearLayout? = null
    private var tvVarsHeader: TextView? = null
    private var tvVarsContent: TextView? = null
    private var replHistoryCard: LinearLayout? = null
    private var replHistoryLayout: LinearLayout? = null
    private var replHistoryScroll: ScrollView? = null
    private var etReplInput: EditText? = null
    private var btnEvalRepl: NeumorphicKeyView? = null

    private var isFilesMode: Boolean = true
    private var activeFileName: String = "physics_sim.math"
    private var activeSessionId: Int = -1
    private val openSessionIds = mutableListOf<Int>()
    private val sessionLogs = mutableMapOf<Int, MutableList<Pair<String, String>>>()
    private var exportedSnapshotCache: String = ""

    private val wobbleAnimators = mutableListOf<ValueAnimator>()
    private val springAnimators = mutableListOf<Pair<View, ValueAnimator>>()

    /** SAF Launcher to load/import a `.math` script from device storage. */
    private val importScriptLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportedUri(uri)
        }
    }

    /** SAF Launcher to export/save the active `.math` script to device storage. */
    private val exportScriptLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            handleExportedUri(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val activity = requireActivity() as MainActivity
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        ensureSampleMathFiles(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(14f), dp(42f), dp(14f), dp(12f))
        }
        rootLayout = root

        // 1. Top Header Bar: "← Calc" button + Title
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        topHeaderBar = headerRow

        btnBackCalc = NeumorphicKeyView(ctx).apply {
            text = "← Calc"
            textSize = 13f
            cornerRadiusDp = 16f
            shadowPadDp = 3f
            isAccentKey = true
            isDarkThemeMode = activity.isDarkMode
            layoutParams = LinearLayout.LayoutParams(dp(82f), dp(42f)).apply {
                marginEnd = dp(10f)
            }
            setOnClickListener {
                springPulse(this, 0.07f)
                activity.switchWorkspacePage(0)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(this, "Return to calculator (or swipe right)")
                else activity.dismissButtonPopup()
            }
        }
        headerRow.addView(btnBackCalc)

        tvWorkspaceTitle = TextView(ctx).apply {
            text = ".math Studio & Sessions"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvWorkspaceTitle)

        // 2. Mode Switcher Bar: [.math Programs & Files] | [Live Sessions]
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        modeToggleBar = modeRow

        btnModeFiles = NeumorphicKeyView(ctx).apply {
            text = "📁 .math Files"
            textSize = 13.5f
            cornerRadiusDp = 14f
            shadowPadDp = 3.5f
            isAccentKey = true
            isDarkThemeMode = activity.isDarkMode
            layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f).apply { marginEnd = dp(4f) }
            setOnClickListener {
                if (!isFilesMode) {
                    isFilesMode = true
                    updateModeVisibility(animate = true)
                } else {
                    springPulse(this, 0.06f)
                }
            }
        }
        btnModeSessions = NeumorphicKeyView(ctx).apply {
            text = "⚡ Sessions"
            textSize = 13.5f
            cornerRadiusDp = 14f
            shadowPadDp = 3.5f
            isAccentKey = false
            isDarkThemeMode = activity.isDarkMode
            layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f).apply { marginStart = dp(4f) }
            setOnClickListener {
                if (isFilesMode) {
                    isFilesMode = false
                    updateModeVisibility(animate = true)
                } else {
                    springPulse(this, 0.06f)
                }
            }
        }
        modeRow.addView(btnModeFiles)
        modeRow.addView(btnModeSessions)

        root.addView(headerRow)
        root.addView(modeRow)

        // 3. Build Files & Editor Section
        filesSection = buildFilesSection(ctx, activity, dp)
        root.addView(filesSection)

        // 4. Build Interactive Sessions Section
        sessionsSection = buildSessionsSection(ctx, activity, dp)
        root.addView(sessionsSection)

        applyTheme(activity.isDarkMode)
        refreshFileChips()
        loadFileIntoEditor(activeFileName, animate = false)
        ensureDefaultSession()
        updateModeVisibility(animate = false)

        return root
    }

    private fun buildFilesSection(
        ctx: Context,
        activity: MainActivity,
        dp: (Float) -> Int
    ): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            // Row 1: Dedicated full-width horizontal scroll bar for .math file tabs
            val fileScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44f)
                ).apply { bottomMargin = dp(4f) }
            }
            fileChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(2f), 0)
            }
            fileScroll.addView(fileChipsRow)
            addView(fileScroll)

            // Row 2: Action toolbar (+ New | Save | Del | Load Ext | Export)
            val fileActionsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }

            fun makeActionBtn(label: String, accent: Boolean, weight: Float = 1f): NeumorphicKeyView {
                return NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = 11.5f
                    cornerRadiusDp = 12f
                    shadowPadDp = 3f
                    isAccentKey = accent
                    isDarkThemeMode = activity.isDarkMode
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), weight).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

            btnNewFile = makeActionBtn("+ New", true, 1f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptNewFileDialog()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Create a new .math script")
                    else activity.dismissButtonPopup()
                }
            }
            btnSaveFile = makeActionBtn("Save", false, 0.9f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    saveCurrentFile(showToast = true)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Save changes to current .math file")
                    else activity.dismissButtonPopup()
                }
            }
            btnDeleteFile = makeActionBtn("Del", false, 0.85f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    deleteCurrentFile()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Delete current .math script")
                    else activity.dismissButtonPopup()
                }
            }
            btnImportFile = makeActionBtn("📂 Load", true, 1.1f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchImportFromStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Load a .math script from device storage")
                    else activity.dismissButtonPopup()
                }
            }
            btnExportFile = makeActionBtn("💾 Export", true, 1.15f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchExportToStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Export current .math script to device storage")
                    else activity.dismissButtonPopup()
                }
            }

            fileActionsRow.addView(btnNewFile)
            fileActionsRow.addView(btnSaveFile)
            fileActionsRow.addView(btnDeleteFile)
            fileActionsRow.addView(btnImportFile)
            fileActionsRow.addView(btnExportFile)
            addView(fileActionsRow)

            // Active file label
            tvActiveFileName = TextView(ctx).apply {
                text = "Editing: $activeFileName"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(4f), 0, dp(4f), dp(4f))
            }
            addView(tvActiveFileName)

            // Multi-line .math code editor
            etCodeEditor = EditText(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    0.56f
                ).apply { bottomMargin = dp(6f) }
                gravity = Gravity.TOP or Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                hint = "# Write .math expressions, functions, or [module] definitions..."
            }
            addView(etCodeEditor)

            // Execution controls: [▶ Run Script] [⚙ Load .math] [Clear]
            val execRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }

            btnRunScript = NeumorphicKeyView(ctx).apply {
                text = "▶ Run Script"
                textSize = 13f
                cornerRadiusDp = 14f
                shadowPadDp = 3.5f
                isAccentKey = true
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1.15f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    runCurrentProgramInSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Run Script — evaluate all statements sequentially in the active session"
                    ) else activity.dismissButtonPopup()
                }
            }

            btnLoadModule = NeumorphicKeyView(ctx).apply {
                text = "⚙ Load .math"
                textSize = 13f
                cornerRadiusDp = 14f
                shadowPadDp = 3.5f
                isAccentKey = true
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1.15f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    compileAndLoadExtension()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Load .math — compile [module] extension into native .mathc and link into engine"
                    ) else activity.dismissButtonPopup()
                }
            }

            btnClearOutput = NeumorphicKeyView(ctx).apply {
                text = "Clear"
                textSize = 12f
                cornerRadiusDp = 14f
                shadowPadDp = 3.5f
                isAccentKey = false
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(dp(68f), dp(44f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    tvProgramOutput?.text = "Ready. Tap '▶ Run Script' to execute statements or '⚙ Load .math' to compile a module."
                    outputCard?.let { springPulse(it, 0.04f) }
                }
            }

            execRow.addView(btnRunScript)
            execRow.addView(btnLoadModule)
            execRow.addView(btnClearOutput)
            addView(execRow)

            // Output console card
            val outCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    0.44f
                )
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            }
            outputCard = outCard

            val outScroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            tvProgramOutput = TextView(ctx).apply {
                text = "Ready. Tap '▶ Run Script' to execute statements or '⚙ Load .math' to compile a module."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            outScroll.addView(tvProgramOutput)
            outCard.addView(outScroll)
            addView(outCard)
        }
    }

    private fun buildSessionsSection(
        ctx: Context,
        activity: MainActivity,
        dp: (Float) -> Int
    ): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            // Row 1: Full-width horizontal scroll of session chips
            val sessScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44f)
                ).apply { bottomMargin = dp(4f) }
            }
            sessionChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(2f), 0)
            }
            sessScroll.addView(sessionChipsRow)
            addView(sessScroll)

            // Row 2: Session actions (+ New | Reset | Close | Export | Restore)
            val sessBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }

            fun makeSessBtn(label: String, accent: Boolean, weight: Float = 1f): NeumorphicKeyView {
                return NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = 11.5f
                    cornerRadiusDp = 12f
                    shadowPadDp = 3f
                    isAccentKey = accent
                    isDarkThemeMode = activity.isDarkMode
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), weight).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

            btnNewSession = makeSessBtn("+ New", true, 0.95f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    openNewSession()
                }
            }
            btnResetSession = makeSessBtn("Reset", false, 0.9f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    resetActiveSession()
                }
            }
            btnCloseSession = makeSessBtn("Close", false, 0.9f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    closeActiveSession()
                }
            }
            btnExportSnap = makeSessBtn("📤 Snap", false, 1.1f).apply {
                setOnClickListener {
                    springPulse(this, 0.06f)
                    exportActiveSessionSnapshots()
                }
            }
            btnRestoreSnap = makeSessBtn("📥 Restore", false, 1.15f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.06f)
                    restoreSessionFromSnapshotDialog()
                }
            }

            sessBar.addView(btnNewSession)
            sessBar.addView(btnResetSession)
            sessBar.addView(btnCloseSession)
            sessBar.addView(btnExportSnap)
            sessBar.addView(btnRestoreSnap)
            addView(sessBar)

            // Live Session Variables inspector card
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
                text = "Active Session Variables (MSP1 State)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
            }
            tvVarsContent = TextView(ctx).apply {
                text = "No variables bound yet. Try: x := 42; f(t) := t^2 + 1"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(4f), 0, 0)
            }
            vCard.addView(tvVarsHeader)
            vCard.addView(tvVarsContent)
            addView(vCard)

            // REPL evaluation log
            val histCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply { bottomMargin = dp(6f) }
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            }
            replHistoryCard = histCard

            val hScroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            replHistoryScroll = hScroll
            replHistoryLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }
            hScroll.addView(replHistoryLayout)
            histCard.addView(hScroll)
            addView(histCard)

            // Bottom REPL input bar
            val inputRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            etReplInput = EditText(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(6f) }
                hint = "Evaluate in session (e.g. a := 10; a * 3)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = Typeface.MONOSPACE
                setSingleLine(true)
                setPadding(dp(12f), 0, dp(12f), 0)
            }
            btnEvalRepl = NeumorphicKeyView(ctx).apply {
                text = "Eval ↵"
                textSize = 13f
                cornerRadiusDp = 14f
                shadowPadDp = 3.5f
                isAccentKey = true
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(dp(78f), dp(44f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    evaluateReplLine()
                }
            }
            inputRow.addView(etReplInput)
            inputRow.addView(btnEvalRepl)
            addView(inputRow)
        }
    }

    private fun mathDir(ctx: Context): File =
        File(ctx.filesDir, "math_programs").also { if (!it.exists()) it.mkdirs() }

    private fun ensureSampleMathFiles(ctx: Context) {
        val dir = mathDir(ctx)
        // Remove the old exposed internal trig_ext.math file if present so internal
        // calculator functions are never exposed or accidentally edited by the user.
        val legacyInternal = File(dir, "trig_ext.math")
        if (legacyInternal.exists()) {
            legacyInternal.delete()
        }

        val progFile = File(dir, "physics_sim.math")
        if (!progFile.exists()) {
            progFile.writeText(
                "# Stateful Session Script (.math)\n" +
                "# Tap '▶ Run Script' to execute line-by-line in the active session!\n\n" +
                "v0 := 25.0\n" +
                "angle := 45.0\n" +
                "g := 9.80665\n\n" +
                "range_m := (v0^2 * sin(deg2rad(2 * angle))) / g\n" +
                "max_h := (v0 * sin(deg2rad(angle)))^2 / (2 * g)\n" +
                "flight_t := (2 * v0 * sin(deg2rad(angle))) / g\n\n" +
                "# Define a reusable session function:\n" +
                "height_at(t) := v0 * sin(deg2rad(angle)) * t - 0.5 * g * t^2\n" +
                "height_at(1.8)\n"
            )
        }

        val vecFile = File(dir, "vectors_stats.math")
        if (!vecFile.exists()) {
            vecFile.writeText(
                "# Vectors, Comprehensions & Symbolic Math (.math)\n" +
                "# Tap '▶ Run Script' to run in the active session!\n\n" +
                "squares := [x^2 for x in 1...8]\n" +
                "total := sum(squares)\n" +
                "avg := mean(squares)\n" +
                "std_val := stddev(squares)\n\n" +
                "# Symbolic differentiation & equation solving:\n" +
                "deriv_expr := sym_diff(x, x^3 - 6*x^2 + 11*x - 6)\n" +
                "roots := sym_solve(x, x^2 - 5*x + 6 = 0)\n" +
                "roots\n"
            )
        }

        val customModFile = File(dir, "custom_module.math")
        if (!customModFile.exists()) {
            customModFile.writeText(
                "# Custom Native Extension Module (.math)\n" +
                "# Tap '⚙ Load .math' to compile into native .mathc and link into the engine.\n" +
                "# Note: Each declaration in a [module] ends with a semicolon (;).\n\n" +
                "[custom_ops]\n" +
                "<[arith]\n" +
                "<[trig]\n\n" +
                " hypot3(a: real, b: real, c: real) = sqrt(a^2 + b^2 + c^2);\n" +
                " kinetic_e(m: real, v: real) = 0.5 * m * v^2;\n" +
                " deg_sin(x: real) = sin(deg2rad(x));\n"
            )
        }

        val available = dir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (available.isNotEmpty() && available.none { it.name == activeFileName }) {
            activeFileName = available.first().name
        }
    }

    private fun refreshFileChips() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val row = fileChipsRow ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        row.removeAllViews()
        val files = mathDir(ctx).listFiles { f -> f.name.endsWith(".math") }
            ?.sortedBy { it.name } ?: emptyList()

        files.forEach { file ->
            val isSelected = file.name == activeFileName
            val chip = NeumorphicKeyView(ctx).apply {
                text = file.name
                textSize = 12f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = isSelected
                isDarkThemeMode = activity.isDarkMode
                val w = (file.name.length * 8.5f + 32f).coerceAtLeast(96f)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(40f)).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    saveCurrentFile(showToast = false)
                    activeFileName = file.name
                    refreshFileChips()
                    loadFileIntoEditor(file.name, animate = true)
                }
            }
            row.addView(chip)
        }
    }

    private fun loadFileIntoEditor(fileName: String, animate: Boolean) {
        val ctx = context ?: return
        val file = File(mathDir(ctx), fileName)
        activeFileName = fileName
        tvActiveFileName?.text = "Editing: $fileName"
        if (file.exists()) {
            etCodeEditor?.setText(file.readText())
        } else {
            etCodeEditor?.setText("")
        }
        if (animate) {
            etCodeEditor?.let { springSlideIn(it) }
            tvActiveFileName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun saveCurrentFile(showToast: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val code = etCodeEditor?.text?.toString() ?: return
        val file = File(mathDir(ctx), activeFileName)
        file.writeText(code)
        if (showToast) {
            activity.showButtonToast("Saved $activeFileName")
            tvActiveFileName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun launchImportFromStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            saveCurrentFile(showToast = false)
            importScriptLauncher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open file picker: ${t.message}")
        }
    }

    private fun handleImportedUri(uri: Uri) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        try {
            var displayName = "imported.math"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    val rawName = cursor.getString(nameIndex)
                    if (!rawName.isNullOrBlank()) {
                        displayName = rawName.trim()
                    }
                }
            }
            if (!displayName.endsWith(".math")) {
                displayName = displayName.substringBeforeLast('.') + ".math"
            }
            val content = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: run {
                    activity.showButtonToast("Unable to read selected file")
                    return
                }
            val targetFile = File(mathDir(ctx), displayName)
            targetFile.writeText(content)
            activeFileName = displayName
            refreshFileChips()
            loadFileIntoEditor(displayName, animate = true)
            tvProgramOutput?.text = "📂 Loaded '$displayName' from device storage (${content.length} chars)."
            outputCard?.let { springPulse(it, 0.045f) }
            activity.showButtonToast("Loaded $displayName")
        } catch (t: Throwable) {
            activity.showButtonToast("Import failed: ${t.message}")
        }
    }

    private fun launchExportToStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            saveCurrentFile(showToast = false)
            exportScriptLauncher.launch(activeFileName)
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open save picker: ${t.message}")
        }
    }

    private fun handleExportedUri(uri: Uri) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        try {
            val code = etCodeEditor?.text?.toString() ?: ""
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(code)
            } ?: run {
                activity.showButtonToast("Unable to write to destination")
                return
            }
            tvProgramOutput?.text = "💾 Exported '$activeFileName' (${code.length} chars) to device storage."
            outputCard?.let { springPulse(it, 0.045f) }
            activity.showButtonToast("Exported $activeFileName")
        } catch (t: Throwable) {
            activity.showButtonToast("Export failed: ${t.message}")
        }
    }

    private fun promptNewFileDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val input = EditText(ctx).apply {
            hint = "program_name.math"
            setSingleLine(true)
        }
        AlertDialog.Builder(ctx)
            .setTitle("New .math Program")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (!name.endsWith(".math")) name += ".math"
                    saveCurrentFile(showToast = false)
                    val newFile = File(mathDir(ctx), name)
                    if (!newFile.exists()) {
                        newFile.writeText("# $name\n\nx := 10\nx * 2\n")
                    }
                    activeFileName = name
                    refreshFileChips()
                    loadFileIntoEditor(name, animate = true)
                    activity.showButtonToast("Created $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCurrentFile() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dir = mathDir(ctx)
        val files = dir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (files.size <= 1) {
            activity.showButtonToast("Cannot delete the last .math file")
            return
        }
        File(dir, activeFileName).delete()
        val remaining = dir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        activeFileName = remaining.first().name
        refreshFileChips()
        loadFileIntoEditor(activeFileName, animate = true)
        activity.showButtonToast("Deleted file")
    }

    private fun compileAndLoadExtension() {
        saveCurrentFile(showToast = false)
        val code = etCodeEditor?.text?.toString() ?: ""
        val activity = activity as? MainActivity ?: return
        MathEngineManager.compileAndLoadExtension(code) { ok, message ->
            activity.runOnUiThread {
                tvProgramOutput?.text = if (ok) {
                    "✅ Compiled & Linked .mathc Extension:\n$message"
                } else {
                    "❌ Extension Compile Error:\n$message"
                }
                outputCard?.let { springPulse(it, 0.045f) }
                activity.showButtonToast(if (ok) "Module loaded into engine" else "Compile failed")
            }
        }
    }

    private fun runCurrentProgramInSession() {
        saveCurrentFile(showToast = false)
        val code = etCodeEditor?.text?.toString() ?: ""
        val activity = activity as? MainActivity ?: return
        ensureDefaultSession()
        val sid = activeSessionId

        MathEngineManager.runProgramScript(sid, code) { results, snapSummary ->
            activity.runOnUiThread {
                val sb = StringBuilder()
                sb.append("=== Executed $activeFileName (Session #$sid) ===\n")
                if (results.isEmpty()) {
                    sb.append("(No executable statements found)\n")
                } else {
                    results.forEachIndexed { idx, (stmt, outcome) ->
                        if (outcome.ok) {
                            val u = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                            sb.append("[${idx + 1}] $stmt  ⟹  ${outcome.value}$u (${outcome.type})\n")
                            sessionLogs.getOrPut(sid) { mutableListOf() }
                                .add(stmt to "${outcome.value}$u")
                        } else {
                            sb.append("[${idx + 1}] $stmt  ⟹  ⚠ ${outcome.error}\n")
                            sessionLogs.getOrPut(sid) { mutableListOf() }
                                .add(stmt to "⚠ ${outcome.error}")
                        }
                    }
                }
                if (snapSummary.isNotBlank()) {
                    sb.append("\n--- Session Variables ---\n").append(snapSummary)
                }
                tvProgramOutput?.text = sb.toString().trimEnd()
                outputCard?.let { springPulse(it, 0.045f) }
                refreshSessionUI()
            }
        }
    }

    private fun ensureDefaultSession() {
        if (openSessionIds.isNotEmpty() && activeSessionId in openSessionIds) return
        MathEngineManager.openSession { sid ->
            activity?.runOnUiThread {
                if (sid > 0) {
                    if (sid !in openSessionIds) openSessionIds.add(sid)
                    activeSessionId = sid
                    sessionLogs.getOrPut(sid) { mutableListOf() }
                    refreshSessionChips()
                    refreshSessionUI()
                }
            }
        }
    }

    private fun openNewSession() {
        val activity = activity as? MainActivity ?: return
        MathEngineManager.openSession { sid ->
            activity.runOnUiThread {
                if (sid > 0) {
                    openSessionIds.add(sid)
                    activeSessionId = sid
                    sessionLogs[sid] = mutableListOf()
                    refreshSessionChips()
                    refreshSessionUI()
                    varsCard?.let { springPulse(it, 0.05f) }
                    activity.showButtonToast("Opened Session #$sid")
                } else {
                    activity.showButtonToast("Max sessions (8) reached")
                }
            }
        }
    }

    private fun resetActiveSession() {
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        MathEngineManager.resetSession(sid) { ok ->
            activity.runOnUiThread {
                sessionLogs[sid]?.clear()
                refreshSessionUI()
                varsCard?.let { springPulse(it, 0.05f) }
                replHistoryCard?.let { springPulse(it, 0.04f) }
                activity.showButtonToast(if (ok) "Session #$sid reset" else "Reset failed")
            }
        }
    }

    private fun closeActiveSession() {
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        if (openSessionIds.size <= 1) {
            resetActiveSession()
            return
        }
        MathEngineManager.closeSession(sid) { _ ->
            activity.runOnUiThread {
                openSessionIds.remove(sid)
                sessionLogs.remove(sid)
                activeSessionId = openSessionIds.first()
                refreshSessionChips()
                refreshSessionUI()
                activity.showButtonToast("Closed Session #$sid")
            }
        }
    }

    private fun exportActiveSessionSnapshots() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        MathEngineManager.exportSessionSnapshots(sid) { snaps ->
            activity.runOnUiThread {
                exportedSnapshotCache = snaps
                val snapFile = File(mathDir(ctx), "session_${sid}.msp1")
                snapFile.writeText(snaps)
                val preview = if (snaps.isBlank()) "(Empty session — evaluate expressions first)" else snaps
                AlertDialog.Builder(ctx)
                    .setTitle("Session #$sid MSP1 Snapshot")
                    .setMessage("Saved to ${snapFile.name} & cached for restore:\n\n$preview")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun restoreSessionFromSnapshotDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val input = EditText(ctx).apply {
            setText(exportedSnapshotCache)
            hint = "MSP1|seq:1|prev:00000000|hash:...|vars:..."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Restore Session from MSP1 Snapshot")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotEmpty()) {
                    MathEngineManager.restoreSession(raw) { newSid, err ->
                        activity.runOnUiThread {
                            if (newSid > 0) {
                                if (newSid !in openSessionIds) openSessionIds.add(newSid)
                                activeSessionId = newSid
                                sessionLogs.getOrPut(newSid) { mutableListOf() }
                                    .add("Restored MSP1 Snapshot" to "Session #$newSid active")
                                refreshSessionChips()
                                refreshSessionUI()
                                varsCard?.let { springPulse(it, 0.05f) }
                                activity.showButtonToast("Restored into Session #$newSid")
                            } else {
                                activity.showButtonToast("Restore failed: ${err ?: "invalid snapshot"}")
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun evaluateReplLine() {
        val activity = activity as? MainActivity ?: return
        val expr = etReplInput?.text?.toString()?.trim() ?: ""
        if (expr.isEmpty()) return
        ensureDefaultSession()
        val sid = activeSessionId
        etReplInput?.setText("")

        MathEngineManager.evalInSession(sid, expr, activity.isDegreeMode) { outcome, varsSummary ->
            activity.runOnUiThread {
                val resText = if (outcome.ok) {
                    val u = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                    "${outcome.value}$u"
                } else {
                    "⚠ ${outcome.error ?: "Error"}"
                }
                sessionLogs.getOrPut(sid) { mutableListOf() }.add(expr to resText)
                tvVarsContent?.text = varsSummary.ifBlank { "No variables bound yet." }
                refreshSessionLogViews()
                varsCard?.let { springPulse(it, 0.045f) }
                replHistoryCard?.let { springPulse(it, 0.035f) }
            }
        }
    }

    private fun refreshSessionChips() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val row = sessionChipsRow ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        row.removeAllViews()
        openSessionIds.forEach { sid ->
            val selected = sid == activeSessionId
            val chip = NeumorphicKeyView(ctx).apply {
                text = "Session #$sid"
                textSize = 12f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = selected
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(dp(102f), dp(40f)).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    activeSessionId = sid
                    refreshSessionChips()
                    refreshSessionUI()
                    varsCard?.let { springPulse(it, 0.05f) }
                }
            }
            row.addView(chip)
        }
    }

    private fun refreshSessionUI() {
        val sid = activeSessionId
        if (sid <= 0) return
        MathEngineManager.inspectSessionVariables(sid) { summary ->
            activity?.runOnUiThread {
                tvVarsHeader?.text = "Session #$sid Variables (MSP1 State)"
                tvVarsContent?.text = summary.ifBlank {
                    "No variables bound yet. Try: x := 42; f(t) := t^2 + 1"
                }
                refreshSessionLogViews()
            }
        }
    }

    private fun refreshSessionLogViews() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val lay = replHistoryLayout ?: return
        val dark = activity.isDarkMode
        val primaryColor = Color.parseColor(if (dark) "#E0E0E0" else "#333333")
        val accentColor = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
        val subColor = Color.parseColor(if (dark) "#8E8E8E" else "#777777")

        lay.removeAllViews()
        val entries = sessionLogs[activeSessionId] ?: emptyList()
        if (entries.isEmpty()) {
            lay.addView(TextView(ctx).apply {
                text = "Session #$activeSessionId ready. Enter any .math expression, variable assignment (x := 5), or function definition (f(x) := x^2) below."
                setTextColor(subColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(8, 16, 8, 16)
            })
        } else {
            entries.forEach { (input, output) ->
                val itemRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(8, 10, 8, 10)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        etReplInput?.setText(input)
                        etReplInput?.setSelection(input.length)
                        springPulse(this, 0.04f)
                    }
                }
                itemRow.addView(TextView(ctx).apply {
                    text = "❯ $input"
                    setTextColor(primaryColor)
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                itemRow.addView(TextView(ctx).apply {
                    text = "  = $output"
                    setTextColor(accentColor)
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setTypeface(typeface, Typeface.BOLD)
                })
                lay.addView(itemRow)
            }
            replHistoryScroll?.post { replHistoryScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun updateModeVisibility(animate: Boolean) {
        btnModeFiles?.isAccentKey = isFilesMode
        btnModeSessions?.isAccentKey = !isFilesMode
        if (isFilesMode) {
            sessionsSection?.visibility = View.GONE
            filesSection?.visibility = View.VISIBLE
            if (animate) {
                btnModeFiles?.let { springPulse(it, 0.06f) }
                filesSection?.let { springSlideIn(it) }
            }
        } else {
            filesSection?.visibility = View.GONE
            sessionsSection?.visibility = View.VISIBLE
            refreshSessionUI()
            if (animate) {
                btnModeSessions?.let { springPulse(it, 0.06f) }
                sessionsSection?.let { springSlideIn(it) }
            }
        }
    }

    fun applyTheme(dark: Boolean) {
        if (view == null) return
        val density = resources.displayMetrics.density
        val bg = Color.parseColor(if (dark) "#2D2D2D" else "#F8F8F8")
        val cardBg = Color.parseColor(if (dark) "#252525" else "#ECECEC")
        val editorBg = Color.parseColor(if (dark) "#1E1E1E" else "#FFFFFF")
        val strokeColor = Color.parseColor(if (dark) "#3E3E3E" else "#D6D6D6")
        val primaryText = Color.parseColor(if (dark) "#E0E0E0" else "#333333")
        val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")
        val accentText = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

        rootLayout?.setBackgroundColor(bg)
        tvWorkspaceTitle?.setTextColor(primaryText)
        tvActiveFileName?.setTextColor(accentText)

        fun styleCard(v: View?, fill: Int) {
            v?.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(fill)
                setStroke((1f * density).toInt(), strokeColor)
            }
        }

        styleCard(etCodeEditor, editorBg)
        styleCard(outputCard, cardBg)
        styleCard(varsCard, cardBg)
        styleCard(replHistoryCard, cardBg)
        styleCard(etReplInput, editorBg)

        etCodeEditor?.setTextColor(primaryText)
        etCodeEditor?.setHintTextColor(secondaryText)
        tvProgramOutput?.setTextColor(primaryText)

        tvVarsHeader?.setTextColor(accentText)
        tvVarsContent?.setTextColor(primaryText)
        etReplInput?.setTextColor(primaryText)
        etReplInput?.setHintTextColor(secondaryText)

        listOf(
            btnBackCalc, btnModeFiles, btnModeSessions,
            btnNewFile, btnSaveFile, btnDeleteFile,
            btnImportFile, btnExportFile,
            btnRunScript, btnLoadModule, btnClearOutput,
            btnNewSession, btnResetSession, btnCloseSession,
            btnExportSnap, btnRestoreSnap, btnEvalRepl
        ).forEach { it?.isDarkThemeMode = dark }

        refreshFileChips()
        refreshSessionChips()
        refreshSessionLogViews()
    }

    /** Same damped-spring pulse (e^-7.5t * sin(22t)) used across the calculator. */
    private fun springPulse(target: View, bump: Float) {
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

    /** Underdamped spring slide-in for panels and file switches. */
    private fun springSlideIn(target: View) {
        springAnimators.removeAll { (v, a) -> if (v === target) { a.cancel(); true } else false }
        val dist = 36f * resources.displayMetrics.density
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480L
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val pulse = decay * Math.sin(t * 22.0)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                target.translationX = dist * springFactor
                target.scaleX = (1.0 + 0.025 * pulse).toFloat()
                target.scaleY = (1.0 + 0.025 * pulse).toFloat()
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

    /** Ripple wavefront wobble when theme or page changes. */
    fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        val speed = maxRadius / revealDurationMs
        val density = resources.displayMetrics.density
        val views = listOfNotNull(
            topHeaderBar, modeToggleBar, etCodeEditor, outputCard,
            varsCard, replHistoryCard, etReplInput, btnEvalRepl
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

    override fun onDestroyView() {
        saveCurrentFile(showToast = false)
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        springAnimators.forEach { it.second.cancel() }
        springAnimators.clear()
        super.onDestroyView()
    }
}
