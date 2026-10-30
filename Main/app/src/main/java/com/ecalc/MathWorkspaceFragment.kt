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
 * Free-form `.math` Modules & Sessions Workspace.
 *
 * Separated into two distinct top-level tabs:
 * 1. **Modules (`🧩 Modules`)**: Dedicated to native `[module]` extension `.math` files,
 *    with inner `📝 Editor` and `📋 Log` sub-tabs. Any file in Modules that is not a true
 *    `[module]` and can be classified as a standard `.math` evaluation file is automatically
 *    promoted into a separate Session as a proper evaluation file.
 * 2. **Sessions (`⚡ Sessions`)**:
 *    - Beneath the Sessions tab, displays the **Active Sessions** chips (`Session #1`, `Session #2`, ...)
 *      along with dedicated **Session controls** (`+ Session`, `Reset`, `Close`, `📂 Load Sess`, `💾 Export Sess`).
 *    - Beneath the Session controls, displays the **Individual `.math` Evaluation Files** chips
 *      and **individual File controls** (`+ File`, `Save`, `Del`, `📂 Load File`, `💾 Export File`)
 *      that operate strictly on the `.math` files themselves (exporting a `.math` file exports
 *      only the file independently, not the session).
 *    - Provides inner `📝 Editor` and `📋 Log` sub-tabs (with `Snap` and `Restore` removed
 *      from the Log viewer since session Export/Load are handled directly in the Session bar).
 */
class MathWorkspaceFragment : Fragment() {

    private var rootLayout: LinearLayout? = null
    private var topHeaderBar: LinearLayout? = null
    private var tvWorkspaceTitle: TextView? = null
    private var btnBackCalc: NeumorphicKeyView? = null

    private var modeToggleBar: LinearLayout? = null
    private var btnModeModules: NeumorphicKeyView? = null
    private var btnModeSessions: NeumorphicKeyView? = null

    // --- Top-level Section 1: Modules Section ---
    private var modulesSection: LinearLayout? = null
    private var moduleChipsRow: LinearLayout? = null
    private var btnNewModule: NeumorphicKeyView? = null
    private var btnSaveModule: NeumorphicKeyView? = null
    private var btnDeleteModule: NeumorphicKeyView? = null
    private var btnImportModule: NeumorphicKeyView? = null
    private var btnExportModule: NeumorphicKeyView? = null

    private var moduleSubTabBar: LinearLayout? = null
    private var btnModuleSubEditor: NeumorphicKeyView? = null
    private var btnModuleSubLog: NeumorphicKeyView? = null

    private var moduleEditorPane: LinearLayout? = null
    private var tvActiveModuleName: TextView? = null
    private var etModuleEditor: EditText? = null
    private var btnCompileModule: NeumorphicKeyView? = null
    private var btnPromoteModule: NeumorphicKeyView? = null
    private var btnModuleViewLog: NeumorphicKeyView? = null
    private var moduleQuickStatusCard: LinearLayout? = null
    private var tvModuleQuickStatus: TextView? = null

    private var moduleLogPane: LinearLayout? = null
    private var moduleLogCard: LinearLayout? = null
    private var tvModuleLogOutput: TextView? = null
    private var btnModuleBackToEditor: NeumorphicKeyView? = null
    private var btnClearModuleLog: NeumorphicKeyView? = null

    // --- Top-level Section 2: Sessions Section ---
    private var sessionsSection: LinearLayout? = null

    // 2A. Active Sessions Bar & Session-Level Controls
    private var tvSessionsGroupLabel: TextView? = null
    private var sessionChipsRow: LinearLayout? = null
    private var btnNewSession: NeumorphicKeyView? = null
    private var btnResetSession: NeumorphicKeyView? = null
    private var btnCloseSession: NeumorphicKeyView? = null
    private var btnLoadSession: NeumorphicKeyView? = null
    private var btnExportSession: NeumorphicKeyView? = null

    // 2B. Individual .math Evaluation Files Bar & File-Level Controls
    private var tvEvalFilesGroupLabel: TextView? = null
    private var evalFileChipsRow: LinearLayout? = null
    private var btnNewEvalFile: NeumorphicKeyView? = null
    private var btnSaveEvalFile: NeumorphicKeyView? = null
    private var btnDeleteEvalFile: NeumorphicKeyView? = null
    private var btnImportEvalFile: NeumorphicKeyView? = null
    private var btnExportEvalFile: NeumorphicKeyView? = null

    // 2C. Inner Sub-Tabs for Sessions: [📝 Editor] | [📋 Log]
    private var sessionSubTabBar: LinearLayout? = null
    private var btnSessionSubEditor: NeumorphicKeyView? = null
    private var btnSessionSubLog: NeumorphicKeyView? = null

    private var sessionEditorPane: LinearLayout? = null
    private var tvActiveSessionFileName: TextView? = null
    private var etSessionEditor: EditText? = null
    private var btnRunSessionScript: NeumorphicKeyView? = null
    private var btnSessionViewLog: NeumorphicKeyView? = null
    private var sessionQuickStatusCard: LinearLayout? = null
    private var tvSessionQuickStatus: TextView? = null

    private var sessionLogPane: LinearLayout? = null
    private var varsCard: LinearLayout? = null
    private var tvVarsHeader: TextView? = null
    private var tvVarsContent: TextView? = null
    private var sessionLogCard: LinearLayout? = null
    private var tvSessionLogOutput: TextView? = null
    private var btnSessionBackToEditor: NeumorphicKeyView? = null
    private var btnClearSessionLog: NeumorphicKeyView? = null

    // --- Workspace State ---
    private var isModulesMode: Boolean = true
    private var isModuleEditorSubTab: Boolean = true
    private var isSessionEditorSubTab: Boolean = true

    private var activeModuleFileName: String = "custom_module.math"
    private val moduleLogs = mutableMapOf<String, String>()

    /** Independent active sessions state (`sessionId` list + per-session log & MSP1 vars). */
    private val openSessionIds = mutableListOf<Int>()
    private var activeSessionId: Int = -1
    private val sessionLogs = mutableMapOf<Int, String>()
    private val sessionVarsCache = mutableMapOf<Int, String>()
    private var pendingSessionExportPayload: String = ""

    /** Independent `.math` evaluation files state in the Sessions tab. */
    private var activeEvalFileName: String = "physics_sim.math"

    private val wobbleAnimators = mutableListOf<ValueAnimator>()
    private val springAnimators = mutableListOf<Pair<View, ValueAnimator>>()

    /** SAF Launcher to load/import an individual `.math` file from device storage. */
    private val importMathFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportedMathFileUri(uri)
        }
    }

    /** SAF Launcher to export/save an individual `.math` file to device storage. */
    private val exportMathFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            handleExportedMathFileUri(uri)
        }
    }

    /** SAF Launcher to load/restore an active Session (`.msp1` snapshot) from device storage. */
    private val importSessionStateLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportedSessionUri(uri)
        }
    }

    /** SAF Launcher to export the current active Session (`.msp1` snapshot) to device storage. */
    private val exportSessionStateLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            handleExportedSessionUri(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val activity = requireActivity() as MainActivity
        val dark = activity.isDarkMode
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        ensureSampleAndClassifiedFiles(ctx)

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
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(82f), dp(40f)).apply {
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
            text = ".math Modules & Sessions"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvWorkspaceTitle)

        // 2. Top-Level Mode Switcher Bar: [🧩 Modules] | [⚡ Sessions]
        val modeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        modeToggleBar = modeRow

        btnModeModules = NeumorphicKeyView(ctx).apply {
            text = "🧩 Modules"
            textSize = 13.5f
            cornerRadiusDp = 14f
            shadowPadDp = 3.5f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1f).apply { marginEnd = dp(4f) }
            setOnClickListener {
                if (!isModulesMode) {
                    saveCurrentEvalFile(showToast = false)
                    isModulesMode = true
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
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1f).apply { marginStart = dp(4f) }
            setOnClickListener {
                if (isModulesMode) {
                    saveCurrentModuleFile(showToast = false, checkPromotion = true)
                    isModulesMode = false
                    updateModeVisibility(animate = true)
                } else {
                    springPulse(this, 0.06f)
                }
            }
        }
        modeRow.addView(btnModeModules)
        modeRow.addView(btnModeSessions)

        root.addView(headerRow)
        root.addView(modeRow)

        // 3. Build Modules Section (with inner Editor & Log tabs)
        modulesSection = buildModulesSection(ctx, activity, dp)
        root.addView(modulesSection)

        // 4. Build Sessions Section (with separate Session bar, File bar, and inner Editor & Log tabs)
        sessionsSection = buildSessionsSection(ctx, activity, dp)
        root.addView(sessionsSection)

        applyTheme(dark)
        refreshModuleChips()
        loadModuleIntoEditor(activeModuleFileName, animate = false)
        ensureDefaultSession()
        refreshEvalFileChips()
        loadEvalFileIntoEditor(activeEvalFileName, animate = false)
        updateModeVisibility(animate = false)
        updateModuleSubTabVisibility(animate = false)
        updateSessionSubTabVisibility(animate = false)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.let { applyTheme(it.isDarkMode) }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { applyTheme(it.isDarkMode) }
    }

    // =========================================================================
    //  Section 1: Modules UI Builder (Module Chips + Actions + [Editor | Log])
    // =========================================================================

    private fun buildModulesSection(
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

            // Row 1: Horizontal scroll bar for Module .math file chips
            val modScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(40f)
                ).apply { bottomMargin = dp(4f) }
            }
            moduleChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(2f), 0)
            }
            modScroll.addView(moduleChipsRow)
            addView(modScroll)

            // Row 2: Module file action toolbar (+ New | Save | Del | 📂 Load | 💾 Export)
            val modActionsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }

            fun makeBtn(label: String, accent: Boolean, weight: Float = 1f): NeumorphicKeyView {
                return NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = 11.5f
                    cornerRadiusDp = 12f
                    shadowPadDp = 3f
                    isAccentKey = accent
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), weight).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

            btnNewModule = makeBtn("+ New", true, 1f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptNewModuleDialog()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Create a new [module] .math file (non-module scripts auto-promote to Sessions)"
                    ) else activity.dismissButtonPopup()
                }
            }
            btnSaveModule = makeBtn("Save", false, 0.9f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    saveCurrentModuleFile(showToast = true, checkPromotion = true)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Save module file (if classified as standard .math evaluation file, promotes to a Session)"
                    ) else activity.dismissButtonPopup()
                }
            }
            btnDeleteModule = makeBtn("Del", false, 0.85f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    deleteCurrentModuleFile()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Delete current .math module")
                    else activity.dismissButtonPopup()
                }
            }
            btnImportModule = makeBtn("📂 Load", true, 1.1f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchImportMathFileFromStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Load a .math file from storage (evaluation files auto-promote to a Session)"
                    ) else activity.dismissButtonPopup()
                }
            }
            btnExportModule = makeBtn("💾 Export", true, 1.15f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchExportMathFileToStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Export current .math module file independently")
                    else activity.dismissButtonPopup()
                }
            }

            modActionsRow.addView(btnNewModule)
            modActionsRow.addView(btnSaveModule)
            modActionsRow.addView(btnDeleteModule)
            modActionsRow.addView(btnImportModule)
            modActionsRow.addView(btnExportModule)
            addView(modActionsRow)

            // Row 3: Inner Sub-Tab Bar for Modules: [📝 Editor] | [📋 Log]
            val subRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }
            moduleSubTabBar = subRow

            btnModuleSubEditor = NeumorphicKeyView(ctx).apply {
                text = "📝 Editor"
                textSize = 12.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    if (!isModuleEditorSubTab) {
                        isModuleEditorSubTab = true
                        updateModuleSubTabVisibility(animate = true)
                    } else {
                        springPulse(this, 0.06f)
                    }
                }
            }
            btnModuleSubLog = NeumorphicKeyView(ctx).apply {
                text = "📋 Log"
                textSize = 12.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f).apply { marginStart = dp(4f) }
                setOnClickListener {
                    if (isModuleEditorSubTab) {
                        isModuleEditorSubTab = false
                        updateModuleSubTabVisibility(animate = true)
                    } else {
                        springPulse(this, 0.06f)
                    }
                }
            }
            subRow.addView(btnModuleSubEditor)
            subRow.addView(btnModuleSubLog)
            addView(subRow)

            // --- Module Sub-Pane A: Editor Pane ---
            moduleEditorPane = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )

                tvActiveModuleName = TextView(ctx).apply {
                    text = "Module Editor: $activeModuleFileName"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(accentText)
                    setPadding(dp(4f), 0, dp(4f), dp(4f))
                }
                addView(tvActiveModuleName)

                etModuleEditor = EditText(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply { bottomMargin = dp(6f) }
                    gravity = Gravity.TOP or Gravity.START
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(primaryText)
                    setHintTextColor(secondaryText)
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    hint = "# Write [module] extension definitions (or standard .math to auto-promote to a Session)..."
                }
                addView(etModuleEditor)

                val execRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6f) }
                }

                btnCompileModule = NeumorphicKeyView(ctx).apply {
                    text = "⚙ Compile & Load"
                    textSize = 12.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1.35f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        compileOrPromoteCurrentModule()
                    }
                    setOnLongClickListener { true }
                    holdListener = { held ->
                        if (held) activity.showButtonPopup(
                            this,
                            "Compile [module] into .mathc & link (or auto-promote non-module .math file into a Session)"
                        ) else activity.dismissButtonPopup()
                    }
                }

                btnPromoteModule = NeumorphicKeyView(ctx).apply {
                    text = "⚡ To Session"
                    textSize = 12f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 0.95f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        val code = etModuleEditor?.text?.toString() ?: ""
                        promoteModuleFileToSession(activeModuleFileName, code, autoEvaluate = true)
                    }
                    setOnLongClickListener { true }
                    holdListener = { held ->
                        if (held) activity.showButtonPopup(
                            this,
                            "Promote this .math file into a separate evaluation Session"
                        ) else activity.dismissButtonPopup()
                    }
                }

                btnModuleViewLog = NeumorphicKeyView(ctx).apply {
                    text = "📋 Log"
                    textSize = 12f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(74f), dp(40f))
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        isModuleEditorSubTab = false
                        updateModuleSubTabVisibility(animate = true)
                    }
                }

                execRow.addView(btnCompileModule)
                execRow.addView(btnPromoteModule)
                execRow.addView(btnModuleViewLog)
                addView(execRow)

                moduleQuickStatusCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                }
                tvModuleQuickStatus = TextView(ctx).apply {
                    text = "Ready. True [module] files compile to .mathc; standard .math evaluation scripts auto-promote to Sessions."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(secondaryText)
                    maxLines = 2
                }
                moduleQuickStatusCard?.addView(tvModuleQuickStatus)
                addView(moduleQuickStatusCard)
            }
            addView(moduleEditorPane)

            // --- Module Sub-Pane B: Log Pane ---
            moduleLogPane = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )

                val mLogCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply { bottomMargin = dp(6f) }
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                }
                moduleLogCard = mLogCard

                val mLogScroll = ScrollView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }
                tvModuleLogOutput = TextView(ctx).apply {
                    text = "=== Module Build & Link Log ===\nTap '⚙ Compile & Load' in the Editor tab to build the active [module] into native .mathc."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(primaryText)
                    setTextIsSelectable(true)
                }
                mLogScroll.addView(tvModuleLogOutput)
                mLogCard.addView(mLogScroll)
                addView(mLogCard)

                val mLogActions = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                btnModuleBackToEditor = NeumorphicKeyView(ctx).apply {
                    text = "📝 Back to Editor"
                    textSize = 12.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginEnd = dp(6f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        isModuleEditorSubTab = true
                        updateModuleSubTabVisibility(animate = true)
                    }
                }
                btnClearModuleLog = NeumorphicKeyView(ctx).apply {
                    text = "Clear Log"
                    textSize = 12f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(96f), dp(40f))
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        val cleared = "=== Module Build & Link Log ($activeModuleFileName) ===\n(Log cleared)"
                        moduleLogs[activeModuleFileName] = cleared
                        tvModuleLogOutput?.text = cleared
                        moduleLogCard?.let { springPulse(it, 0.04f) }
                    }
                }
                mLogActions.addView(btnModuleBackToEditor)
                mLogActions.addView(btnClearModuleLog)
                addView(mLogActions)
            }
            addView(moduleLogPane)
        }
    }

    // =========================================================================
    //  Section 2: Sessions UI Builder
    //  - Beneath Sessions tab: Active Sessions chips + Session controls
    //    (+ Session | Reset | Close | 📂 Load Sess | 💾 Export Sess)
    //  - Beneath Session controls: Individual .math Evaluation Files chips + File controls
    //    (+ File | Save | Del | 📂 Load File | 💾 Export File)
    //  - Inner Sub-Tabs: [📝 Editor] | [📋 Log] (no Snap/Restore in Log viewer)
    // =========================================================================

    private fun buildSessionsSection(
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
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            fun makeBarBtn(label: String, accent: Boolean, weight: Float = 1f): NeumorphicKeyView {
                return NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = 10.8f
                    cornerRadiusDp = 11f
                    shadowPadDp = 2.8f
                    isAccentKey = accent
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(36f), weight).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

            // -----------------------------------------------------------------
            // 2A. Active Sessions Bar & Session-Level Actions
            // -----------------------------------------------------------------
            tvSessionsGroupLabel = TextView(ctx).apply {
                text = "ACTIVE SESSIONS"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(secondaryText)
                setPadding(dp(4f), 0, dp(4f), dp(2f))
            }
            addView(tvSessionsGroupLabel)

            val sessScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(38f)
                ).apply { bottomMargin = dp(3f) }
            }
            sessionChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(2f), 0)
            }
            sessScroll.addView(sessionChipsRow)
            addView(sessScroll)

            val sessBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }

            btnNewSession = makeBarBtn("+ Session", true, 1.05f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    openNewStandaloneSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Create a new active evaluation session")
                    else activity.dismissButtonPopup()
                }
            }
            btnResetSession = makeBarBtn("Reset", false, 0.8f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    resetActiveSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Reset the current active session's variables & log")
                    else activity.dismissButtonPopup()
                }
            }
            btnCloseSession = makeBarBtn("Close", false, 0.8f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    closeActiveSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Close the current active session")
                    else activity.dismissButtonPopup()
                }
            }
            btnLoadSession = makeBarBtn("📂 Load Sess", true, 1.15f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchLoadSessionFromStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Load a saved session (.msp1) from storage")
                    else activity.dismissButtonPopup()
                }
            }
            btnExportSession = makeBarBtn("💾 Export Sess", true, 1.2f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchExportActiveSessionToStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Export the current active session (.msp1) to storage")
                    else activity.dismissButtonPopup()
                }
            }

            sessBar.addView(btnNewSession)
            sessBar.addView(btnResetSession)
            sessBar.addView(btnCloseSession)
            sessBar.addView(btnLoadSession)
            sessBar.addView(btnExportSession)
            addView(sessBar)

            // -----------------------------------------------------------------
            // 2B. Individual .math Evaluation Files Bar & File-Level Actions
            // -----------------------------------------------------------------
            tvEvalFilesGroupLabel = TextView(ctx).apply {
                text = "EVALUATION .MATH FILES (INDEPENDENT)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(secondaryText)
                setPadding(dp(4f), 0, dp(4f), dp(2f))
            }
            addView(tvEvalFilesGroupLabel)

            val evalFileScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(38f)
                ).apply { bottomMargin = dp(3f) }
            }
            evalFileChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(2f), 0)
            }
            evalFileScroll.addView(evalFileChipsRow)
            addView(evalFileScroll)

            val evalFileBar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }

            btnNewEvalFile = makeBarBtn("+ New File", true, 1.0f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptNewEvalFileDialog()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Create a new .math evaluation file independently (does not create a new session)"
                    ) else activity.dismissButtonPopup()
                }
            }
            btnSaveEvalFile = makeBarBtn("Save", false, 0.8f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    saveCurrentEvalFile(showToast = true)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Save changes to the current .math file")
                    else activity.dismissButtonPopup()
                }
            }
            btnDeleteEvalFile = makeBarBtn("Del", false, 0.75f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    deleteCurrentEvalFile()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Delete the current .math evaluation file")
                    else activity.dismissButtonPopup()
                }
            }
            btnImportEvalFile = makeBarBtn("📂 Load File", true, 1.15f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchImportMathFileFromStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Load an individual .math file from storage")
                    else activity.dismissButtonPopup()
                }
            }
            btnExportEvalFile = makeBarBtn("💾 Export File", true, 1.2f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchExportMathFileToStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Export only this .math file independently (does not export the session)"
                    ) else activity.dismissButtonPopup()
                }
            }

            evalFileBar.addView(btnNewEvalFile)
            evalFileBar.addView(btnSaveEvalFile)
            evalFileBar.addView(btnDeleteEvalFile)
            evalFileBar.addView(btnImportEvalFile)
            evalFileBar.addView(btnExportEvalFile)
            addView(evalFileBar)

            // -----------------------------------------------------------------
            // 2C. Inner Sub-Tab Bar for Sessions: [📝 Editor] | [📋 Log]
            // -----------------------------------------------------------------
            val sessSubRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5f) }
            }
            sessionSubTabBar = sessSubRow

            btnSessionSubEditor = NeumorphicKeyView(ctx).apply {
                text = "📝 Editor"
                textSize = 12.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    if (!isSessionEditorSubTab) {
                        isSessionEditorSubTab = true
                        updateSessionSubTabVisibility(animate = true)
                    } else {
                        springPulse(this, 0.06f)
                    }
                }
            }
            btnSessionSubLog = NeumorphicKeyView(ctx).apply {
                text = "📋 Log"
                textSize = 12.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(36f), 1f).apply { marginStart = dp(4f) }
                setOnClickListener {
                    if (isSessionEditorSubTab) {
                        saveCurrentEvalFile(showToast = false)
                        isSessionEditorSubTab = false
                        updateSessionSubTabVisibility(animate = true)
                    } else {
                        springPulse(this, 0.06f)
                    }
                }
            }
            sessSubRow.addView(btnSessionSubEditor)
            sessSubRow.addView(btnSessionSubLog)
            addView(sessSubRow)

            // --- Session Sub-Pane A: .math Evaluation File Editor Pane ---
            sessionEditorPane = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )

                tvActiveSessionFileName = TextView(ctx).apply {
                    text = "Active: Session opening…  •  File: $activeEvalFileName"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(accentText)
                    setPadding(dp(4f), 0, dp(4f), dp(3f))
                }
                addView(tvActiveSessionFileName)

                etSessionEditor = EditText(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply { bottomMargin = dp(5f) }
                    gravity = Gravity.TOP or Gravity.START
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(primaryText)
                    setHintTextColor(secondaryText)
                    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    hint = "# Edit .math evaluation file and tap '▶ Run in Session'..."
                }
                addView(etSessionEditor)

                val sessExecRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(5f) }
                }

                btnRunSessionScript = NeumorphicKeyView(ctx).apply {
                    text = "▶ Run in Session"
                    textSize = 12.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1.5f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        runCurrentEvalFileInActiveSession(switchToLogAfterRun = false)
                    }
                    setOnLongClickListener { true }
                    holdListener = { held ->
                        if (held) activity.showButtonPopup(
                            this,
                            "Evaluate the current .math file inside the active session and record output in the Log tab"
                        ) else activity.dismissButtonPopup()
                    }
                }

                btnSessionViewLog = NeumorphicKeyView(ctx).apply {
                    text = "📋 View Log"
                    textSize = 12f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 0.9f)
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        saveCurrentEvalFile(showToast = false)
                        isSessionEditorSubTab = false
                        updateSessionSubTabVisibility(animate = true)
                    }
                }

                sessExecRow.addView(btnRunSessionScript)
                sessExecRow.addView(btnSessionViewLog)
                addView(sessExecRow)

                sessionQuickStatusCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        isSessionEditorSubTab = false
                        updateSessionSubTabVisibility(animate = true)
                    }
                }
                tvSessionQuickStatus = TextView(ctx).apply {
                    text = "Ready. Tap '▶ Run in Session' to evaluate $activeEvalFileName in the active session."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(secondaryText)
                    maxLines = 2
                }
                sessionQuickStatusCard?.addView(tvSessionQuickStatus)
                addView(sessionQuickStatusCard)
            }
            addView(sessionEditorPane)

            // --- Session Sub-Pane B: Evaluation Log & MSP1 Variables Pane (No Snap/Restore buttons) ---
            sessionLogPane = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )

                // Live Session Variables inspector card (MSP1 State)
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
                    setTextColor(accentText)
                }
                tvVarsContent = TextView(ctx).apply {
                    text = "No variables bound yet. Run a .math file in the Editor tab."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(primaryText)
                    setPadding(0, dp(4f), 0, 0)
                }
                vCard.addView(tvVarsHeader)
                vCard.addView(tvVarsContent)
                addView(vCard)

                // Full scrollable Evaluation Log Console card
                val sLogCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply { bottomMargin = dp(6f) }
                    setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                }
                sessionLogCard = sLogCard

                val sLogScroll = ScrollView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                }
                tvSessionLogOutput = TextView(ctx).apply {
                    text = "Ready. Tap '▶ Run in Session' in the Editor tab to evaluate statements."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(primaryText)
                    setTextIsSelectable(true)
                }
                sLogScroll.addView(tvSessionLogOutput)
                sLogCard.addView(sLogScroll)
                addView(sLogCard)

                // Bottom Log Action Row: ONLY [📝 Back to Editor] and [Clear Log] (Snap & Restore removed!)
                val sLogActions = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                btnSessionBackToEditor = NeumorphicKeyView(ctx).apply {
                    text = "📝 Back to Editor"
                    textSize = 12.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1f).apply { marginEnd = dp(6f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        isSessionEditorSubTab = true
                        updateSessionSubTabVisibility(animate = true)
                    }
                }
                btnClearSessionLog = NeumorphicKeyView(ctx).apply {
                    text = "Clear Log"
                    textSize = 12f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(96f), dp(40f))
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        val sid = activeSessionId
                        val cleared = "=== Session #$sid Log ===\n(Log cleared)"
                        sessionLogs[sid] = cleared
                        tvSessionLogOutput?.text = cleared
                        sessionLogCard?.let { springPulse(it, 0.04f) }
                    }
                }

                sLogActions.addView(btnSessionBackToEditor)
                sLogActions.addView(btnClearSessionLog)
                addView(sLogActions)
            }
            addView(sessionLogPane)
        }
    }

    // =========================================================================
    //  File System & Module-vs-Session Classification / Auto-Promotion
    // =========================================================================

    private fun legacyMathDir(ctx: Context): File =
        File(ctx.filesDir, "math_programs").also { if (!it.exists()) it.mkdirs() }

    private fun modulesDir(ctx: Context): File =
        File(ctx.filesDir, "math_modules").also { if (!it.exists()) it.mkdirs() }

    private fun sessionsDir(ctx: Context): File =
        File(ctx.filesDir, "math_sessions").also { if (!it.exists()) it.mkdirs() }

    private fun hasNonEmptyStatements(raw: String): Boolean =
        raw.lines().any { it.substringBefore("#").trim().isNotEmpty() }

    /**
     * Seeds default sample files and classifies any `.math` files across `math_programs/`
     * and `math_modules/`. Any file in `math_modules/` that is NOT a true `[module]`
     * and is a standard `.math` evaluation file is automatically promoted into
     * `math_sessions/`.
     */
    private fun ensureSampleAndClassifiedFiles(ctx: Context) {
        val legDir = legacyMathDir(ctx)
        val modDir = modulesDir(ctx)
        val sessDir = sessionsDir(ctx)

        File(legDir, "trig_ext.math").takeIf { it.exists() }?.delete()
        File(modDir, "trig_ext.math").takeIf { it.exists() }?.delete()

        legDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { file ->
            val content = runCatching { file.readText() }.getOrDefault("")
            val targetDir = if (MathEngineManager.isTrueModuleSource(content)) modDir else sessDir
            val dest = File(targetDir, file.name)
            if (!dest.exists()) {
                dest.writeText(content)
            }
            file.delete()
        }

        val customModFile = File(modDir, "custom_module.math")
        if (!customModFile.exists() && (modDir.listFiles { f -> f.name.endsWith(".math") }?.isEmpty() != false)) {
            customModFile.writeText(
                "# Custom Native Extension Module (.math)\n" +
                "# Tap '⚙ Compile & Load' to compile into native .mathc and link into the engine.\n" +
                "# Note: Each declaration in a [module] ends with a semicolon (;).\n\n" +
                "[custom_ops]\n" +
                "<[arith]\n" +
                "<[trig]\n\n" +
                " hypot3(a: real, b: real, c: real) = sqrt(a^2 + b^2 + c^2);\n" +
                " kinetic_e(m: real, v: real) = 0.5 * m * v^2;\n" +
                " deg_sin(x: real) = sin(deg2rad(x));\n"
            )
        }

        // Scan modulesDir: promote any non-module standard .math evaluation file into sessionsDir
        modDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { file ->
            val content = runCatching { file.readText() }.getOrDefault("")
            if (hasNonEmptyStatements(content) && !MathEngineManager.isTrueModuleSource(content)) {
                File(sessDir, file.name).writeText(content)
                file.delete()
            }
        }

        if (modDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.isEmpty() != false) {
            customModFile.writeText(
                "# Custom Native Extension Module (.math)\n" +
                "[custom_ops]\n" +
                "<[arith]\n" +
                "<[trig]\n\n" +
                " hypot3(a: real, b: real, c: real) = sqrt(a^2 + b^2 + c^2);\n" +
                " kinetic_e(m: real, v: real) = 0.5 * m * v^2;\n" +
                " deg_sin(x: real) = sin(deg2rad(x));\n"
            )
        }

        val existingSessFiles = sessDir.listFiles { f -> f.isFile && f.name.endsWith(".math") } ?: emptyArray()
        if (existingSessFiles.isEmpty()) {
            File(sessDir, "physics_sim.math").writeText(
                "# Stateful Session Evaluation Script (.math)\n" +
                "# Tap '▶ Run in Session' to evaluate line-by-line in the active session!\n\n" +
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
            File(sessDir, "vectors_stats.math").writeText(
                "# Vectors, Comprehensions & Symbolic Math (.math)\n" +
                "# Tap '▶ Run in Session' to evaluate in the active session!\n\n" +
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

        val availableMods = modDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (availableMods.isNotEmpty() && availableMods.none { it.name == activeModuleFileName }) {
            activeModuleFileName = availableMods.first().name
        }

        val availableEvalFiles = sessDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (availableEvalFiles.isNotEmpty() && availableEvalFiles.none { it.name == activeEvalFileName }) {
            activeEvalFileName = availableEvalFiles.first().name
        }
    }

    /**
     * Promotes a `.math` file from the Modules section into a separate Session
     * as a proper evaluation `.math` file when it is classified as a standard
     * `.math` evaluation script rather than a true `[module]`.
     */
    private fun promoteModuleFileToSession(
        fileName: String,
        code: String,
        autoEvaluate: Boolean
    ) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val modDir = modulesDir(ctx)
        val sessDir = sessionsDir(ctx)

        val safeName = if (fileName.endsWith(".math")) fileName else "$fileName.math"
        val finalContent = if (code.isBlank()) "# Promoted Evaluation Script: $safeName\n\nx := 10\nx * 2\n" else code
        File(sessDir, safeName).writeText(finalContent)
        File(modDir, safeName).takeIf { it.exists() }?.delete()

        val remainingMods = modDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (remainingMods.isEmpty()) {
            ensureSampleAndClassifiedFiles(ctx)
        }
        val updatedMods = modDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        activeModuleFileName = updatedMods.firstOrNull()?.name ?: "custom_module.math"
        refreshModuleChips()
        loadModuleIntoEditor(activeModuleFileName, animate = false)

        activeEvalFileName = safeName
        MathEngineManager.openSession { newSid ->
            activity.runOnUiThread {
                if (newSid <= 0) {
                    activity.showButtonToast("Engine booting… retry promotion in a moment")
                    return@runOnUiThread
                }
                val sid = newSid
                if (sid !in openSessionIds) openSessionIds.add(sid)
                activeSessionId = sid
                sessionLogs[sid] = "=== Session #$sid ===\n" +
                    "🚀 Promoted '$safeName' from Modules into Session #$sid as a standard .math evaluation file."

                val promoMsg = "🚀 Promoted '$safeName' to Session #$sid (evaluation file)."
                tvModuleQuickStatus?.text = promoMsg
                moduleLogs[activeModuleFileName] = promoMsg

                isModulesMode = false
                isSessionEditorSubTab = true
                refreshSessionChips()
                refreshEvalFileChips()
                loadEvalFileIntoEditor(safeName, animate = true)
                refreshActiveSessionLogAndVars()
                updateModeVisibility(animate = true)
                updateSessionSubTabVisibility(animate = false)

                activity.showButtonToast("Promoted $safeName to Session #$sid")
                if (autoEvaluate) {
                    runCurrentEvalFileInActiveSession(switchToLogAfterRun = false)
                }
            }
        }
    }

    // =========================================================================
    //  Modules Operations
    // =========================================================================

    private fun refreshModuleChips() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val row = moduleChipsRow ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        row.removeAllViews()
        val files = modulesDir(ctx).listFiles { f -> f.name.endsWith(".math") }
            ?.sortedBy { it.name } ?: emptyList()

        files.forEach { file ->
            val isSelected = file.name == activeModuleFileName
            val chip = NeumorphicKeyView(ctx).apply {
                text = "🧩 ${file.name}"
                textSize = 11.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = isSelected
                isDarkThemeMode = activity.isDarkMode
                val w = (file.name.length * 8.2f + 46f).coerceAtLeast(108f)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(36f)).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    saveCurrentModuleFile(showToast = false, checkPromotion = false)
                    activeModuleFileName = file.name
                    refreshModuleChips()
                    loadModuleIntoEditor(file.name, animate = true)
                }
            }
            row.addView(chip)
        }
    }

    private fun loadModuleIntoEditor(fileName: String, animate: Boolean) {
        val ctx = context ?: return
        val file = File(modulesDir(ctx), fileName)
        activeModuleFileName = fileName
        tvActiveModuleName?.text = "Module Editor: $fileName"
        if (file.exists()) {
            etModuleEditor?.setText(file.readText())
        } else {
            etModuleEditor?.setText("")
        }
        val savedLog = moduleLogs[fileName]
            ?: "=== Module Build & Link Log ($fileName) ===\nReady. Tap '⚙ Compile & Load' in the Editor tab to build this [module]."
        tvModuleLogOutput?.text = savedLog
        if (animate) {
            etModuleEditor?.let { springSlideIn(it) }
            tvActiveModuleName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun saveCurrentModuleFile(showToast: Boolean, checkPromotion: Boolean): Boolean {
        val ctx = context ?: return false
        val activity = activity as? MainActivity ?: return false
        val code = etModuleEditor?.text?.toString() ?: return false

        if (checkPromotion && hasNonEmptyStatements(code) && !MathEngineManager.isTrueModuleSource(code)) {
            promoteModuleFileToSession(activeModuleFileName, code, autoEvaluate = false)
            return true
        }

        val file = File(modulesDir(ctx), activeModuleFileName)
        file.writeText(code)
        if (showToast) {
            activity.showButtonToast("Saved module $activeModuleFileName")
            tvActiveModuleName?.let { springPulse(it, 0.05f) }
        }
        return false
    }

    private fun compileOrPromoteCurrentModule() {
        val activity = activity as? MainActivity ?: return
        val code = etModuleEditor?.text?.toString() ?: ""

        if (hasNonEmptyStatements(code) && !MathEngineManager.isTrueModuleSource(code)) {
            promoteModuleFileToSession(activeModuleFileName, code, autoEvaluate = true)
            return
        }

        saveCurrentModuleFile(showToast = false, checkPromotion = false)
        val modFile = activeModuleFileName
        MathEngineManager.compileAndLoadExtension(code) { ok, message ->
            activity.runOnUiThread {
                val logEntry = if (ok) {
                    "=== Module Build & Link Log ($modFile) ===\n" +
                        "✅ Compiled & Linked .mathc Extension:\n$message"
                } else {
                    "=== Module Build & Link Log ($modFile) ===\n" +
                        "❌ Extension Compile Error:\n$message"
                }
                moduleLogs[modFile] = logEntry
                tvModuleLogOutput?.text = logEntry
                tvModuleQuickStatus?.text = if (ok) {
                    "✅ Linked '$modFile': $message"
                } else {
                    "❌ Compile error in '$modFile': $message"
                }
                moduleQuickStatusCard?.let { springPulse(it, 0.045f) }
                moduleLogCard?.let { springPulse(it, 0.045f) }
                activity.showButtonToast(if (ok) "Module loaded into engine" else "Compile failed")
            }
        }
    }

    private fun promptNewModuleDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dark = activity.isDarkMode
        val input = EditText(ctx).apply {
            hint = "module_name.math"
            setSingleLine(true)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            setHintTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
        }
        AlertDialog.Builder(ctx)
            .setTitle("New .math Module")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (!name.endsWith(".math")) name += ".math"
                    saveCurrentModuleFile(showToast = false, checkPromotion = false)
                    val modIdentifier = name.removeSuffix(".math")
                        .replace(Regex("[^a-zA-Z0-9_]"), "_")
                        .ifBlank { "custom_mod" }
                    val newFile = File(modulesDir(ctx), name)
                    if (!newFile.exists()) {
                        newFile.writeText(
                            "# Native Extension Module: $name\n" +
                            "# Note: If you write standard evaluation statements instead of a [module],\n" +
                            "# this file will automatically be promoted into a separate Session.\n\n" +
                            "[$modIdentifier]\n" +
                            "<[arith]\n\n" +
                            " square_sum(a: real, b: real) = a^2 + b^2;\n"
                        )
                    }
                    activeModuleFileName = name
                    isModuleEditorSubTab = true
                    refreshModuleChips()
                    loadModuleIntoEditor(name, animate = true)
                    updateModuleSubTabVisibility(animate = false)
                    activity.showButtonToast("Created module $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCurrentModuleFile() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dir = modulesDir(ctx)
        val files = dir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (files.size <= 1) {
            activity.showButtonToast("Cannot delete the last .math module")
            return
        }
        File(dir, activeModuleFileName).delete()
        moduleLogs.remove(activeModuleFileName)
        val remaining = dir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        activeModuleFileName = remaining.first().name
        refreshModuleChips()
        loadModuleIntoEditor(activeModuleFileName, animate = true)
        activity.showButtonToast("Deleted module")
    }

    // =========================================================================
    //  Sessions Operations (Independent Session Bar + Independent .math File Bar)
    // =========================================================================

    private fun ensureDefaultSession() {
        if (openSessionIds.isNotEmpty() && activeSessionId in openSessionIds && activeSessionId > 0) {
            refreshSessionChips()
            refreshActiveSessionLogAndVars()
            return
        }
        MathEngineManager.openSession { sid ->
            activity?.runOnUiThread {
                if (sid > 0) {
                    if (sid !in openSessionIds) openSessionIds.add(sid)
                    activeSessionId = sid
                    sessionLogs.getOrPut(sid) {
                        "=== Session #$sid Log ===\nReady. Select any .math evaluation file and tap '▶ Run in Session'."
                    }
                    refreshSessionChips()
                    refreshActiveSessionLogAndVars()
                } else {
                    // Engine not ready yet or open failed — do NOT fake a session id.
                    // Session chips will show the opening state; Run paths retry via
                    // routeOntoActiveSession().
                    (activity as? MainActivity)?.showButtonToast("Engine booting… try again in a moment")
                    refreshActiveSessionLogAndVars()
                }
            }
        }
    }

    /** Runs [a] on the active session; if none is (really) open yet, opens one first.
     *  Never fabricates a session id: on failure it retries once, then reports. */
    private fun routeOntoActiveSession(action: (sid: Int) -> Unit) {
        if (activeSessionId > 0 && activeSessionId in openSessionIds) {
            action(activeSessionId)
            return
        }
        val activity = activity as? MainActivity ?: return
        var retriedOnce = false
        fun attempt() {
            MathEngineManager.openSession { sid ->
                activity.runOnUiThread {
                    if (sid > 0) {
                        if (sid !in openSessionIds) openSessionIds.add(sid)
                        activeSessionId = sid
                        sessionLogs.getOrPut(sid) { "=== Session #$sid Log ===\n" }
                        refreshSessionChips()
                        refreshActiveSessionLogAndVars()
                        action(sid)
                    } else if (!retriedOnce) {
                        retriedOnce = true
                        tvActiveSessionFileName?.postDelayed({ attempt() }, 400)
                        tvActiveSessionFileName?.text = "Active: Session opening… • File: $activeEvalFileName"
                    } else {
                        activity.showButtonToast("Engine not ready — try again")
                    }
                }
            }
        }
        attempt()
    }

    /** Creates a new active Session without creating or altering any `.math` file. */
    private fun openNewStandaloneSession() {
        val activity = activity as? MainActivity ?: return
        MathEngineManager.openSession { sid ->
            activity.runOnUiThread {
                if (sid > 0) {
                    if (sid !in openSessionIds) openSessionIds.add(sid)
                    activeSessionId = sid
                    sessionLogs[sid] = "=== Session #$sid Log ===\nOpened new active Session #$sid."
                    sessionVarsCache[sid] = ""
                    refreshSessionChips()
                    refreshActiveSessionLogAndVars()
                    tvSessionQuickStatus?.text = "Switched to new Session #$sid (File: $activeEvalFileName)."
                    activity.showButtonToast("Opened Session #$sid")
                } else {
                    activity.showButtonToast("Max active sessions (8) reached")
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
                sessionVarsCache[sid] = ""
                sessionLogs[sid] = "=== Session #$sid Log ===\n(Session #$sid state & variables reset)"
                refreshActiveSessionLogAndVars()
                tvSessionQuickStatus?.text = "Session #$sid reset. Tap '▶ Run in Session' to evaluate $activeEvalFileName."
                varsCard?.let { springPulse(it, 0.05f) }
                sessionLogCard?.let { springPulse(it, 0.04f) }
                activity.showButtonToast(if (ok) "Session #$sid reset" else "Reset failed")
            }
        }
    }

    /** Closes the active session without deleting any `.math` file. */
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
                sessionVarsCache.remove(sid)
                activeSessionId = openSessionIds.first()
                refreshSessionChips()
                refreshActiveSessionLogAndVars()
                activity.showButtonToast("Closed Session #$sid")
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
                text = "⚡ Session #$sid"
                textSize = 11.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = selected
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(dp(110f), dp(34f)).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    activeSessionId = sid
                    refreshSessionChips()
                    refreshActiveSessionLogAndVars()
                    tvActiveSessionFileName?.let { springPulse(it, 0.045f) }
                    varsCard?.let { springPulse(it, 0.045f) }
                }
            }
            row.addView(chip)
        }
    }

    private fun refreshActiveSessionLogAndVars() {
        val sid = activeSessionId
        if (sid <= 0) {
            tvActiveSessionFileName?.text = "Active: Session opening…  •  File: $activeEvalFileName"
            tvSessionLogOutput?.text = "Session is opening… tap again in a moment."
            return
        }
        val shownSid = sid
        tvActiveSessionFileName?.text = "Active: Session #$shownSid  •  File: $activeEvalFileName"
        btnRunSessionScript?.text = "▶ Run in Session #$shownSid"
        tvVarsHeader?.text = "Session #$shownSid Variables (MSP1 State)"
        tvVarsContent?.text = sessionVarsCache[shownSid].orEmpty().ifBlank {
            "No variables bound yet in Session #$shownSid."
        }
        tvSessionLogOutput?.text = sessionLogs[shownSid]
            ?: "=== Session #$shownSid Log ===\nReady. Tap '▶ Run in Session #$shownSid' in the Editor tab."

        if (sid > 0) {
            MathEngineManager.inspectSessionVariables(sid) { summary ->
                activity?.runOnUiThread {
                    sessionVarsCache[sid] = summary
                    if (activeSessionId == sid) {
                        tvVarsContent?.text = summary.ifBlank {
                            "No variables bound yet in Session #$sid."
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    //  Individual .math Evaluation Files Operations (Independent of Sessions!)
    // =========================================================================

    private fun refreshEvalFileChips() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val row = evalFileChipsRow ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        row.removeAllViews()
        val files = sessionsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".math") }
            ?.sortedBy { it.name } ?: emptyList()

        files.forEach { file ->
            val selected = file.name == activeEvalFileName
            val chip = NeumorphicKeyView(ctx).apply {
                text = "📄 ${file.name}"
                textSize = 11.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = selected
                isDarkThemeMode = activity.isDarkMode
                val w = (file.name.length * 8.0f + 44f).coerceAtLeast(106f)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(34f)).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    saveCurrentEvalFile(showToast = false)
                    activeEvalFileName = file.name
                    refreshEvalFileChips()
                    loadEvalFileIntoEditor(file.name, animate = true)
                }
            }
            row.addView(chip)
        }
    }

    private fun loadEvalFileIntoEditor(fileName: String, animate: Boolean) {
        val ctx = context ?: return
        val file = File(sessionsDir(ctx), fileName)
        activeEvalFileName = fileName
        val shownSid = if (activeSessionId > 0) activeSessionId else 1
        tvActiveSessionFileName?.text = "Active: Session #$shownSid  •  File: $fileName"
        if (file.exists()) {
            etSessionEditor?.setText(file.readText())
        } else {
            etSessionEditor?.setText("")
        }
        if (animate) {
            etSessionEditor?.let { springSlideIn(it) }
            tvActiveSessionFileName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun saveCurrentEvalFile(showToast: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val code = etSessionEditor?.text?.toString() ?: return
        File(sessionsDir(ctx), activeEvalFileName).writeText(code)
        if (showToast) {
            activity.showButtonToast("Saved file $activeEvalFileName")
            tvActiveSessionFileName?.let { springPulse(it, 0.05f) }
        }
    }

    /** Creates a new `.math` evaluation file independently without opening a new session. */
    private fun promptNewEvalFileDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dark = activity.isDarkMode
        val input = EditText(ctx).apply {
            hint = "script_name.math"
            setSingleLine(true)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            setHintTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
        }
        AlertDialog.Builder(ctx)
            .setTitle("New .math Evaluation File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (!name.endsWith(".math")) name += ".math"
                    saveCurrentEvalFile(showToast = false)
                    val newFile = File(sessionsDir(ctx), name)
                    if (!newFile.exists()) {
                        newFile.writeText(
                            "# Evaluation Script: $name\n\n" +
                            "x := 10\n" +
                            "y := x * 2 + 5\n" +
                            "y\n"
                        )
                    }
                    activeEvalFileName = name
                    isSessionEditorSubTab = true
                    refreshEvalFileChips()
                    loadEvalFileIntoEditor(name, animate = true)
                    updateSessionSubTabVisibility(animate = false)
                    activity.showButtonToast("Created file $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Deletes the current `.math` evaluation file independently without closing the session. */
    private fun deleteCurrentEvalFile() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dir = sessionsDir(ctx)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (files.size <= 1) {
            activity.showButtonToast("Cannot delete the last .math evaluation file")
            return
        }
        val deletedName = activeEvalFileName
        File(dir, deletedName).delete()
        val remaining = dir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        activeEvalFileName = remaining.first().name
        refreshEvalFileChips()
        loadEvalFileIntoEditor(activeEvalFileName, animate = true)
        activity.showButtonToast("Deleted $deletedName")
    }

    private fun runCurrentEvalFileInActiveSession(switchToLogAfterRun: Boolean) {
        saveCurrentEvalFile(showToast = false)
        val activity = activity as? MainActivity ?: return
        val code = etSessionEditor?.text?.toString() ?: ""
        val evalFile = activeEvalFileName

        fun executeOnSession(sid: Int) {
            MathEngineManager.runProgramScript(sid, code) { results, snapSummary ->
                activity.runOnUiThread {
                    val sb = StringBuilder()
                    sb.append("=== Executed $evalFile in Session #$sid ===\n")
                    var lastSummaryLine = "(No executable statements found)"
                    if (results.isEmpty()) {
                        sb.append("(No executable statements found)\n")
                    } else {
                        results.forEachIndexed { idx, (stmt, outcome) ->
                            if (outcome.ok) {
                                val u = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                                val line = "[${idx + 1}] $stmt  ⟹  ${outcome.value}$u (${outcome.type})"
                                sb.append(line).append("\n")
                                lastSummaryLine = "✅ [S#$sid · $evalFile] $stmt ⟹ ${outcome.value}$u"
                            } else {
                                val line = "[${idx + 1}] $stmt  ⟹  ⚠ ${outcome.error}"
                                sb.append(line).append("\n")
                                lastSummaryLine = "⚠ Error on [$stmt]: ${outcome.error}"
                            }
                        }
                    }
                    if (snapSummary.isNotBlank()) {
                        sb.append("\n--- Session #$sid Variables ---\n").append(snapSummary)
                    }
                    sessionLogs[sid] = sb.toString().trimEnd()
                    sessionVarsCache[sid] = snapSummary
                    refreshActiveSessionLogAndVars()
                    tvSessionQuickStatus?.text = "$lastSummaryLine  (Tap for 📋 Log)"
                    sessionQuickStatusCard?.let { springPulse(it, 0.045f) }
                    sessionLogCard?.let { springPulse(it, 0.045f) }
                    varsCard?.let { springPulse(it, 0.045f) }
                    if (switchToLogAfterRun) {
                        isSessionEditorSubTab = false
                        updateSessionSubTabVisibility(animate = true)
                    }
                }
            }
        }

        if (activeSessionId > 0 && activeSessionId in openSessionIds) {
            executeOnSession(activeSessionId)
        } else {
            routeOntoActiveSession { sid ->
                activity.runOnUiThread { executeOnSession(sid) }
            }
        }
    }

    // =========================================================================
    //  Session Export & Load (Beneath the Sessions Tab)
    // =========================================================================

    private fun launchExportActiveSessionToStorage() {
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) {
            activity.showButtonToast("No active session to export")
            return
        }
        MathEngineManager.exportSessionSnapshots(sid) { snaps ->
            activity.runOnUiThread {
                if (snaps.isBlank()) {
                    activity.showButtonToast("Session #$sid is empty — evaluate a .math file first")
                    return@runOnUiThread
                }
                pendingSessionExportPayload = snaps
                try {
                    exportSessionStateLauncher.launch("session_${sid}.msp1")
                } catch (t: Throwable) {
                    activity.showButtonToast("Could not open save picker: ${t.message}")
                }
            }
        }
    }

    private fun handleExportedSessionUri(uri: Uri) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        try {
            val payload = pendingSessionExportPayload
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(payload)
            } ?: run {
                activity.showButtonToast("Unable to write session file")
                return
            }
            tvSessionQuickStatus?.text = "💾 Exported Session #$activeSessionId (${payload.length} chars) to storage."
            activity.showButtonToast("Exported Session #$activeSessionId")
        } catch (t: Throwable) {
            activity.showButtonToast("Session export failed: ${t.message}")
        }
    }

    private fun launchLoadSessionFromStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            importSessionStateLauncher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open file picker: ${t.message}")
        }
    }

    private fun handleImportedSessionUri(uri: Uri) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        try {
            val rawSnap = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.trim()
                ?: run {
                    activity.showButtonToast("Unable to read selected session file")
                    return
                }
            if (rawSnap.isEmpty()) {
                activity.showButtonToast("Selected session file is empty")
                return
            }
            MathEngineManager.restoreSession(rawSnap) { newSid, err ->
                activity.runOnUiThread {
                    if (newSid > 0) {
                        if (newSid !in openSessionIds) openSessionIds.add(newSid)
                        activeSessionId = newSid
                        MathEngineManager.inspectSessionVariables(newSid) { summary ->
                            activity.runOnUiThread {
                                sessionVarsCache[newSid] = summary
                                sessionLogs[newSid] = "=== Session #$newSid Log ===\n" +
                                    "📂 Loaded & restored MSP1 session state from storage.\n\n" +
                                    (if (summary.isNotBlank()) "--- Restored Variables ---\n$summary" else "(No bound variables)")
                                refreshSessionChips()
                                refreshActiveSessionLogAndVars()
                                tvSessionQuickStatus?.text = "📂 Loaded Session #$newSid from storage."
                                activity.showButtonToast("Loaded Session #$newSid")
                            }
                        }
                    } else {
                        activity.showButtonToast("Load session failed: ${err ?: "invalid .msp1 file"}")
                    }
                }
            }
        } catch (t: Throwable) {
            activity.showButtonToast("Session load failed: ${t.message}")
        }
    }

    // =========================================================================
    //  Independent .math File Import / Export (Applies Strictly to .math Files!)
    // =========================================================================

    private fun launchImportMathFileFromStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            if (isModulesMode) {
                saveCurrentModuleFile(showToast = false, checkPromotion = false)
            } else {
                saveCurrentEvalFile(showToast = false)
            }
            importMathFileLauncher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open file picker: ${t.message}")
        }
    }

    private fun handleImportedMathFileUri(uri: Uri) {
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

            val isTrueModule = MathEngineManager.isTrueModuleSource(content)
            if (isModulesMode && !isTrueModule && hasNonEmptyStatements(content)) {
                // Imported in Modules tab, but it is a standard .math evaluation file -> promote to Session!
                promoteModuleFileToSession(displayName, content, autoEvaluate = false)
                return
            }

            if (isModulesMode) {
                File(modulesDir(ctx), displayName).writeText(content)
                activeModuleFileName = displayName
                refreshModuleChips()
                loadModuleIntoEditor(displayName, animate = true)
                tvModuleQuickStatus?.text = "📂 Loaded module '$displayName' (${content.length} chars)."
                activity.showButtonToast("Loaded module $displayName")
            } else {
                // Independent .math file import in Sessions tab — does NOT create a new session
                File(sessionsDir(ctx), displayName).writeText(content)
                activeEvalFileName = displayName
                isSessionEditorSubTab = true
                refreshEvalFileChips()
                loadEvalFileIntoEditor(displayName, animate = true)
                updateSessionSubTabVisibility(animate = false)
                tvSessionQuickStatus?.text = "📂 Loaded file '$displayName' (${content.length} chars)."
                activity.showButtonToast("Loaded file $displayName")
            }
        } catch (t: Throwable) {
            activity.showButtonToast("Import failed: ${t.message}")
        }
    }

    /** Exports only the single `.math` file independently (never exports the session). */
    private fun launchExportMathFileToStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            val exportName = if (isModulesMode) {
                saveCurrentModuleFile(showToast = false, checkPromotion = false)
                activeModuleFileName
            } else {
                saveCurrentEvalFile(showToast = false)
                activeEvalFileName
            }
            exportMathFileLauncher.launch(exportName)
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open save picker: ${t.message}")
        }
    }

    private fun handleExportedMathFileUri(uri: Uri) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        try {
            val code = if (isModulesMode) {
                etModuleEditor?.text?.toString() ?: ""
            } else {
                etSessionEditor?.text?.toString() ?: ""
            }
            val fileName = if (isModulesMode) activeModuleFileName else activeEvalFileName
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(code)
            } ?: run {
                activity.showButtonToast("Unable to write to destination")
                return
            }
            if (isModulesMode) {
                tvModuleQuickStatus?.text = "💾 Exported module '$fileName' (${code.length} chars)."
            } else {
                tvSessionQuickStatus?.text = "💾 Exported file '$fileName' (${code.length} chars) independently."
            }
            activity.showButtonToast("Exported file $fileName")
        } catch (t: Throwable) {
            activity.showButtonToast("Export failed: ${t.message}")
        }
    }

    // =========================================================================
    //  Mode & Sub-Tab Visibility + Theme Synchronization
    // =========================================================================

    private fun updateModeVisibility(animate: Boolean) {
        btnModeModules?.isAccentKey = isModulesMode
        btnModeSessions?.isAccentKey = !isModulesMode
        if (isModulesMode) {
            sessionsSection?.visibility = View.GONE
            modulesSection?.visibility = View.VISIBLE
            if (animate) {
                btnModeModules?.let { springPulse(it, 0.06f) }
                modulesSection?.let { springSlideIn(it) }
            }
        } else {
            modulesSection?.visibility = View.GONE
            sessionsSection?.visibility = View.VISIBLE
            refreshActiveSessionLogAndVars()
            if (animate) {
                btnModeSessions?.let { springPulse(it, 0.06f) }
                sessionsSection?.let { springSlideIn(it) }
            }
        }
    }

    private fun updateModuleSubTabVisibility(animate: Boolean) {
        btnModuleSubEditor?.isAccentKey = isModuleEditorSubTab
        btnModuleSubLog?.isAccentKey = !isModuleEditorSubTab
        if (isModuleEditorSubTab) {
            moduleLogPane?.visibility = View.GONE
            moduleEditorPane?.visibility = View.VISIBLE
            if (animate) {
                btnModuleSubEditor?.let { springPulse(it, 0.06f) }
                moduleEditorPane?.let { springSlideIn(it) }
            }
        } else {
            moduleEditorPane?.visibility = View.GONE
            moduleLogPane?.visibility = View.VISIBLE
            if (animate) {
                btnModuleSubLog?.let { springPulse(it, 0.06f) }
                moduleLogPane?.let { springSlideIn(it) }
            }
        }
    }

    private fun updateSessionSubTabVisibility(animate: Boolean) {
        btnSessionSubEditor?.isAccentKey = isSessionEditorSubTab
        btnSessionSubLog?.isAccentKey = !isSessionEditorSubTab
        if (isSessionEditorSubTab) {
            sessionLogPane?.visibility = View.GONE
            sessionEditorPane?.visibility = View.VISIBLE
            if (animate) {
                btnSessionSubEditor?.let { springPulse(it, 0.06f) }
                sessionEditorPane?.let { springSlideIn(it) }
            }
        } else {
            sessionEditorPane?.visibility = View.GONE
            sessionLogPane?.visibility = View.VISIBLE
            refreshActiveSessionLogAndVars()
            if (animate) {
                btnSessionSubLog?.let { springPulse(it, 0.06f) }
                sessionLogPane?.let { springSlideIn(it) }
            }
        }
    }

    fun applyTheme(dark: Boolean) {
        val root = rootLayout ?: return
        val density = resources.displayMetrics.density
        val bg = Color.parseColor(if (dark) "#2D2D2D" else "#F8F8F8")
        val cardBg = Color.parseColor(if (dark) "#252525" else "#ECECEC")
        val editorBg = Color.parseColor(if (dark) "#1E1E1E" else "#FFFFFF")
        val strokeColor = Color.parseColor(if (dark) "#3E3E3E" else "#D6D6D6")
        val primaryText = Color.parseColor(if (dark) "#E0E0E0" else "#333333")
        val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")
        val accentText = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

        root.setBackgroundColor(bg)
        tvWorkspaceTitle?.setTextColor(primaryText)
        tvActiveModuleName?.setTextColor(accentText)
        tvActiveSessionFileName?.setTextColor(accentText)
        tvSessionsGroupLabel?.setTextColor(secondaryText)
        tvEvalFilesGroupLabel?.setTextColor(secondaryText)

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
        styleCard(moduleLogCard, cardBg)

        styleCard(etSessionEditor, editorBg)
        styleCard(sessionQuickStatusCard, cardBg)
        styleCard(varsCard, cardBg)
        styleCard(sessionLogCard, cardBg)

        etModuleEditor?.setTextColor(primaryText)
        etModuleEditor?.setHintTextColor(secondaryText)
        tvModuleQuickStatus?.setTextColor(secondaryText)
        tvModuleLogOutput?.setTextColor(primaryText)

        etSessionEditor?.setTextColor(primaryText)
        etSessionEditor?.setHintTextColor(secondaryText)
        tvSessionQuickStatus?.setTextColor(secondaryText)

        tvVarsHeader?.setTextColor(accentText)
        tvVarsContent?.setTextColor(primaryText)
        tvSessionLogOutput?.setTextColor(primaryText)

        listOf(
            btnBackCalc, btnModeModules, btnModeSessions,
            btnNewModule, btnSaveModule, btnDeleteModule, btnImportModule, btnExportModule,
            btnModuleSubEditor, btnModuleSubLog,
            btnCompileModule, btnPromoteModule, btnModuleViewLog,
            btnModuleBackToEditor, btnClearModuleLog,
            btnNewSession, btnResetSession, btnCloseSession, btnLoadSession, btnExportSession,
            btnNewEvalFile, btnSaveEvalFile, btnDeleteEvalFile, btnImportEvalFile, btnExportEvalFile,
            btnSessionSubEditor, btnSessionSubLog,
            btnRunSessionScript, btnSessionViewLog,
            btnSessionBackToEditor, btnClearSessionLog
        ).forEach { it?.isDarkThemeMode = dark }

        refreshModuleChips()
        refreshSessionChips()
        refreshEvalFileChips()
    }

    // =========================================================================
    //  Damped-Spring Physics Helpers
    // =========================================================================

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

    fun wobbleRound(cx: Float, cy: Float, maxRadius: Float, revealDurationMs: Long) {
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        val speed = maxRadius / revealDurationMs
        val density = resources.displayMetrics.density
        val views = listOfNotNull(
            topHeaderBar, modeToggleBar,
            moduleSubTabBar, etModuleEditor, moduleQuickStatusCard, moduleLogCard,
            sessionSubTabBar, etSessionEditor, sessionQuickStatusCard, varsCard, sessionLogCard
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
        saveCurrentModuleFile(showToast = false, checkPromotion = false)
        saveCurrentEvalFile(showToast = false)
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        springAnimators.forEach { it.second.cancel() }
        springAnimators.clear()
        super.onDestroyView()
    }
}
