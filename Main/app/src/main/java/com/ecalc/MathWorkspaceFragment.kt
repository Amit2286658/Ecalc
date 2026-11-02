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
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
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
 * Free-form `.math` Modules & Isolated Sessions Workspace with Unified Left-Drawer File Manager.
 *
 * Key design:
 * 1. **Clean, Maximized Main Editing Window**:
 *    - Top Header Bar has the **`☰` Hamburger Menu** button on the far left and **`← Calc`** right next to it.
 *    - All duplicate file/session action toolbars (`New`, `Open`, `Save`, `Delete`, `Import`, `Export`,
 *      `Reset`, `Close`, etc.) are removed from the main window so the `Editor` and `Log` panes
 *      use the full vertical screen real estate.
 *    - In the **`🧩 Modules`** tab, files always stay in Modules (no option or auto-promotion to run/open
 *      a module in a session, even if the file is not a true `[module]` — at best a non-module file
 *      simply evaluates, logs its values into the Module Log, and exits).
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

    // --- Left-Side Sliding File Manager Drawer & Frosted Modal Dialogs ---
    var isFileManagerDrawerOpen: Boolean = false
        private set
    var isModalDialogOpen: Boolean = false
        private set
    private var activeModalOverlay: FrameLayout? = null
    private var activeModalBlurBitmap: Bitmap? = null
    private var drawerScrimView: View? = null
    private var fileManagerDrawerPanel: LinearLayout? = null
    /** Frosted snapshot of the workspace shown behind the open drawer. */
    private var drawerFrostView: ImageView? = null
    /** In-flight Modules↔Sessions exchange animators inside the drawer. */
    private val drawerExchangeAnimators = mutableListOf<ValueAnimator>()
    private var drawerExchangeUnderway = false
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
                    "File Manager drawer — a left-hand workspace panel holding two modes.\n" +
                        "🧩 Modules: stored .math module files, open/active state, and editor operations.\n" +
                        "⚡ Sessions: every isolated evaluation session with its own variables, files " +
                        "and log. Tapping ☰ again slides the drawer back out of view."
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
                if (held) activity.showButtonPopup(this, "← Calc — returns to the basic calculator. Sessions keep " +
                    "their variable state while you are away; open files stay open. You can also swipe right from " +
                    "the left edge of this screen, or press the system back button.")
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
        // Frosted backdrop sits above the workspace content but below the
        // scrim and the drawer itself: while the drawer is active the whole
        // background gets a frosted-glass treatment (snapshot of the plain
        // workspace, taken while overlays are still invisible).
        val frost = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
            visibility = View.INVISIBLE
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        drawerFrostView = frost
        outerFrame.addView(frost)

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

    /** Handles system Back button when a frosted modal dialog or the File Manager drawer is open. */
    fun handleBackPressed(): Boolean {
        if (activeModalOverlay != null) {
            dismissWorkspaceModalDialog()
            return true
        }
        if (isFileManagerDrawerOpen) {
            toggleFileManagerDrawer(open = false, animate = true)
            return true
        }
        return false
    }

    private fun switchWorkspaceMode(toModules: Boolean, animate: Boolean) {
        if (toModules == isModulesMode) return
        if (isModulesMode) {
            saveCurrentModuleEditorState(showToast = false)
        } else {
            saveCurrentSessionEditorState(activeSessionId, showToast = false)
        }
        if (animate && isFileManagerDrawerOpen) {
            animateDrawerModeExchange(toModules)
            return
        }
        isModulesMode = toModules
        updateModeVisibility(animate = animate)
        refreshFileManagerDrawer()
    }

    /**
     * Modules↔Sessions exchange inside the open drawer: the current content
     * slides left out of the drawer's edge (quick fade, 45 ms stagger), then
     * the new mode's content refreshes and springs in from the right with the
     * app's damped-spring physics — same family as every other transition.
     * The getting-covered main pane still gets its plain spring pulse.
     */
    private fun animateDrawerModeExchange(toModules: Boolean) {
        val activity = activity as? MainActivity ?: return
        drawerExchangeAnimators.forEach { it.cancel() }
        drawerExchangeAnimators.clear()
        drawerExchangeUnderway = true

        val outgoing = listOfNotNull(drawerSessionsBlock, drawerFileListContainer)
            .filter { it.visibility == View.VISIBLE }
        isModulesMode = toModules
        updateModeVisibility(animate = true)
        // Directional exchange: the incoming mode's content springs in from
        // the side its button sits on (Modules left, Sessions right), so the
        // outgoing content exits toward the OPPOSITE side.
        val incomingDir = if (toModules) -1 else 1

        if (outgoing.isEmpty()) {
            refreshFileManagerDrawer()
            springInDrawerContent(incomingDir)
            drawerExchangeUnderway = false
            return
        }

        var remaining = outgoing.size
        var exchanged = false
        fun finishExchange() {
            if (exchanged) return
            exchanged = true
            refreshFileManagerDrawer()
            springInDrawerContent(incomingDir)
            drawerExchangeUnderway = false
        }
        outgoing.forEachIndexed { idx, block ->
            val fallbackW = (activity.resources.displayMetrics.widthPixels * 0.7f).toInt()
            val exitDist = (block.width.takeIf { it > 0 } ?: fallbackW) * 1.1f
            ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = idx * 45L
                duration = 170L
                interpolator = AccelerateInterpolator(0.8f)
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    block.translationX = exitDist * incomingDir * f
                    block.alpha = 1f - f
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Reset before the rebuild so refresh() sees a clean block
                        block.translationX = 0f
                        block.alpha = 1f
                        remaining--
                        if (remaining == 0) finishExchange()
                    }
                })
                start()
                drawerExchangeAnimators.add(this)
            }
        }
    }

    /** Staggered damped-spring entrance for the drawer content that just
     *  re-materialized after a mode exchange. [fromDirection]: -1 arrives
     *  from the left (Modules), +1 from the right (Sessions). */
    private fun springInDrawerContent(fromDirection: Int) {
        listOfNotNull(drawerSessionsBlock, drawerFileListContainer)
            .filter { it.visibility == View.VISIBLE }
            .forEachIndexed { idx, block ->
                block.postDelayed({ springSlideIn(block, fromDirection) }, idx * 40L)
            }
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
     * Damped-spring slide animation for the Left-Side File Manager Drawer.
     */
    fun toggleFileManagerDrawer(open: Boolean, animate: Boolean) {
        val drawer = fileManagerDrawerPanel ?: return
        val scrim = drawerScrimView ?: return
        val density = resources.displayMetrics.density
        val drawerWidth = 316f * density

        drawerAnimator?.cancel()
        isFileManagerDrawerOpen = open
        scrim.isClickable = open

        // Frosted-glass backdrop: Android 12+ gets a LIVE RenderEffect blur on
        // the workspace content (everything behind the drawer keeps animating
        // through the frost); older versions fall back to a static frosted
        // snapshot of the plain workspace (captured before overlays show).
        if (open) {
            val contentRoot = rootLayout
            val frost = drawerFrostView
            val densityNow = resources.displayMetrics.density
            if (contentRoot != null && Frost.tryLiveBlur(contentRoot, 9f * densityNow)) {
                // liveRenderEffect active: hide the static frost layer
                frost?.visibility = View.INVISIBLE
            } else if (frost != null) {
                val src = workspaceRootFrame
                if (src != null) {
                    frost.setImageBitmap(Frost.frozenBackdrop(src))
                    frost.alpha = 0f
                    frost.visibility = View.VISIBLE
                    frost.animate().alpha(0.92f).setDuration(240L).start()
                }
            }
        } else {
            Frost.clearLiveBlur(rootLayout)
            drawerFrostView?.let { frost ->
                frost.animate().alpha(0f).setDuration(200L).withEndAction {
                    frost.visibility = View.INVISIBLE
                    frost.setImageBitmap(null)
                }.start()
            }
        }

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
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                drawer.translationX = endTx - deltaTx * springFactor

                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                drawer.scaleX = 1f + 0.03f * pulse

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
                            saveCurrentModuleEditorState(showToast = true)
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
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.6f).apply { marginEnd = dp(6f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        compileCurrentModule()
                    }
                    setOnLongClickListener { true }
                    holdListener = { held ->
                        if (held) activity.showButtonPopup(
                            this,
                            "Compile & Load — converts the current [module] block into a binary .mathc bundle and " +
                                "links it into the running engine so its functions become callable everywhere. " +
                                "If the file contains plain expressions instead of a [module], it evaluates, logs " +
                                "its values in the Module Log, and exits while staying in Modules."
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
                    layoutParams = LinearLayout.LayoutParams(dp(88f), dp(38f))
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        isModuleEditorSubTab = false
                        updateModuleSubTabVisibility(animate = true)
                    }
                }

                execRow.addView(btnCompileModule)
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
                            "▶ Run in Session — evaluates the open .math file line-by-line inside the active " +
                            "session (shown in the header above). Every variable it binds stays in that " +
                            "session's MSP1 state and appears in the Variables card; output lines are appended " +
                            "to the session Log. Other sessions are untouched."
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

        openModuleFiles.forEachIndexed { targetIdx, modName ->
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
                    val prevIdx = openModuleFiles.indexOf(activeModuleFileName)
                    if (targetIdx == prevIdx && isSelected) {
                        springPulse(this, 0.06f)
                        return@setOnClickListener
                    }
                    // Dynamic starting direction relative to currently selected module:
                    // Clicking an item to the right (targetIdx > prevIdx) starts from right (+1);
                    // clicking an item to the left (targetIdx < prevIdx) starts from left (-1).
                    val fromDir = if (prevIdx < 0 || targetIdx >= prevIdx) 1 else -1
                    val dist = if (prevIdx < 0) 1 else kotlin.math.abs(targetIdx - prevIdx).coerceAtLeast(1)
                    saveCurrentModuleEditorState(showToast = false)
                    activeModuleFileName = modName
                    refreshModuleChips()
                    loadModuleStateIntoEditor(animate = true, fromDirection = fromDir, distance = dist)
                    refreshFileManagerDrawer()
                }
            }
            row.addView(chip)
        }
    }

    private fun loadModuleStateIntoEditor(
        animate: Boolean,
        fromDirection: Int = -1,
        distance: Int = 1
    ) {
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
            etModuleEditor?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            moduleQuickStatusCard?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            moduleLogCard?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            tvActiveModuleName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun saveCurrentModuleEditorState(showToast: Boolean): Boolean {
        val ctx = context ?: return false
        val activity = activity as? MainActivity ?: return false
        val code = etModuleEditor?.text?.toString() ?: return false
        val modName = activeModuleFileName

        if (modName != null && modName in openModuleFiles) {
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
        val prevIdx = openModuleFiles.indexOf(activeModuleFileName)
        saveCurrentModuleEditorState(showToast = false)
        if (fileName !in openModuleFiles) openModuleFiles.add(fileName)
        val newIdx = openModuleFiles.indexOf(fileName)
        val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
        val dist = if (prevIdx < 0) 1 else kotlin.math.abs(newIdx - prevIdx).coerceAtLeast(1)
        activeModuleFileName = fileName
        isModulesMode = true
        isModuleEditorSubTab = true
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = true, fromDirection = fromDir, distance = dist)
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
        val closedIdx = openModuleFiles.indexOf(fileName)
        if (activeModuleFileName == fileName) {
            saveCurrentModuleEditorState(showToast = false)
        }
        openModuleFiles.remove(fileName)
        if (activeModuleFileName == fileName) {
            activeModuleFileName = openModuleFiles.lastOrNull()
        }
        val newIdx = openModuleFiles.indexOf(activeModuleFileName)
        val fromDir = if (newIdx >= 0 && closedIdx >= 0 && newIdx > closedIdx) 1 else -1
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = true, fromDirection = fromDir, distance = 1)
        refreshFileManagerDrawer()
        tvModuleQuickStatus?.text = "✖ Closed module '$fileName' (still stored in ☰ File Manager)."
        activity.showButtonToast("Closed module $fileName")
    }

    private fun promptRenameOrCopyModuleFileDialog(targetName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        saveCurrentModuleEditorState(showToast = false)
        val baseStem = targetName.removeSuffix(".math")
        showFrostedFileNamingModal(
            badgeIcon = "✏️",
            title = "Rename or Copy Module",
            subtitle = "Module '$targetName' • Native [module] Extension",
            description = "Enter a new name to rename '$targetName' in-place, or tap 'Save Copy' to duplicate it as a new module.",
            initialText = targetName,
            hintText = "${baseStem}_v2.math",
            suggestions = listOf(
                "${baseStem}_v2.math",
                "${baseStem}_copy.math",
                "${baseStem}_ext.math"
            ),
            primaryButtonLabel = "Rename",
            onPrimary = { rawInput ->
                var newName = rawInput.trim()
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
            },
            secondaryButtonLabel = "Save Copy",
            onSecondary = { rawInput ->
                var copyName = rawInput.trim()
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
                        val prevIdx = openModuleFiles.indexOf(activeModuleFileName)
                        if (copyName !in openModuleFiles) openModuleFiles.add(copyName)
                        val newIdx = openModuleFiles.indexOf(copyName)
                        val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
                        activeModuleFileName = copyName
                        refreshModuleChips()
                        loadModuleStateIntoEditor(animate = true, fromDirection = fromDir)
                        refreshFileManagerDrawer()
                        activity.showButtonToast("Copied module to $copyName")
                    }
                }
            }
        )
    }

    private fun confirmDeleteSpecificModuleFile(fileName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val file = File(modulesDir(ctx), fileName)
        if (!file.exists()) return
        showFrostedConfirmModal(
            badgeIcon = "🗑",
            title = "Delete Module '$fileName'?",
            subtitle = "Modules Storage • Permanent Action",
            message = "Delete '$fileName' from Modules storage? Any unsaved edits in this module file will be removed.",
            confirmLabel = "Delete Module",
            onConfirm = {
                val prevIdx = openModuleFiles.indexOf(fileName)
                file.delete()
                moduleLogs.remove(fileName)
                openModuleFiles.remove(fileName)
                if (activeModuleFileName == fileName) {
                    activeModuleFileName = openModuleFiles.firstOrNull()
                }
                val newIdx = openModuleFiles.indexOf(activeModuleFileName)
                val fromDir = if (newIdx >= 0 && prevIdx >= 0 && newIdx > prevIdx) 1 else -1
                refreshModuleChips()
                loadModuleStateIntoEditor(animate = true, fromDirection = fromDir)
                refreshFileManagerDrawer()
                tvModuleQuickStatus?.text = "🗑 Deleted module '$fileName'."
                activity.showButtonToast("Deleted module $fileName")
            }
        )
    }

    private fun compileCurrentModule() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val code = etModuleEditor?.text?.toString() ?: ""

        var modFile = activeModuleFileName
        if (modFile == null || modFile !in openModuleFiles) {
            modFile = "custom_module.math"
            File(modulesDir(ctx), modFile).writeText(code)
            if (modFile !in openModuleFiles) openModuleFiles.add(modFile)
            activeModuleFileName = modFile
            refreshModuleChips()
            refreshFileManagerDrawer()
        } else {
            saveCurrentModuleEditorState(showToast = false)
        }

        val targetMod = modFile
        val isTrueModule = MathEngineManager.isTrueModuleSource(code)
        MathEngineManager.compileAndLoadExtension(code) { ok, message ->
            activity.runOnUiThread {
                val logEntry = if (isTrueModule) {
                    if (ok) {
                        "=== Module Build & Link Log ($targetMod) ===\n" +
                            "✅ Compiled & Linked .mathc Extension:\n$message"
                    } else {
                        "=== Module Build & Link Log ($targetMod) ===\n" +
                            "❌ Extension Compile Error:\n$message"
                    }
                } else {
                    "=== Module Evaluation Log ($targetMod) ===\n$message"
                }
                moduleLogs[targetMod] = logEntry
                tvModuleLogOutput?.text = logEntry
                tvModuleQuickStatus?.text = if (isTrueModule) {
                    if (ok) "✅ Linked '$targetMod': $message"
                    else "❌ Compile error in '$targetMod': $message"
                } else {
                    val firstLine = message.lines().lastOrNull().orEmpty()
                    if (ok) "✅ Evaluated '$targetMod': $firstLine"
                    else "⚠ Evaluated '$targetMod' with warnings (see 📋 Log)"
                }
                moduleQuickStatusCard?.let { springPulse(it, 0.045f) }
                moduleLogCard?.let { springPulse(it, 0.045f) }
                val toastText = if (isTrueModule) {
                    if (ok) "Module loaded into engine" else "Compile failed"
                } else {
                    if (ok) "Evaluated & logged values" else "Evaluation finished with errors"
                }
                activity.showButtonToast(toastText)
            }
        }
    }

    private fun promptNewModuleDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val nextNum = listModuleFiles(ctx).size + 1
        val defaultName = "module_${nextNum}.math"
        showFrostedFileNamingModal(
            badgeIcon = "🧩",
            title = "New .math Module",
            subtitle = "Native [module] Extension • Auto-appends .math",
            description = "Creates a reusable [module] extension file that compiles into native .mathc bytecode and links into the engine.",
            initialText = "",
            hintText = defaultName,
            suggestions = listOf(
                defaultName,
                "custom_ops.math",
                "physics_ext.math",
                "finance_fx.math"
            ),
            primaryButtonLabel = "✨ Create & Open",
            onPrimary = { rawInput ->
                var name = rawInput.trim()
                if (name.isEmpty()) {
                    name = defaultName
                }
                if (!name.endsWith(".math")) name += ".math"
                val prevIdx = openModuleFiles.indexOf(activeModuleFileName)
                saveCurrentModuleEditorState(showToast = false)
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
                val newIdx = openModuleFiles.indexOf(name)
                val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
                val dist = if (prevIdx < 0) 1 else kotlin.math.abs(newIdx - prevIdx).coerceAtLeast(1)
                activeModuleFileName = name
                isModulesMode = true
                isModuleEditorSubTab = true
                refreshModuleChips()
                loadModuleStateIntoEditor(animate = true, fromDirection = fromDir, distance = dist)
                updateModeVisibility(animate = false)
                updateModuleSubTabVisibility(animate = false)
                refreshFileManagerDrawer()
                activity.showButtonToast("Created module $name")
            }
        )
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
        val prevIdx = openSessionIds.indexOf(activeSessionId)
        val newIdx = openSessionIds.indexOf(targetSid)
        // Dynamic starting direction relative to currently selected session:
        // Clicking a session to the right (newIdx > prevIdx) starts from right (+1);
        // clicking a session to the left (newIdx < prevIdx) starts from left (-1).
        val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
        val dist = if (prevIdx < 0) 1 else kotlin.math.abs(newIdx - prevIdx).coerceAtLeast(1)

        saveCurrentSessionEditorState(activeSessionId, showToast = false)
        activeSessionId = targetSid
        refreshEvalFileChips()
        loadSessionStateIntoEditor(targetSid, animate = true, fromDirection = fromDir, distance = dist)
        refreshActiveSessionLogAndVars()
        refreshFileManagerDrawer()
        evalFileChipsRow?.let { springSlideIn(it, fromDirection = fromDir, distance = dist) }
        drawerFileListContainer?.let { springSlideIn(it, fromDirection = fromDir, distance = dist) }
        tvDrawerFilesSectionLabel?.let { springSlideIn(it, fromDirection = fromDir, distance = dist) }
        tvDrawerTitle?.let { springPulse(it, 0.045f) }
        tvActiveSessionFileName?.let { springPulse(it, 0.045f) }
    }

    /** Creates a brand-new isolated Session (`Session #2`, `Session #3`, ...) with zero files from other sessions. */
    private fun openNewStandaloneSession() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val prevIdx = openSessionIds.indexOf(activeSessionId)
        saveCurrentSessionEditorState(activeSessionId, showToast = false)

        MathEngineManager.openSession { sid ->
            activity.runOnUiThread {
                if (sid > 0) {
                    if (sid !in openSessionIds) openSessionIds.add(sid)
                    val newIdx = openSessionIds.indexOf(sid)
                    val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
                    val dist = if (prevIdx < 0) 1 else kotlin.math.abs(newIdx - prevIdx).coerceAtLeast(1)
                    initCleanIsolatedSessionState(ctx, sid, wipeDiskFiles = true)
                    activeSessionId = sid
                    sessionLogs[sid] = "=== Session #$sid Log ===\n" +
                        "Opened isolated Session #$sid (no files from other sessions are active here).\n" +
                        "Use '+ New' or '📥 Import' in ☰ File Manager to add files to Session #$sid."
                    refreshEvalFileChips()
                    loadSessionStateIntoEditor(sid, animate = true, fromDirection = fromDir, distance = dist)
                    refreshActiveSessionLogAndVars()
                    refreshFileManagerDrawer()
                    drawerFileListContainer?.let { springSlideIn(it, fromDirection = fromDir, distance = dist) }
                    tvDrawerFilesSectionLabel?.let { springSlideIn(it, fromDirection = fromDir, distance = dist) }
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
        val closedIdx = openSessionIds.indexOf(sid)
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
                val newIdx = openSessionIds.indexOf(activeSessionId)
                val fromDir = if (newIdx >= 0 && closedIdx >= 0 && newIdx > closedIdx) 1 else -1
                refreshEvalFileChips()
                loadSessionStateIntoEditor(activeSessionId, animate = true, fromDirection = fromDir, distance = 1)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                drawerFileListContainer?.let { springSlideIn(it, fromDirection = fromDir, distance = 1) }
                tvDrawerFilesSectionLabel?.let { springSlideIn(it, fromDirection = fromDir, distance = 1) }
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

        openList.forEachIndexed { targetIdx, fileName ->
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
                    val prevIdx = openList.indexOf(activeEvalFileBySession[sid])
                    if (targetIdx == prevIdx && selected) {
                        springPulse(this, 0.06f)
                        if (!isSessionEditorSubTab) {
                            animateSessionLogWindow(fromDirection = 1, distance = 1)
                        }
                        return@setOnClickListener
                    }
                    // Dynamic starting direction relative to currently selected file:
                    // Clicking an item to the right (targetIdx > prevIdx) starts from right (+1);
                    // clicking an item to the left (targetIdx < prevIdx) starts from left (-1).
                    val fromDir = if (prevIdx < 0 || targetIdx >= prevIdx) 1 else -1
                    val dist = if (prevIdx < 0) 1 else kotlin.math.abs(targetIdx - prevIdx).coerceAtLeast(1)
                    saveCurrentSessionEditorState(sid, showToast = false)
                    activeEvalFileBySession[sid] = fileName
                    refreshEvalFileChips()
                    loadSessionStateIntoEditor(sid, animate = true, fromDirection = fromDir, distance = dist)
                    refreshActiveSessionLogAndVars()
                    refreshFileManagerDrawer()
                }
            }
            row.addView(chip)
        }
    }

    /**
     * Animates the session log window (`varsCard` & `sessionLogCard`) with a directional
     * damped-spring entrance even when switching between files in the same session whose
     * shared session log content remains unchanged.
     */
    private fun animateSessionLogWindow(fromDirection: Int, distance: Int = 1) {
        varsCard?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
        sessionLogCard?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
    }

    private fun loadSessionStateIntoEditor(
        sid: Int,
        animate: Boolean,
        fromDirection: Int = -1,
        distance: Int = 1
    ) {
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
            etSessionEditor?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            sessionQuickStatusCard?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            animateSessionLogWindow(fromDirection = fromDirection, distance = distance)
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
        val defaultName = "session_${sid}_script.math"
        showFrostedFileNamingModal(
            badgeIcon = "💾",
            title = "Save File in Session #$sid",
            subtitle = "Isolated Session #$sid • Auto-appends .math",
            description = "Choose a file name to save the current scratchpad buffer inside isolated Session #$sid.",
            initialText = defaultName,
            hintText = defaultName,
            suggestions = listOf(
                defaultName,
                "calc_notes.math",
                "currency_fx.math",
                "matrix_lab.math"
            ),
            primaryButtonLabel = "💾 Save & Open",
            onPrimary = { rawInput ->
                var name = rawInput.trim()
                if (name.isEmpty()) name = defaultName
                if (!name.endsWith(".math")) name += ".math"
                val finalCode = if (codeToSave.isBlank()) "# Session #$sid Script: $name\n\n" else codeToSave
                val file = File(sessionFilesDir(ctx, sid), name)
                file.writeText(finalCode)
                getCachedFileMeta(file)
                val openList = getOpenFilesForSession(sid)
                val prevIdx = openList.indexOf(activeEvalFileBySession[sid])
                if (name !in openList) openList.add(name)
                val newIdx = openList.indexOf(name)
                val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
                val dist = if (prevIdx < 0) 1 else kotlin.math.abs(newIdx - prevIdx).coerceAtLeast(1)
                activeEvalFileBySession[sid] = name
                sessionUnsavedBuffers[sid] = ""
                refreshEvalFileChips()
                loadSessionStateIntoEditor(sid, animate = true, fromDirection = fromDir, distance = dist)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                tvSessionQuickStatus?.text = "💾 Saved & opened '$name' in Session #$sid."
                activity.showButtonToast("Saved $name in Session #$sid")
            }
        )
    }

    private fun promptNewEvalFileDialog() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val sid = activeSessionId
        if (sid <= 0) {
            activity.showButtonToast("Wait for session to initialize")
            return
        }
        val existingCount = listSessionFiles(ctx, sid).size + 1
        val defaultName = "session_${sid}_file_${existingCount}.math"
        showFrostedFileNamingModal(
            badgeIcon = "📄",
            title = "New File in Session #$sid",
            subtitle = "Isolated Session #$sid Workspace • Auto-appends .math",
            description = "Creates a new evaluation script isolated to Session #$sid with starter expressions and opens it in the editor.",
            initialText = "",
            hintText = defaultName,
            suggestions = listOf(
                defaultName,
                "calc_notes.math",
                "currency_fx.math",
                "matrix_lab.math"
            ),
            primaryButtonLabel = "✨ Create & Open",
            onPrimary = { rawInput ->
                var name = rawInput.trim()
                if (name.isEmpty()) {
                    name = defaultName
                }
                if (!name.endsWith(".math")) name += ".math"
                val openList = getOpenFilesForSession(sid)
                val prevIdx = openList.indexOf(activeEvalFileBySession[sid])
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
                if (name !in openList) openList.add(name)
                val newIdx = openList.indexOf(name)
                val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
                val dist = if (prevIdx < 0) 1 else kotlin.math.abs(newIdx - prevIdx).coerceAtLeast(1)
                activeEvalFileBySession[sid] = name
                isModulesMode = false
                isSessionEditorSubTab = true
                refreshEvalFileChips()
                loadSessionStateIntoEditor(sid, animate = true, fromDirection = fromDir, distance = dist)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                updateModeVisibility(animate = false)
                updateSessionSubTabVisibility(animate = false)
                tvSessionQuickStatus?.text = "Created & opened '$name' in isolated Session #$sid."
                activity.showButtonToast("Created $name in Session #$sid")
            }
        )
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
        val prevIdx = openList.indexOf(activeEvalFileBySession[sid])
        if (fileName !in openList) {
            openList.add(fileName)
        }
        val newIdx = openList.indexOf(fileName)
        val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
        val dist = if (prevIdx < 0) 1 else kotlin.math.abs(newIdx - prevIdx).coerceAtLeast(1)
        activeEvalFileBySession[sid] = fileName
        isModulesMode = false
        isSessionEditorSubTab = true
        refreshEvalFileChips()
        loadSessionStateIntoEditor(sid, animate = true, fromDirection = fromDir, distance = dist)
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
        val closedIdx = openList.indexOf(fileName)
        if (activeEvalFileBySession[sid] == fileName) {
            saveCurrentSessionEditorState(sid, showToast = false)
        }
        openList.remove(fileName)
        if (activeEvalFileBySession[sid] == fileName) {
            activeEvalFileBySession[sid] = openList.lastOrNull()
        }
        val newIdx = openList.indexOf(activeEvalFileBySession[sid])
        val fromDir = if (newIdx >= 0 && closedIdx >= 0 && newIdx > closedIdx) 1 else -1
        refreshEvalFileChips()
        loadSessionStateIntoEditor(sid, animate = true, fromDirection = fromDir, distance = 1)
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
        val baseStem = targetName.removeSuffix(".math")
        showFrostedFileNamingModal(
            badgeIcon = "✏️",
            title = "Rename or Copy File",
            subtitle = "Session #$sid • '$targetName'",
            description = "Enter a new name to rename '$targetName' in Session #$sid, or tap 'Save Copy' to duplicate it.",
            initialText = targetName,
            hintText = "${baseStem}_v2.math",
            suggestions = listOf(
                "${baseStem}_v2.math",
                "${baseStem}_copy.math",
                "session_${sid}_${baseStem}.math"
            ),
            primaryButtonLabel = "Rename",
            onPrimary = { rawInput ->
                var newName = rawInput.trim()
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
            },
            secondaryButtonLabel = "Save Copy",
            onSecondary = { rawInput ->
                var copyName = rawInput.trim()
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
                        val prevIdx = openList.indexOf(activeEvalFileBySession[sid])
                        if (copyName !in openList) openList.add(copyName)
                        val newIdx = openList.indexOf(copyName)
                        val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
                        activeEvalFileBySession[sid] = copyName
                        refreshEvalFileChips()
                        loadSessionStateIntoEditor(sid, animate = true, fromDirection = fromDir)
                        refreshActiveSessionLogAndVars()
                        refreshFileManagerDrawer()
                        activity.showButtonToast("Copied to $copyName in Session #$sid")
                    }
                }
            }
        )
    }

    private fun confirmDeleteSpecificFileInSession(sid: Int, fileName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val file = File(sessionFilesDir(ctx, sid), fileName)
        if (!file.exists()) {
            activity.showButtonToast("File not found in Session #$sid")
            return
        }
        showFrostedConfirmModal(
            badgeIcon = "🗑",
            title = "Delete '$fileName'?",
            subtitle = "Isolated Session #$sid • Permanent Action",
            message = "Delete '$fileName' from isolated Session #$sid? Other sessions will not be affected.",
            confirmLabel = "Delete File",
            onConfirm = {
                val openList = getOpenFilesForSession(sid)
                val prevIdx = openList.indexOf(fileName)
                file.delete()
                openList.remove(fileName)
                if (activeEvalFileBySession[sid] == fileName) {
                    activeEvalFileBySession[sid] = openList.firstOrNull()
                }
                val newIdx = openList.indexOf(activeEvalFileBySession[sid])
                val fromDir = if (newIdx >= 0 && prevIdx >= 0 && newIdx > prevIdx) 1 else -1
                refreshEvalFileChips()
                loadSessionStateIntoEditor(sid, animate = true, fromDirection = fromDir)
                refreshActiveSessionLogAndVars()
                refreshFileManagerDrawer()
                tvSessionQuickStatus?.text = "🗑 Deleted '$fileName' from Session #$sid."
                activity.showButtonToast("Deleted $fileName from Session #$sid")
            }
        )
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
                                loadSessionStateIntoEditor(newSid, animate = true, fromDirection = 1)
                                refreshActiveSessionLogAndVars()
                                refreshFileManagerDrawer()
                                drawerFileListContainer?.let { springSlideIn(it, fromDirection = 1) }
                                tvDrawerFilesSectionLabel?.let { springSlideIn(it, fromDirection = 1) }
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
        saveCurrentModuleEditorState(showToast = false)
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

            if (pendingImportForModules) {
                val file = File(modulesDir(ctx), displayName)
                file.writeText(content)
                getCachedFileMeta(file)
                if (displayName !in openModuleFiles) openModuleFiles.add(displayName)
                activeModuleFileName = displayName
                isModulesMode = true
                isModuleEditorSubTab = true
                refreshModuleChips()
                loadModuleStateIntoEditor(animate = true, fromDirection = 1)
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
                loadSessionStateIntoEditor(targetSid, animate = true, fromDirection = 1)
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
            saveCurrentModuleEditorState(showToast = false)
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
                // Modules sits LEFT of Sessions: its pane springs in from the left
                modulesSection?.let { springSlideIn(it, fromDirection = -1) }
            }
        } else {
            modulesSection?.visibility = View.GONE
            sessionsSection?.visibility = View.VISIBLE
            refreshEvalFileChips()
            refreshActiveSessionLogAndVars()
            if (animate) {
                btnModeSessions?.let { springPulse(it, 0.06f) }
                // Sessions sits right: its pane springs in from the right
                sessionsSection?.let { springSlideIn(it, fromDirection = 1) }
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
                // Editor is the LEFT sub-tab: its pane springs in from the left
                moduleEditorPane?.let { springSlideIn(it, fromDirection = -1) }
            }
        } else {
            moduleEditorPane?.visibility = View.GONE
            moduleLogPane?.visibility = View.VISIBLE
            if (animate) {
                btnModuleSubLog?.let { springPulse(it, 0.06f) }
                // Log is the RIGHT sub-tab: its pane springs in from the right
                moduleLogPane?.let { springSlideIn(it, fromDirection = 1) }
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
                // Editor is the LEFT sub-tab: its pane springs in from the left
                sessionEditorPane?.let { springSlideIn(it, fromDirection = -1) }
            }
        } else {
            sessionEditorPane?.visibility = View.GONE
            sessionLogPane?.visibility = View.VISIBLE
            refreshActiveSessionLogAndVars()
            if (animate) {
                btnSessionSubLog?.let { springPulse(it, 0.06f) }
                // Log is the RIGHT sub-tab: its pane springs in from the right
                sessionLogPane?.let { springSlideIn(it, fromDirection = 1) }
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
            btnCompileModule, btnModuleViewLog,
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

    private fun springSlideIn(target: View, fromDirection: Int = 1, distance: Int = 1) {
        springAnimators.removeAll { (v, a) -> if (v === target) { a.cancel(); true } else false }
        // fromDirection: +1 = spring in from the right, -1 = from the left.
        // Scales proportionally with item distance (1 = adjacent item, up to 4 = farther item).
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

    private fun hideSoftKeyboardFrom(view: View?) {
        val ctx = context ?: return
        val target = view ?: return
        runCatching {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(target.windowToken, 0)
        }
    }

    fun dismissWorkspaceModalDialog(onEnd: (() -> Unit)? = null) {
        val overlay = activeModalOverlay
        if (overlay == null) {
            isModalDialogOpen = false
            onEnd?.invoke()
            return
        }
        activeModalOverlay = null
        isModalDialogOpen = false
        hideSoftKeyboardFrom(overlay)

        // Restore live blur state: if File Manager drawer is open, keep rootLayout blurred
        // and unblur the drawer panel; otherwise clear blur on both.
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

    /**
     * Displays a modern, tactile neumorphic modal dialog for naming a new or existing `.math` file,
     * backed by the same frosted-glass blur backdrop as the Info window, File Manager drawer,
     * and hold-tooltip popups.
     */
    private fun showFrostedFileNamingModal(
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

        // Capture frosted snapshot of the entire workspace (including the open File Manager drawer)
        // before attaching the modal overlay, and apply live GPU RenderEffect blur on Android 12+.
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

        // 1. Header Row: Badge Icon + Title/Subtitle + Top-Right '✕' Close Button
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

        // 2. Description Box
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

        // 3. Recessed Input Field with Accent Focus Border & '.math' Pill
        val tvInputLabel = TextView(ctx).apply {
            text = "FILE NAME"
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

        // 4. Quick-Pick Name Suggestion Chips
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

        // 5. Action Buttons Row (Cancel + Optional Secondary + Primary Action)
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

        // Animate backdrop fade + damped-spring entrance of the dialog card
        overlay.alpha = 0f
        dialogCard.scaleX = 0.78f
        dialogCard.scaleY = 0.78f
        dialogCard.translationY = 18f * density
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
                    inputEdit.requestFocus()
                    runCatching {
                        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(inputEdit, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            })
            start()
        }
    }

    /**
     * Displays a frosted-glass neumorphic confirmation modal (e.g., for deleting a file/module).
     */
    private fun showFrostedConfirmModal(
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

        overlay.alpha = 0f
        dialogCard.scaleX = 0.78f
        dialogCard.scaleY = 0.78f
        dialogCard.translationY = 18f * density
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
                }
            })
            start()
        }
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
        saveCurrentModuleEditorState(showToast = false)
        saveCurrentSessionEditorState(activeSessionId, showToast = false)
        drawerAnimator?.cancel()
        drawerExchangeAnimators.forEach { it.cancel() }
        drawerExchangeAnimators.clear()
        drawerExchangeUnderway = false
        activeModalOverlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        activeModalOverlay = null
        isModalDialogOpen = false
        activeModalBlurBitmap?.recycle()
        activeModalBlurBitmap = null
        drawerFrostView?.setImageBitmap(null)
        drawerFrostView = null
        wobbleAnimators.forEach { it.cancel() }
        wobbleAnimators.clear()
        springAnimators.forEach { it.second.cancel() }
        springAnimators.clear()
        super.onDestroyView()
    }
}
