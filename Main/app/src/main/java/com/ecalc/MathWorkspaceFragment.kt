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
 * 2. **Sessions (`⚡ Sessions`)**: Each session owns its own `.math` evaluation file and
 *    native engine session, providing inner `📝 Editor` (for `.math` file editing and running)
 *    and `📋 Log` (for viewing evaluation logs and live MSP1 session variables) sub-tabs.
 */
class MathWorkspaceFragment : Fragment() {

    data class SessionEntry(
        var sessionId: Int,
        var fileName: String,
        var logText: String = "",
        var varsSummary: String = ""
    )

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
    private var sessionChipsRow: LinearLayout? = null
    private var btnNewSession: NeumorphicKeyView? = null
    private var btnSaveSessionFile: NeumorphicKeyView? = null
    private var btnResetSession: NeumorphicKeyView? = null
    private var btnCloseSession: NeumorphicKeyView? = null
    private var btnImportSessionFile: NeumorphicKeyView? = null
    private var btnExportSessionFile: NeumorphicKeyView? = null

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
    private var btnExportSnap: NeumorphicKeyView? = null
    private var btnRestoreSnap: NeumorphicKeyView? = null
    private var btnClearSessionLog: NeumorphicKeyView? = null

    // --- Workspace State ---
    private var isModulesMode: Boolean = true
    private var isModuleEditorSubTab: Boolean = true
    private var isSessionEditorSubTab: Boolean = true

    private var activeModuleFileName: String = "custom_module.math"
    private val moduleLogs = mutableMapOf<String, String>()

    private val sessionEntries = mutableListOf<SessionEntry>()
    private var activeSessionIndex: Int = 0
    private var exportedSnapshotCache: String = ""

    private val wobbleAnimators = mutableListOf<ValueAnimator>()
    private val springAnimators = mutableListOf<Pair<View, ValueAnimator>>()

    /** SAF Launcher to load/import a `.math` file from device storage. */
    private val importScriptLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportedUri(uri)
        }
    }

    /** SAF Launcher to export/save the active `.math` file to device storage. */
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
            ).apply { bottomMargin = dp(8f) }
        }
        modeToggleBar = modeRow

        btnModeModules = NeumorphicKeyView(ctx).apply {
            text = "🧩 Modules"
            textSize = 13.5f
            cornerRadiusDp = 14f
            shadowPadDp = 3.5f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(4f) }
            setOnClickListener {
                if (!isModulesMode) {
                    saveActiveSessionEditorBuffer(showToast = false)
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
            layoutParams = LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginStart = dp(4f) }
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

        // 4. Build Sessions Section (with inner Editor & Log tabs)
        sessionsSection = buildSessionsSection(ctx, activity, dp)
        root.addView(sessionsSection)

        // Apply theme colors immediately (no early return on view == null!)
        applyTheme(dark)
        refreshModuleChips()
        loadModuleIntoEditor(activeModuleFileName, animate = false)
        initializeSessionsFromFiles(ctx)
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
                    dp(42f)
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
                    launchImportFromStorage()
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
                    launchExportToStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Export current .math module to device storage")
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
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginEnd = dp(4f) }
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
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginStart = dp(4f) }
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
                    layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1.35f).apply { marginEnd = dp(4f) }
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
                    layoutParams = LinearLayout.LayoutParams(0, dp(42f), 0.95f).apply { marginEnd = dp(4f) }
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
                    layoutParams = LinearLayout.LayoutParams(dp(74f), dp(42f))
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
                    layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1f).apply { marginEnd = dp(6f) }
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
                    layoutParams = LinearLayout.LayoutParams(dp(96f), dp(42f))
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
    //  Section 2: Sessions UI Builder (Session Chips + Actions + [Editor | Log])
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

            // Row 1: Full-width horizontal scroll of Session chips (each backed by a .math evaluation file)
            val sessScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(42f)
                ).apply { bottomMargin = dp(4f) }
            }
            sessionChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(2f), 0)
            }
            sessScroll.addView(sessionChipsRow)
            addView(sessScroll)

            // Row 2: Session & Evaluation File Action Bar (+ New | Save | Reset | Close | 📂 Load | 💾 Export)
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
                    textSize = 11f
                    cornerRadiusDp = 12f
                    shadowPadDp = 3f
                    isAccentKey = accent
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), weight).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

            btnNewSession = makeSessBtn("+ New", true, 0.95f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptNewSessionEvaluationFileDialog()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Create a new stateful Session with its own .math evaluation file"
                    ) else activity.dismissButtonPopup()
                }
            }
            btnSaveSessionFile = makeSessBtn("Save", false, 0.85f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    saveActiveSessionEditorBuffer(showToast = true)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Save current session's .math evaluation file")
                    else activity.dismissButtonPopup()
                }
            }
            btnResetSession = makeSessBtn("Reset", false, 0.85f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    resetActiveSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Reset session variables and evaluation log")
                    else activity.dismissButtonPopup()
                }
            }
            btnCloseSession = makeSessBtn("Close", false, 0.85f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    closeActiveSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Close active session")
                    else activity.dismissButtonPopup()
                }
            }
            btnImportSessionFile = makeSessBtn("📂 Load", true, 1.05f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchImportFromStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Load a .math evaluation script into a session")
                    else activity.dismissButtonPopup()
                }
            }
            btnExportSessionFile = makeSessBtn("💾 Export", true, 1.1f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchExportToStorage()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Export active session's .math file to storage")
                    else activity.dismissButtonPopup()
                }
            }

            sessBar.addView(btnNewSession)
            sessBar.addView(btnSaveSessionFile)
            sessBar.addView(btnResetSession)
            sessBar.addView(btnCloseSession)
            sessBar.addView(btnImportSessionFile)
            sessBar.addView(btnExportSessionFile)
            addView(sessBar)

            // Row 3: Inner Sub-Tab Bar for Sessions: [📝 Editor] | [📋 Log]
            val sessSubRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }
            sessionSubTabBar = sessSubRow

            btnSessionSubEditor = NeumorphicKeyView(ctx).apply {
                text = "📝 Editor"
                textSize = 12.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginEnd = dp(4f) }
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
                layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginStart = dp(4f) }
                setOnClickListener {
                    if (isSessionEditorSubTab) {
                        saveActiveSessionEditorBuffer(showToast = false)
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
                    text = "Session #1 — Editing: physics_sim.math"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(accentText)
                    setPadding(dp(4f), 0, dp(4f), dp(4f))
                }
                addView(tvActiveSessionFileName)

                etSessionEditor = EditText(ctx).apply {
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
                    hint = "# Write stateful .math expressions, assignments (x := 10), and functions (f(t) := t^2)..."
                }
                addView(etSessionEditor)

                val sessExecRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6f) }
                }

                btnRunSessionScript = NeumorphicKeyView(ctx).apply {
                    text = "▶ Run Script in Session"
                    textSize = 13f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(42f), 1.5f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        runActiveSessionScript(switchToLogAfterRun = false)
                    }
                    setOnLongClickListener { true }
                    holdListener = { held ->
                        if (held) activity.showButtonPopup(
                            this,
                            "Evaluate all statements in this session's .math file and record results in the Log tab"
                        ) else activity.dismissButtonPopup()
                    }
                }

                btnSessionViewLog = NeumorphicKeyView(ctx).apply {
                    text = "📋 View Log"
                    textSize = 12.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(42f), 0.9f)
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        saveActiveSessionEditorBuffer(showToast = false)
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
                    setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        isSessionEditorSubTab = false
                        updateSessionSubTabVisibility(animate = true)
                    }
                }
                tvSessionQuickStatus = TextView(ctx).apply {
                    text = "Ready. Tap '▶ Run Script in Session' to evaluate, or switch to '📋 Log' to inspect output & MSP1 variables."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(secondaryText)
                    maxLines = 2
                }
                sessionQuickStatusCard?.addView(tvSessionQuickStatus)
                addView(sessionQuickStatusCard)
            }
            addView(sessionEditorPane)

            // --- Session Sub-Pane B: Evaluation Log & MSP1 Variables Pane ---
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
                    text = "No variables bound yet. Run the session's .math script in the Editor tab."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(primaryText)
                    setPadding(0, dp(4f), 0, 0)
                }
                vCard.addView(tvVarsHeader)
                vCard.addView(tvVarsContent)
                addView(vCard)

                // Full scrollable Evaluation Log Console card (like the original .math files console)
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
                    text = "Ready. Tap '▶ Run Script in Session' in the Editor tab to evaluate statements."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(primaryText)
                    setTextIsSelectable(true)
                }
                sLogScroll.addView(tvSessionLogOutput)
                sLogCard.addView(sLogScroll)
                addView(sLogCard)

                // Bottom Log Action Row: [📝 Editor] [📤 Snap] [📥 Restore] [Clear]
                val sLogActions = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }

                btnSessionBackToEditor = NeumorphicKeyView(ctx).apply {
                    text = "📝 Editor"
                    textSize = 12f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1.05f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        isSessionEditorSubTab = true
                        updateSessionSubTabVisibility(animate = true)
                    }
                }
                btnExportSnap = NeumorphicKeyView(ctx).apply {
                    text = "📤 Snap"
                    textSize = 11.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 0.95f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.06f)
                        exportActiveSessionSnapshots()
                    }
                }
                btnRestoreSnap = NeumorphicKeyView(ctx).apply {
                    text = "📥 Restore"
                    textSize = 11.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1.05f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.06f)
                        restoreSessionFromSnapshotDialog()
                    }
                }
                btnClearSessionLog = NeumorphicKeyView(ctx).apply {
                    text = "Clear"
                    textSize = 11.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.5f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(40f), 0.8f)
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        val entry = currentSessionEntry() ?: return@setOnClickListener
                        entry.logText = "=== Session #${entry.sessionId} (${entry.fileName}) ===\n(Log cleared)"
                        tvSessionLogOutput?.text = entry.logText
                        sessionLogCard?.let { springPulse(it, 0.04f) }
                    }
                }

                sLogActions.addView(btnSessionBackToEditor)
                sLogActions.addView(btnExportSnap)
                sLogActions.addView(btnRestoreSnap)
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
     * `math_sessions/` so it opens as its own evaluation Session.
     */
    private fun ensureSampleAndClassifiedFiles(ctx: Context) {
        val legDir = legacyMathDir(ctx)
        val modDir = modulesDir(ctx)
        val sessDir = sessionsDir(ctx)

        // Remove old internal trig_ext.math if present
        File(legDir, "trig_ext.math").takeIf { it.exists() }?.delete()
        File(modDir, "trig_ext.math").takeIf { it.exists() }?.delete()

        // Migrate any existing .math files from legacy math_programs/ based on classification
        legDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { file ->
            val content = runCatching { file.readText() }.getOrDefault("")
            val targetDir = if (MathEngineManager.isTrueModuleSource(content)) modDir else sessDir
            val dest = File(targetDir, file.name)
            if (!dest.exists()) {
                dest.writeText(content)
            }
            file.delete()
        }

        // Seed default custom_module.math in modulesDir if none exist
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

        // Ensure at least one valid module file exists in modulesDir after promotion scan
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

        // Seed default session evaluation .math files if sessionsDir is empty
        val existingSessFiles = sessDir.listFiles { f -> f.isFile && f.name.endsWith(".math") } ?: emptyArray()
        if (existingSessFiles.isEmpty()) {
            File(sessDir, "physics_sim.math").writeText(
                "# Stateful Session Evaluation Script (.math)\n" +
                "# Tap '▶ Run Script in Session' to evaluate line-by-line and inspect results in the Log tab!\n\n" +
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
                "# Tap '▶ Run Script in Session' to evaluate in this session!\n\n" +
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
    }

    /**
     * Promotes a `.math` file from the Modules section into a dedicated Session
     * in the Sessions section when it is classified as a standard `.math` evaluation
     * script rather than a true `[module]`.
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

        // Write evaluation file to sessionsDir and remove from modulesDir
        val targetSessionFile = File(sessDir, safeName)
        val finalContent = if (code.isBlank()) "# Promoted Session Script: $safeName\n\nx := 10\nx * 2\n" else code
        targetSessionFile.writeText(finalContent)
        File(modDir, safeName).takeIf { it.exists() }?.delete()

        // Ensure modulesDir still has at least one valid [module] file so the Modules tab stays populated
        val remainingMods = modDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (remainingMods.isEmpty()) {
            ensureSampleAndClassifiedFiles(ctx)
        }
        val updatedMods = modDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        activeModuleFileName = updatedMods.firstOrNull()?.name ?: "custom_module.math"
        refreshModuleChips()
        loadModuleIntoEditor(activeModuleFileName, animate = false)

        val existingIdx = sessionEntries.indexOfFirst { it.fileName == safeName }
        if (existingIdx >= 0) {
            activeSessionIndex = existingIdx
            switchToPromotedSession(safeName, autoEvaluate)
        } else {
            val placeholder = SessionEntry(
                sessionId = -1,
                fileName = safeName,
                logText = "🚀 Promoted '$safeName' from Modules into a dedicated evaluation Session."
            )
            sessionEntries.add(placeholder)
            val newIdx = sessionEntries.lastIndex
            activeSessionIndex = newIdx
            MathEngineManager.openSession { sid ->
                activity.runOnUiThread {
                    val assignedSid = if (sid > 0) sid else (newIdx + 1)
                    placeholder.sessionId = assignedSid
                    placeholder.logText = "=== Session #$assignedSid ($safeName) ===\n" +
                        "🚀 Promoted '$safeName' from Modules into Session #$assignedSid as a standard .math evaluation file."
                    switchToPromotedSession(safeName, autoEvaluate)
                }
            }
        }
    }

    private fun switchToPromotedSession(promotedFileName: String, autoEvaluate: Boolean) {
        val activity = activity as? MainActivity ?: return
        val entry = currentSessionEntry() ?: return

        val promoMsg = "🚀 Promoted '$promotedFileName' to Session #${entry.sessionId} (standard .math evaluation file)."
        tvModuleQuickStatus?.text = promoMsg
        moduleLogs[activeModuleFileName] = promoMsg

        isModulesMode = false
        isSessionEditorSubTab = true
        refreshSessionChips()
        loadActiveSessionIntoViews(animate = true)
        updateModeVisibility(animate = true)
        updateSessionSubTabVisibility(animate = false)

        activity.showButtonToast("Promoted $promotedFileName to Session #${entry.sessionId}")
        if (autoEvaluate) {
            runActiveSessionScript(switchToLogAfterRun = false)
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
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(38f)).apply {
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

    /**
     * Saves the active module file. If `checkPromotion` is true and the file is not
     * a true `[module]` (i.e. it is a standard `.math` evaluation script), promotes
     * it into a separate Session in the Sessions tab.
     */
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

        // If this file is not a true [module] and is a standard .math evaluation file,
        // automatically promote it into a separate Session and evaluate it there!
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
    //  Sessions Operations (Each Session = .math Evaluation File + Engine Session)
    // =========================================================================

    private fun currentSessionEntry(): SessionEntry? =
        sessionEntries.getOrNull(activeSessionIndex)

    private fun initializeSessionsFromFiles(ctx: Context) {
        if (sessionEntries.isNotEmpty()) {
            refreshSessionChips()
            loadActiveSessionIntoViews(animate = false)
            return
        }
        val files = sessionsDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".math") }
            ?.sortedBy { it.name } ?: emptyList()

        files.forEachIndexed { idx, file ->
            val entry = SessionEntry(
                sessionId = idx + 1,
                fileName = file.name,
                logText = "=== Session #${idx + 1} (${file.name}) ===\nReady. Tap '▶ Run Script in Session' in the Editor tab to evaluate."
            )
            sessionEntries.add(entry)
            MathEngineManager.openSession { sid ->
                activity?.runOnUiThread {
                    if (sid > 0) {
                        entry.sessionId = sid
                        refreshSessionChips()
                        if (currentSessionEntry() === entry) {
                            updateActiveSessionLabels()
                        }
                    }
                }
            }
        }
        activeSessionIndex = 0
        refreshSessionChips()
        loadActiveSessionIntoViews(animate = false)
    }

    private fun refreshSessionChips() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val row = sessionChipsRow ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        row.removeAllViews()
        sessionEntries.forEachIndexed { idx, entry ->
            val selected = idx == activeSessionIndex
            val label = "S${entry.sessionId} · ${entry.fileName}"
            val chip = NeumorphicKeyView(ctx).apply {
                text = label
                textSize = 11.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = selected
                isDarkThemeMode = activity.isDarkMode
                val w = (label.length * 7.8f + 34f).coerceAtLeast(116f)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(38f)).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    saveActiveSessionEditorBuffer(showToast = false)
                    activeSessionIndex = idx
                    refreshSessionChips()
                    loadActiveSessionIntoViews(animate = true)
                }
            }
            row.addView(chip)
        }
    }

    private fun updateActiveSessionLabels() {
        val entry = currentSessionEntry() ?: return
        tvActiveSessionFileName?.text = "Session #${entry.sessionId} — Editing: ${entry.fileName}"
        tvVarsHeader?.text = "Session #${entry.sessionId} Variables (${entry.fileName} · MSP1 State)"
        tvVarsContent?.text = entry.varsSummary.ifBlank {
            "No variables bound yet in Session #${entry.sessionId}. Run ${entry.fileName} in the Editor tab."
        }
        tvSessionLogOutput?.text = entry.logText.ifBlank {
            "=== Session #${entry.sessionId} (${entry.fileName}) ===\nReady. Tap '▶ Run Script in Session' in the Editor tab."
        }
    }

    private fun loadActiveSessionIntoViews(animate: Boolean) {
        val ctx = context ?: return
        val entry = currentSessionEntry() ?: return
        val file = File(sessionsDir(ctx), entry.fileName)
        if (file.exists()) {
            etSessionEditor?.setText(file.readText())
        } else {
            etSessionEditor?.setText("")
        }
        updateActiveSessionLabels()
        if (entry.sessionId > 0) {
            MathEngineManager.inspectSessionVariables(entry.sessionId) { summary ->
                activity?.runOnUiThread {
                    entry.varsSummary = summary
                    if (currentSessionEntry() === entry) {
                        updateActiveSessionLabels()
                    }
                }
            }
        }
        if (animate) {
            val activePane = if (isSessionEditorSubTab) sessionEditorPane else sessionLogPane
            activePane?.let { springSlideIn(it) }
            tvActiveSessionFileName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun saveActiveSessionEditorBuffer(showToast: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val entry = currentSessionEntry() ?: return
        val code = etSessionEditor?.text?.toString() ?: return
        File(sessionsDir(ctx), entry.fileName).writeText(code)
        if (showToast) {
            activity.showButtonToast("Saved ${entry.fileName} (Session #${entry.sessionId})")
            tvActiveSessionFileName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun runActiveSessionScript(switchToLogAfterRun: Boolean) {
        saveActiveSessionEditorBuffer(showToast = false)
        val activity = activity as? MainActivity ?: return
        val entry = currentSessionEntry() ?: return
        val code = etSessionEditor?.text?.toString() ?: ""

        fun executeWithSid(sid: Int) {
            MathEngineManager.runProgramScript(sid, code) { results, snapSummary ->
                activity.runOnUiThread {
                    val sb = StringBuilder()
                    sb.append("=== Executed ${entry.fileName} (Session #$sid) ===\n")
                    var lastSummaryLine = "(No executable statements found)"
                    if (results.isEmpty()) {
                        sb.append("(No executable statements found)\n")
                    } else {
                        results.forEachIndexed { idx, (stmt, outcome) ->
                            if (outcome.ok) {
                                val u = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                                val line = "[${idx + 1}] $stmt  ⟹  ${outcome.value}$u (${outcome.type})"
                                sb.append(line).append("\n")
                                lastSummaryLine = "✅ [${results.size} stmts] Last: $stmt ⟹ ${outcome.value}$u"
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
                    entry.logText = sb.toString().trimEnd()
                    entry.varsSummary = snapSummary
                    updateActiveSessionLabels()
                    tvSessionQuickStatus?.text = "$lastSummaryLine  (Tap to open 📋 Log)"
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

        if (entry.sessionId > 0) {
            executeWithSid(entry.sessionId)
        } else {
            MathEngineManager.openSession { newSid ->
                activity.runOnUiThread {
                    val validSid = if (newSid > 0) newSid else 1
                    entry.sessionId = validSid
                    refreshSessionChips()
                    executeWithSid(validSid)
                }
            }
        }
    }

    private fun promptNewSessionEvaluationFileDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dark = activity.isDarkMode
        val nextNum = sessionEntries.size + 1
        val input = EditText(ctx).apply {
            hint = "session_${nextNum}.math"
            setSingleLine(true)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            setHintTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
        }
        AlertDialog.Builder(ctx)
            .setTitle("New Session & .math Evaluation File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                saveActiveSessionEditorBuffer(showToast = false)
                var name = input.text.toString().trim().ifEmpty { "session_${nextNum}.math" }
                if (!name.endsWith(".math")) name += ".math"
                val file = File(sessionsDir(ctx), name)
                if (!file.exists()) {
                    file.writeText(
                        "# Session Evaluation Script: $name\n\n" +
                        "a := 12.5\n" +
                        "b := 4.0\n" +
                        "result := a * b + sqrt(a)\n" +
                        "result\n"
                    )
                }
                MathEngineManager.openSession { sid ->
                    activity.runOnUiThread {
                        val assignedSid = if (sid > 0) sid else (sessionEntries.size + 1)
                        val newEntry = SessionEntry(
                            sessionId = assignedSid,
                            fileName = name,
                            logText = "=== Session #$assignedSid ($name) ===\nReady. Tap '▶ Run Script in Session' to evaluate."
                        )
                        sessionEntries.add(newEntry)
                        activeSessionIndex = sessionEntries.lastIndex
                        isSessionEditorSubTab = true
                        refreshSessionChips()
                        loadActiveSessionIntoViews(animate = true)
                        updateSessionSubTabVisibility(animate = false)
                        activity.showButtonToast("Opened Session #$assignedSid ($name)")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetActiveSession() {
        val activity = activity as? MainActivity ?: return
        val entry = currentSessionEntry() ?: return
        val sid = entry.sessionId
        if (sid <= 0) return
        MathEngineManager.resetSession(sid) { ok ->
            activity.runOnUiThread {
                entry.varsSummary = ""
                entry.logText = "=== Session #$sid (${entry.fileName}) ===\n(Session state & variables reset)"
                updateActiveSessionLabels()
                tvSessionQuickStatus?.text = "Session #$sid reset. Tap '▶ Run Script in Session' to re-evaluate."
                varsCard?.let { springPulse(it, 0.05f) }
                sessionLogCard?.let { springPulse(it, 0.04f) }
                activity.showButtonToast(if (ok) "Session #$sid reset" else "Reset failed")
            }
        }
    }

    private fun closeActiveSession() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val entry = currentSessionEntry() ?: return
        if (sessionEntries.size <= 1) {
            resetActiveSession()
            return
        }
        val sid = entry.sessionId
        val closingIdx = activeSessionIndex
        MathEngineManager.closeSession(sid) { _ ->
            activity.runOnUiThread {
                File(sessionsDir(ctx), entry.fileName).takeIf { it.exists() }?.delete()
                sessionEntries.removeAt(closingIdx)
                activeSessionIndex = activeSessionIndex.coerceIn(0, sessionEntries.lastIndex)
                refreshSessionChips()
                loadActiveSessionIntoViews(animate = true)
                activity.showButtonToast("Closed Session #$sid")
            }
        }
    }

    private fun exportActiveSessionSnapshots() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val entry = currentSessionEntry() ?: return
        val sid = entry.sessionId
        if (sid <= 0) return
        MathEngineManager.exportSessionSnapshots(sid) { snaps ->
            activity.runOnUiThread {
                exportedSnapshotCache = snaps
                val snapFile = File(sessionsDir(ctx), "session_${sid}.msp1")
                snapFile.writeText(snaps)
                val preview = if (snaps.isBlank()) "(Empty session — run the .math script first)" else snaps
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
        val dark = activity.isDarkMode
        val input = EditText(ctx).apply {
            setText(exportedSnapshotCache)
            hint = "MSP1|seq:1|prev:00000000|hash:...|vars:..."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            setHintTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
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
                                val fileName = "restored_s${newSid}.math"
                                val restoredFile = File(sessionsDir(ctx), fileName)
                                if (!restoredFile.exists()) {
                                    restoredFile.writeText("# Restored Session #$newSid\n# Variables restored from MSP1 snapshot\n")
                                }
                                MathEngineManager.inspectSessionVariables(newSid) { summary ->
                                    activity.runOnUiThread {
                                        val restoredEntry = SessionEntry(
                                            sessionId = newSid,
                                            fileName = fileName,
                                            logText = "=== Session #$newSid ($fileName) ===\n✅ Restored MSP1 Snapshot into Session #$newSid.",
                                            varsSummary = summary
                                        )
                                        sessionEntries.add(restoredEntry)
                                        activeSessionIndex = sessionEntries.lastIndex
                                        isSessionEditorSubTab = false
                                        refreshSessionChips()
                                        loadActiveSessionIntoViews(animate = true)
                                        updateSessionSubTabVisibility(animate = true)
                                        activity.showButtonToast("Restored into Session #$newSid")
                                    }
                                }
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

    // =========================================================================
    //  Storage Import / Export (with Module-vs-Session Classification)
    // =========================================================================

    private fun launchImportFromStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            if (isModulesMode) {
                saveCurrentModuleFile(showToast = false, checkPromotion = false)
            } else {
                saveActiveSessionEditorBuffer(showToast = false)
            }
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

            val isTrueModule = MathEngineManager.isTrueModuleSource(content)
            if (isModulesMode && !isTrueModule && hasNonEmptyStatements(content)) {
                // Imported in Modules tab, but it's a standard .math evaluation file -> promote to Session!
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
                File(sessionsDir(ctx), displayName).writeText(content)
                val existingIdx = sessionEntries.indexOfFirst { it.fileName == displayName }
                if (existingIdx >= 0) {
                    activeSessionIndex = existingIdx
                    refreshSessionChips()
                    loadActiveSessionIntoViews(animate = true)
                } else {
                    MathEngineManager.openSession { sid ->
                        activity.runOnUiThread {
                            val assignedSid = if (sid > 0) sid else (sessionEntries.size + 1)
                            sessionEntries.add(
                                SessionEntry(
                                    sessionId = assignedSid,
                                    fileName = displayName,
                                    logText = "=== Session #$assignedSid ($displayName) ===\n📂 Loaded '$displayName' from storage."
                                )
                            )
                            activeSessionIndex = sessionEntries.lastIndex
                            refreshSessionChips()
                            loadActiveSessionIntoViews(animate = true)
                        }
                    }
                }
                activity.showButtonToast("Loaded $displayName into Session")
            }
        } catch (t: Throwable) {
            activity.showButtonToast("Import failed: ${t.message}")
        }
    }

    private fun launchExportToStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            val exportName = if (isModulesMode) {
                saveCurrentModuleFile(showToast = false, checkPromotion = false)
                activeModuleFileName
            } else {
                saveActiveSessionEditorBuffer(showToast = false)
                currentSessionEntry()?.fileName ?: "session.math"
            }
            exportScriptLauncher.launch(exportName)
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open save picker: ${t.message}")
        }
    }

    private fun handleExportedUri(uri: Uri) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        try {
            val code = if (isModulesMode) {
                etModuleEditor?.text?.toString() ?: ""
            } else {
                etSessionEditor?.text?.toString() ?: ""
            }
            val fileName = if (isModulesMode) activeModuleFileName else (currentSessionEntry()?.fileName ?: "session.math")
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(code)
            } ?: run {
                activity.showButtonToast("Unable to write to destination")
                return
            }
            if (isModulesMode) {
                tvModuleQuickStatus?.text = "💾 Exported '$fileName' (${code.length} chars) to storage."
            } else {
                tvSessionQuickStatus?.text = "💾 Exported '$fileName' (${code.length} chars) to storage."
            }
            activity.showButtonToast("Exported $fileName")
        } catch (t: Throwable) {
            activity.showButtonToast("Export failed: ${t.message}")
        }
    }

    // =========================================================================
    //  Mode & Sub-Tab Visibility + Theme Synchronization (Fix 2 & Fix 3)
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
            updateActiveSessionLabels()
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
            updateActiveSessionLabels()
            if (animate) {
                btnSessionSubLog?.let { springPulse(it, 0.06f) }
                sessionLogPane?.let { springSlideIn(it) }
            }
        }
    }

    /**
     * Applies dark/light theme colors across all views in the workspace.
     * Uses `rootLayout == null` instead of `view == null` so calling `applyTheme`
     * during `onCreateView()` or from `MainActivity.onCreate()` always succeeds on first load.
     */
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
            btnNewSession, btnSaveSessionFile, btnResetSession, btnCloseSession,
            btnImportSessionFile, btnExportSessionFile,
            btnSessionSubEditor, btnSessionSubLog,
            btnRunSessionScript, btnSessionViewLog,
            btnSessionBackToEditor, btnExportSnap, btnRestoreSnap, btnClearSessionLog
        ).forEach { it?.isDarkThemeMode = dark }

        refreshModuleChips()
        refreshSessionChips()
    }

    // =========================================================================
    //  Damped-Spring Physics Helpers
    // =========================================================================

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
        saveActiveSessionEditorBuffer(showToast = false)
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        springAnimators.forEach { it.second.cancel() }
        springAnimators.clear()
        super.onDestroyView()
    }
}
