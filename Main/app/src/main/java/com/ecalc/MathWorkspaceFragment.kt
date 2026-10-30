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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.File
import kotlin.math.hypot

/**
 * Free-form `.math` Modules & Isolated Sessions Workspace with Per-Session Left-Drawer File Manager.
 *
 * Key architecture:
 * 1. **Top Header Bar**:
 *    - Leftmost **`☰` Hamburger Menu button** (`btnHamburgerMenu`) opens/toggles the left-side
 *      sliding **Session File Manager Drawer**.
 *    - Right next to it, the **`← Calc` button** (`btnBackCalc`) returns to the Basic Calculator.
 * 2. **Strict Per-Session Isolation (`⚡ Sessions`)**:
 *    - Each session `sid` (`Session #1`, `Session #2`, ...) owns an isolated directory on disk
 *      (`filesDir/math_sessions/session_<sid>/`), an isolated open-files list (`sessionOpenFiles[sid]`),
 *      an isolated active editor file (`activeEvalFileBySession[sid]`), an isolated scratch buffer
 *      (`sessionUnsavedBuffers[sid]`), and isolated MSP1 variables & evaluation logs.
 *    - Creating a new session (e.g. `Session #2`) starts completely isolated with zero files
 *      carried over from `Session #1`.
 * 3. **Left-Side Sliding Session File Manager Drawer**:
 *    - Provides complete per-session file operations:
 *      `+ New`, `📂 Open`, `✖ Close`, `💾 Save`, `✏ Rename / Copy`, `🗑 Delete`,
 *      `📥 Import In`, `📤 Export Out`, `▶ Run`, and `✖ Close All`.
 */
class MathWorkspaceFragment : Fragment() {

    private var workspaceRootFrame: FrameLayout? = null
    private var rootLayout: LinearLayout? = null
    private var topHeaderBar: LinearLayout? = null
    private var btnHamburgerMenu: NeumorphicKeyView? = null
    private var btnBackCalc: NeumorphicKeyView? = null
    private var tvWorkspaceTitle: TextView? = null

    private var modeToggleBar: LinearLayout? = null
    private var btnModeModules: NeumorphicKeyView? = null
    private var btnModeSessions: NeumorphicKeyView? = null

    // --- Left-Side Sliding File Manager Drawer ---
    var isFileManagerDrawerOpen: Boolean = false
        private set
    private var drawerScrimView: View? = null
    private var fileManagerDrawerPanel: LinearLayout? = null
    private var tvDrawerTitle: TextView? = null
    private var tvDrawerSubtitle: TextView? = null
    private var btnDrawerCloseIcon: NeumorphicKeyView? = null
    private var drawerSessionChipsRow: LinearLayout? = null
    private var drawerSelectedBannerCard: LinearLayout? = null
    private var tvDrawerSelectedFile: TextView? = null
    private var drawerFileListContainer: LinearLayout? = null

    // Drawer File Manager Action Buttons
    private var btnFmNew: NeumorphicKeyView? = null
    private var btnFmOpen: NeumorphicKeyView? = null
    private var btnFmClose: NeumorphicKeyView? = null
    private var btnFmSave: NeumorphicKeyView? = null
    private var btnFmRename: NeumorphicKeyView? = null
    private var btnFmDelete: NeumorphicKeyView? = null
    private var btnFmImportIn: NeumorphicKeyView? = null
    private var btnFmExportOut: NeumorphicKeyView? = null
    private var btnFmRun: NeumorphicKeyView? = null
    private var btnFmCloseAll: NeumorphicKeyView? = null

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

    // 2B. Per-Session Open .math Evaluation Files Bar & Quick File Controls
    private var tvEvalFilesGroupLabel: TextView? = null
    private var btnOpenFileManagerInline: NeumorphicKeyView? = null
    private var evalFileChipsRow: LinearLayout? = null
    private var btnNewEvalFile: NeumorphicKeyView? = null
    private var btnOpenEvalFile: NeumorphicKeyView? = null
    private var btnCloseEvalFile: NeumorphicKeyView? = null
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

    /**
     * Independent active sessions state (`sessionId` list + per-session isolated file sets,
     * active file, scratch editor buffer, selected file in File Manager, log, and MSP1 vars).
     */
    private val openSessionIds = mutableListOf<Int>()
    private var activeSessionId: Int = -1
    private val sessionLogs = mutableMapOf<Int, String>()
    private val sessionVarsCache = mutableMapOf<Int, String>()
    private val sessionOpenFiles = mutableMapOf<Int, MutableList<String>>()
    private val activeEvalFileBySession = mutableMapOf<Int, String?>()
    private val sessionUnsavedBuffers = mutableMapOf<Int, String>()
    private val sessionSelectedManagerFile = mutableMapOf<Int, String?>()

    private var pendingSessionExportPayload: String = ""
    private var pendingExportMathContent: String = ""
    private var pendingExportMathFileName: String = ""
    private var pendingImportTargetSessionId: Int = -1

    private val wobbleAnimators = mutableListOf<ValueAnimator>()
    private val springAnimators = mutableListOf<Pair<View, ValueAnimator>>()
    private var drawerAnimator: ValueAnimator? = null

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

        val outerFrame = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        workspaceRootFrame = outerFrame

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(14f), dp(42f), dp(14f), dp(12f))
        }
        rootLayout = root

        // 1. Top Header Bar: [☰ Hamburger Menu] + [← Calc] button + Title
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        topHeaderBar = headerRow

        btnHamburgerMenu = NeumorphicKeyView(ctx).apply {
            text = "☰"
            textSize = 17f
            cornerRadiusDp = 15f
            shadowPadDp = 3f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(40f)).apply {
                marginEnd = dp(6f)
            }
            setOnClickListener {
                springPulse(this, 0.08f)
                if (isModulesMode) {
                    saveCurrentModuleFile(showToast = false, checkPromotion = true)
                    isModulesMode = false
                    updateModeVisibility(animate = true)
                }
                toggleFileManagerDrawer(open = !isFileManagerDrawerOpen, animate = true)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(
                    this,
                    "Open / close isolated Session File Manager (Open, Close, Save, Delete, Import, Export)"
                ) else activity.dismissButtonPopup()
            }
        }
        headerRow.addView(btnHamburgerMenu)

        btnBackCalc = NeumorphicKeyView(ctx).apply {
            text = "← Calc"
            textSize = 13f
            cornerRadiusDp = 16f
            shadowPadDp = 3f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(80f), dp(40f)).apply {
                marginEnd = dp(10f)
            }
            setOnClickListener {
                springPulse(this, 0.07f)
                if (isFileManagerDrawerOpen) {
                    toggleFileManagerDrawer(open = false, animate = false)
                }
                activity.switchWorkspacePage(0)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(this, "Return to basic calculator (or swipe right)")
                else activity.dismissButtonPopup()
            }
        }
        headerRow.addView(btnBackCalc)

        tvWorkspaceTitle = TextView(ctx).apply {
            text = ".math Modules & Sessions"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
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
                    saveCurrentSessionEditorState(activeSessionId, showToast = false)
                    isModulesMode = true
                    if (isFileManagerDrawerOpen) {
                        toggleFileManagerDrawer(open = false, animate = true)
                    }
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

        // 4. Build Sessions Section (with Session bar, Isolated Open-File bar, and inner Editor & Log tabs)
        sessionsSection = buildSessionsSection(ctx, activity, dp)
        root.addView(sessionsSection)

        outerFrame.addView(root)

        // 5. Build Left-Side Sliding File Manager Drawer + Dimmed Scrim
        val scrim = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#88000000"))
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                toggleFileManagerDrawer(open = false, animate = true)
            }
        }
        drawerScrimView = scrim
        outerFrame.addView(scrim)

        val drawer = buildFileManagerDrawer(ctx, activity, dp)
        fileManagerDrawerPanel = drawer
        outerFrame.addView(drawer)

        applyTheme(dark)
        refreshModuleChips()
        loadModuleIntoEditor(activeModuleFileName, animate = false)
        ensureDefaultSession()
        updateModeVisibility(animate = false)
        updateModuleSubTabVisibility(animate = false)
        updateSessionSubTabVisibility(animate = false)

        return outerFrame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.let { applyTheme(it.isDarkMode) }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { applyTheme(it.isDarkMode) }
    }

    /** Handles system Back button when the File Manager drawer is open. */
    fun handleBackPressed(): Boolean {
        if (isFileManagerDrawerOpen) {
            toggleFileManagerDrawer(open = false, animate = true)
            return true
        }
        return false
    }

    // =========================================================================
    //  Left-Side Sliding File Manager Drawer Builder & Animation
    // =========================================================================

    private fun buildFileManagerDrawer(
        ctx: Context,
        activity: MainActivity,
        dp: (Float) -> Int
    ): LinearLayout {
        val dark = activity.isDarkMode
        val drawerWidthPx = dp(312f)
        val primaryText = Color.parseColor(if (dark) "#E0E0E0" else "#333333")
        val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")
        val accentText = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            translationX = -drawerWidthPx.toFloat()
            elevation = dp(18f).toFloat()
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                drawerWidthPx,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
            setPadding(dp(12f), dp(42f), dp(12f), dp(12f))

            // 1. Drawer Header: Title + Subtitle + Close [✕] button
            val headerRow = LinearLayout(ctx).apply {
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
            tvDrawerTitle = TextView(ctx).apply {
                text = "📁 Session #1 File Manager"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryText)
            }
            tvDrawerSubtitle = TextView(ctx).apply {
                text = "Isolated Workspace • 0 open / 0 stored"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(secondaryText)
            }
            titleCol.addView(tvDrawerTitle)
            titleCol.addView(tvDrawerSubtitle)
            headerRow.addView(titleCol)

            btnDrawerCloseIcon = NeumorphicKeyView(ctx).apply {
                text = "✕"
                textSize = 14f
                cornerRadiusDp = 13f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(38f), dp(36f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    toggleFileManagerDrawer(open = false, animate = true)
                }
            }
            headerRow.addView(btnDrawerCloseIcon)
            addView(headerRow)

            // 2. Session Switcher Strip inside Drawer
            val sessSwitchScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(34f)
                ).apply { bottomMargin = dp(6f) }
            }
            drawerSessionChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            sessSwitchScroll.addView(drawerSessionChipsRow)
            addView(sessSwitchScroll)

            // 3. Selected File Banner Card inside Drawer
            val selBanner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
            }
            drawerSelectedBannerCard = selBanner
            tvDrawerSelectedFile = TextView(ctx).apply {
                text = "Selected: None"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.MONOSPACE
                setTextColor(accentText)
                maxLines = 2
            }
            selBanner.addView(tvDrawerSelectedFile)
            addView(selBanner)

            // 4. Complete File Manager Operations Grid (Row 1 + Row 2 + Row 3)
            fun makeFmBtn(label: String, accent: Boolean, weight: Float = 1f): NeumorphicKeyView {
                return NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = 10.8f
                    cornerRadiusDp = 11f
                    shadowPadDp = 2.6f
                    isAccentKey = accent
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(35f), weight).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

            // Row A: [+ New] | [📂 Open] | [✖ Close] | [💾 Save]
            val fmRow1 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4f) }
            }
            btnFmNew = makeFmBtn("+ New", true, 1f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptNewEvalFileDialog()
                }
            }
            btnFmOpen = makeFmBtn("📂 Open", true, 1.05f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    openSelectedFileFromManager()
                }
            }
            btnFmClose = makeFmBtn("✖ Close", false, 1.05f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    closeSelectedOrActiveFileInSession()
                }
            }
            btnFmSave = makeFmBtn("💾 Save", false, 1f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    saveCurrentEvalFile(showToast = true)
                }
            }
            fmRow1.addView(btnFmNew)
            fmRow1.addView(btnFmOpen)
            fmRow1.addView(btnFmClose)
            fmRow1.addView(btnFmSave)
            addView(fmRow1)

            // Row B: [📥 Import In] | [📤 Export Out] | [✏ Rename] | [🗑 Delete]
            val fmRow2 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4f) }
            }
            btnFmImportIn = makeFmBtn("📥 Import", true, 1.1f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchImportMathFileIntoActiveSession()
                }
            }
            btnFmExportOut = makeFmBtn("📤 Export", true, 1.1f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    exportSelectedOrActiveSessionFile()
                }
            }
            btnFmRename = makeFmBtn("✏ Rename", false, 1.05f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptRenameOrCopySessionFileDialog()
                }
            }
            btnFmDelete = makeFmBtn("🗑 Delete", false, 1.0f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    confirmDeleteSelectedOrActiveFileInSession()
                }
            }
            fmRow2.addView(btnFmImportIn)
            fmRow2.addView(btnFmExportOut)
            fmRow2.addView(btnFmRename)
            fmRow2.addView(btnFmDelete)
            addView(fmRow2)

            // Row C: [▶ Open & Run in Session] | [✖ Close All Open]
            val fmRow3 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
            }
            btnFmRun = makeFmBtn("▶ Open & Run in Session", true, 1.45f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    runSelectedFileFromManager()
                }
            }
            btnFmCloseAll = makeFmBtn("✖ Close All", false, 0.95f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    closeAllOpenFilesInActiveSession()
                }
            }
            fmRow3.addView(btnFmRun)
            fmRow3.addView(btnFmCloseAll)
            addView(fmRow3)

            // 5. Scrollable List of Files Isolated to the Active Session
            val listScroll = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            drawerFileListContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            listScroll.addView(drawerFileListContainer)
            addView(listScroll)
        }
    }

    fun toggleFileManagerDrawer(open: Boolean, animate: Boolean) {
        val drawer = fileManagerDrawerPanel ?: return
        val scrim = drawerScrimView ?: return
        val density = resources.displayMetrics.density
        val drawerWidth = (312f * density)

        if (open) {
            refreshFileManagerDrawer()
        }

        drawerAnimator?.cancel()
        isFileManagerDrawerOpen = open

        if (!animate) {
            if (open) {
                drawer.visibility = View.VISIBLE
                scrim.visibility = View.VISIBLE
                drawer.translationX = 0f
                scrim.alpha = 1f
            } else {
                drawer.translationX = -drawerWidth
                scrim.alpha = 0f
                drawer.visibility = View.GONE
                scrim.visibility = View.GONE
            }
            return
        }

        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        val startTx = drawer.translationX
        val endTx = if (open) 0f else -drawerWidth
        val deltaTx = endTx - startTx
        val startAlpha = scrim.alpha
        val endAlpha = if (open) 1f else 0f

        drawerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                drawer.translationX = (endTx - deltaTx * springFactor).coerceAtMost(0f)
                scrim.alpha = (startAlpha + (endAlpha - startAlpha) * (f / 0.35f).coerceIn(0f, 1f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    drawer.translationX = endTx
                    scrim.alpha = endAlpha
                    if (!open) {
                        drawer.visibility = View.GONE
                        scrim.visibility = View.GONE
                    }
                }
            })
            start()
        }
    }

    private fun refreshFileManagerDrawer() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dark = activity.isDarkMode
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        val primaryText = Color.parseColor(if (dark) "#E0E0E0" else "#333333")
        val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")
        val accentText = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
        val rowBg = Color.parseColor(if (dark) "#262626" else "#EFEFEF")
        val selectedRowBg = Color.parseColor(if (dark) "#1D3638" else "#FCEAE6")
        val strokeColor = Color.parseColor(if (dark) "#3E3E3E" else "#D4D4D4")
        val selectedStroke = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

        val sid = activeSessionId
        // 1. Populate Drawer Session Switcher Chips
        drawerSessionChipsRow?.let { sRow ->
            sRow.removeAllViews()
            openSessionIds.forEach { id ->
                val sFilesCount = listSessionFiles(ctx, id).size
                val chip = NeumorphicKeyView(ctx).apply {
                    text = "⚡ S#$id ($sFilesCount)"
                    textSize = 10.5f
                    cornerRadiusDp = 10f
                    shadowPadDp = 2.5f
                    isAccentKey = (id == sid)
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(82f), dp(30f)).apply {
                        marginEnd = dp(4f)
                    }
                    setOnClickListener {
                        if (id != activeSessionId) {
                            switchToSession(id)
                        } else {
                            springPulse(this, 0.05f)
                        }
                    }
                }
                sRow.addView(chip)
            }
            val addSessChip = NeumorphicKeyView(ctx).apply {
                text = "+ Session"
                textSize = 10.5f
                cornerRadiusDp = 10f
                shadowPadDp = 2.5f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(78f), dp(30f))
                setOnClickListener {
                    springPulse(this, 0.06f)
                    openNewStandaloneSession()
                }
            }
            sRow.addView(addSessChip)
        }

        if (sid <= 0) {
            tvDrawerTitle?.text = "📁 Session File Manager"
            tvDrawerSubtitle?.text = "Session opening…"
            tvDrawerSelectedFile?.text = "Selected: None"
            drawerFileListContainer?.removeAllViews()
            return
        }

        val allFiles = listSessionFiles(ctx, sid)
        val openList = getOpenFilesForSession(sid)
        val activeFile = activeEvalFileBySession[sid]

        // Ensure selected manager file is valid for this session
        var selFile = sessionSelectedManagerFile[sid]
        if (selFile == null || allFiles.none { it.name == selFile }) {
            selFile = activeFile?.takeIf { af -> allFiles.any { it.name == af } }
                ?: allFiles.firstOrNull()?.name
            sessionSelectedManagerFile[sid] = selFile
        }

        tvDrawerTitle?.text = "📁 Session #$sid File Manager"
        tvDrawerSubtitle?.text = "Isolated to Session #$sid • ${openList.size} open / ${allFiles.size} stored"
        btnOpenFileManagerInline?.text = "☰ Files (${openList.size}/${allFiles.size})"

        val selStatus = when {
            selFile == null -> "None (Tap + New or 📥 Import)"
            selFile == activeFile && selFile in openList -> "$selFile  [🟢 ACTIVE IN EDITOR]"
            selFile in openList -> "$selFile  [🔵 OPEN IN SESSION #$sid]"
            else -> "$selFile  [⚪ CLOSED / STORED]"
        }
        tvDrawerSelectedFile?.text = "Selected: $selStatus"

        val listContainer = drawerFileListContainer ?: return
        listContainer.removeAllViews()

        if (allFiles.isEmpty()) {
            val emptyCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12f), dp(14f), dp(12f), dp(14f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(rowBg)
                    setStroke((1f * density).toInt(), strokeColor)
                }
            }
            val tvEmpty = TextView(ctx).apply {
                text = "Session #$sid has no files yet.\n\n" +
                    "• Each session is 100% isolated — files from Session #1 or other sessions are never active here.\n" +
                    "• Tap '+ New' above to create a file for Session #$sid, or '📥 Import' to load one from storage."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(secondaryText)
            }
            emptyCard.addView(tvEmpty)
            listContainer.addView(emptyCard)
            return
        }

        allFiles.forEach { file ->
            val fname = file.name
            val isOpen = fname in openList
            val isActive = (fname == activeFile && isOpen)
            val isSelected = (fname == selFile)
            val content = runCatching { file.readText() }.getOrDefault("")
            val lineCount = if (content.isEmpty()) 0 else content.lines().size

            val itemCard = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(if (isSelected) selectedRowBg else rowBg)
                    setStroke(((if (isSelected) 1.6f else 1f) * density).toInt(), if (isSelected) selectedStroke else strokeColor)
                }
                setOnClickListener {
                    sessionSelectedManagerFile[sid] = fname
                    refreshFileManagerDrawer()
                }
            }

            // Top line of file card: File Name + Status Badge
            val topLine = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val tvName = TextView(ctx).apply {
                text = "📄 $fname"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, if (isActive || isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isActive) accentText else primaryText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvBadge = TextView(ctx).apply {
                text = when {
                    isActive -> "🟢 ACTIVE"
                    isOpen -> "🔵 OPEN"
                    else -> "⚪ CLOSED"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isOpen) accentText else secondaryText)
            }
            topLine.addView(tvName)
            topLine.addView(tvBadge)
            itemCard.addView(topLine)

            // Second line: file metrics
            val tvMeta = TextView(ctx).apply {
                text = "Session #$sid • $lineCount lines • ${file.length()} B"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.MONOSPACE
                setTextColor(secondaryText)
                setPadding(0, dp(2f), 0, dp(5f))
            }
            itemCard.addView(tvMeta)

            // Third line: Quick per-file inline buttons: [Open / Activate] | [Close] | [Save] | [Export] | [Del]
            val inlineRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            fun makeRowActionBtn(label: String, accent: Boolean): NeumorphicKeyView {
                return NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = 10f
                    cornerRadiusDp = 9f
                    shadowPadDp = 2.2f
                    isAccentKey = accent
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(29f), 1f).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

            val btnRowOpen = makeRowActionBtn(if (isActive) "Active" else "Open", !isActive).apply {
                setOnClickListener {
                    sessionSelectedManagerFile[sid] = fname
                    openFileInSession(sid, fname, closeDrawerAfter = false)
                }
            }
            val btnRowClose = makeRowActionBtn("Close", false).apply {
                setOnClickListener {
                    sessionSelectedManagerFile[sid] = fname
                    closeSpecificFileInSession(sid, fname)
                }
            }
            val btnRowSave = makeRowActionBtn("Save", false).apply {
                setOnClickListener {
                    sessionSelectedManagerFile[sid] = fname
                    if (activeEvalFileBySession[sid] == fname) {
                        saveCurrentEvalFile(showToast = true)
                    } else {
                        activity.showButtonToast("Saved $fname in Session #$sid")
                    }
                    refreshFileManagerDrawer()
                }
            }
            val btnRowExport = makeRowActionBtn("Export", true).apply {
                setOnClickListener {
                    sessionSelectedManagerFile[sid] = fname
                    exportSpecificSessionFile(sid, fname)
                }
            }
            val btnRowDel = makeRowActionBtn("Del", false).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    sessionSelectedManagerFile[sid] = fname
                    confirmDeleteSpecificFileInSession(sid, fname)
                }
            }

            inlineRow.addView(btnRowOpen)
            inlineRow.addView(btnRowClose)
            inlineRow.addView(btnRowSave)
            inlineRow.addView(btnRowExport)
            inlineRow.addView(btnRowDel)
            itemCard.addView(inlineRow)

            listContainer.addView(itemCard)
        }
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
                            "Promote this .math file into a separate isolated evaluation Session"
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
    //  Section 2: Sessions UI Builder (Isolated Sessions + Per-Session Open Files)
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
                    textSize = 10.5f
                    cornerRadiusDp = 11f
                    shadowPadDp = 2.6f
                    isAccentKey = accent
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(35f), weight).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

            // -----------------------------------------------------------------
            // 2A. Active Sessions Bar & Session-Level Actions
            // -----------------------------------------------------------------
            tvSessionsGroupLabel = TextView(ctx).apply {
                text = "ACTIVE SESSIONS (EACH SESSION IS ISOLATED)"
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
                    if (held) activity.showButtonPopup(
                        this,
                        "Create a new isolated session (starts clean with no files from other sessions)"
                    ) else activity.dismissButtonPopup()
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
                    if (held) activity.showButtonPopup(this, "Close the current active session and its isolated state")
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
            // 2B. Isolated Session Open .math Files Bar & Quick File Actions
            // -----------------------------------------------------------------
            val filesHeaderRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2f) }
            }
            tvEvalFilesGroupLabel = TextView(ctx).apply {
                text = "SESSION #1 OPEN FILES (ISOLATED)"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(secondaryText)
                setPadding(dp(4f), 0, dp(4f), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            btnOpenFileManagerInline = NeumorphicKeyView(ctx).apply {
                text = "☰ Files"
                textSize = 10.5f
                cornerRadiusDp = 10f
                shadowPadDp = 2.4f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(94f), dp(28f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    toggleFileManagerDrawer(open = true, animate = true)
                }
            }
            filesHeaderRow.addView(tvEvalFilesGroupLabel)
            filesHeaderRow.addView(btnOpenFileManagerInline)
            addView(filesHeaderRow)

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

            btnNewEvalFile = makeBarBtn("+ New", true, 0.9f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptNewEvalFileDialog()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Create a new .math file inside this isolated session"
                    ) else activity.dismissButtonPopup()
                }
            }
            btnOpenEvalFile = makeBarBtn("Open", true, 0.85f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    toggleFileManagerDrawer(open = true, animate = true)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Open this session's File Manager to open, close, or manage files"
                    ) else activity.dismissButtonPopup()
                }
            }
            btnCloseEvalFile = makeBarBtn("Close", false, 0.85f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    closeSelectedOrActiveFileInSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Close the active .math file in this session (keeps it stored in Session File Manager)"
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
                    if (held) activity.showButtonPopup(this, "Save changes to the active .math file in this session")
                    else activity.dismissButtonPopup()
                }
            }
            btnDeleteEvalFile = makeBarBtn("Del", false, 0.75f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    confirmDeleteSelectedOrActiveFileInSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Delete the active .math file from this session")
                    else activity.dismissButtonPopup()
                }
            }
            btnImportEvalFile = makeBarBtn("📥 In", true, 0.85f).apply {
                setOnClickListener {
                    springPulse(this, 0.07f)
                    launchImportMathFileIntoActiveSession()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(this, "Import a .math file into this isolated session")
                    else activity.dismissButtonPopup()
                }
            }
            btnExportEvalFile = makeBarBtn("📤 Out", true, 0.9f).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    springPulse(this, 0.07f)
                    exportSelectedOrActiveSessionFile()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "Export the active .math file from this session to storage"
                    ) else activity.dismissButtonPopup()
                }
            }

            evalFileBar.addView(btnNewEvalFile)
            evalFileBar.addView(btnOpenEvalFile)
            evalFileBar.addView(btnCloseEvalFile)
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
                        saveCurrentSessionEditorState(activeSessionId, showToast = false)
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
                    text = "Active: Session opening…"
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
                    hint = "# Isolated session editor — open or create a file via ☰ File Manager, or type & tap Save/Run..."
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
                            "Evaluate the current .math file inside this isolated session"
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
                        saveCurrentSessionEditorState(activeSessionId, showToast = false)
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
                    text = "Ready. Each session is isolated with its own ☰ File Manager."
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
    //  Per-Session Isolated File System & Classification / Auto-Promotion
    // =========================================================================

    private fun legacyMathDir(ctx: Context): File =
        File(ctx.filesDir, "math_programs").also { if (!it.exists()) it.mkdirs() }

    private fun modulesDir(ctx: Context): File =
        File(ctx.filesDir, "math_modules").also { if (!it.exists()) it.mkdirs() }

    private fun sessionsRootDir(ctx: Context): File =
        File(ctx.filesDir, "math_sessions").also { if (!it.exists()) it.mkdirs() }

    /** Each session `sid` has its own strictly isolated directory on disk. */
    private fun sessionFilesDir(ctx: Context, sid: Int): File =
        File(sessionsRootDir(ctx), "session_${sid.coerceAtLeast(1)}").also { if (!it.exists()) it.mkdirs() }

    private fun listSessionFiles(ctx: Context, sid: Int): List<File> {
        if (sid <= 0) return emptyList()
        return sessionFilesDir(ctx, sid)
            .listFiles { f -> f.isFile && f.name.endsWith(".math") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun getOpenFilesForSession(sid: Int): MutableList<String> =
        sessionOpenFiles.getOrPut(sid) { mutableListOf() }

    private fun hasNonEmptyStatements(raw: String): Boolean =
        raw.lines().any { it.substringBefore("#").trim().isNotEmpty() }

    /**
     * Seeds default sample files for Modules and for initial Session #1 only,
     * and migrates any legacy root files into `session_1/` so that Session #2, #3, etc.
     * remain 100% isolated from Session #1.
     */
    private fun ensureSampleAndClassifiedFiles(ctx: Context) {
        val legDir = legacyMathDir(ctx)
        val modDir = modulesDir(ctx)
        val sessRoot = sessionsRootDir(ctx)
        val sess1Dir = sessionFilesDir(ctx, 1)

        File(legDir, "trig_ext.math").takeIf { it.exists() }?.delete()
        File(modDir, "trig_ext.math").takeIf { it.exists() }?.delete()

        legDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { file ->
            val content = runCatching { file.readText() }.getOrDefault("")
            val targetDir = if (MathEngineManager.isTrueModuleSource(content)) modDir else sess1Dir
            val dest = File(targetDir, file.name)
            if (!dest.exists()) {
                dest.writeText(content)
            }
            file.delete()
        }

        // Migrate any legacy root-level .math files in math_sessions/ into math_sessions/session_1/
        sessRoot.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { rootFile ->
            val dest = File(sess1Dir, rootFile.name)
            if (!dest.exists()) {
                runCatching { dest.writeText(rootFile.readText()) }
            }
            rootFile.delete()
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

        // Scan modulesDir: promote any non-module standard .math evaluation file into session_1
        modDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { file ->
            val content = runCatching { file.readText() }.getOrDefault("")
            if (hasNonEmptyStatements(content) && !MathEngineManager.isTrueModuleSource(content)) {
                File(sess1Dir, file.name).writeText(content)
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

        // Seed Session #1 default sample files once
        val seedMarker = File(sessRoot, ".seeded_session_1_v2")
        val existingSess1Files = sess1Dir.listFiles { f -> f.isFile && f.name.endsWith(".math") } ?: emptyArray()
        if (!seedMarker.exists() && existingSess1Files.isEmpty()) {
            File(sess1Dir, "physics_sim.math").writeText(
                "# Stateful Session Evaluation Script (.math)\n" +
                "# Tap '▶ Run in Session' to evaluate line-by-line in Session #1!\n\n" +
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
            File(sess1Dir, "vectors_stats.math").writeText(
                "# Vectors, Comprehensions & Symbolic Math (.math)\n" +
                "# Tap '▶ Run in Session' to evaluate in Session #1!\n\n" +
                "squares := [x^2 for x in 1...8]\n" +
                "total := sum(squares)\n" +
                "avg := mean(squares)\n" +
                "std_val := stddev(squares)\n\n" +
                "# Symbolic differentiation & equation solving:\n" +
                "deriv_expr := sym_diff(x, x^3 - 6*x^2 + 11*x - 6)\n" +
                "roots := sym_solve(x, x^2 - 5*x + 6 = 0)\n" +
                "roots\n"
            )
            runCatching { seedMarker.writeText("ok") }
        }

        val availableMods = modDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (availableMods.isNotEmpty() && availableMods.none { it.name == activeModuleFileName }) {
            activeModuleFileName = availableMods.first().name
        }

        // Initialize Session #1 open file state if not yet initialized
        if (!sessionOpenFiles.containsKey(1)) {
            val s1Files = listSessionFiles(ctx, 1)
            val openList = mutableListOf<String>()
            if (s1Files.isNotEmpty()) {
                openList.add(s1Files.first().name)
                activeEvalFileBySession[1] = s1Files.first().name
                sessionSelectedManagerFile[1] = s1Files.first().name
            } else {
                activeEvalFileBySession[1] = null
                sessionSelectedManagerFile[1] = null
            }
            sessionOpenFiles[1] = openList
        }
    }

    /** Clears all files and state for a newly created or closed session `sid` so it is 100% isolated. */
    private fun initCleanIsolatedSessionState(ctx: Context, sid: Int, wipeDiskFiles: Boolean) {
        if (wipeDiskFiles) {
            sessionFilesDir(ctx, sid).listFiles()?.forEach { it.delete() }
        }
        sessionOpenFiles[sid] = mutableListOf()
        activeEvalFileBySession[sid] = null
        sessionUnsavedBuffers[sid] = ""
        sessionSelectedManagerFile[sid] = null
        sessionVarsCache[sid] = ""
    }

    /**
     * Promotes a `.math` file from the Modules section into a newly opened, isolated Session
     * as a proper evaluation `.math` file when it is classified as a standard `.math` evaluation script.
     */
    private fun promoteModuleFileToSession(
        fileName: String,
        code: String,
        autoEvaluate: Boolean
    ) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val modDir = modulesDir(ctx)

        val safeName = if (fileName.endsWith(".math")) fileName else "$fileName.math"
        val finalContent = if (code.isBlank()) "# Promoted Evaluation Script: $safeName\n\nx := 10\nx * 2\n" else code
        File(modDir, safeName).takeIf { it.exists() }?.delete()

        val remainingMods = modDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        if (remainingMods.isEmpty()) {
            ensureSampleAndClassifiedFiles(ctx)
        }
        val updatedMods = modDir.listFiles { f -> f.name.endsWith(".math") }?.sortedBy { it.name } ?: emptyList()
        activeModuleFileName = updatedMods.firstOrNull()?.name ?: "custom_module.math"
        refreshModuleChips()
        loadModuleIntoEditor(activeModuleFileName, animate = false)

        saveCurrentSessionEditorState(activeSessionId, showToast = false)

        MathEngineManager.openSession { newSid ->
            activity.runOnUiThread {
                if (newSid <= 0) {
                    activity.showButtonToast("Engine booting… retry promotion in a moment")
                    return@runOnUiThread
                }
                val sid = newSid
                if (sid !in openSessionIds) openSessionIds.add(sid)

                // Isolate promoted file strictly inside Session #sid's own directory
                initCleanIsolatedSessionState(ctx, sid, wipeDiskFiles = true)
                File(sessionFilesDir(ctx, sid), safeName).writeText(finalContent)
                sessionOpenFiles[sid] = mutableListOf(safeName)
                activeEvalFileBySession[sid] = safeName
                sessionSelectedManagerFile[sid] = safeName

                activeSessionId = sid
                sessionLogs[sid] = "=== Session #$sid ===\n" +
                    "🚀 Promoted '$safeName' from Modules into isolated Session #$sid."

                val promoMsg = "🚀 Promoted '$safeName' to isolated Session #$sid."
                tvModuleQuickStatus?.text = promoMsg
                moduleLogs[activeModuleFileName] = promoMsg

                isModulesMode = false
                isSessionEditorSubTab = true
                refreshSessionChips()
                refreshEvalFileChips()
                loadSessionStateIntoEditor(sid, animate = true)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
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
    //  Isolated Sessions Operations & Per-Session File Management
    // =========================================================================

    private fun ensureDefaultSession() {
        val ctx = context ?: return
        if (openSessionIds.isNotEmpty() && activeSessionId in openSessionIds && activeSessionId > 0) {
            refreshSessionChips()
            refreshEvalFileChips()
            loadSessionStateIntoEditor(activeSessionId, animate = false)
            refreshActiveSessionLogAndVars()
            refreshFileManagerDrawer()
            return
        }
        MathEngineManager.openSession { sid ->
            activity?.runOnUiThread {
                if (sid > 0) {
                    if (sid !in openSessionIds) openSessionIds.add(sid)
                    activeSessionId = sid
                    if (!sessionOpenFiles.containsKey(sid)) {
                        val existing = listSessionFiles(ctx, sid)
                        if (sid == 1 && existing.isNotEmpty()) {
                            sessionOpenFiles[sid] = mutableListOf(existing.first().name)
                            activeEvalFileBySession[sid] = existing.first().name
                            sessionSelectedManagerFile[sid] = existing.first().name
                        } else {
                            sessionOpenFiles[sid] = mutableListOf()
                            activeEvalFileBySession[sid] = null
                            sessionSelectedManagerFile[sid] = existing.firstOrNull()?.name
                        }
                    }
                    sessionLogs.getOrPut(sid) {
                        "=== Session #$sid Log ===\nReady. Session #$sid is isolated with its own ☰ File Manager."
                    }
                    refreshSessionChips()
                    refreshEvalFileChips()
                    loadSessionStateIntoEditor(sid, animate = false)
                    refreshActiveSessionLogAndVars()
                    refreshFileManagerDrawer()
                } else {
                    (activity as? MainActivity)?.showButtonToast("Engine booting… try again in a moment")
                    refreshActiveSessionLogAndVars()
                }
            }
        }
    }

    private fun routeOntoActiveSession(action: (sid: Int) -> Unit) {
        if (activeSessionId > 0 && activeSessionId in openSessionIds) {
            action(activeSessionId)
            return
        }
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        var retriedOnce = false
        fun attempt() {
            MathEngineManager.openSession { sid ->
                activity.runOnUiThread {
                    if (sid > 0) {
                        if (sid !in openSessionIds) openSessionIds.add(sid)
                        activeSessionId = sid
                        if (!sessionOpenFiles.containsKey(sid)) {
                            val existing = listSessionFiles(ctx, sid)
                            sessionOpenFiles[sid] = if (sid == 1 && existing.isNotEmpty()) {
                                mutableListOf(existing.first().name)
                            } else {
                                mutableListOf()
                            }
                            activeEvalFileBySession[sid] = sessionOpenFiles[sid]?.firstOrNull()
                        }
                        sessionLogs.getOrPut(sid) { "=== Session #$sid Log ===\n" }
                        refreshSessionChips()
                        refreshEvalFileChips()
                        refreshActiveSessionLogAndVars()
                        refreshFileManagerDrawer()
                        action(sid)
                    } else if (!retriedOnce) {
                        retriedOnce = true
                        tvActiveSessionFileName?.postDelayed({ attempt() }, 400)
                        tvActiveSessionFileName?.text = "Active: Session opening…"
                    } else {
                        activity.showButtonToast("Engine not ready — try again")
                    }
                }
            }
        }
        attempt()
    }

    /** Switches between active sessions, preserving each session's isolated files, open tabs, and editor state. */
    private fun switchToSession(targetSid: Int) {
        if (targetSid <= 0 || targetSid !in openSessionIds) return
        saveCurrentSessionEditorState(activeSessionId, showToast = false)
        activeSessionId = targetSid
        refreshSessionChips()
        refreshEvalFileChips()
        loadSessionStateIntoEditor(targetSid, animate = true)
        refreshActiveSessionLogAndVars()
        refreshFileManagerDrawer()
        tvActiveSessionFileName?.let { springPulse(it, 0.045f) }
        varsCard?.let { springPulse(it, 0.045f) }
    }

    /**
     * Creates a brand-new isolated Session (`Session #2`, `Session #3`, ...).
     * Files from `Session #1` (or any other session) are NOT active or associated with the new session.
     */
    private fun openNewStandaloneSession() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        saveCurrentSessionEditorState(activeSessionId, showToast = false)

        MathEngineManager.openSession { sid ->
            activity.runOnUiThread {
                if (sid > 0) {
                    if (sid !in openSessionIds) openSessionIds.add(sid)
                    // Ensure this newly created session starts 100% isolated with no files from other sessions
                    initCleanIsolatedSessionState(ctx, sid, wipeDiskFiles = true)
                    activeSessionId = sid
                    sessionLogs[sid] = "=== Session #$sid Log ===\n" +
                        "Opened isolated Session #$sid (no files from other sessions are active here).\n" +
                        "Use ☰ File Manager or '+ New' to create or import files for Session #$sid."
                    refreshSessionChips()
                    refreshEvalFileChips()
                    loadSessionStateIntoEditor(sid, animate = true)
                    refreshActiveSessionLogAndVars()
                    refreshFileManagerDrawer()
                    tvSessionQuickStatus?.text =
                        "Opened isolated Session #$sid. No files open yet — tap ☰ File Manager or '+ New'."
                    activity.showButtonToast("Opened isolated Session #$sid")
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
                val curFile = activeEvalFileBySession[sid] ?: "your script"
                tvSessionQuickStatus?.text = "Session #$sid reset. Tap '▶ Run in Session' to evaluate $curFile."
                varsCard?.let { springPulse(it, 0.05f) }
                sessionLogCard?.let { springPulse(it, 0.04f) }
                activity.showButtonToast(if (ok) "Session #$sid reset" else "Reset failed")
            }
        }
    }

    /** Closes the active session and cleans up its isolated session slot. */
    private fun closeActiveSession() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        if (openSessionIds.size <= 1) {
            resetActiveSession()
            return
        }
        MathEngineManager.closeSession(sid) { _ ->
            activity.runOnUiThread {
                initCleanIsolatedSessionState(ctx, sid, wipeDiskFiles = true)
                openSessionIds.remove(sid)
                sessionLogs.remove(sid)
                sessionVarsCache.remove(sid)
                sessionOpenFiles.remove(sid)
                activeEvalFileBySession.remove(sid)
                sessionUnsavedBuffers.remove(sid)
                sessionSelectedManagerFile.remove(sid)

                activeSessionId = openSessionIds.first()
                refreshSessionChips()
                refreshEvalFileChips()
                loadSessionStateIntoEditor(activeSessionId, animate = true)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
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
            val openCnt = getOpenFilesForSession(sid).size
            val totalCnt = listSessionFiles(ctx, sid).size
            val chip = NeumorphicKeyView(ctx).apply {
                text = "⚡ Session #$sid ($openCnt/$totalCnt)"
                textSize = 11.2f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = selected
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(dp(132f), dp(34f)).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    if (sid != activeSessionId) {
                        switchToSession(sid)
                    } else {
                        springPulse(this, 0.05f)
                    }
                }
            }
            row.addView(chip)
        }
    }

    private fun refreshActiveSessionLogAndVars() {
        val ctx = context
        val sid = activeSessionId
        if (sid <= 0) {
            tvActiveSessionFileName?.text = "Active: Session opening…"
            tvSessionLogOutput?.text = "Session is opening… tap again in a moment."
            return
        }
        val activeFile = activeEvalFileBySession[sid]
        val openCnt = getOpenFilesForSession(sid).size
        val totalCnt = if (ctx != null) listSessionFiles(ctx, sid).size else openCnt

        tvEvalFilesGroupLabel?.text = "SESSION #$sid OPEN FILES ($openCnt OPEN / $totalCnt STORED)"
        btnOpenFileManagerInline?.text = "☰ Files ($openCnt/$totalCnt)"
        val fileLabel = activeFile ?: "None open (Tap ☰ or + New)"
        tvActiveSessionFileName?.text = "Active: Session #$sid (Isolated)  •  File: $fileLabel"
        btnRunSessionScript?.text = "▶ Run in Session #$sid"
        tvVarsHeader?.text = "Session #$sid Variables (MSP1 State)"
        tvVarsContent?.text = sessionVarsCache[sid].orEmpty().ifBlank {
            "No variables bound yet in Session #$sid."
        }
        tvSessionLogOutput?.text = sessionLogs[sid]
            ?: "=== Session #$sid Log ===\nReady. Tap '▶ Run in Session #$sid' in the Editor tab."

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

    // =========================================================================
    //  Per-Session Isolated File Operations (New, Open, Close, Save, Rename, Delete, Import, Export)
    // =========================================================================

    private fun refreshEvalFileChips() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val row = evalFileChipsRow ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        row.removeAllViews()
        val sid = activeSessionId
        if (sid <= 0) return

        val allFilesOnDisk = listSessionFiles(ctx, sid).map { it.name }.toSet()
        val openList = getOpenFilesForSession(sid)
        openList.retainAll { it in allFilesOnDisk }

        var activeFile = activeEvalFileBySession[sid]
        if (activeFile != null && activeFile !in openList) {
            activeFile = openList.firstOrNull()
            activeEvalFileBySession[sid] = activeFile
        }

        if (openList.isEmpty()) {
            val emptyChip = NeumorphicKeyView(ctx).apply {
                text = "☰ No open files in Session #$sid — Tap to open File Manager"
                textSize = 11f
                cornerRadiusDp = 12f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(dp(310f), dp(34f))
                setOnClickListener {
                    springPulse(this, 0.06f)
                    toggleFileManagerDrawer(open = true, animate = true)
                }
            }
            row.addView(emptyChip)
            return
        }

        openList.forEach { fileName ->
            val selected = (fileName == activeFile)
            val chip = NeumorphicKeyView(ctx).apply {
                text = "📄 $fileName"
                textSize = 11.5f
                cornerRadiusDp = 12f
                shadowPadDp = 3f
                isAccentKey = selected
                isDarkThemeMode = activity.isDarkMode
                val w = (fileName.length * 8.0f + 44f).coerceAtLeast(106f)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(34f)).apply {
                    marginEnd = dp(6f)
                }
                setOnClickListener {
                    saveCurrentSessionEditorState(sid, showToast = false)
                    activeEvalFileBySession[sid] = fileName
                    sessionSelectedManagerFile[sid] = fileName
                    refreshEvalFileChips()
                    loadSessionStateIntoEditor(sid, animate = true)
                    refreshFileManagerDrawer()
                }
            }
            row.addView(chip)
        }
    }

    private fun loadSessionStateIntoEditor(sid: Int, animate: Boolean) {
        val ctx = context ?: return
        if (sid <= 0) return
        val activeFile = activeEvalFileBySession[sid]
        val openList = getOpenFilesForSession(sid)

        if (activeFile != null && activeFile in openList) {
            val file = File(sessionFilesDir(ctx, sid), activeFile)
            tvActiveSessionFileName?.text = "Active: Session #$sid (Isolated)  •  File: $activeFile"
            etSessionEditor?.hint = "# Editing $activeFile in isolated Session #$sid..."
            etSessionEditor?.setText(if (file.exists()) file.readText() else "")
        } else {
            activeEvalFileBySession[sid] = null
            tvActiveSessionFileName?.text = "Active: Session #$sid (Isolated)  •  File: None open (Tap ☰ or + New)"
            etSessionEditor?.hint =
                "# Session #$sid (Isolated Workspace)\n" +
                "# No files from other sessions are active here.\n" +
                "# Open ☰ File Manager (top-left) to create, open, or import files for Session #$sid,\n" +
                "# or type expressions here and tap Save / Run."
            etSessionEditor?.setText(sessionUnsavedBuffers[sid] ?: "")
        }

        if (animate) {
            etSessionEditor?.let { springSlideIn(it) }
            tvActiveSessionFileName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun saveCurrentSessionEditorState(sid: Int, showToast: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        if (sid <= 0) return
        val code = etSessionEditor?.text?.toString() ?: return
        val activeFile = activeEvalFileBySession[sid]
        val openList = getOpenFilesForSession(sid)

        if (activeFile != null && activeFile in openList) {
            File(sessionFilesDir(ctx, sid), activeFile).writeText(code)
            if (showToast) {
                activity.showButtonToast("Saved $activeFile in Session #$sid")
                tvActiveSessionFileName?.let { springPulse(it, 0.05f) }
            }
        } else {
            sessionUnsavedBuffers[sid] = code
        }
    }

    private fun saveCurrentEvalFile(showToast: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        val code = etSessionEditor?.text?.toString() ?: return
        val activeFile = activeEvalFileBySession[sid]
        val openList = getOpenFilesForSession(sid)

        if (activeFile != null && activeFile in openList) {
            File(sessionFilesDir(ctx, sid), activeFile).writeText(code)
            if (showToast) {
                tvSessionQuickStatus?.text = "💾 Saved '$activeFile' in isolated Session #$sid."
                activity.showButtonToast("Saved $activeFile (Session #$sid)")
                tvActiveSessionFileName?.let { springPulse(it, 0.05f) }
            }
            refreshFileManagerDrawer()
        } else {
            // No file currently open in this session: if showToast is true, prompt to save as a new file in Session #sid
            sessionUnsavedBuffers[sid] = code
            if (showToast) {
                promptSaveUntitledBufferAsFileDialog(sid, code)
            }
        }
    }

    private fun promptSaveUntitledBufferAsFileDialog(sid: Int, codeToSave: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dark = activity.isDarkMode
        val input = EditText(ctx).apply {
            setText("session_${sid}_script.math")
            setSingleLine(true)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            setHintTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
        }
        AlertDialog.Builder(ctx)
            .setTitle("Save File in Session #$sid")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    if (!name.endsWith(".math")) name += ".math"
                    val finalCode = if (codeToSave.isBlank()) "# Session #$sid Script: $name\n\n" else codeToSave
                    File(sessionFilesDir(ctx, sid), name).writeText(finalCode)
                    val openList = getOpenFilesForSession(sid)
                    if (name !in openList) openList.add(name)
                    activeEvalFileBySession[sid] = name
                    sessionSelectedManagerFile[sid] = name
                    sessionUnsavedBuffers[sid] = ""
                    refreshSessionChips()
                    refreshEvalFileChips()
                    loadSessionStateIntoEditor(sid, animate = true)
                    refreshActiveSessionLogAndVars()
                    refreshFileManagerDrawer()
                    tvSessionQuickStatus?.text = "💾 Saved & opened '$name' in Session #$sid."
                    activity.showButtonToast("Saved $name in Session #$sid")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Creates a new `.math` evaluation file strictly inside the active session (`Session #sid`). */
    private fun promptNewEvalFileDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) {
            activity.showButtonToast("Wait for session to initialize")
            return
        }
        val dark = activity.isDarkMode
        val existingCount = listSessionFiles(ctx, sid).size + 1
        val input = EditText(ctx).apply {
            hint = "session_${sid}_file_${existingCount}.math"
            setSingleLine(true)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            setHintTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
        }
        AlertDialog.Builder(ctx)
            .setTitle("New File in Session #$sid")
            .setView(input)
            .setPositiveButton("Create & Open") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) {
                    name = "session_${sid}_file_${existingCount}.math"
                }
                if (!name.endsWith(".math")) name += ".math"
                saveCurrentSessionEditorState(sid, showToast = false)

                val newFile = File(sessionFilesDir(ctx, sid), name)
                if (!newFile.exists()) {
                    newFile.writeText(
                        "# Isolated Session #$sid Script: $name\n\n" +
                        "x := ${sid * 10}\n" +
                        "y := x * 2 + 5\n" +
                        "y\n"
                    )
                }
                val openList = getOpenFilesForSession(sid)
                if (name !in openList) openList.add(name)
                activeEvalFileBySession[sid] = name
                sessionSelectedManagerFile[sid] = name
                isSessionEditorSubTab = true
                refreshSessionChips()
                refreshEvalFileChips()
                loadSessionStateIntoEditor(sid, animate = true)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                updateSessionSubTabVisibility(animate = false)
                tvSessionQuickStatus?.text = "Created & opened '$name' in isolated Session #$sid."
                activity.showButtonToast("Created $name in Session #$sid")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Opens a file stored in `Session #sid` into `Session #sid`'s open files and active editor. */
    private fun openFileInSession(sid: Int, fileName: String, closeDrawerAfter: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val file = File(sessionFilesDir(ctx, sid), fileName)
        if (!file.exists()) {
            activity.showButtonToast("File $fileName not found in Session #$sid")
            return
        }
        saveCurrentSessionEditorState(sid, showToast = false)
        val openList = getOpenFilesForSession(sid)
        if (fileName !in openList) {
            openList.add(fileName)
        }
        activeEvalFileBySession[sid] = fileName
        sessionSelectedManagerFile[sid] = fileName
        isSessionEditorSubTab = true
        refreshSessionChips()
        refreshEvalFileChips()
        loadSessionStateIntoEditor(sid, animate = true)
        refreshActiveSessionLogAndVars()
        refreshFileManagerDrawer()
        updateSessionSubTabVisibility(animate = false)
        tvSessionQuickStatus?.text = "📂 Opened '$fileName' in Session #$sid."
        activity.showButtonToast("Opened $fileName in Session #$sid")
        if (closeDrawerAfter && isFileManagerDrawerOpen) {
            toggleFileManagerDrawer(open = false, animate = true)
        }
    }

    private fun openSelectedFileFromManager() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        val allFiles = listSessionFiles(ctx, sid)
        if (allFiles.isEmpty()) {
            activity.showButtonToast("No files in Session #$sid yet — tap '+ New' or '📥 Import'")
            return
        }
        val target = sessionSelectedManagerFile[sid]?.takeIf { sel -> allFiles.any { it.name == sel } }
            ?: allFiles.first().name
        openFileInSession(sid, target, closeDrawerAfter = true)
    }

    /**
     * Closes a specific file in `Session #sid`: saves any pending edits, removes it from
     * `sessionOpenFiles[sid]` (so it is no longer active in the editor), while keeping the file
     * safely stored on disk in `Session #sid`'s File Manager.
     */
    private fun closeSpecificFileInSession(sid: Int, fileName: String) {
        val activity = activity as? MainActivity ?: return
        val openList = getOpenFilesForSession(sid)
        if (fileName !in openList) {
            activity.showButtonToast("$fileName is already closed in Session #$sid")
            return
        }
        if (activeEvalFileBySession[sid] == fileName) {
            saveCurrentSessionEditorState(sid, showToast = false)
        }
        openList.remove(fileName)
        if (activeEvalFileBySession[sid] == fileName) {
            activeEvalFileBySession[sid] = openList.lastOrNull()
        }
        refreshSessionChips()
        refreshEvalFileChips()
        loadSessionStateIntoEditor(sid, animate = true)
        refreshActiveSessionLogAndVars()
        refreshFileManagerDrawer()
        tvSessionQuickStatus?.text =
            "✖ Closed '$fileName' in Session #$sid (still stored in ☰ File Manager)."
        activity.showButtonToast("Closed $fileName in Session #$sid")
    }

    private fun closeSelectedOrActiveFileInSession() {
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        val openList = getOpenFilesForSession(sid)
        val sel = sessionSelectedManagerFile[sid]
        val target = when {
            sel != null && sel in openList -> sel
            activeEvalFileBySession[sid] != null -> activeEvalFileBySession[sid]
            openList.isNotEmpty() -> openList.last()
            else -> null
        }
        if (target == null) {
            activity.showButtonToast("No open file to close in Session #$sid")
            return
        }
        closeSpecificFileInSession(sid, target)
    }

    private fun closeAllOpenFilesInActiveSession() {
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        val openList = getOpenFilesForSession(sid)
        if (openList.isEmpty()) {
            activity.showButtonToast("No files are currently open in Session #$sid")
            return
        }
        saveCurrentSessionEditorState(sid, showToast = false)
        openList.clear()
        activeEvalFileBySession[sid] = null
        sessionUnsavedBuffers[sid] = ""
        refreshSessionChips()
        refreshEvalFileChips()
        loadSessionStateIntoEditor(sid, animate = true)
        refreshActiveSessionLogAndVars()
        refreshFileManagerDrawer()
        tvSessionQuickStatus?.text = "✖ Closed all open files in Session #$sid."
        activity.showButtonToast("Closed all open files in Session #$sid")
    }

    /** Renames or duplicates the selected file inside `Session #sid`. */
    private fun promptRenameOrCopySessionFileDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        val allFiles = listSessionFiles(ctx, sid)
        val targetName = sessionSelectedManagerFile[sid]?.takeIf { sel -> allFiles.any { it.name == sel } }
            ?: activeEvalFileBySession[sid]
            ?: allFiles.firstOrNull()?.name
        if (targetName == null) {
            activity.showButtonToast("No file in Session #$sid to rename")
            return
        }
        saveCurrentSessionEditorState(sid, showToast = false)
        val dark = activity.isDarkMode
        val input = EditText(ctx).apply {
            setText(targetName)
            setSingleLine(true)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            setHintTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
        }
        AlertDialog.Builder(ctx)
            .setTitle("Rename or Copy '$targetName' (Session #$sid)")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                var newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    if (!newName.endsWith(".math")) newName += ".math"
                    if (newName != targetName) {
                        val dir = sessionFilesDir(ctx, sid)
                        val srcFile = File(dir, targetName)
                        val dstFile = File(dir, newName)
                        if (srcFile.exists()) {
                            dstFile.writeText(srcFile.readText())
                            srcFile.delete()
                            val openList = getOpenFilesForSession(sid)
                            val idx = openList.indexOf(targetName)
                            if (idx >= 0) openList[idx] = newName
                            if (activeEvalFileBySession[sid] == targetName) {
                                activeEvalFileBySession[sid] = newName
                            }
                            sessionSelectedManagerFile[sid] = newName
                            refreshSessionChips()
                            refreshEvalFileChips()
                            loadSessionStateIntoEditor(sid, animate = false)
                            refreshActiveSessionLogAndVars()
                            refreshFileManagerDrawer()
                            activity.showButtonToast("Renamed to $newName")
                        }
                    }
                }
            }
            .setNeutralButton("Save Copy") { _, _ ->
                var copyName = input.text.toString().trim()
                if (copyName.isNotEmpty()) {
                    if (!copyName.endsWith(".math")) copyName += ".math"
                    if (copyName == targetName) {
                        copyName = targetName.removeSuffix(".math") + "_copy.math"
                    }
                    val dir = sessionFilesDir(ctx, sid)
                    val srcFile = File(dir, targetName)
                    val dstFile = File(dir, copyName)
                    if (srcFile.exists()) {
                        dstFile.writeText(srcFile.readText())
                        val openList = getOpenFilesForSession(sid)
                        if (copyName !in openList) openList.add(copyName)
                        activeEvalFileBySession[sid] = copyName
                        sessionSelectedManagerFile[sid] = copyName
                        refreshSessionChips()
                        refreshEvalFileChips()
                        loadSessionStateIntoEditor(sid, animate = true)
                        refreshActiveSessionLogAndVars()
                        refreshFileManagerDrawer()
                        activity.showButtonToast("Copied to $copyName in Session #$sid")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Deletes a specific `.math` file from `Session #sid`'s isolated storage. */
    private fun confirmDeleteSpecificFileInSession(sid: Int, fileName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val file = File(sessionFilesDir(ctx, sid), fileName)
        if (!file.exists()) {
            activity.showButtonToast("File not found in Session #$sid")
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("Delete '$fileName'?")
            .setMessage("Delete '$fileName' from isolated Session #$sid? Other sessions will not be affected.")
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                val openList = getOpenFilesForSession(sid)
                openList.remove(fileName)
                if (activeEvalFileBySession[sid] == fileName) {
                    activeEvalFileBySession[sid] = openList.firstOrNull()
                }
                val remaining = listSessionFiles(ctx, sid)
                sessionSelectedManagerFile[sid] = activeEvalFileBySession[sid] ?: remaining.firstOrNull()?.name
                refreshSessionChips()
                refreshEvalFileChips()
                loadSessionStateIntoEditor(sid, animate = true)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                tvSessionQuickStatus?.text = "🗑 Deleted '$fileName' from Session #$sid."
                activity.showButtonToast("Deleted $fileName from Session #$sid")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSelectedOrActiveFileInSession() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        val allFiles = listSessionFiles(ctx, sid)
        if (allFiles.isEmpty()) {
            activity.showButtonToast("No files in Session #$sid to delete")
            return
        }
        val target = if (isFileManagerDrawerOpen) {
            sessionSelectedManagerFile[sid]?.takeIf { sel -> allFiles.any { it.name == sel } }
                ?: activeEvalFileBySession[sid]
                ?: allFiles.first().name
        } else {
            activeEvalFileBySession[sid]
                ?: sessionSelectedManagerFile[sid]?.takeIf { sel -> allFiles.any { it.name == sel } }
                ?: allFiles.first().name
        }
        confirmDeleteSpecificFileInSession(sid, target)
    }

    private fun runSelectedFileFromManager() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        val allFiles = listSessionFiles(ctx, sid)
        if (allFiles.isEmpty()) {
            activity.showButtonToast("No file in Session #$sid to run — create or import one first")
            return
        }
        val target = sessionSelectedManagerFile[sid]?.takeIf { sel -> allFiles.any { it.name == sel } }
            ?: activeEvalFileBySession[sid]
            ?: allFiles.first().name
        openFileInSession(sid, target, closeDrawerAfter = true)
        runCurrentEvalFileInActiveSession(switchToLogAfterRun = false)
    }

    private fun runCurrentEvalFileInActiveSession(switchToLogAfterRun: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val code = etSessionEditor?.text?.toString() ?: ""

        fun executeOnSession(sid: Int) {
            // If the user typed code into an isolated session without creating a file first,
            // auto-save & open an isolated session file so it belongs strictly to Session #sid.
            var evalFile = activeEvalFileBySession[sid]
            val openList = getOpenFilesForSession(sid)
            if ((evalFile == null || evalFile !in openList) && hasNonEmptyStatements(code)) {
                val autoName = "session_${sid}_script.math"
                File(sessionFilesDir(ctx, sid), autoName).writeText(code)
                if (autoName !in openList) openList.add(autoName)
                activeEvalFileBySession[sid] = autoName
                sessionSelectedManagerFile[sid] = autoName
                sessionUnsavedBuffers[sid] = ""
                evalFile = autoName
                refreshSessionChips()
                refreshEvalFileChips()
                refreshFileManagerDrawer()
            } else {
                saveCurrentSessionEditorState(sid, showToast = false)
            }
            val shownFile = evalFile ?: "(Scratchpad)"

            MathEngineManager.runProgramScript(sid, code) { results, snapSummary ->
                activity.runOnUiThread {
                    val sb = StringBuilder()
                    sb.append("=== Executed $shownFile in Isolated Session #$sid ===\n")
                    var lastSummaryLine = "(No executable statements found)"
                    if (results.isEmpty()) {
                        sb.append("(No executable statements found)\n")
                    } else {
                        results.forEachIndexed { idx, (stmt, outcome) ->
                            if (outcome.ok) {
                                val u = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                                val line = "[${idx + 1}] $stmt  ⟹  ${outcome.value}$u (${outcome.type})"
                                sb.append(line).append("\n")
                                lastSummaryLine = "✅ [S#$sid · $shownFile] $stmt ⟹ ${outcome.value}$u"
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
    //  Session Export & Load (.msp1 State)
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
            saveCurrentSessionEditorState(activeSessionId, showToast = false)
            MathEngineManager.restoreSession(rawSnap) { newSid, err ->
                activity.runOnUiThread {
                    if (newSid > 0) {
                        if (newSid !in openSessionIds) openSessionIds.add(newSid)
                        initCleanIsolatedSessionState(ctx, newSid, wipeDiskFiles = true)
                        activeSessionId = newSid
                        MathEngineManager.inspectSessionVariables(newSid) { summary ->
                            activity.runOnUiThread {
                                sessionVarsCache[newSid] = summary
                                sessionLogs[newSid] = "=== Session #$newSid Log ===\n" +
                                    "📂 Loaded & restored isolated MSP1 session state from storage.\n\n" +
                                    (if (summary.isNotBlank()) "--- Restored Variables ---\n$summary" else "(No bound variables)")
                                refreshSessionChips()
                                refreshEvalFileChips()
                                loadSessionStateIntoEditor(newSid, animate = true)
                                refreshActiveSessionLogAndVars()
                                refreshFileManagerDrawer()
                                tvSessionQuickStatus?.text = "📂 Loaded isolated Session #$newSid from storage."
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
    //  Per-Session .math File Import In / Export Out
    // =========================================================================

    private fun launchImportMathFileIntoActiveSession() {
        val activity = activity as? MainActivity ?: return
        if (activeSessionId <= 0) {
            activity.showButtonToast("Wait for session to initialize")
            return
        }
        saveCurrentSessionEditorState(activeSessionId, showToast = false)
        pendingImportTargetSessionId = activeSessionId
        try {
            importMathFileLauncher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open file picker: ${t.message}")
        }
    }

    private fun launchImportMathFileFromStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            if (isModulesMode) {
                saveCurrentModuleFile(showToast = false, checkPromotion = false)
                pendingImportTargetSessionId = -1
            } else {
                saveCurrentSessionEditorState(activeSessionId, showToast = false)
                pendingImportTargetSessionId = activeSessionId
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
            if (isModulesMode && pendingImportTargetSessionId <= 0 && !isTrueModule && hasNonEmptyStatements(content)) {
                promoteModuleFileToSession(displayName, content, autoEvaluate = false)
                return
            }

            if (isModulesMode && pendingImportTargetSessionId <= 0) {
                File(modulesDir(ctx), displayName).writeText(content)
                activeModuleFileName = displayName
                refreshModuleChips()
                loadModuleIntoEditor(displayName, animate = true)
                tvModuleQuickStatus?.text = "📂 Loaded module '$displayName' (${content.length} chars)."
                activity.showButtonToast("Loaded module $displayName")
            } else {
                val targetSid = if (pendingImportTargetSessionId > 0) pendingImportTargetSessionId else activeSessionId
                if (targetSid <= 0) {
                    activity.showButtonToast("No active session for import")
                    return
                }
                File(sessionFilesDir(ctx, targetSid), displayName).writeText(content)
                val openList = getOpenFilesForSession(targetSid)
                if (displayName !in openList) openList.add(displayName)
                activeEvalFileBySession[targetSid] = displayName
                sessionSelectedManagerFile[targetSid] = displayName
                isModulesMode = false
                isSessionEditorSubTab = true
                refreshSessionChips()
                refreshEvalFileChips()
                loadSessionStateIntoEditor(targetSid, animate = true)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                updateModeVisibility(animate = false)
                updateSessionSubTabVisibility(animate = false)
                tvSessionQuickStatus?.text =
                    "📥 Imported '$displayName' (${content.length} chars) into isolated Session #$targetSid."
                activity.showButtonToast("Imported $displayName into Session #$targetSid")
            }
        } catch (t: Throwable) {
            activity.showButtonToast("Import failed: ${t.message}")
        }
    }

    private fun exportSpecificSessionFile(sid: Int, fileName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        if (sid <= 0) return
        if (activeEvalFileBySession[sid] == fileName) {
            saveCurrentSessionEditorState(sid, showToast = false)
        }
        val file = File(sessionFilesDir(ctx, sid), fileName)
        if (!file.exists()) {
            activity.showButtonToast("File $fileName not found in Session #$sid")
            return
        }
        pendingExportMathContent = file.readText()
        pendingExportMathFileName = fileName
        try {
            exportMathFileLauncher.launch(fileName)
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open save picker: ${t.message}")
        }
    }

    private fun exportSelectedOrActiveSessionFile() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) return
        saveCurrentSessionEditorState(sid, showToast = false)
        val allFiles = listSessionFiles(ctx, sid)
        val target = if (isFileManagerDrawerOpen) {
            sessionSelectedManagerFile[sid]?.takeIf { sel -> allFiles.any { it.name == sel } }
                ?: activeEvalFileBySession[sid]
                ?: allFiles.firstOrNull()?.name
        } else {
            activeEvalFileBySession[sid]
                ?: sessionSelectedManagerFile[sid]?.takeIf { sel -> allFiles.any { it.name == sel } }
                ?: allFiles.firstOrNull()?.name
        }
        if (target != null) {
            exportSpecificSessionFile(sid, target)
        } else {
            val scratch = etSessionEditor?.text?.toString() ?: ""
            if (scratch.isBlank()) {
                activity.showButtonToast("No file or content in Session #$sid to export")
                return
            }
            pendingExportMathContent = scratch
            pendingExportMathFileName = "session_${sid}_script.math"
            exportMathFileLauncher.launch(pendingExportMathFileName)
        }
    }

    private fun launchExportMathFileToStorage() {
        val activity = activity as? MainActivity ?: return
        try {
            if (isModulesMode) {
                saveCurrentModuleFile(showToast = false, checkPromotion = false)
                pendingExportMathContent = etModuleEditor?.text?.toString() ?: ""
                pendingExportMathFileName = activeModuleFileName
                exportMathFileLauncher.launch(activeModuleFileName)
            } else {
                exportSelectedOrActiveSessionFile()
            }
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open save picker: ${t.message}")
        }
    }

    private fun handleExportedMathFileUri(uri: Uri) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        try {
            val code = pendingExportMathContent
            val fileName = pendingExportMathFileName.ifBlank { "exported.math" }
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(code)
            } ?: run {
                activity.showButtonToast("Unable to write to destination")
                return
            }
            if (isModulesMode) {
                tvModuleQuickStatus?.text = "💾 Exported module '$fileName' (${code.length} chars)."
            } else {
                tvSessionQuickStatus?.text =
                    "📤 Exported '$fileName' (${code.length} chars) from Session #$activeSessionId."
            }
            activity.showButtonToast("Exported $fileName")
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
            refreshSessionChips()
            refreshEvalFileChips()
            refreshActiveSessionLogAndVars()
            refreshFileManagerDrawer()
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
        tvDrawerSelectedFile?.setTextColor(accentText)
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

        styleCard(drawerSelectedBannerCard, cardBg)
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
            btnHamburgerMenu, btnBackCalc, btnModeModules, btnModeSessions,
            btnDrawerCloseIcon, btnFmNew, btnFmOpen, btnFmClose, btnFmSave,
            btnFmRename, btnFmDelete, btnFmImportIn, btnFmExportOut, btnFmRun, btnFmCloseAll,
            btnNewModule, btnSaveModule, btnDeleteModule, btnImportModule, btnExportModule,
            btnModuleSubEditor, btnModuleSubLog,
            btnCompileModule, btnPromoteModule, btnModuleViewLog,
            btnModuleBackToEditor, btnClearModuleLog,
            btnNewSession, btnResetSession, btnCloseSession, btnLoadSession, btnExportSession,
            btnOpenFileManagerInline,
            btnNewEvalFile, btnOpenEvalFile, btnCloseEvalFile, btnSaveEvalFile,
            btnDeleteEvalFile, btnImportEvalFile, btnExportEvalFile,
            btnSessionSubEditor, btnSessionSubLog,
            btnRunSessionScript, btnSessionViewLog,
            btnSessionBackToEditor, btnClearSessionLog
        ).forEach { it?.isDarkThemeMode = dark }

        refreshModuleChips()
        refreshSessionChips()
        refreshEvalFileChips()
        refreshFileManagerDrawer()
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
        saveCurrentSessionEditorState(activeSessionId, showToast = false)
        drawerAnimator?.cancel()
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        springAnimators.forEach { it.second.cancel() }
        springAnimators.clear()
        super.onDestroyView()
    }
}
