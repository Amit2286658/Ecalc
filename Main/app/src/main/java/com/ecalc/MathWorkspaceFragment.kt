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
 * Free-form `.math` Modules & Isolated Sessions Workspace with Unified Left-Drawer File Manager.
 *
 * Key design:
 * 1. **Clean, Maximized Main Editing Window**:
 *    - Top Header Bar has the **`☰` Hamburger Menu** button on the far left and **`← Calc`** right next to it.
 *    - All duplicate file/session action toolbars (`New`, `Open`, `Save`, `Delete`, `Import`, `Export`,
 *      `Reset`, `Close`, etc.) are removed from the main window so the `Editor` and `Log` panes
 *      use the full vertical screen real estate.
 * 2. **Unified Left-Side Hamburger File Manager Drawer (`🧩 Modules` & `⚡ Sessions`)**:
 *    - **In `⚡ Sessions` mode**:
 *      - Displays the active sessions chips row at the top.
 *      - Directly below the sessions row, displays the Session controls:
 *        `+ Sess`, `Reset`, `Close`, `📂 Load`, `💾 Export`.
 *      - Below the session controls, displays a single creation/import header (`+ New File`, `📥 Import`)
 *        with no duplicate top-bar file buttons, followed by the per-session isolated file list where
 *        each file entry has its own inline actions (`Open`, `Close`, `Save`, `Rename`, `Export`, `Del`).
 *    - **In `🧩 Modules` mode**:
 *      - Displays the module creation/import header (`+ New Module`, `📥 Import`), followed by the
 *        module file list where each module entry has its own inline actions
 *        (`Open`, `Close`, `Save`, `Rename`, `Export`, `Del`).
 * 3. **Jank-Free Drawer Physics Animation**:
 *    - Keeps the drawer pre-measured (`View.INVISIBLE` when closed instead of `View.GONE`), caches file
 *      line/byte metadata in memory, uses hardware layer acceleration during the slide transition, and
 *      uses a continuous, non-clamped left-anchored spring curve so it never freezes or detaches.
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
    private var btnDrawerModeModules: NeumorphicKeyView? = null
    private var btnDrawerModeSessions: NeumorphicKeyView? = null

    // Drawer Sessions Controls (visible when Drawer is in Sessions mode)
    private var drawerSessionsBlock: LinearLayout? = null
    private var tvDrawerSessionsLabel: TextView? = null
    private var drawerSessionChipsRow: LinearLayout? = null
    private var btnNewSession: NeumorphicKeyView? = null
    private var btnResetSession: NeumorphicKeyView? = null
    private var btnCloseSession: NeumorphicKeyView? = null
    private var btnLoadSession: NeumorphicKeyView? = null
    private var btnExportSession: NeumorphicKeyView? = null

    // Drawer File List Creation/Import Header (no duplicate Open/Close/Save/Del top bar!)
    private var tvDrawerFilesSectionLabel: TextView? = null
    private var btnDrawerCreateNewFile: NeumorphicKeyView? = null
    private var btnDrawerImportFile: NeumorphicKeyView? = null
    private var drawerFileListContainer: LinearLayout? = null

    // --- Top-level Section 1: Modules Main Window Section ---
    private var modulesSection: LinearLayout? = null
    private var moduleChipsRow: LinearLayout? = null

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

    // --- Top-level Section 2: Sessions Main Window Section ---
    private var sessionsSection: LinearLayout? = null
    private var evalFileChipsRow: LinearLayout? = null

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

    // Modules isolated open/active state
    private val openModuleFiles = mutableListOf<String>()
    private var activeModuleFileName: String? = "custom_module.math"
    private var moduleUnsavedBuffer: String = ""
    private val moduleLogs = mutableMapOf<String, String>()

    // Sessions isolated state (`sessionId` list + per-session open files, active file, buffer, log, vars)
    private val openSessionIds = mutableListOf<Int>()
    private var activeSessionId: Int = -1
    private val sessionLogs = mutableMapOf<Int, String>()
    private val sessionVarsCache = mutableMapOf<Int, String>()
    private val sessionOpenFiles = mutableMapOf<Int, MutableList<String>>()
    private val activeEvalFileBySession = mutableMapOf<Int, String?>()
    private val sessionUnsavedBuffers = mutableMapOf<Int, String>()

    // Cached file metadata (`<dir>/<name>` -> Pair(lineCount, byteLength)) so drawer rendering never blocks on disk I/O
    private val fileMetaCache = mutableMapOf<String, Pair<Int, Long>>()

    private var pendingSessionExportPayload: String = ""
    private var pendingExportMathContent: String = ""
    private var pendingExportMathFileName: String = ""
    private var pendingImportForModules: Boolean = false
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
            setPadding(dp(12f), dp(40f), dp(12f), dp(10f))
        }
        rootLayout = root

        // 1. Top Header Bar: [☰ Hamburger Menu] + [← Calc] button + Title
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5f) }
        }
        topHeaderBar = headerRow

        btnHamburgerMenu = NeumorphicKeyView(ctx).apply {
            text = "☰"
            textSize = 17f
            cornerRadiusDp = 15f
            shadowPadDp = 3f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(38f)).apply {
                marginEnd = dp(6f)
            }
            setOnClickListener {
                springPulse(this, 0.06f)
                toggleFileManagerDrawer(open = !isFileManagerDrawerOpen, animate = true)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) activity.showButtonPopup(
                    this,
                    "Open / close File Manager (Modules & isolated Sessions)"
                ) else activity.dismissButtonPopup()
            }
        }
        headerRow.addView(btnHamburgerMenu)

        btnBackCalc = NeumorphicKeyView(ctx).apply {
            text = "← Calc"
            textSize = 13f
            cornerRadiusDp = 15f
            shadowPadDp = 3f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(80f), dp(38f)).apply {
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
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
            ).apply { bottomMargin = dp(5f) }
        }
        modeToggleBar = modeRow

        btnModeModules = NeumorphicKeyView(ctx).apply {
            text = "🧩 Modules"
            textSize = 13f
            cornerRadiusDp = 13f
            shadowPadDp = 3f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginEnd = dp(4f) }
            setOnClickListener {
                if (!isModulesMode) {
                    switchWorkspaceMode(toModules = true, animate = true)
                } else {
                    springPulse(this, 0.06f)
                }
            }
        }
        btnModeSessions = NeumorphicKeyView(ctx).apply {
            text = "⚡ Sessions"
            textSize = 13f
            cornerRadiusDp = 13f
            shadowPadDp = 3f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginStart = dp(4f) }
            setOnClickListener {
                if (isModulesMode) {
                    switchWorkspaceMode(toModules = false, animate = true)
                } else {
                    springPulse(this, 0.06f)
                }
            }
        }
        modeRow.addView(btnModeModules)
        modeRow.addView(btnModeSessions)

        root.addView(headerRow)
        root.addView(modeRow)

        // 3. Build Clean Modules Section (Maximized Editor & Log panes)
        modulesSection = buildModulesSection(ctx, activity, dp)
        root.addView(modulesSection)

        // 4. Build Clean Sessions Section (Maximized Editor & Log panes)
        sessionsSection = buildSessionsSection(ctx, activity, dp)
        root.addView(sessionsSection)

        outerFrame.addView(root)

        // 5. Build Left-Side Sliding File Manager Drawer + Dimmed Scrim
        // Use View.INVISIBLE (not GONE) when closed so Android pre-measures and pre-lays out the drawer
        // before the user opens it, eliminating first-frame layout jank.
        val scrim = View(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#88000000"))
            alpha = 0f
            visibility = View.INVISIBLE
            isClickable = false
            setOnClickListener {
                if (isFileManagerDrawerOpen) {
                    toggleFileManagerDrawer(open = false, animate = true)
                }
            }
        }
        drawerScrimView = scrim
        outerFrame.addView(scrim)

        val drawer = buildFileManagerDrawer(ctx, activity, dp)
        fileManagerDrawerPanel = drawer
        outerFrame.addView(drawer)

        applyTheme(dark)
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = false)
        ensureDefaultSession()
        updateModeVisibility(animate = false)
        updateModuleSubTabVisibility(animate = false)
        updateSessionSubTabVisibility(animate = false)
        refreshFileManagerDrawer()

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

    private fun switchWorkspaceMode(toModules: Boolean, animate: Boolean) {
        if (toModules == isModulesMode) return
        if (isModulesMode) {
            saveCurrentModuleEditorState(showToast = false, checkPromotion = true)
        } else {
            saveCurrentSessionEditorState(activeSessionId, showToast = false)
        }
        isModulesMode = toModules
        updateModeVisibility(animate = animate)
        refreshFileManagerDrawer()
    }

    // =========================================================================
    //  Left-Side Sliding File Manager Drawer Builder & Jank-Free Spring Physics
    // =========================================================================

    private fun buildFileManagerDrawer(
        ctx: Context,
        activity: MainActivity,
        dp: (Float) -> Int
    ): LinearLayout {
        val dark = activity.isDarkMode
        val drawerWidthPx = dp(316f)
        val primaryText = Color.parseColor(if (dark) "#E0E0E0" else "#333333")
        val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.INVISIBLE
            translationX = -drawerWidthPx.toFloat()
            pivotX = 0f
            elevation = dp(18f).toFloat()
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                drawerWidthPx,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
            setPadding(dp(12f), dp(40f), dp(12f), dp(12f))

            fun makeFmBarBtn(label: String, accent: Boolean, weight: Float = 1f): NeumorphicKeyView {
                return NeumorphicKeyView(ctx).apply {
                    text = label
                    textSize = 10.5f
                    cornerRadiusDp = 11f
                    shadowPadDp = 2.5f
                    isAccentKey = accent
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(34f), weight).apply {
                        marginEnd = dp(3f)
                    }
                }
            }

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
                text = "📁 File Manager"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryText)
            }
            tvDrawerSubtitle = TextView(ctx).apply {
                text = "Isolated Workspace"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(secondaryText)
            }
            titleCol.addView(tvDrawerTitle)
            titleCol.addView(tvDrawerSubtitle)
            headerRow.addView(titleCol)

            btnDrawerCloseIcon = NeumorphicKeyView(ctx).apply {
                text = "✕"
                textSize = 14f
                cornerRadiusDp = 12f
                shadowPadDp = 2.6f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(36f), dp(34f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    toggleFileManagerDrawer(open = false, animate = true)
                }
            }
            headerRow.addView(btnDrawerCloseIcon)
            addView(headerRow)

            // 2. Mode Switcher inside Drawer: [🧩 Modules] | [⚡ Sessions]
            val drawerModeRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
            }
            btnDrawerModeModules = NeumorphicKeyView(ctx).apply {
                text = "🧩 Modules"
                textSize = 11.5f
                cornerRadiusDp = 11f
                shadowPadDp = 2.5f
                isAccentKey = isModulesMode
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    if (!isModulesMode) {
                        switchWorkspaceMode(toModules = true, animate = true)
                    } else {
                        springPulse(this, 0.05f)
                    }
                }
            }
            btnDrawerModeSessions = NeumorphicKeyView(ctx).apply {
                text = "⚡ Sessions"
                textSize = 11.5f
                cornerRadiusDp = 11f
                shadowPadDp = 2.5f
                isAccentKey = !isModulesMode
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginStart = dp(4f) }
                setOnClickListener {
                    if (isModulesMode) {
                        switchWorkspaceMode(toModules = false, animate = true)
                    } else {
                        springPulse(this, 0.05f)
                    }
                }
            }
            drawerModeRow.addView(btnDrawerModeModules)
            drawerModeRow.addView(btnDrawerModeSessions)
            addView(drawerModeRow)

            // 3. Sessions Block inside Drawer (Active Sessions Chips + Session Options right below Sessions)
            drawerSessionsBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }

                tvDrawerSessionsLabel = TextView(ctx).apply {
                    text = "ACTIVE SESSIONS (ISOLATED)"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(secondaryText)
                    setPadding(dp(2f), 0, dp(2f), dp(3f))
                }
                addView(tvDrawerSessionsLabel)

                val sessSwitchScroll = HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(34f)
                    ).apply { bottomMargin = dp(4f) }
                }
                drawerSessionChipsRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                sessSwitchScroll.addView(drawerSessionChipsRow)
                addView(sessSwitchScroll)

                // Session Options Bar directly below Sessions: [+ Sess] | [Reset] | [Close] | [📂 Load] | [💾 Export]
                val sessActionsRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                btnNewSession = makeFmBarBtn("+ Sess", true, 0.95f).apply {
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        openNewStandaloneSession()
                    }
                }
                btnResetSession = makeFmBarBtn("Reset", false, 0.85f).apply {
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        resetActiveSession()
                    }
                }
                btnCloseSession = makeFmBarBtn("Close", false, 0.85f).apply {
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        closeActiveSession()
                    }
                }
                btnLoadSession = makeFmBarBtn("📂 Load", true, 1.05f).apply {
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        launchLoadSessionFromStorage()
                    }
                }
                btnExportSession = makeFmBarBtn("💾 Export", true, 1.1f).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        launchExportActiveSessionToStorage()
                    }
                }
                sessActionsRow.addView(btnNewSession)
                sessActionsRow.addView(btnResetSession)
                sessActionsRow.addView(btnCloseSession)
                sessActionsRow.addView(btnLoadSession)
                sessActionsRow.addView(btnExportSession)
                addView(sessActionsRow)
            }
            addView(drawerSessionsBlock)

            // 4. Files Section Header: Label + [+ New] + [📥 Import]
            // (No duplicate Open/Close/Save/Delete top bar, because each file entry has its own options!)
            val filesHeaderRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }
            tvDrawerFilesSectionLabel = TextView(ctx).apply {
                text = "FILES"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(secondaryText)
                setPadding(dp(2f), 0, dp(4f), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            btnDrawerCreateNewFile = NeumorphicKeyView(ctx).apply {
                text = "+ New"
                textSize = 11f
                cornerRadiusDp = 10f
                shadowPadDp = 2.4f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(74f), dp(32f)).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    if (isModulesMode) {
                        promptNewModuleDialog()
                    } else {
                        promptNewEvalFileDialog()
                    }
                }
            }
            btnDrawerImportFile = NeumorphicKeyView(ctx).apply {
                text = "📥 Import"
                textSize = 11f
                cornerRadiusDp = 10f
                shadowPadDp = 2.4f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(84f), dp(32f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    if (isModulesMode) {
                        launchImportMathFileForModules()
                    } else {
                        launchImportMathFileIntoActiveSession()
                    }
                }
            }
            filesHeaderRow.addView(tvDrawerFilesSectionLabel)
            filesHeaderRow.addView(btnDrawerCreateNewFile)
            filesHeaderRow.addView(btnDrawerImportFile)
            addView(filesHeaderRow)

            // 5. Scrollable File Entries List (each file card carries its own Open, Close, Save, Rename, Export, Del buttons)
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

    /**
     * Damped-spring slide animation for the Left-Side File Manager Drawer:
     * - Pre-measured (`View.INVISIBLE` instead of `View.GONE`) so zero layout pass happens on Frame 0.
     * - Uses hardware texture layer (`LAYER_TYPE_HARDWARE`) during the slide so the GPU composites
     *   the entire drawer as a single texture instead of re-rendering software shadows per frame.
     * - Uses a damped-spring translation curve (`e^(-7.5t)·(cos(22t) + (7.5/22)·sin(22t))`) that
     *   visibly overshoots and bounces back to rest, combined with a left-anchored (`pivotX = 0f`)
     *   synchronized spring flex. No `.coerceAtMost(0f)` clamp → no mid-bounce freeze at the edge.
     */
    fun toggleFileManagerDrawer(open: Boolean, animate: Boolean) {
        val drawer = fileManagerDrawerPanel ?: return
        val scrim = drawerScrimView ?: return
        val density = resources.displayMetrics.density
        val drawerWidth = 316f * density

        drawerAnimator?.cancel()
        isFileManagerDrawerOpen = open
        scrim.isClickable = open

        if (!animate) {
            drawer.setLayerType(View.LAYER_TYPE_NONE, null)
            drawer.scaleX = 1f
            if (open) {
                refreshFileManagerDrawer()
                drawer.visibility = View.VISIBLE
                scrim.visibility = View.VISIBLE
                drawer.translationX = 0f
                scrim.alpha = 1f
            } else {
                drawer.translationX = -drawerWidth
                scrim.alpha = 0f
                drawer.visibility = View.INVISIBLE
                scrim.visibility = View.INVISIBLE
            }
            return
        }

        drawer.pivotX = 0f
        drawer.pivotY = drawer.height / 2f
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        drawer.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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
                // Damped spring (same family as the tab/key pulses): the
                // springFactor swings past 0 so the drawer visibly overshoots
                // and bounces back to rest — no mid-animation freeze.
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                // Note: overshoot past the resting point is allowed (never clamped);
                // the left-anchored pivot keeps it attached to the screen edge.
                drawer.translationX = endTx - deltaTx * springFactor

                // Left-anchored flex synchronized with the bounce
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                drawer.scaleX = 1f + 0.03f * pulse

                // Scrim fades in quickly during the first part of the slide
                scrim.alpha = startAlpha + (endAlpha - startAlpha) * (f / 0.35f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    drawer.translationX = endTx
                    drawer.scaleX = 1f
                    scrim.alpha = endAlpha
                    drawer.setLayerType(View.LAYER_TYPE_NONE, null)
                    if (!open) {
                        drawer.visibility = View.INVISIBLE
                        scrim.visibility = View.INVISIBLE
                    }
                }
            })
            start()
        }
    }

    private fun getCachedFileMeta(file: File): Pair<Int, Long> {
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        return fileMetaCache.getOrPut(key) {
            val content = runCatching { file.readText() }.getOrDefault("")
            val lines = if (content.isEmpty()) 0 else content.lines().size
            lines to file.length()
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
        val activeRowBg = Color.parseColor(if (dark) "#1D3638" else "#FCEAE6")
        val strokeColor = Color.parseColor(if (dark) "#3E3E3E" else "#D4D4D4")
        val activeStroke = Color.parseColor(if (dark) "#13959B" else "#E58A7B")

        btnDrawerModeModules?.isAccentKey = isModulesMode
        btnDrawerModeSessions?.isAccentKey = !isModulesMode

        val listContainer = drawerFileListContainer ?: return
        listContainer.removeAllViews()

        fun makeEntryActionBtn(label: String, accent: Boolean): NeumorphicKeyView {
            return NeumorphicKeyView(ctx).apply {
                text = label
                textSize = 9.8f
                cornerRadiusDp = 8.5f
                shadowPadDp = 2.0f
                isAccentKey = accent
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(28f), 1f).apply {
                    marginEnd = dp(2f)
                }
            }
        }

        if (isModulesMode) {
            // --- Drawer in MODULES mode ---
            drawerSessionsBlock?.visibility = View.GONE
            val allModFiles = listModuleFiles(ctx)
            openModuleFiles.retainAll { name -> allModFiles.any { it.name == name } }
            tvDrawerTitle?.text = "📁 Modules File Manager"
            tvDrawerSubtitle?.text = "Native [module] Extensions • ${openModuleFiles.size} open / ${allModFiles.size} stored"
            tvDrawerFilesSectionLabel?.text = "MODULE .MATH FILES"
            btnDrawerCreateNewFile?.text = "+ Module"

            if (allModFiles.isEmpty()) {
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
                    text = "No module files stored.\nTap '+ Module' or '📥 Import' above to add a [module] .math file."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(secondaryText)
                }
                emptyCard.addView(tvEmpty)
                listContainer.addView(emptyCard)
                return
            }

            allModFiles.forEach { file ->
                val fname = file.name
                val isOpen = fname in openModuleFiles
                val isActive = (fname == activeModuleFileName && isOpen)
                val (lineCount, byteLen) = getCachedFileMeta(file)

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
                        setColor(if (isActive) activeRowBg else rowBg)
                        setStroke(((if (isActive) 1.5f else 1f) * density).toInt(), if (isActive) activeStroke else strokeColor)
                    }
                }

                val topLine = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val tvName = TextView(ctx).apply {
                    text = "🧩 $fname"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
                    setTypeface(typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isActive) accentText else primaryText)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvBadge = TextView(ctx).apply {
                    text = when {
                        isActive -> "🟢 ACTIVE"
                        isOpen -> "🔵 OPEN"
                        else -> "⚪ CLOSED"
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isOpen) accentText else secondaryText)
                }
                topLine.addView(tvName)
                topLine.addView(tvBadge)
                itemCard.addView(topLine)

                val tvMeta = TextView(ctx).apply {
                    text = "Module • $lineCount lines • $byteLen B"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(secondaryText)
                    setPadding(0, dp(2f), 0, dp(5f))
                }
                itemCard.addView(tvMeta)

                // Per-module inline options: [Open] | [Close] | [Save] | [Rename] | [Export] | [Del]
                val inlineRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val btnRowOpen = makeEntryActionBtn(if (isActive) "Active" else "Open", !isActive).apply {
                    setOnClickListener {
                        openModuleFile(fname, closeDrawerAfter = true)
                    }
                }
                val btnRowClose = makeEntryActionBtn("Close", false).apply {
                    setOnClickListener {
                        closeSpecificModuleFile(fname)
                    }
                }
                val btnRowSave = makeEntryActionBtn("Save", false).apply {
                    setOnClickListener {
                        if (activeModuleFileName == fname) {
                            saveCurrentModuleEditorState(showToast = true, checkPromotion = true)
                        } else {
                            activity.showButtonToast("Saved $fname")
                        }
                        refreshFileManagerDrawer()
                    }
                }
                val btnRowRename = makeEntryActionBtn("Rename", false).apply {
                    setOnClickListener {
                        promptRenameOrCopyModuleFileDialog(fname)
                    }
                }
                val btnRowExport = makeEntryActionBtn("Export", true).apply {
                    setOnClickListener {
                        exportSpecificModuleFile(fname)
                    }
                }
                val btnRowDel = makeEntryActionBtn("Del", false).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                    setOnClickListener {
                        confirmDeleteSpecificModuleFile(fname)
                    }
                }

                inlineRow.addView(btnRowOpen)
                inlineRow.addView(btnRowClose)
                inlineRow.addView(btnRowSave)
                inlineRow.addView(btnRowRename)
                inlineRow.addView(btnRowExport)
                inlineRow.addView(btnRowDel)
                itemCard.addView(inlineRow)

                listContainer.addView(itemCard)
            }
            return
        }

        // --- Drawer in SESSIONS mode ---
        drawerSessionsBlock?.visibility = View.VISIBLE
        val sid = activeSessionId

        // Populate Drawer Session Switcher Chips
        drawerSessionChipsRow?.let { sRow ->
            sRow.removeAllViews()
            openSessionIds.forEach { id ->
                val openCnt = getOpenFilesForSession(id).size
                val sFilesCount = listSessionFiles(ctx, id).size
                val chip = NeumorphicKeyView(ctx).apply {
                    text = "⚡ Session #$id ($openCnt/$sFilesCount)"
                    textSize = 10.5f
                    cornerRadiusDp = 10f
                    shadowPadDp = 2.4f
                    isAccentKey = (id == sid)
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(116f), dp(30f)).apply {
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
        }

        if (sid <= 0) {
            tvDrawerTitle?.text = "📁 Session File Manager"
            tvDrawerSubtitle?.text = "Session opening…"
            return
        }

        val allFiles = listSessionFiles(ctx, sid)
        val openList = getOpenFilesForSession(sid)
        val activeFile = activeEvalFileBySession[sid]

        tvDrawerTitle?.text = "📁 Session #$sid File Manager"
        tvDrawerSubtitle?.text = "Isolated to Session #$sid • ${openList.size} open / ${allFiles.size} stored"
        tvDrawerFilesSectionLabel?.text = "SESSION #$sid FILES"
        btnDrawerCreateNewFile?.text = "+ New"

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
                    "• Tap '+ New' or '📥 Import' above to add files to Session #$sid."
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
            val (lineCount, byteLen) = getCachedFileMeta(file)

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
                    setColor(if (isActive) activeRowBg else rowBg)
                    setStroke(((if (isActive) 1.5f else 1f) * density).toInt(), if (isActive) activeStroke else strokeColor)
                }
            }

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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.8f)
                setTypeface(typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isActive) accentText else primaryText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvBadge = TextView(ctx).apply {
                text = when {
                    isActive -> "🟢 ACTIVE"
                    isOpen -> "🔵 OPEN"
                    else -> "⚪ CLOSED"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isOpen) accentText else secondaryText)
            }
            topLine.addView(tvName)
            topLine.addView(tvBadge)
            itemCard.addView(topLine)

            val tvMeta = TextView(ctx).apply {
                text = "Session #$sid • $lineCount lines • $byteLen B"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.MONOSPACE
                setTextColor(secondaryText)
                setPadding(0, dp(2f), 0, dp(5f))
            }
            itemCard.addView(tvMeta)

            // Per-file associated options: [Open] | [Close] | [Save] | [Rename] | [Export] | [Del]
            val inlineRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val btnRowOpen = makeEntryActionBtn(if (isActive) "Active" else "Open", !isActive).apply {
                setOnClickListener {
                    openFileInSession(sid, fname, closeDrawerAfter = true)
                }
            }
            val btnRowClose = makeEntryActionBtn("Close", false).apply {
                setOnClickListener {
                    closeSpecificFileInSession(sid, fname)
                }
            }
            val btnRowSave = makeEntryActionBtn("Save", false).apply {
                setOnClickListener {
                    if (activeEvalFileBySession[sid] == fname) {
                        saveCurrentEvalFile(showToast = true)
                    } else {
                        activity.showButtonToast("Saved $fname in Session #$sid")
                    }
                    refreshFileManagerDrawer()
                }
            }
            val btnRowRename = makeEntryActionBtn("Rename", false).apply {
                setOnClickListener {
                    promptRenameOrCopySessionFileDialog(sid, fname)
                }
            }
            val btnRowExport = makeEntryActionBtn("Export", true).apply {
                setOnClickListener {
                    exportSpecificSessionFile(sid, fname)
                }
            }
            val btnRowDel = makeEntryActionBtn("Del", false).apply {
                (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                setOnClickListener {
                    confirmDeleteSpecificFileInSession(sid, fname)
                }
            }

            inlineRow.addView(btnRowOpen)
            inlineRow.addView(btnRowClose)
            inlineRow.addView(btnRowSave)
            inlineRow.addView(btnRowRename)
            inlineRow.addView(btnRowExport)
            inlineRow.addView(btnRowDel)
            itemCard.addView(inlineRow)

            listContainer.addView(itemCard)
        }
    }

    // =========================================================================
    //  Section 1: Modules Main Window UI Builder (Maximized Editor & Log)
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

            // Row 2: Inner Sub-Tab Bar for Modules: [📝 Editor] | [📋 Log]
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
            }
            btnModuleSubLog = NeumorphicKeyView(ctx).apply {
                text = "📋 Log"
                textSize = 12f
                cornerRadiusDp = 12f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginStart = dp(4f) }
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

            // --- Module Sub-Pane A: Maximized Editor Pane ---
            moduleEditorPane = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )

                tvActiveModuleName = TextView(ctx).apply {
                    text = "Module Editor: ${activeModuleFileName ?: "None open"}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(accentText)
                    setPadding(dp(4f), 0, dp(4f), dp(3f))
                }
                addView(tvActiveModuleName)

                etModuleEditor = EditText(ctx).apply {
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
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    hint = "# Write [module] extension definitions (use ☰ File Manager to create, open, save, or import modules)..."
                }
                addView(etModuleEditor)

                val execRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(4f) }
                }

                btnCompileModule = NeumorphicKeyView(ctx).apply {
                    text = "⚙ Compile & Load"
                    textSize = 12.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.2f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.35f).apply { marginEnd = dp(4f) }
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
                    shadowPadDp = 3.2f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 0.95f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        val code = etModuleEditor?.text?.toString() ?: ""
                        val modName = activeModuleFileName ?: "promoted_script.math"
                        promoteModuleFileToSession(modName, code, autoEvaluate = true)
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
                    shadowPadDp = 3.2f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(74f), dp(38f))
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
                    setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                }
                tvModuleQuickStatus = TextView(ctx).apply {
                    text = "Use ☰ File Manager (top-left) to create, open, close, save, export, or delete modules."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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
                    shadowPadDp = 3.2f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginEnd = dp(6f) }
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
                    shadowPadDp = 3.2f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(96f), dp(38f))
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        val key = activeModuleFileName ?: "default"
                        val cleared = "=== Module Build & Link Log ($key) ===\n(Log cleared)"
                        moduleLogs[key] = cleared
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
    //  Section 2: Sessions Main Window UI Builder (Maximized Editor & Log)
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

            // Row 1: Compact Open Files Strip for the Active Isolated Session
            val evalFileScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(34f)
                ).apply { bottomMargin = dp(4f) }
            }
            evalFileChipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2f), 0, dp(2f), 0)
            }
            evalFileScroll.addView(evalFileChipsRow)
            addView(evalFileScroll)

            // Row 2: Inner Sub-Tab Bar for Sessions: [📝 Editor] | [📋 Log]
            val sessSubRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4f) }
            }
            sessionSubTabBar = sessSubRow

            btnSessionSubEditor = NeumorphicKeyView(ctx).apply {
                text = "📝 Editor"
                textSize = 12f
                cornerRadiusDp = 12f
                shadowPadDp = 2.8f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginEnd = dp(4f) }
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
                textSize = 12f
                cornerRadiusDp = 12f
                shadowPadDp = 2.8f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(34f), 1f).apply { marginStart = dp(4f) }
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

            // --- Session Sub-Pane A: Maximized .math Evaluation File Editor Pane ---
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
                    setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    hint = "# Isolated session editor — open ☰ File Manager (top-left) to manage sessions & files..."
                }
                addView(etSessionEditor)

                val sessExecRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(4f) }
                }

                btnRunSessionScript = NeumorphicKeyView(ctx).apply {
                    text = "▶ Run in Session"
                    textSize = 12.5f
                    cornerRadiusDp = 14f
                    shadowPadDp = 3.2f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.5f).apply { marginEnd = dp(4f) }
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
                    shadowPadDp = 3.2f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 0.9f)
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
                    text = "Use ☰ File Manager (top-left) for Session controls & per-file Open, Close, Save, Export, Delete."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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
                    shadowPadDp = 3.2f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1f).apply { marginEnd = dp(6f) }
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
                    shadowPadDp = 3.2f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(96f), dp(38f))
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
    //  Per-Session & Module File System Helpers
    // =========================================================================

    private fun legacyMathDir(ctx: Context): File =
        File(ctx.filesDir, "math_programs").also { if (!it.exists()) it.mkdirs() }

    private fun modulesDir(ctx: Context): File =
        File(ctx.filesDir, "math_modules").also { if (!it.exists()) it.mkdirs() }

    private fun sessionsRootDir(ctx: Context): File =
        File(ctx.filesDir, "math_sessions").also { if (!it.exists()) it.mkdirs() }

    private fun sessionFilesDir(ctx: Context, sid: Int): File =
        File(sessionsRootDir(ctx), "session_${sid.coerceAtLeast(1)}").also { if (!it.exists()) it.mkdirs() }

    private fun listModuleFiles(ctx: Context): List<File> =
        modulesDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".math") }
            ?.sortedBy { it.name }
            ?: emptyList()

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

        sessRoot.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { rootFile ->
            val dest = File(sess1Dir, rootFile.name)
            if (!dest.exists()) {
                runCatching { dest.writeText(rootFile.readText()) }
            }
            rootFile.delete()
        }

        val modSeedMarker = File(modDir, ".seeded_modules_v2")
        val customModFile = File(modDir, "custom_module.math")
        if (!modSeedMarker.exists() && !customModFile.exists() && listModuleFiles(ctx).isEmpty()) {
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
            runCatching { modSeedMarker.writeText("ok") }
        }

        modDir.listFiles { f -> f.isFile && f.name.endsWith(".math") }?.forEach { file ->
            val content = runCatching { file.readText() }.getOrDefault("")
            if (hasNonEmptyStatements(content) && !MathEngineManager.isTrueModuleSource(content)) {
                File(sess1Dir, file.name).writeText(content)
                file.delete()
            }
        }

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

        val availableMods = listModuleFiles(ctx)
        if (openModuleFiles.isEmpty() && availableMods.isNotEmpty()) {
            openModuleFiles.add(availableMods.first().name)
            activeModuleFileName = availableMods.first().name
        } else if (availableMods.isEmpty()) {
            activeModuleFileName = null
        }

        if (!sessionOpenFiles.containsKey(1)) {
            val s1Files = listSessionFiles(ctx, 1)
            val openList = mutableListOf<String>()
            if (s1Files.isNotEmpty()) {
                openList.add(s1Files.first().name)
                activeEvalFileBySession[1] = s1Files.first().name
            } else {
                activeEvalFileBySession[1] = null
            }
            sessionOpenFiles[1] = openList
        }
    }

    private fun initCleanIsolatedSessionState(ctx: Context, sid: Int, wipeDiskFiles: Boolean) {
        if (wipeDiskFiles) {
            sessionFilesDir(ctx, sid).listFiles()?.forEach { it.delete() }
        }
        sessionOpenFiles[sid] = mutableListOf()
        activeEvalFileBySession[sid] = null
        sessionUnsavedBuffers[sid] = ""
        sessionVarsCache[sid] = ""
    }

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
        openModuleFiles.remove(safeName)
        if (activeModuleFileName == safeName) {
            activeModuleFileName = openModuleFiles.firstOrNull()
        }
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = false)

        saveCurrentSessionEditorState(activeSessionId, showToast = false)

        MathEngineManager.openSession { newSid ->
            activity.runOnUiThread {
                if (newSid <= 0) {
                    activity.showButtonToast("Engine booting… retry promotion in a moment")
                    return@runOnUiThread
                }
                val sid = newSid
                if (sid !in openSessionIds) openSessionIds.add(sid)

                initCleanIsolatedSessionState(ctx, sid, wipeDiskFiles = true)
                File(sessionFilesDir(ctx, sid), safeName).writeText(finalContent)
                sessionOpenFiles[sid] = mutableListOf(safeName)
                activeEvalFileBySession[sid] = safeName

                activeSessionId = sid
                sessionLogs[sid] = "=== Session #$sid ===\n" +
                    "🚀 Promoted '$safeName' from Modules into isolated Session #$sid."

                val promoMsg = "🚀 Promoted '$safeName' to isolated Session #$sid."
                tvModuleQuickStatus?.text = promoMsg
                moduleLogs[safeName] = promoMsg

                isModulesMode = false
                isSessionEditorSubTab = true
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
    //  Modules Operations (Managed via Hamburger File Manager Drawer)
    // =========================================================================

    private fun refreshModuleChips() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val row = moduleChipsRow ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        row.removeAllViews()
        val allModNames = listModuleFiles(ctx).map { it.name }.toSet()
        openModuleFiles.retainAll { it in allModNames }
        if (activeModuleFileName != null && activeModuleFileName !in openModuleFiles) {
            activeModuleFileName = openModuleFiles.firstOrNull()
        }

        if (openModuleFiles.isEmpty()) {
            val emptyChip = NeumorphicKeyView(ctx).apply {
                text = "☰ No open module — Tap to open File Manager"
                textSize = 11f
                cornerRadiusDp = 11f
                shadowPadDp = 2.5f
                isAccentKey = false
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(dp(290f), dp(30f))
                setOnClickListener {
                    springPulse(this, 0.06f)
                    toggleFileManagerDrawer(open = true, animate = true)
                }
            }
            row.addView(emptyChip)
            return
        }

        openModuleFiles.forEach { modName ->
            val isSelected = (modName == activeModuleFileName)
            val chip = NeumorphicKeyView(ctx).apply {
                text = "🧩 $modName"
                textSize = 11f
                cornerRadiusDp = 11f
                shadowPadDp = 2.6f
                isAccentKey = isSelected
                isDarkThemeMode = activity.isDarkMode
                val w = (modName.length * 7.8f + 42f).coerceAtLeast(104f)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(30f)).apply {
                    marginEnd = dp(5f)
                }
                setOnClickListener {
                    saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
                    activeModuleFileName = modName
                    refreshModuleChips()
                    loadModuleStateIntoEditor(animate = true)
                    refreshFileManagerDrawer()
                }
            }
            row.addView(chip)
        }
    }

    private fun loadModuleStateIntoEditor(animate: Boolean) {
        val ctx = context ?: return
        val modName = activeModuleFileName
        if (modName != null && modName in openModuleFiles) {
            val file = File(modulesDir(ctx), modName)
            tvActiveModuleName?.text = "Active Module: $modName"
            etModuleEditor?.setText(if (file.exists()) file.readText() else "")
            val savedLog = moduleLogs[modName]
                ?: "=== Module Build & Link Log ($modName) ===\nReady. Tap '⚙ Compile & Load' in the Editor tab to build this [module]."
            tvModuleLogOutput?.text = savedLog
        } else {
            activeModuleFileName = null
            tvActiveModuleName?.text = "Active Module: None open (Tap ☰ File Manager)"
            etModuleEditor?.setText(moduleUnsavedBuffer)
            tvModuleLogOutput?.text = "=== Module Build & Link Log ===\nOpen or create a module via ☰ File Manager."
        }
        if (animate) {
            etModuleEditor?.let { springSlideIn(it) }
            tvActiveModuleName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun saveCurrentModuleEditorState(showToast: Boolean, checkPromotion: Boolean): Boolean {
        val ctx = context ?: return false
        val activity = activity as? MainActivity ?: return false
        val code = etModuleEditor?.text?.toString() ?: return false
        val modName = activeModuleFileName

        if (modName != null && modName in openModuleFiles) {
            if (checkPromotion && hasNonEmptyStatements(code) && !MathEngineManager.isTrueModuleSource(code)) {
                promoteModuleFileToSession(modName, code, autoEvaluate = false)
                return true
            }
            val file = File(modulesDir(ctx), modName)
            file.writeText(code)
            getCachedFileMeta(file)
            if (showToast) {
                tvModuleQuickStatus?.text = "💾 Saved module '$modName'."
                activity.showButtonToast("Saved module $modName")
                tvActiveModuleName?.let { springPulse(it, 0.05f) }
            }
        } else {
            moduleUnsavedBuffer = code
        }
        return false
    }

    private fun openModuleFile(fileName: String, closeDrawerAfter: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val file = File(modulesDir(ctx), fileName)
        if (!file.exists()) {
            activity.showButtonToast("Module $fileName not found")
            return
        }
        saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
        if (fileName !in openModuleFiles) openModuleFiles.add(fileName)
        activeModuleFileName = fileName
        isModulesMode = true
        isModuleEditorSubTab = true
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = true)
        updateModeVisibility(animate = false)
        updateModuleSubTabVisibility(animate = false)
        refreshFileManagerDrawer()
        tvModuleQuickStatus?.text = "📂 Opened module '$fileName'."
        activity.showButtonToast("Opened module $fileName")
        if (closeDrawerAfter && isFileManagerDrawerOpen) {
            toggleFileManagerDrawer(open = false, animate = true)
        }
    }

    private fun closeSpecificModuleFile(fileName: String) {
        val activity = activity as? MainActivity ?: return
        if (fileName !in openModuleFiles) {
            activity.showButtonToast("$fileName is already closed")
            return
        }
        if (activeModuleFileName == fileName) {
            saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
        }
        openModuleFiles.remove(fileName)
        if (activeModuleFileName == fileName) {
            activeModuleFileName = openModuleFiles.lastOrNull()
        }
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = true)
        refreshFileManagerDrawer()
        tvModuleQuickStatus?.text = "✖ Closed module '$fileName' (still stored in ☰ File Manager)."
        activity.showButtonToast("Closed module $fileName")
    }

    private fun promptRenameOrCopyModuleFileDialog(targetName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
        val dark = activity.isDarkMode
        val input = EditText(ctx).apply {
            setText(targetName)
            setSingleLine(true)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            setHintTextColor(Color.parseColor(if (dark) "#8E8E8E" else "#666666"))
        }
        AlertDialog.Builder(ctx)
            .setTitle("Rename or Copy Module '$targetName'")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                var newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    if (!newName.endsWith(".math")) newName += ".math"
                    if (newName != targetName) {
                        val dir = modulesDir(ctx)
                        val srcFile = File(dir, targetName)
                        val dstFile = File(dir, newName)
                        if (srcFile.exists()) {
                            dstFile.writeText(srcFile.readText())
                            srcFile.delete()
                            val idx = openModuleFiles.indexOf(targetName)
                            if (idx >= 0) openModuleFiles[idx] = newName
                            if (activeModuleFileName == targetName) activeModuleFileName = newName
                            refreshModuleChips()
                            loadModuleStateIntoEditor(animate = false)
                            refreshFileManagerDrawer()
                            activity.showButtonToast("Renamed module to $newName")
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
                    val dir = modulesDir(ctx)
                    val srcFile = File(dir, targetName)
                    val dstFile = File(dir, copyName)
                    if (srcFile.exists()) {
                        dstFile.writeText(srcFile.readText())
                        if (copyName !in openModuleFiles) openModuleFiles.add(copyName)
                        activeModuleFileName = copyName
                        refreshModuleChips()
                        loadModuleStateIntoEditor(animate = true)
                        refreshFileManagerDrawer()
                        activity.showButtonToast("Copied module to $copyName")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteSpecificModuleFile(fileName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val file = File(modulesDir(ctx), fileName)
        if (!file.exists()) return
        AlertDialog.Builder(ctx)
            .setTitle("Delete Module '$fileName'?")
            .setMessage("Delete '$fileName' from Modules storage?")
            .setPositiveButton("Delete") { _, _ ->
                file.delete()
                moduleLogs.remove(fileName)
                openModuleFiles.remove(fileName)
                if (activeModuleFileName == fileName) {
                    activeModuleFileName = openModuleFiles.firstOrNull()
                }
                refreshModuleChips()
                loadModuleStateIntoEditor(animate = true)
                refreshFileManagerDrawer()
                tvModuleQuickStatus?.text = "🗑 Deleted module '$fileName'."
                activity.showButtonToast("Deleted module $fileName")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun compileOrPromoteCurrentModule() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val code = etModuleEditor?.text?.toString() ?: ""

        if (hasNonEmptyStatements(code) && !MathEngineManager.isTrueModuleSource(code)) {
            val name = activeModuleFileName ?: "promoted_script.math"
            promoteModuleFileToSession(name, code, autoEvaluate = true)
            return
        }

        var modFile = activeModuleFileName
        if (modFile == null || modFile !in openModuleFiles) {
            modFile = "custom_module.math"
            File(modulesDir(ctx), modFile).writeText(code)
            if (modFile !in openModuleFiles) openModuleFiles.add(modFile)
            activeModuleFileName = modFile
            refreshModuleChips()
            refreshFileManagerDrawer()
        } else {
            saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
        }

        val targetMod = modFile
        MathEngineManager.compileAndLoadExtension(code) { ok, message ->
            activity.runOnUiThread {
                val logEntry = if (ok) {
                    "=== Module Build & Link Log ($targetMod) ===\n" +
                        "✅ Compiled & Linked .mathc Extension:\n$message"
                } else {
                    "=== Module Build & Link Log ($targetMod) ===\n" +
                        "❌ Extension Compile Error:\n$message"
                }
                moduleLogs[targetMod] = logEntry
                tvModuleLogOutput?.text = logEntry
                tvModuleQuickStatus?.text = if (ok) {
                    "✅ Linked '$targetMod': $message"
                } else {
                    "❌ Compile error in '$targetMod': $message"
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
            .setPositiveButton("Create & Open") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) {
                    name = "module_${listModuleFiles(ctx).size + 1}.math"
                }
                if (!name.endsWith(".math")) name += ".math"
                saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
                val modIdentifier = name.removeSuffix(".math")
                    .replace(Regex("[^a-zA-Z0-9_]"), "_")
                    .ifBlank { "custom_mod" }
                val newFile = File(modulesDir(ctx), name)
                if (!newFile.exists()) {
                    newFile.writeText(
                        "# Native Extension Module: $name\n" +
                        "[$modIdentifier]\n" +
                        "<[arith]\n\n" +
                        " square_sum(a: real, b: real) = a^2 + b^2;\n"
                    )
                }
                if (name !in openModuleFiles) openModuleFiles.add(name)
                activeModuleFileName = name
                isModulesMode = true
                isModuleEditorSubTab = true
                refreshModuleChips()
                loadModuleStateIntoEditor(animate = true)
                updateModeVisibility(animate = false)
                updateModuleSubTabVisibility(animate = false)
                refreshFileManagerDrawer()
                activity.showButtonToast("Created module $name")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    //  Isolated Sessions Operations & Per-Session File Management
    // =========================================================================

    private fun ensureDefaultSession() {
        val ctx = context ?: return
        if (openSessionIds.isNotEmpty() && activeSessionId in openSessionIds && activeSessionId > 0) {
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
                        } else {
                            sessionOpenFiles[sid] = mutableListOf()
                            activeEvalFileBySession[sid] = null
                        }
                    }
                    sessionLogs.getOrPut(sid) {
                        "=== Session #$sid Log ===\nReady. Session #$sid is isolated with its own ☰ File Manager."
                    }
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

    private fun switchToSession(targetSid: Int) {
        if (targetSid <= 0 || targetSid !in openSessionIds) return
        saveCurrentSessionEditorState(activeSessionId, showToast = false)
        activeSessionId = targetSid
        refreshEvalFileChips()
        loadSessionStateIntoEditor(targetSid, animate = true)
        refreshActiveSessionLogAndVars()
        refreshFileManagerDrawer()
        tvActiveSessionFileName?.let { springPulse(it, 0.045f) }
        varsCard?.let { springPulse(it, 0.045f) }
    }

    /** Creates a brand-new isolated Session (`Session #2`, `Session #3`, ...) with zero files from other sessions. */
    private fun openNewStandaloneSession() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        saveCurrentSessionEditorState(activeSessionId, showToast = false)

        MathEngineManager.openSession { sid ->
            activity.runOnUiThread {
                if (sid > 0) {
                    if (sid !in openSessionIds) openSessionIds.add(sid)
                    initCleanIsolatedSessionState(ctx, sid, wipeDiskFiles = true)
                    activeSessionId = sid
                    sessionLogs[sid] = "=== Session #$sid Log ===\n" +
                        "Opened isolated Session #$sid (no files from other sessions are active here).\n" +
                        "Use '+ New' or '📥 Import' in ☰ File Manager to add files to Session #$sid."
                    refreshEvalFileChips()
                    loadSessionStateIntoEditor(sid, animate = true)
                    refreshActiveSessionLogAndVars()
                    refreshFileManagerDrawer()
                    tvSessionQuickStatus?.text =
                        "Opened isolated Session #$sid. No files open yet — use ☰ File Manager to create or import."
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

                activeSessionId = openSessionIds.first()
                refreshEvalFileChips()
                loadSessionStateIntoEditor(activeSessionId, animate = true)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                activity.showButtonToast("Closed Session #$sid")
            }
        }
    }

    private fun refreshActiveSessionLogAndVars() {
        val sid = activeSessionId
        if (sid <= 0) {
            tvActiveSessionFileName?.text = "Active: Session opening…"
            tvSessionLogOutput?.text = "Session is opening… tap again in a moment."
            return
        }
        val activeFile = activeEvalFileBySession[sid]
        val fileLabel = activeFile ?: "None open (Tap ☰ File Manager)"
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

    private fun refreshEvalFileChips() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val row = evalFileChipsRow ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }

        row.removeAllViews()
        val sid = activeSessionId
        if (sid <= 0) return

        // Compact active session badge at the start of the strip (tapping opens ☰ File Manager)
        val sessBadge = NeumorphicKeyView(ctx).apply {
            text = "⚡ Session #$sid"
            textSize = 11f
            cornerRadiusDp = 11f
            shadowPadDp = 2.5f
            isAccentKey = true
            isDarkThemeMode = activity.isDarkMode
            layoutParams = LinearLayout.LayoutParams(dp(106f), dp(30f)).apply {
                marginEnd = dp(6f)
            }
            setOnClickListener {
                springPulse(this, 0.06f)
                toggleFileManagerDrawer(open = true, animate = true)
            }
        }
        row.addView(sessBadge)

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
                text = "☰ No open files — Tap to open File Manager"
                textSize = 10.8f
                cornerRadiusDp = 11f
                shadowPadDp = 2.5f
                isAccentKey = false
                isDarkThemeMode = activity.isDarkMode
                layoutParams = LinearLayout.LayoutParams(dp(256f), dp(30f))
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
                textSize = 11f
                cornerRadiusDp = 11f
                shadowPadDp = 2.6f
                isAccentKey = selected
                isDarkThemeMode = activity.isDarkMode
                val w = (fileName.length * 7.8f + 42f).coerceAtLeast(102f)
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(30f)).apply {
                    marginEnd = dp(5f)
                }
                setOnClickListener {
                    saveCurrentSessionEditorState(sid, showToast = false)
                    activeEvalFileBySession[sid] = fileName
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
            tvActiveSessionFileName?.text = "Active: Session #$sid (Isolated)  •  File: None open (Tap ☰ File Manager)"
            etSessionEditor?.hint =
                "# Session #$sid (Isolated Workspace)\n" +
                "# No files from other sessions are active here.\n" +
                "# Open ☰ File Manager (top-left) to create, open, or import files for Session #$sid,\n" +
                "# or type expressions here and tap Run."
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
            val file = File(sessionFilesDir(ctx, sid), activeFile)
            file.writeText(code)
            getCachedFileMeta(file)
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
            val file = File(sessionFilesDir(ctx, sid), activeFile)
            file.writeText(code)
            getCachedFileMeta(file)
            if (showToast) {
                tvSessionQuickStatus?.text = "💾 Saved '$activeFile' in isolated Session #$sid."
                activity.showButtonToast("Saved $activeFile (Session #$sid)")
                tvActiveSessionFileName?.let { springPulse(it, 0.05f) }
            }
            refreshFileManagerDrawer()
        } else {
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
                    val file = File(sessionFilesDir(ctx, sid), name)
                    file.writeText(finalCode)
                    getCachedFileMeta(file)
                    val openList = getOpenFilesForSession(sid)
                    if (name !in openList) openList.add(name)
                    activeEvalFileBySession[sid] = name
                    sessionUnsavedBuffers[sid] = ""
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
                getCachedFileMeta(newFile)
                val openList = getOpenFilesForSession(sid)
                if (name !in openList) openList.add(name)
                activeEvalFileBySession[sid] = name
                isModulesMode = false
                isSessionEditorSubTab = true
                refreshEvalFileChips()
                loadSessionStateIntoEditor(sid, animate = true)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                updateModeVisibility(animate = false)
                updateSessionSubTabVisibility(animate = false)
                tvSessionQuickStatus?.text = "Created & opened '$name' in isolated Session #$sid."
                activity.showButtonToast("Created $name in Session #$sid")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
        isModulesMode = false
        isSessionEditorSubTab = true
        refreshEvalFileChips()
        loadSessionStateIntoEditor(sid, animate = true)
        refreshActiveSessionLogAndVars()
        refreshFileManagerDrawer()
        updateModeVisibility(animate = false)
        updateSessionSubTabVisibility(animate = false)
        tvSessionQuickStatus?.text = "📂 Opened '$fileName' in Session #$sid."
        activity.showButtonToast("Opened $fileName in Session #$sid")
        if (closeDrawerAfter && isFileManagerDrawerOpen) {
            toggleFileManagerDrawer(open = false, animate = true)
        }
    }

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
        refreshEvalFileChips()
        loadSessionStateIntoEditor(sid, animate = true)
        refreshActiveSessionLogAndVars()
        refreshFileManagerDrawer()
        tvSessionQuickStatus?.text =
            "✖ Closed '$fileName' in Session #$sid (still stored in ☰ File Manager)."
        activity.showButtonToast("Closed $fileName in Session #$sid")
    }

    private fun promptRenameOrCopySessionFileDialog(sid: Int, targetName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
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
                            getCachedFileMeta(dstFile)
                            val openList = getOpenFilesForSession(sid)
                            val idx = openList.indexOf(targetName)
                            if (idx >= 0) openList[idx] = newName
                            if (activeEvalFileBySession[sid] == targetName) {
                                activeEvalFileBySession[sid] = newName
                            }
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
                        getCachedFileMeta(dstFile)
                        val openList = getOpenFilesForSession(sid)
                        if (copyName !in openList) openList.add(copyName)
                        activeEvalFileBySession[sid] = copyName
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

    private fun runCurrentEvalFileInActiveSession(switchToLogAfterRun: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val code = etSessionEditor?.text?.toString() ?: ""

        fun executeOnSession(sid: Int) {
            var evalFile = activeEvalFileBySession[sid]
            val openList = getOpenFilesForSession(sid)
            if ((evalFile == null || evalFile !in openList) && hasNonEmptyStatements(code)) {
                val autoName = "session_${sid}_script.math"
                val file = File(sessionFilesDir(ctx, sid), autoName)
                file.writeText(code)
                getCachedFileMeta(file)
                if (autoName !in openList) openList.add(autoName)
                activeEvalFileBySession[sid] = autoName
                sessionUnsavedBuffers[sid] = ""
                evalFile = autoName
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
    //  Per-Module & Per-Session .math File Import In / Export Out
    // =========================================================================

    private fun launchImportMathFileForModules() {
        val activity = activity as? MainActivity ?: return
        saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
        pendingImportForModules = true
        pendingImportTargetSessionId = -1
        try {
            importMathFileLauncher.launch(arrayOf("*/*"))
        } catch (t: Throwable) {
            activity.showButtonToast("Could not open file picker: ${t.message}")
        }
    }

    private fun launchImportMathFileIntoActiveSession() {
        val activity = activity as? MainActivity ?: return
        if (activeSessionId <= 0) {
            activity.showButtonToast("Wait for session to initialize")
            return
        }
        saveCurrentSessionEditorState(activeSessionId, showToast = false)
        pendingImportForModules = false
        pendingImportTargetSessionId = activeSessionId
        try {
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
            if (pendingImportForModules && !isTrueModule && hasNonEmptyStatements(content)) {
                promoteModuleFileToSession(displayName, content, autoEvaluate = false)
                return
            }

            if (pendingImportForModules) {
                val file = File(modulesDir(ctx), displayName)
                file.writeText(content)
                getCachedFileMeta(file)
                if (displayName !in openModuleFiles) openModuleFiles.add(displayName)
                activeModuleFileName = displayName
                isModulesMode = true
                isModuleEditorSubTab = true
                refreshModuleChips()
                loadModuleStateIntoEditor(animate = true)
                refreshFileManagerDrawer()
                updateModeVisibility(animate = false)
                updateModuleSubTabVisibility(animate = false)
                tvModuleQuickStatus?.text = "📥 Imported module '$displayName' (${content.length} chars)."
                activity.showButtonToast("Imported module $displayName")
            } else {
                val targetSid = if (pendingImportTargetSessionId > 0) pendingImportTargetSessionId else activeSessionId
                if (targetSid <= 0) {
                    activity.showButtonToast("No active session for import")
                    return
                }
                val file = File(sessionFilesDir(ctx, targetSid), displayName)
                file.writeText(content)
                getCachedFileMeta(file)
                val openList = getOpenFilesForSession(targetSid)
                if (displayName !in openList) openList.add(displayName)
                activeEvalFileBySession[targetSid] = displayName
                isModulesMode = false
                isSessionEditorSubTab = true
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

    private fun exportSpecificModuleFile(fileName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        if (activeModuleFileName == fileName) {
            saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
        }
        val file = File(modulesDir(ctx), fileName)
        if (!file.exists()) {
            activity.showButtonToast("Module $fileName not found")
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
                tvModuleQuickStatus?.text = "📤 Exported module '$fileName' (${code.length} chars)."
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
        btnDrawerModeModules?.isAccentKey = isModulesMode
        btnDrawerModeSessions?.isAccentKey = !isModulesMode

        if (isModulesMode) {
            sessionsSection?.visibility = View.GONE
            modulesSection?.visibility = View.VISIBLE
            refreshModuleChips()
            if (animate) {
                btnModeModules?.let { springPulse(it, 0.06f) }
                modulesSection?.let { springSlideIn(it) }
            }
        } else {
            modulesSection?.visibility = View.GONE
            sessionsSection?.visibility = View.VISIBLE
            refreshEvalFileChips()
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
        tvDrawerSessionsLabel?.setTextColor(secondaryText)
        tvDrawerFilesSectionLabel?.setTextColor(secondaryText)
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
            btnHamburgerMenu, btnBackCalc, btnModeModules, btnModeSessions,
            btnDrawerCloseIcon, btnDrawerModeModules, btnDrawerModeSessions,
            btnNewSession, btnResetSession, btnCloseSession, btnLoadSession, btnExportSession,
            btnDrawerCreateNewFile, btnDrawerImportFile,
            btnModuleSubEditor, btnModuleSubLog,
            btnCompileModule, btnPromoteModule, btnModuleViewLog,
            btnModuleBackToEditor, btnClearModuleLog,
            btnSessionSubEditor, btnSessionSubLog,
            btnRunSessionScript, btnSessionViewLog,
            btnSessionBackToEditor, btnClearSessionLog
        ).forEach { it?.isDarkThemeMode = dark }

        refreshModuleChips()
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
        saveCurrentModuleEditorState(showToast = false, checkPromotion = false)
        saveCurrentSessionEditorState(activeSessionId, showToast = false)
        drawerAnimator?.cancel()
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        springAnimators.forEach { it.second.cancel() }
        springAnimators.clear()
        super.onDestroyView()
    }
}
