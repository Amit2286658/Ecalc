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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import java.io.File
import kotlin.math.hypot

/**
 * Unified Top-to-Bottom Executable `.math` Modules & Multi-File Project Workspace
 * with Left-Drawer Project & File Manager.
 */
class MathWorkspaceFragment : Fragment() {

    internal var workspaceRootFrame: FrameLayout? = null
    internal var rootLayout: LinearLayout? = null
    internal var topHeaderBar: LinearLayout? = null
    internal var btnHamburgerMenu: NeumorphicKeyView? = null
    internal var btnBackCalc: NeumorphicKeyView? = null
    internal var tvWorkspaceTitle: TextView? = null
    internal var btnActiveProjectPill: NeumorphicKeyView? = null

    // --- Left-Side Sliding File Manager Drawer & Frosted Modal Dialogs ---
    var isFileManagerDrawerOpen: Boolean = false
        internal set
    var isModalDialogOpen: Boolean = false
        internal set
    internal var activeModalOverlay: FrameLayout? = null
    internal var activeModalBlurBitmap: Bitmap? = null
    internal var drawerScrimView: View? = null
    internal var fileManagerDrawerPanel: LinearLayout? = null
    internal var drawerFrostView: ImageView? = null
    internal var tvDrawerTitle: TextView? = null
    internal var tvDrawerSubtitle: TextView? = null
    internal var btnDrawerCloseIcon: NeumorphicKeyView? = null

    // Drawer Projects Section
    internal var tvDrawerProjectsSectionLabel: TextView? = null
    internal var btnDrawerRunProject: NeumorphicKeyView? = null
    internal var btnDrawerCreateProject: NeumorphicKeyView? = null
    internal var btnDrawerResetProjectEngine: NeumorphicKeyView? = null
    internal var drawerProjectListContainer: LinearLayout? = null

    // Drawer File List Header & Container
    internal var tvDrawerFilesSectionLabel: TextView? = null
    internal var btnDrawerCreateNewFile: NeumorphicKeyView? = null
    internal var btnDrawerImportFile: NeumorphicKeyView? = null
    internal var drawerFileListContainer: LinearLayout? = null

    // --- Main Window Section ---
    internal var modulesSection: LinearLayout? = null
    internal var moduleChipsRow: LinearLayout? = null

    internal var moduleSubTabBar: LinearLayout? = null
    internal var btnModuleSubEditor: NeumorphicKeyView? = null
    internal var btnModuleSubLog: NeumorphicKeyView? = null

    internal var moduleEditorPane: LinearLayout? = null
    internal var tvActiveModuleName: TextView? = null
    internal var etModuleEditor: MathCodeEditText? = null
    internal var symbolChipsRow: LinearLayout? = null
    internal val symbolChipViews = mutableListOf<NeumorphicKeyView>()
    internal var btnCompileModule: NeumorphicKeyView? = null
    internal var btnPlotModule: NeumorphicKeyView? = null
    internal var btnBytecodeModule: NeumorphicKeyView? = null
    internal var btnResetActiveModule: NeumorphicKeyView? = null
    internal var btnModuleViewLog: NeumorphicKeyView? = null
    internal var moduleQuickStatusCard: LinearLayout? = null
    internal var tvModuleQuickStatus: TextView? = null

    internal var moduleLogPane: LinearLayout? = null
    internal var varsCard: LinearLayout? = null
    internal var tvVarsHeader: TextView? = null
    internal var tvVarsContent: TextView? = null
    internal var inspectorCard: LinearLayout? = null
    internal var tvInspectorHeader: TextView? = null
    internal var inspectorChipsRow: LinearLayout? = null
    internal var inspectorTableContainer: LinearLayout? = null
    internal var consoleCard: LinearLayout? = null
    internal var tvConsoleHeader: TextView? = null
    internal var tvConsoleOutput: TextView? = null
    internal var btnClearConsole: NeumorphicKeyView? = null
    internal var moduleLogCard: LinearLayout? = null
    internal var tvModuleLogOutput: TextView? = null
    internal var btnModuleBackToEditor: NeumorphicKeyView? = null
    internal var btnOutputPlot: NeumorphicKeyView? = null
    internal var btnOutputBytecode: NeumorphicKeyView? = null
    internal var btnClearModuleLog: NeumorphicKeyView? = null

    // --- Workspace State ---
    internal var activeProjectName: String = MathEngineManager.DEFAULT_PROJECT_NAME
    internal val projectOpenModulesMap = mutableMapOf<String, MutableList<String>>()
    internal val projectActiveFileMap = mutableMapOf<String, String?>()
    internal var isModuleEditorSubTab: Boolean = true
    internal val openModuleFiles = mutableListOf<String>()
    internal var activeModuleFileName: String? = "physics_sim.math"
    internal var moduleUnsavedBuffer: String = ""
    internal val moduleLogs = mutableMapOf<String, String>()
    internal val moduleConsoleLogs = mutableMapOf<String, String>()
    internal val moduleVarsCache = mutableMapOf<String, String>()
    internal val moduleErrorLines = mutableMapOf<String, Pair<Int, String>>()
    internal val moduleInspectableItems = mutableMapOf<String, List<Triple<String, String, String>>>()

    // Cached file metadata (`<path>:<mtime>:<len>` -> Pair(lineCount, byteLength))
    internal val fileMetaCache = mutableMapOf<String, Pair<Int, Long>>()

    internal var pendingExportMathContent: String = ""
    internal var pendingExportMathFileName: String = ""

    internal val wobbleAnimators = mutableListOf<ValueAnimator>()
    internal val springAnimators = mutableListOf<Pair<View, ValueAnimator>>()
    internal var drawerAnimator: ValueAnimator? = null

    /** SAF Launcher to load/import an individual `.math` module file from device storage. */
    internal val importMathFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportedMathFileUri(uri)
        }
    }

    /** SAF Launcher to export/save an individual `.math` module file to device storage. */
    internal val exportMathFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            handleExportedMathFileUri(uri)
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

        ensureSampleAndMigratedModuleFiles(ctx)

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
                    "Project & Module Manager — opens the left-hand drawer.\n" +
                        "• Run an entire project via '▶ Run' in Project Options (executes main() / @entry located in any file).\n" +
                        "• Individual .math files run top-to-bottom on their own via '▶ Run / Link' in the Editor."
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
                if (held) activity.showButtonPopup(
                    this,
                    "← Calc — returns to the scientific calculator (Standalone Mode on the shared engine)."
                ) else activity.dismissButtonPopup()
            }
        }
        headerRow.addView(btnBackCalc)

        tvWorkspaceTitle = TextView(ctx).apply {
            text = ".math Studio"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvWorkspaceTitle)

        btnActiveProjectPill = NeumorphicKeyView(ctx).apply {
            text = "📦 $activeProjectName"
            textSize = 11.2f
            cornerRadiusDp = 13f
            shadowPadDp = 2.6f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(106f), dp(36f))
            setOnClickListener {
                springPulse(this, 0.06f)
                toggleFileManagerDrawer(open = true, animate = true)
            }
            setOnLongClickListener { true }
            holdListener = { held ->
                if (held) {
                    val status = MathEngineManager.getProjectBinaryStatus(ctx, activeProjectName)
                    val ep = MathEngineManager.findProjectEntryPointOnDisk(
                        ctx,
                        activeProjectName,
                        activeModuleFileName,
                        etModuleEditor?.text?.toString()
                    )
                    val epDesc = if (ep != null) "${ep.fileName} :: ${ep.entryFn}()" else "None (individual files run top-to-bottom)"
                    activity.showButtonPopup(
                        this,
                        "Active Project: [$activeProjectName]\n" +
                            "Entry Point: $epDesc\n" +
                            "• engine.so: ${if (status.hasEngineSo) "${status.engineSoBytes / 1024} KB" else "provisioned"}\n" +
                            "• stdlib.mathc: ${status.stdlibMathcBytes / 1024} KB (${status.linkedModulesCount} module(s) linked)\n" +
                            "Tap to open ☰ Project Options & File Manager."
                    )
                } else activity.dismissButtonPopup()
            }
        }
        headerRow.addView(btnActiveProjectPill)
        root.addView(headerRow)

        // 2. Build Unified Executable Modules Section (Maximized Editor & Output/Vars panes)
        modulesSection = buildModulesSection(ctx, activity, dp)
        root.addView(modulesSection)

        outerFrame.addView(root)

        // 3. Build Left-Side Sliding File Manager Drawer + Frosted Backdrop & Scrim
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

        // 4. Dynamic Virtual Keyboard (IME) & System-Bar Inset Handling (fixes edge-to-edge targetSdk 36 keyboard overlap)
        var lastAppliedImePad = -1
        fun applyKeyboardInset(imeBottomPx: Int, sysTopPx: Int, sysBottomPx: Int) {
            val topPad = maxOf(dp(40f), sysTopPx + dp(8f))
            val effectiveBottom = if (imeBottomPx > sysBottomPx + dp(48f)) {
                imeBottomPx + dp(6f)
            } else {
                maxOf(dp(10f), sysBottomPx + dp(8f))
            }
            if (effectiveBottom != lastAppliedImePad || root.paddingTop != topPad) {
                val wasKeyboardUp = lastAppliedImePad > dp(80f)
                val isKeyboardUp = effectiveBottom > dp(80f)
                lastAppliedImePad = effectiveBottom
                root.setPadding(dp(12f), topPad, dp(12f), effectiveBottom)
                root.requestLayout()
                activeModalOverlay?.setPadding(0, 0, 0, (imeBottomPx / 2).coerceAtLeast(0))
                if (isKeyboardUp) {
                    etModuleEditor?.post {
                        etModuleEditor?.ensureCursorVisible()
                        if (!wasKeyboardUp && etModuleEditor?.isSuggestionsPopupShowing() == true) {
                            etModuleEditor?.editableText?.let { etModuleEditor?.updateSuggestionsForCurrentCursor(it) }
                        }
                    }
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(outerFrame) { _, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val sysInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            applyKeyboardInset(imeInsets.bottom, sysInsets.top, sysInsets.bottom)
            windowInsets
        }

        outerFrame.viewTreeObserver.addOnGlobalLayoutListener {
            if (!isAdded || outerFrame.height <= 0) return@addOnGlobalLayoutListener
            val rect = android.graphics.Rect()
            outerFrame.getWindowVisibleDisplayFrame(rect)
            val screenH = outerFrame.rootView.height
            if (screenH > 0 && rect.bottom > rect.top) {
                val keypadHeight = (screenH - rect.bottom).coerceAtLeast(0)
                if (keypadHeight > screenH * 0.15f) {
                    applyKeyboardInset(keypadHeight, rect.top, 0)
                } else if (lastAppliedImePad > dp(80f)) {
                    applyKeyboardInset(0, rect.top, 0)
                }
            }
        }

        loadModuleStateIntoEditor(animate = false)
        updateModuleSubTabVisibility(animate = false)
        applyTheme(dark)

        return outerFrame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { applyTheme(it.isDarkMode) }
    }

    /** Handles system Back button when a frosted modal dialog or the File Manager drawer is open. */
    fun handleBackPressed(): Boolean {
        if (etModuleEditor?.isSuggestionsPopupShowing() == true) {
            etModuleEditor?.dismissSuggestionsPopup()
            return true
        }
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

    // =========================================================================
    //  Left-Side Sliding File Manager Drawer Builder & Jank-Free Spring Physics
    // =========================================================================

    internal fun buildFileManagerDrawer(
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

            // 1. Drawer Header: Title + Subtitle + Close [✕] button
            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
            }

            val titleCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvDrawerTitle = TextView(ctx).apply {
                text = "📦 Project & Module Manager"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryText)
            }
            tvDrawerSubtitle = TextView(ctx).apply {
                text = "Project [$activeProjectName] • Shared Recyclable Engine"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f)
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

            // 2. Projects Section Header: Label + [▶ Run] + [+ Project] (no flush engine bloat button)
            val projectsHeaderRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4f) }
            }
            tvDrawerProjectsSectionLabel = TextView(ctx).apply {
                text = "STUDIO PROJECTS"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(secondaryText)
                setPadding(dp(2f), 0, dp(4f), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            btnDrawerRunProject = NeumorphicKeyView(ctx).apply {
                text = "▶ Run"
                textSize = 10.2f
                cornerRadiusDp = 10f
                shadowPadDp = 2.3f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(64f), dp(30f)).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    runSpecificProject(activeProjectName, closeDrawerAfter = true)
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "▶ Run Project — finds and executes the main() or @entry(fn) function located anywhere in any .math file of the active project.\n" +
                            "If no main/@entry exists in any file, the project itself cannot run while individual files can still run top-to-bottom on their own."
                    ) else activity.dismissButtonPopup()
                }
            }
            btnDrawerCreateProject = NeumorphicKeyView(ctx).apply {
                text = "+ Project"
                textSize = 10.2f
                cornerRadiusDp = 10f
                shadowPadDp = 2.3f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(76f), dp(30f))
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptNewProjectDialog()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "+ Project — creates a new Studio project folder."
                    ) else activity.dismissButtonPopup()
                }
            }
            projectsHeaderRow.addView(tvDrawerProjectsSectionLabel)
            projectsHeaderRow.addView(btnDrawerRunProject)
            projectsHeaderRow.addView(btnDrawerCreateProject)
            addView(projectsHeaderRow)

            drawerProjectListContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }
            }
            addView(drawerProjectListContainer)

            // 3. Files Section Header: Label + [+ Module] + [📥 Import]
            val filesHeaderRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6f) }
            }
            tvDrawerFilesSectionLabel = TextView(ctx).apply {
                text = "PROJECT .MATH MODULES"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(secondaryText)
                setPadding(dp(2f), 0, dp(4f), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            btnDrawerCreateNewFile = NeumorphicKeyView(ctx).apply {
                text = "+ Module"
                textSize = 11f
                cornerRadiusDp = 10f
                shadowPadDp = 2.4f
                isAccentKey = true
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(82f), dp(32f)).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    springPulse(this, 0.07f)
                    promptNewModuleDialog()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "+ Module — creates a new top-to-bottom executable .math module file in the active project."
                    ) else activity.dismissButtonPopup()
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
                    launchImportMathFileForModules()
                }
                setOnLongClickListener { true }
                holdListener = { held ->
                    if (held) activity.showButtonPopup(
                        this,
                        "📥 Import — imports a .math file from device storage into the active project."
                    ) else activity.dismissButtonPopup()
                }
            }
            filesHeaderRow.addView(tvDrawerFilesSectionLabel)
            filesHeaderRow.addView(btnDrawerCreateNewFile)
            filesHeaderRow.addView(btnDrawerImportFile)
            addView(filesHeaderRow)

            // 4. Scrollable File Entries List
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

    /** Damped-spring slide animation for the Left-Side File Manager Drawer. */
    fun toggleFileManagerDrawer(open: Boolean, animate: Boolean) {
        val drawer = fileManagerDrawerPanel ?: return
        val scrim = drawerScrimView ?: return
        val density = resources.displayMetrics.density
        val drawerWidth = 316f * density

        if (open) {
            etModuleEditor?.dismissSuggestionsPopup()
        }
        drawerAnimator?.cancel()
        isFileManagerDrawerOpen = open
        scrim.isClickable = open

        if (open) {
            val contentRoot = rootLayout
            val frost = drawerFrostView
            if (contentRoot != null && Frost.tryLiveBlur(contentRoot, 9f * density)) {
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

        if (open) {
            refreshFileManagerDrawer()
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

    internal fun getCachedFileMeta(file: File): Pair<Int, Long> {
        val key = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        return fileMetaCache.getOrPut(key) {
            val content = runCatching { file.readText() }.getOrDefault("")
            val lines = if (content.isEmpty()) 0 else content.lines().size
            lines to file.length()
        }
    }

    internal fun refreshFileManagerDrawer() {
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

        val listContainer = drawerFileListContainer ?: return
        listContainer.removeAllViews()

        fun makeEntryActionBtn(label: String, accent: Boolean): NeumorphicKeyView {
            return NeumorphicKeyView(ctx).apply {
                text = label
                textSize = 9.6f
                cornerRadiusDp = 8.5f
                shadowPadDp = 2.0f
                isAccentKey = accent
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(0, dp(28f), 1f).apply {
                    marginEnd = dp(2f)
                }
            }
        }

        val allProjects = MathEngineManager.listProjects(ctx)
        val projStatus = MathEngineManager.getProjectBinaryStatus(ctx, activeProjectName)
        val allModFiles = listModuleFiles(ctx)
        openModuleFiles.retainAll { name -> allModFiles.any { it.name == name } }

        val activeEditorText = etModuleEditor?.text?.toString()
        val activeProjEntry = MathEngineManager.findProjectEntryPointOnDisk(
            ctx,
            activeProjectName,
            activeModuleFileName,
            activeEditorText
        )
        btnDrawerRunProject?.apply {
            isAccentKey = activeProjEntry != null
            alpha = if (activeProjEntry != null) 1f else 0.45f
        }

        val pillW = (activeProjectName.length * 7.5f + 44f).coerceIn(96f, 150f)
        btnActiveProjectPill?.text = "📦 $activeProjectName"
        btnActiveProjectPill?.layoutParams = LinearLayout.LayoutParams(dp(pillW), dp(36f))

        if (!isFileManagerDrawerOpen) return

        tvDrawerTitle?.text = "📦 Project & Module Manager"
        val stdlibKb = (projStatus.stdlibMathcBytes / 1024L).coerceAtLeast(1L)
        tvDrawerSubtitle?.text = "[$activeProjectName] • Shared Engine ✓ • stdlib+patches (${stdlibKb}KB)"
        tvDrawerFilesSectionLabel?.text = "MODULES IN [$activeProjectName] (${openModuleFiles.size}/${allModFiles.size})"

        drawerProjectListContainer?.let { projContainer ->
            projContainer.removeAllViews()
            allProjects.forEach { projName ->
                val isCurProj = (projName == activeProjectName)
                val isDefaultProj = (projName == MathEngineManager.DEFAULT_PROJECT_NAME)
                val pStat = MathEngineManager.getProjectBinaryStatus(ctx, projName)
                val pModsCount = listModuleFiles(ctx, projName).size
                val pStdKb = (pStat.stdlibMathcBytes / 1024L).coerceAtLeast(1L)
                val projEntry = MathEngineManager.findProjectEntryPointOnDisk(
                    ctx,
                    projName,
                    if (isCurProj) activeModuleFileName else projectActiveFileMap[projName],
                    if (isCurProj) activeEditorText else null
                )
                val canRunProj = projEntry != null

                val projCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(5f) }
                    setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 11f * density
                        setColor(if (isCurProj) activeRowBg else rowBg)
                        setStroke(((if (isCurProj) 1.5f else 1f) * density).toInt(), if (isCurProj) activeStroke else strokeColor)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        springPulse(this, 0.04f)
                        switchToProject(projName, closeDrawerAfter = true)
                    }
                }

                val pTop = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val tvPName = TextView(ctx).apply {
                    text = if (isDefaultProj) "📦 $projName (default)" else "📦 $projName"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
                    setTypeface(typeface, if (isCurProj) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isCurProj) accentText else primaryText)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvPBadge = TextView(ctx).apply {
                    text = if (isCurProj) "🟢 ACTIVE" else "⚪ SWITCH"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (isCurProj) accentText else secondaryText)
                }
                pTop.addView(tvPName)
                pTop.addView(tvPBadge)
                projCard.addView(pTop)

                val tvPMeta = TextView(ctx).apply {
                    val entryBadge = if (projEntry != null) {
                        "▶ ${projEntry.fileName}::${projEntry.entryFn}()"
                    } else {
                        "⚪ no main/@entry (file-only)"
                    }
                    text = "$pModsCount mod(s) • $entryBadge • stdlib (${pStdKb}KB)"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(secondaryText)
                    setPadding(0, dp(2f), 0, dp(4f))
                }
                projCard.addView(tvPMeta)

                // Project Options Row: [▶ Run] | [Open/Active] | [Clone/Ren] | [Del]
                // Reset is completely removed; for default project (project1), "Del" is greyed out and unclickable.
                val pActions = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val btnRunProj = makeEntryActionBtn("▶ Run", canRunProj).apply {
                    alpha = if (canRunProj) 1f else 0.45f
                    setOnClickListener {
                        springPulse(this, 0.06f)
                        runSpecificProject(projName, closeDrawerAfter = true)
                    }
                    setOnLongClickListener { true }
                    holdListener = { held ->
                        if (held) {
                            val msg = if (projEntry != null) {
                                "▶ Run Project [$projName] — runs ${projEntry.fileName} :: ${projEntry.entryFn}()."
                            } else {
                                "Project [$projName] has no main() or @entry function in any file, so the project itself cannot run. Open individual files to run them top-to-bottom."
                            }
                            activity.showButtonPopup(this, msg)
                        } else activity.dismissButtonPopup()
                    }
                }
                val btnSwitchProj = makeEntryActionBtn(if (isCurProj) "Active" else "Open", !isCurProj).apply {
                    setOnClickListener {
                        springPulse(this, 0.05f)
                        switchToProject(projName, closeDrawerAfter = true)
                    }
                }
                val btnRenameProj = makeEntryActionBtn("Clone/Ren", false).apply {
                    setOnClickListener {
                        promptRenameOrCopyProjectDialog(projName)
                    }
                }
                val btnDelProj = makeEntryActionBtn("Del", false).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                    if (isDefaultProj) {
                        isEnabled = false
                        isClickable = false
                        isFocusable = false
                        alpha = 0.42f
                    } else {
                        setOnClickListener {
                            confirmDeleteOrResetProject(projName)
                        }
                    }
                }
                pActions.addView(btnRunProj)
                pActions.addView(btnSwitchProj)
                pActions.addView(btnRenameProj)
                pActions.addView(btnDelProj)
                projCard.addView(pActions)

                projContainer.addView(projCard)
            }
        }

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
                text = "No .math module files stored.\nTap '+ Module' or '📥 Import' above to create or import a top-to-bottom executable .math module."
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
            val rawSrc = if (isActive && activeEditorText != null) {
                activeEditorText
            } else {
                runCatching { file.readText() }.getOrDefault("")
            }
            val modId = MathEngineManager.deriveModuleName(fname, rawSrc)
            val deps = MathEngineManager.extractModuleDependencies(rawSrc)
            val (entryFn, _) = MathEngineManager.detectModuleEntryPoint(rawSrc)
            val depsLabel = if (deps.isNotEmpty()) " • <[${deps.joinToString(", ")}]" else ""
            val entryLabel = if (entryFn != null) " • @entry:$entryFn()" else ""

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
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    springPulse(this, 0.04f)
                    openModuleFile(fname, closeDrawerAfter = true)
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
                text = "[$modId]$depsLabel$entryLabel • $lineCount lines • $byteLen B"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.2f)
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
    }

    internal var selectedInspectorIdx: Int = 0

    override fun onDestroyView() {
        etModuleEditor?.dismissSuggestionsPopup()
        saveCurrentModuleEditorState(showToast = false)
        drawerAnimator?.cancel()
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
