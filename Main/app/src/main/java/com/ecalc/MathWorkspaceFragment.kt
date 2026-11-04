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
 * with Left-Drawer File Manager.
 *
 * Key design:
 * 1. **Session-Free Unified `.math` Module Architecture**:
 *    - Every `.math` file is a first-class, top-to-bottom executable module (Python-style,
 *      no entry point required).
 *    - A single `.math` file can freely interleave `[module]` headers, `<[dep]` dependencies
 *      on stdlib or sibling `.math` files in the project, `@dim` directives, `$type` structs,
 *      `pub`/`priv` functions & operators, `pub`/`priv` + `mut`/`const` module variables
 *      (`:=` vs `=`), control flow, and standalone expressions.
 * 2. **Maximized Main Editing & Output Window**:
 *    - Top Header Bar has the **`☰` Hamburger Menu** button on the far left and **`← Calc`** right next to it.
 *    - Horizontal scrollable open-module chips row (`🧩 custom_ops.math`, `🧩 physics_sim.math`, ...)
 *      lets you switch between files in your multi-file project with directional damped-spring physics.
 *    - Two sub-tabs: **`📝 Editor`** and **`📋 Output & Vars`**.
 *    - **`📋 Output & Vars`** displays both the **Module Variables & Constants Card** (`pub`/`priv`,
 *      `mut`/`const`, types, units, and values) and the **Top-to-Bottom Execution & Link Log**.
 * 3. **Left-Side Hamburger File Manager Drawer (`☰`)**:
 *    - Provides project-wide module controls (`▶ Run All`, `↺ Reset`, `📤 Export State`),
 *      file creation/import (`+ Module`, `📥 Import`), and per-file inline actions
 *      (`Open`, `Close`, `Save`, `Rename`, `Export`, `Del`).
 */
class MathWorkspaceFragment : Fragment() {

    private var workspaceRootFrame: FrameLayout? = null
    private var rootLayout: LinearLayout? = null
    private var topHeaderBar: LinearLayout? = null
    private var btnHamburgerMenu: NeumorphicKeyView? = null
    private var btnBackCalc: NeumorphicKeyView? = null
    private var tvWorkspaceTitle: TextView? = null

    // --- Left-Side Sliding File Manager Drawer & Frosted Modal Dialogs ---
    var isFileManagerDrawerOpen: Boolean = false
        private set
    var isModalDialogOpen: Boolean = false
        private set
    private var activeModalOverlay: FrameLayout? = null
    private var activeModalBlurBitmap: Bitmap? = null
    private var drawerScrimView: View? = null
    private var fileManagerDrawerPanel: LinearLayout? = null
    private var drawerFrostView: ImageView? = null
    private var tvDrawerTitle: TextView? = null
    private var tvDrawerSubtitle: TextView? = null
    private var btnDrawerCloseIcon: NeumorphicKeyView? = null

    // Drawer Module Controls Bar
    private var drawerModuleControlsBlock: LinearLayout? = null
    private var tvDrawerControlsLabel: TextView? = null
    private var btnDrawerRunAll: NeumorphicKeyView? = null
    private var btnDrawerResetMod: NeumorphicKeyView? = null
    private var btnDrawerExportState: NeumorphicKeyView? = null

    // Drawer File List Header & Container
    private var tvDrawerFilesSectionLabel: TextView? = null
    private var btnDrawerCreateNewFile: NeumorphicKeyView? = null
    private var btnDrawerImportFile: NeumorphicKeyView? = null
    private var drawerFileListContainer: LinearLayout? = null

    // --- Main Window Section ---
    private var modulesSection: LinearLayout? = null
    private var moduleChipsRow: LinearLayout? = null

    private var moduleSubTabBar: LinearLayout? = null
    private var btnModuleSubEditor: NeumorphicKeyView? = null
    private var btnModuleSubLog: NeumorphicKeyView? = null

    private var moduleEditorPane: LinearLayout? = null
    private var tvActiveModuleName: TextView? = null
    private var etModuleEditor: MathCodeEditText? = null
    private var symbolChipsRow: LinearLayout? = null
    private val symbolChipViews = mutableListOf<NeumorphicKeyView>()
    private var btnCompileModule: NeumorphicKeyView? = null
    private var btnPlotModule: NeumorphicKeyView? = null
    private var btnBytecodeModule: NeumorphicKeyView? = null
    private var btnResetActiveModule: NeumorphicKeyView? = null
    private var btnModuleViewLog: NeumorphicKeyView? = null
    private var moduleQuickStatusCard: LinearLayout? = null
    private var tvModuleQuickStatus: TextView? = null

    private var moduleLogPane: LinearLayout? = null
    private var varsCard: LinearLayout? = null
    private var tvVarsHeader: TextView? = null
    private var tvVarsContent: TextView? = null
    private var inspectorCard: LinearLayout? = null
    private var tvInspectorHeader: TextView? = null
    private var inspectorChipsRow: LinearLayout? = null
    private var inspectorTableContainer: LinearLayout? = null
    private var consoleCard: LinearLayout? = null
    private var tvConsoleHeader: TextView? = null
    private var tvConsoleOutput: TextView? = null
    private var btnClearConsole: NeumorphicKeyView? = null
    private var moduleLogCard: LinearLayout? = null
    private var tvModuleLogOutput: TextView? = null
    private var btnModuleBackToEditor: NeumorphicKeyView? = null
    private var btnOutputPlot: NeumorphicKeyView? = null
    private var btnOutputBytecode: NeumorphicKeyView? = null
    private var btnExportModuleState: NeumorphicKeyView? = null
    private var btnClearModuleLog: NeumorphicKeyView? = null

    // --- Workspace State ---
    private var isModuleEditorSubTab: Boolean = true
    private val openModuleFiles = mutableListOf<String>()
    private var activeModuleFileName: String? = "physics_sim.math"
    private var moduleUnsavedBuffer: String = ""
    private val moduleLogs = mutableMapOf<String, String>()
    private val moduleConsoleLogs = mutableMapOf<String, String>()
    private val moduleVarsCache = mutableMapOf<String, String>()
    private val moduleErrorLines = mutableMapOf<String, Pair<Int, String>>()
    private val moduleInspectableItems = mutableMapOf<String, List<Triple<String, String, String>>>()

    // Cached file metadata (`<path>:<mtime>:<len>` -> Pair(lineCount, byteLength))
    private val fileMetaCache = mutableMapOf<String, Pair<Int, Long>>()

    private var pendingExportMathContent: String = ""
    private var pendingExportMathFileName: String = ""

    private val wobbleAnimators = mutableListOf<ValueAnimator>()
    private val springAnimators = mutableListOf<Pair<View, ValueAnimator>>()
    private var drawerAnimator: ValueAnimator? = null

    /** SAF Launcher to load/import an individual `.math` module file from device storage. */
    private val importMathFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportedMathFileUri(uri)
        }
    }

    /** SAF Launcher to export/save an individual `.math` module file or exported module state to device storage. */
    private val exportMathFileLauncher = registerForActivityResult(
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
                    "Module File Manager — opens the left-hand project drawer.\n" +
                        "Every .math file is a top-to-bottom executable [module] that can define " +
                        "pub/priv variables (:= / mut vs = / const), functions, operators, \$type structs, " +
                        "and top-level expressions, and import sibling modules via <[dep]."
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
                    "← Calc — returns to the scientific calculator. Active modules keep their " +
                        "public functions, constants, and variables linked in the engine."
                ) else activity.dismissButtonPopup()
            }
        }
        headerRow.addView(btnBackCalc)

        tvWorkspaceTitle = TextView(ctx).apply {
            text = ".math Module Studio"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(if (dark) "#E0E0E0" else "#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvWorkspaceTitle)
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

        applyTheme(dark)
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = false)
        updateModuleSubTabVisibility(animate = false)
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
                        marginEnd = dp(4f)
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
                ).apply { bottomMargin = dp(8f) }
            }

            val titleCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            tvDrawerTitle = TextView(ctx).apply {
                text = "📁 Module File Manager"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primaryText)
            }
            tvDrawerSubtitle = TextView(ctx).apply {
                text = "Executable .math Modules & Projects"
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

            // 2. Module Controls Block: [▶ Run All] | [↺ Reset Mod] | [📤 Export State]
            drawerModuleControlsBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8f) }

                tvDrawerControlsLabel = TextView(ctx).apply {
                    text = "MODULE EXECUTION & STATE CONTROLS"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(secondaryText)
                    setPadding(dp(2f), 0, dp(2f), dp(3f))
                }
                addView(tvDrawerControlsLabel)

                val modActionsRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                btnDrawerRunAll = makeFmBarBtn("▶ Run Active", true, 1.05f).apply {
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        runCurrentModuleFile(switchToLogAfterRun = false)
                    }
                }
                btnDrawerResetMod = makeFmBarBtn("↺ Reset Mod", false, 0.95f).apply {
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        resetActiveModuleState()
                    }
                }
                btnDrawerExportState = makeFmBarBtn("📤 Export State", true, 1.1f).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginEnd = 0
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        exportActiveModuleRuntimeState()
                    }
                }
                modActionsRow.addView(btnDrawerRunAll)
                modActionsRow.addView(btnDrawerResetMod)
                modActionsRow.addView(btnDrawerExportState)
                addView(modActionsRow)
            }
            addView(drawerModuleControlsBlock)

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

        val allModFiles = listModuleFiles(ctx)
        openModuleFiles.retainAll { name -> allModFiles.any { it.name == name } }
        tvDrawerTitle?.text = "📁 Module File Manager"
        tvDrawerSubtitle?.text = "Executable .math Modules • ${openModuleFiles.size} open / ${allModFiles.size} stored"
        tvDrawerFilesSectionLabel?.text = "PROJECT .MATH MODULES"

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
            val rawSrc = runCatching { file.readText() }.getOrDefault("")
            val modId = MathEngineManager.deriveModuleName(fname, rawSrc)
            val deps = MathEngineManager.extractModuleDependencies(rawSrc)
            val depsLabel = if (deps.isNotEmpty()) " • <[${deps.joinToString(", ")}]" else ""

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
                text = "[$modId]$depsLabel • $lineCount lines • $byteLen B"
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
    }

    // =========================================================================
    //  Main Window UI Builder (Maximized Editor & Output/Variables Panes)
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
                    hint = "# Top-to-bottom executable .math module ([mod], <[dep], pub/priv, := / mut vs = / const, functions, and expressions)..."
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
                    Triple(":=", " := ", 0),
                    Triple("=>", " => ", 0),
                    Triple("->", " -> ", 0),
                    Triple("::", "::", 0),
                    Triple("..<", "..<", 0),
                    Triple("...", "...", 0),
                    Triple("??", " ?? {\n    \n}", 2),
                    Triple("|>", " |> ", 0),
                    Triple("\$\"\"", "\$\"{}\"", 2),
                    Triple("\$struct", "\$struct Point(x: real, y: real)", 0),
                    Triple("\$enum", "\$enum State { Idle, Active(v: real) }", 0),
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
                            "▶ Run / Link — executes the active .math module top-to-bottom on its own " +
                                "(resolving any <[dep] sibling project modules and sandboxed File/JSON/CSV paths). " +
                                "Highlights the exact offending line in the editor if an error occurs."
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
                }

                btnBytecodeModule = NeumorphicKeyView(ctx).apply {
                    text = "⚙ BC"
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
                        val errInfo = modFile?.let { moduleErrorLines[it] }
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
                    text = "Tap '▶ Run / Link' to execute, '📈 Plot' for 2D charts, or '⚙ BC' to inspect VM bytecode."
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
                    text = "🔍 Structured Data Inspector ($map • JSON • CSV • $mat • $vec)"
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
                    text = "Evaluate a $map, JSON object/array, csv_to_maps(...), $mat, or $vec to inspect it as a formatted table."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(secondaryText)
                }
                inspectorTableContainer?.addView(tvInspPlaceholder)
                inspCard.addView(inspectorTableContainer)
                outStack.addView(inspCard)

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
                        val key = activeModuleFileName ?: "default"
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
                    )
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
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 0.95f).apply { marginEnd = dp(4f) }
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
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 0.95f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        showFrostedPlotterModal()
                    }
                }
                btnOutputBytecode = NeumorphicKeyView(ctx).apply {
                    text = "⚙ BC"
                    textSize = 11.2f
                    cornerRadiusDp = 13f
                    shadowPadDp = 2.8f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 0.85f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        showFrostedBytecodeDisasmModal()
                    }
                }
                btnExportModuleState = NeumorphicKeyView(ctx).apply {
                    text = "📤 Export"
                    textSize = 11.2f
                    cornerRadiusDp = 13f
                    shadowPadDp = 2.8f
                    isAccentKey = true
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.0f).apply { marginEnd = dp(4f) }
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        exportActiveModuleRuntimeState()
                    }
                }
                btnClearModuleLog = NeumorphicKeyView(ctx).apply {
                    text = "Clear"
                    textSize = 11.2f
                    cornerRadiusDp = 13f
                    shadowPadDp = 2.8f
                    isAccentKey = false
                    isDarkThemeMode = dark
                    layoutParams = LinearLayout.LayoutParams(dp(64f), dp(38f))
                    setOnClickListener {
                        springPulse(this, 0.07f)
                        val key = activeModuleFileName ?: "default"
                        val cleared = "=== Module Execution & Link Log ($key) ===\n(Log cleared)"
                        moduleLogs[key] = cleared
                        tvModuleLogOutput?.text = cleared
                        moduleLogCard?.let { springPulse(it, 0.04f) }
                    }
                }
                mLogActions.addView(btnModuleBackToEditor)
                mLogActions.addView(btnOutputPlot)
                mLogActions.addView(btnOutputBytecode)
                mLogActions.addView(btnExportModuleState)
                mLogActions.addView(btnClearModuleLog)
                addView(mLogActions)
            }
            addView(moduleLogPane)
        }
    }

    // =========================================================================
    //  Unified Project .math Module File System & Migration Helpers
    // =========================================================================

    private fun legacyMathDir(ctx: Context): File =
        File(ctx.filesDir, "math_programs").also { if (!it.exists()) it.mkdirs() }

    private fun legacySessionsRootDir(ctx: Context): File =
        File(ctx.filesDir, "math_sessions")

    private fun modulesDir(ctx: Context): File =
        File(ctx.filesDir, "math_modules").also { if (!it.exists()) it.mkdirs() }

    private fun listModuleFiles(ctx: Context): List<File> =
        modulesDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".math") }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun collectAllProjectModuleSources(ctx: Context, activeFileOverride: String?, activeCodeOverride: String?): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        listModuleFiles(ctx).forEach { file ->
            val src = if (file.name == activeFileOverride && activeCodeOverride != null) {
                activeCodeOverride
            } else {
                runCatching { file.readText() }.getOrDefault("")
            }
            map[file.name] = src
        }
        if (activeFileOverride != null && activeCodeOverride != null && !map.containsKey(activeFileOverride)) {
            map[activeFileOverride] = activeCodeOverride
        }
        return map
    }

    private fun ensureSampleAndMigratedModuleFiles(ctx: Context) {
        val legDir = legacyMathDir(ctx)
        val modDir = modulesDir(ctx)
        val sessRoot = legacySessionsRootDir(ctx)

        File(legDir, "trig_ext.math").takeIf { it.exists() }?.delete()
        File(modDir, "trig_ext.math").takeIf { it.exists() }?.delete()

        // Migrate any legacy files from math_programs/ or math_sessions/ into unified math_modules/
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

        val seedMarker = File(modDir, ".seeded_unified_modules_v4")
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
                "pub avg_temp := (records[0][\"temp_c\"] + records[1][\"temp_c\"] + records[2][\"temp_c\"]) / 3\n" +
                "records\n"
            )
            File(modDir, "custom_ops.math").writeText(
                "# Unified Executable Module: custom_ops.math\n" +
                "# Defines public & private constants, functions, and top-level expressions.\n\n" +
                "[custom_ops]\n" +
                "<[arith]\n" +
                "<[trig]\n\n" +
                "priv const DEG_SCALE = π / 180\n" +
                "priv deg_to_r(x: real) = x * DEG_SCALE\n\n" +
                "pub const G_STD = 9.80665\n" +
                "pub hypot3(a: real, b: real, c: real) = √(a^2 + b^2 + c^2)\n" +
                "pub kinetic_e(m: real, v: real) = 0.5 * m * v^2\n" +
                "pub deg_sin(x: real) = sin(deg_to_r(x))\n\n" +
                "# Top-level module expression (evaluated when module runs):\n" +
                "hypot3(3, 4, 12)\n"
            )
            File(modDir, "physics_sim.math").writeText(
                "# Multi-File Project Module: physics_sim.math\n" +
                "# Imports <[custom_ops] and runs top-to-bottom without needing a session.\n\n" +
                "[physics_sim]\n" +
                "<[custom_ops]\n" +
                "<[units]\n\n" +
                "pub v0 := 25.0\n" +
                "pub angle := 45.0\n" +
                "priv const g = G_STD\n\n" +
                "pub range_m := (v0^2 * deg_sin(2 * angle)) / g\n" +
                "pub max_h := (v0 * deg_sin(angle))^2 / (2 * g)\n" +
                "pub flight_t := (2 * v0 * deg_sin(angle)) / g\n\n" +
                "# Reusable module function:\n" +
                "pub height_at(t) = v0 * deg_sin(angle) * t - 0.5 * g * t^2\n" +
                "height_at(1.8)\n"
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
    //  Module Tabs, Editor & Top-to-Bottom Execution Operations
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
        val modFileName = activeModuleFileName
        if (modFileName != null && modFileName in openModuleFiles) {
            val file = File(modulesDir(ctx), modFileName)
            val code = if (file.exists()) file.readText() else ""
            val modId = MathEngineManager.deriveModuleName(modFileName, code)
            val deps = MathEngineManager.extractModuleDependencies(code)
            val depsSuffix = if (deps.isNotEmpty()) "  •  <[${deps.joinToString(", ")}]" else ""
            tvActiveModuleName?.text = "File: $modFileName  •  Module: [$modId]$depsSuffix"
            etModuleEditor?.setText(code)
            tvVarsHeader?.text = "Module [$modId] Variables & Constants (pub/priv • mut/const)"
            tvVarsContent?.text = moduleVarsCache[modId].orEmpty().ifBlank {
                "No variables bound yet in [$modId]. Tap '▶ Run / Link Module' in the Editor tab."
            }
            val savedLog = moduleLogs[modFileName]
                ?: "=== Module [$modId] Execution & Link Log ($modFileName) ===\nReady. Tap '▶ Run / Link' in the Editor tab to execute top-to-bottom."
            tvModuleLogOutput?.text = savedLog
            tvConsoleOutput?.text = moduleConsoleLogs[modFileName] ?: "(No print / println output yet)"
            val errPair = moduleErrorLines[modFileName]
            if (errPair != null) {
                etModuleEditor?.highlightErrorLine(errPair.first, errPair.second)
            } else {
                etModuleEditor?.clearErrorHighlight()
            }
            refreshStructuredInspector(modFileName)

            MathEngineManager.inspectModuleVariables(modId) { summary ->
                activity?.runOnUiThread {
                    moduleVarsCache[modId] = summary
                    val curFile = activeModuleFileName
                    if (curFile == modFileName) {
                        tvVarsContent?.text = summary.ifBlank {
                            "No variables bound yet in [$modId]. Tap '▶ Run / Link Module' in the Editor tab."
                        }
                    }
                }
            }
        } else {
            activeModuleFileName = null
            tvActiveModuleName?.text = "Active Module: None open (Tap ☰ File Manager)"
            etModuleEditor?.setText(moduleUnsavedBuffer)
            tvVarsHeader?.text = "Module Variables & Constants"
            tvVarsContent?.text = "Open or create a .math module via ☰ File Manager."
            tvModuleLogOutput?.text = "=== Module Execution & Link Log ===\nOpen or create a .math module via ☰ File Manager."
        }
        if (animate) {
            etModuleEditor?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            moduleQuickStatusCard?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            varsCard?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            moduleLogCard?.let { springSlideIn(it, fromDirection = fromDirection, distance = distance) }
            tvActiveModuleName?.let { springPulse(it, 0.05f) }
        }
    }

    private fun saveCurrentModuleEditorState(showToast: Boolean): Boolean {
        val ctx = context ?: return false
        val activity = activity as? MainActivity ?: return false
        val code = etModuleEditor?.text?.toString() ?: return false
        val modFile = activeModuleFileName

        if (modFile != null && modFile in openModuleFiles) {
            val file = File(modulesDir(ctx), modFile)
            file.writeText(code)
            getCachedFileMeta(file)
            val modId = MathEngineManager.deriveModuleName(modFile, code)
            val deps = MathEngineManager.extractModuleDependencies(code)
            val depsSuffix = if (deps.isNotEmpty()) "  •  <[${deps.joinToString(", ")}]" else ""
            tvActiveModuleName?.text = "File: $modFile  •  Module: [$modId]$depsSuffix"
            if (showToast) {
                tvModuleQuickStatus?.text = "💾 Saved module '$modFile' ([$modId])."
                activity.showButtonToast("Saved $modFile")
                tvActiveModuleName?.let { springPulse(it, 0.05f) }
            }
            return true
        } else {
            moduleUnsavedBuffer = code
            return false
        }
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
        isModuleEditorSubTab = true
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = true, fromDirection = fromDir, distance = dist)
        updateModuleSubTabVisibility(animate = false)
        refreshFileManagerDrawer()
        tvModuleQuickStatus?.text = "📂 Opened module '$fileName'."
        activity.showButtonToast("Opened $fileName")
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
        tvModuleQuickStatus?.text = "✖ Closed '$fileName' (still stored in ☰ File Manager)."
        activity.showButtonToast("Closed $fileName")
    }

    private fun promptRenameOrCopyModuleFileDialog(targetName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        saveCurrentModuleEditorState(showToast = false)
        val baseStem = targetName.removeSuffix(".math")
        showFrostedFileNamingModal(
            badgeIcon = "✏️",
            title = "Rename or Copy Module",
            subtitle = "Module '$targetName' • Executable .math Module",
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
        val rawSrc = runCatching { file.readText() }.getOrDefault("")
        val modId = MathEngineManager.deriveModuleName(fileName, rawSrc)
        showFrostedConfirmModal(
            badgeIcon = "🗑",
            title = "Delete Module '$fileName'?",
            subtitle = "Module [$modId] • Permanent Action",
            message = "Delete '$fileName' from project storage and reset module [$modId] in the engine?",
            confirmLabel = "Delete Module",
            onConfirm = {
                val prevIdx = openModuleFiles.indexOf(fileName)
                file.delete()
                moduleLogs.remove(fileName)
                moduleVarsCache.remove(modId)
                MathEngineManager.resetModule(modId) { }
                openModuleFiles.remove(fileName)
                if (activeModuleFileName == fileName) {
                    activeModuleFileName = openModuleFiles.firstOrNull()
                }
                val newIdx = openModuleFiles.indexOf(activeModuleFileName)
                val fromDir = if (newIdx >= 0 && prevIdx >= 0 && newIdx > prevIdx) 1 else -1
                refreshModuleChips()
                loadModuleStateIntoEditor(animate = true, fromDirection = fromDir)
                refreshFileManagerDrawer()
                tvModuleQuickStatus?.text = "🗑 Deleted module '$fileName' ([$modId])."
                activity.showButtonToast("Deleted $fileName")
            }
        )
    }

    /** Executes the active `.math` module file top-to-bottom and updates its variables & log. */
    private fun runCurrentModuleFile(switchToLogAfterRun: Boolean) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val code = etModuleEditor?.text?.toString() ?: ""

        var modFile = activeModuleFileName
        if (modFile == null || modFile !in openModuleFiles) {
            modFile = "workspace_mod.math"
            File(modulesDir(ctx), modFile).writeText(code)
            if (modFile !in openModuleFiles) openModuleFiles.add(modFile)
            activeModuleFileName = modFile
            refreshModuleChips()
            refreshFileManagerDrawer()
        } else {
            saveCurrentModuleEditorState(showToast = false)
        }

        val targetFile = modFile
        val projectFiles = collectAllProjectModuleSources(ctx, targetFile, code)

        MathEngineManager.runModuleFile(
            fileName = targetFile,
            mathSource = code,
            projectSourcesByFileName = projectFiles,
            resetBeforeRun = true
        ) { modId, results, varsSummary ->
            activity.runOnUiThread {
                val sb = StringBuilder()
                val consoleSb = StringBuilder()
                val inspectables = mutableListOf<Triple<String, String, String>>()
                sb.append("=== Executed $targetFile (Module [$modId]) Top-to-Bottom ===\n")
                var lastSummaryLine = "✅ Linked [$modId] (0 statements)"
                var hadError = false
                var firstErrorLine: Int? = null
                var firstErrorMsg: String? = null

                if (results.isEmpty()) {
                    sb.append("(No executable statements or declarations found)\n")
                } else {
                    results.forEachIndexed { idx, (stmt, outcome) ->
                        if (outcome.stdout.isNotEmpty()) {
                            consoleSb.append(outcome.stdout)
                            if (!outcome.stdout.endsWith("\n")) consoleSb.append("\n")
                        }
                        val oneLineStmt = stmt.replace(Regex("\\s+"), " ").trim()
                        val lineBadge = "L${outcome.sourceLine}"
                        if (outcome.ok) {
                            if (outcome.type == "void") {
                                sb.append("[${idx + 1} • $lineBadge] $oneLineStmt  ⟹  ✓ declared\n")
                                lastSummaryLine = "✅ [$modId] $oneLineStmt ⟹ declared"
                            } else {
                                val u = if (outcome.unit != "none") " [${outcome.unit}]" else ""
                                sb.append("[${idx + 1} • $lineBadge] $oneLineStmt  ⟹  ${outcome.value}$u (${outcome.type})\n")
                                lastSummaryLine = "✅ [$modId] $oneLineStmt ⟹ ${outcome.value}$u"
                                if (outcome.type == "map" || outcome.type == "mat" || outcome.type == "vec") {
                                    val label = oneLineStmt.substringBefore(":=").substringBefore("=").trim().take(22)
                                    inspectables.add(Triple(label.ifEmpty { "Result #${idx + 1}" }, outcome.type, outcome.value))
                                }
                            }
                        } else {
                            hadError = true
                            firstErrorLine = outcome.sourceLine
                            firstErrorMsg = outcome.error ?: "Evaluation error"
                            sb.append("[${idx + 1} • $lineBadge] $oneLineStmt  ⟹  ⚠ ${outcome.error}\n")
                            lastSummaryLine = "⚠ [$modId • Line ${outcome.sourceLine}] ${outcome.error}"
                        }
                    }
                }

                // Also extract any bound $map, $mat, or $vec variables from varsSummary for the Table Inspector
                for (vLine in varsSummary.lineSequence()) {
                    val eqIdx = vLine.indexOf(" = ")
                    val colonIdx = vLine.indexOf(" : ")
                    if (colonIdx > 0 && eqIdx > colonIdx) {
                        val lhsTokens = vLine.substring(0, colonIdx).trim().split(Regex("\\s+"))
                        val vName = lhsTokens.lastOrNull().orEmpty()
                        val vType = vLine.substring(colonIdx + 3, eqIdx).substringBefore(" [").trim()
                        val vVal = vLine.substring(eqIdx + 3).trim()
                        if ((vType == "map" || vType == "mat" || vType == "vec") &&
                            inspectables.none { it.first == vName }) {
                            inspectables.add(Triple(vName, vType, vVal))
                        }
                    }
                }

                if (varsSummary.isNotBlank()) {
                    sb.append("\n--- Module [$modId] Bound Variables & Constants ---\n")
                    sb.append(varsSummary)
                }

                val fullLog = sb.toString().trimEnd()
                val consoleText = consoleSb.toString().trimEnd().ifEmpty { "(No print / println output emitted)" }
                moduleLogs[targetFile] = fullLog
                moduleConsoleLogs[targetFile] = consoleText
                moduleVarsCache[modId] = varsSummary
                moduleInspectableItems[targetFile] = inspectables

                if (hadError && firstErrorLine != null) {
                    moduleErrorLines[targetFile] = firstErrorLine!! to (firstErrorMsg ?: "Error")
                    etModuleEditor?.highlightErrorLine(firstErrorLine, firstErrorMsg)
                } else {
                    moduleErrorLines.remove(targetFile)
                    etModuleEditor?.clearErrorHighlight()
                }

                tvVarsHeader?.text = "Module [$modId] Variables & Constants (pub/priv • mut/const)"
                tvVarsContent?.text = varsSummary.ifBlank {
                    "No variables bound in [$modId]."
                }
                tvConsoleOutput?.text = consoleText
                tvModuleLogOutput?.text = fullLog
                refreshStructuredInspector(targetFile)

                tvModuleQuickStatus?.text = if (hadError && firstErrorLine != null) {
                    "$lastSummaryLine  (Line $firstErrorLine highlighted in Editor)"
                } else {
                    "$lastSummaryLine  (Tap for 📋 Output & Vars)"
                }
                moduleQuickStatusCard?.let { springPulse(it, 0.045f) }
                varsCard?.let { springPulse(it, 0.045f) }
                consoleCard?.let { springPulse(it, 0.045f) }
                inspectorCard?.let { springPulse(it, 0.045f) }
                moduleLogCard?.let { springPulse(it, 0.045f) }

                if (switchToLogAfterRun) {
                    isModuleEditorSubTab = false
                    updateModuleSubTabVisibility(animate = true)
                }
                activity.showButtonToast(
                    if (!hadError) "Executed [$modId] ($targetFile)" else "Error on line ${firstErrorLine ?: 1} in [$modId]"
                )
            }
        }
    }

    private fun resetActiveModuleState() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val modFile = activeModuleFileName ?: return
        val code = etModuleEditor?.text?.toString()
            ?: runCatching { File(modulesDir(ctx), modFile).readText() }.getOrDefault("")
        val modId = MathEngineManager.deriveModuleName(modFile, code)

        MathEngineManager.resetModule(modId) { ok ->
            activity.runOnUiThread {
                moduleVarsCache[modId] = ""
                val cleared = "=== Module [$modId] Execution & Link Log ($modFile) ===\n(Module [$modId] state & variables reset)"
                moduleLogs[modFile] = cleared
                tvVarsContent?.text = "No variables bound in [$modId] (module reset)."
                tvModuleLogOutput?.text = cleared
                tvModuleQuickStatus?.text = "↺ Reset module [$modId]. Tap '▶ Run / Link Module' to re-execute $modFile."
                varsCard?.let { springPulse(it, 0.05f) }
                moduleLogCard?.let { springPulse(it, 0.04f) }
                activity.showButtonToast(if (ok) "Reset module [$modId]" else "Reset [$modId]")
            }
        }
    }

    private fun exportActiveModuleRuntimeState() {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val modFile = activeModuleFileName
        val code = etModuleEditor?.text?.toString()
            ?: modFile?.let { runCatching { File(modulesDir(ctx), it).readText() }.getOrNull() }
            ?: ""
        val modId = MathEngineManager.deriveModuleName(modFile, code)

        MathEngineManager.exportModuleSource(modId) { exportedSrc ->
            activity.runOnUiThread {
                val payload = exportedSrc.ifBlank { code }
                if (payload.isBlank()) {
                    activity.showButtonToast("Module [$modId] is empty — run or write code first")
                    return@runOnUiThread
                }
                pendingExportMathContent = payload
                pendingExportMathFileName = "${modId}_state.math"
                try {
                    exportMathFileLauncher.launch(pendingExportMathFileName)
                } catch (t: Throwable) {
                    activity.showButtonToast("Could not open save picker: ${t.message}")
                }
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
            subtitle = "Top-to-Bottom Executable [module] • Auto-appends .math",
            description = "Creates a top-to-bottom executable .math module that can declare <[dep] dependencies, pub/priv & mut/const variables, functions, types, and expressions.",
            initialText = "",
            hintText = defaultName,
            suggestions = listOf(
                defaultName,
                "custom_ops.math",
                "physics_lab.math",
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
                        "# Executable Module: $name\n" +
                        "[$modIdentifier]\n" +
                        "<[arith]\n\n" +
                        "priv const SCALE = 2\n" +
                        "pub base_x := 10\n" +
                        "pub square_sum(a: real, b: real) = (a^2 + b^2) * SCALE\n\n" +
                        "square_sum(base_x, 5)\n"
                    )
                }
                if (name !in openModuleFiles) openModuleFiles.add(name)
                val newIdx = openModuleFiles.indexOf(name)
                val fromDir = if (prevIdx < 0 || newIdx >= prevIdx) 1 else -1
                val dist = if (prevIdx < 0) 1 else kotlin.math.abs(newIdx - prevIdx).coerceAtLeast(1)
                activeModuleFileName = name
                isModuleEditorSubTab = true
                refreshModuleChips()
                loadModuleStateIntoEditor(animate = true, fromDirection = fromDir, distance = dist)
                updateModuleSubTabVisibility(animate = false)
                refreshFileManagerDrawer()
                activity.showButtonToast("Created module $name")
            }
        )
    }

    // =========================================================================
    //  Per-Module .math File Import In / Export Out
    // =========================================================================

    private fun launchImportMathFileForModules() {
        val activity = activity as? MainActivity ?: return
        saveCurrentModuleEditorState(showToast = false)
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

            val file = File(modulesDir(ctx), displayName)
            file.writeText(content)
            getCachedFileMeta(file)
            if (displayName !in openModuleFiles) openModuleFiles.add(displayName)
            activeModuleFileName = displayName
            isModuleEditorSubTab = true
            refreshModuleChips()
            loadModuleStateIntoEditor(animate = true, fromDirection = 1)
            refreshFileManagerDrawer()
            updateModuleSubTabVisibility(animate = false)
            tvModuleQuickStatus?.text = "📥 Imported module '$displayName' (${content.length} chars)."
            activity.showButtonToast("Imported $displayName")
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
            tvModuleQuickStatus?.text = "📤 Exported '$fileName' (${code.length} chars)."
            activity.showButtonToast("Exported $fileName")
        } catch (t: Throwable) {
            activity.showButtonToast("Export failed: ${t.message}")
        }
    }

    // =========================================================================
    //  Sub-Tab Visibility + Theme Synchronization
    // =========================================================================

    private fun updateModuleSubTabVisibility(animate: Boolean) {
        btnModuleSubEditor?.isAccentKey = isModuleEditorSubTab
        btnModuleSubLog?.isAccentKey = !isModuleEditorSubTab
        if (isModuleEditorSubTab) {
            moduleLogPane?.visibility = View.GONE
            moduleEditorPane?.visibility = View.VISIBLE
            if (animate) {
                btnModuleSubEditor?.let { springPulse(it, 0.06f) }
                moduleEditorPane?.let { springSlideIn(it, fromDirection = -1) }
            }
        } else {
            moduleEditorPane?.visibility = View.GONE
            moduleLogPane?.visibility = View.VISIBLE
            if (animate) {
                btnModuleSubLog?.let { springPulse(it, 0.06f) }
                moduleLogPane?.let { springSlideIn(it, fromDirection = 1) }
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
        tvDrawerControlsLabel?.setTextColor(secondaryText)
        tvDrawerFilesSectionLabel?.setTextColor(secondaryText)
        tvActiveModuleName?.setTextColor(accentText)

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
        styleCard(varsCard, cardBg)
        styleCard(inspectorCard, cardBg)
        styleCard(consoleCard, cardBg)
        styleCard(moduleLogCard, cardBg)

        etModuleEditor?.isDarkThemeMode = dark
        etModuleEditor?.setTextColor(primaryText)
        etModuleEditor?.setHintTextColor(secondaryText)
        tvModuleQuickStatus?.setTextColor(secondaryText)
        tvVarsHeader?.setTextColor(accentText)
        tvVarsContent?.setTextColor(primaryText)
        tvInspectorHeader?.setTextColor(accentText)
        tvConsoleHeader?.setTextColor(accentText)
        tvConsoleOutput?.setTextColor(primaryText)
        tvModuleLogOutput?.setTextColor(primaryText)

        symbolChipViews.forEach { it.isDarkThemeMode = dark }
        listOf(
            btnHamburgerMenu, btnBackCalc,
            btnDrawerCloseIcon, btnDrawerRunAll, btnDrawerResetMod, btnDrawerExportState,
            btnDrawerCreateNewFile, btnDrawerImportFile,
            btnModuleSubEditor, btnModuleSubLog,
            btnCompileModule, btnPlotModule, btnBytecodeModule, btnResetActiveModule, btnModuleViewLog,
            btnClearConsole,
            btnModuleBackToEditor, btnOutputPlot, btnOutputBytecode, btnExportModuleState, btnClearModuleLog
        ).forEach { it?.isDarkThemeMode = dark }
        activeModuleFileName?.let { refreshStructuredInspector(it) }

        refreshModuleChips()
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
            topHeaderBar, moduleSubTabBar, etModuleEditor,
            moduleQuickStatusCard, varsCard, moduleLogCard
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

    // =========================================================================
    //  Interactive $map / JSON / CSV / 2D $mat / $vec Table & Tree Inspector
    // =========================================================================

    private var selectedInspectorIdx: Int = 0

    private fun refreshStructuredInspector(modFileName: String) {
        val ctx = context ?: return
        val activity = activity as? MainActivity ?: return
        val dark = activity.isDarkMode
        val chipsContainer = inspectorChipsRow ?: return
        val tableContainer = inspectorTableContainer ?: return
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }
        val secondaryText = Color.parseColor(if (dark) "#8E8E8E" else "#666666")

        chipsContainer.removeAllViews()
        tableContainer.removeAllViews()

        val items = moduleInspectableItems[modFileName].orEmpty()
        if (items.isEmpty()) {
            val tvEmpty = TextView(ctx).apply {
                text = "Run a module with a \$map, JSON object, csv_to_maps(...), \$mat, or \$vec to inspect it as a formatted table."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
                typeface = Typeface.MONOSPACE
                setTextColor(secondaryText)
            }
            tableContainer.addView(tvEmpty)
            return
        }

        if (selectedInspectorIdx >= items.size) selectedInspectorIdx = 0

        items.forEachIndexed { idx, (label, vType, _) ->
            val isSel = (idx == selectedInspectorIdx)
            val chipLabel = "$label ($vType)"
            val chipW = (chipLabel.length * 7.2f + 24f).coerceAtLeast(78f)
            val chip = NeumorphicKeyView(ctx).apply {
                text = chipLabel
                textSize = 10.2f
                cornerRadiusDp = 9.5f
                shadowPadDp = 2.0f
                isAccentKey = isSel
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(chipW), dp(27f)).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    selectedInspectorIdx = idx
                    refreshStructuredInspector(modFileName)
                }
            }
            chipsContainer.addView(chip)
        }

        val (_, selType, selVal) = items[selectedInspectorIdx]
        tableContainer.addView(buildStructuredTableView(ctx, dark, selType, selVal))
    }

    private fun parseMapEntriesFromFormatted(raw: String): List<Pair<String, String>> {
        val trimmed = raw.trim()
        val inner = when {
            trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed.substring(1, trimmed.length - 1).trim()
            trimmed.startsWith("map(") && trimmed.endsWith(")") -> trimmed.substring(4, trimmed.length - 1).trim()
            trimmed.startsWith("dict(") && trimmed.endsWith(")") -> trimmed.substring(5, trimmed.length - 1).trim()
            else -> return emptyList()
        }
        if (inner.isEmpty()) return emptyList()
        val pairs = mutableListOf<Pair<String, String>>()
        val tokens = splitTopLevelCommas(inner)
        for (tok in tokens) {
            val colonIdx = findTopLevelColon(tok)
            if (colonIdx > 0) {
                val k = tok.substring(0, colonIdx).trim().removeSurrounding("\"")
                val v = tok.substring(colonIdx + 1).trim()
                pairs.add(k to v)
            }
        }
        return pairs
    }

    private fun splitTopLevelCommas(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && inQuotes && i + 1 < s.length) {
                cur.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                inQuotes = !inQuotes
                cur.append(c)
            } else if (!inQuotes) {
                when (c) {
                    '{', '[', '(' -> { depth++; cur.append(c) }
                    '}', ']', ')' -> { if (depth > 0) depth--; cur.append(c) }
                    ',' -> if (depth == 0) {
                        out.add(cur.toString().trim())
                        cur.setLength(0)
                    } else {
                        cur.append(c)
                    }
                    else -> cur.append(c)
                }
            } else {
                cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    private fun findTopLevelColon(s: String): Int {
        var depth = 0
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && inQuotes && i + 1 < s.length) {
                i += 2
                continue
            }
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes) {
                when (c) {
                    '{', '[', '(' -> depth++
                    '}', ']', ')' -> if (depth > 0) depth--
                    ':' -> if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun buildStructuredTableView(
        ctx: Context,
        dark: Boolean,
        vType: String,
        rawValue: String
    ): View {
        val density = resources.displayMetrics.density
        val dp = { v: Float -> (v * density).toInt() }
        val hdrBg = Color.parseColor(if (dark) "#1D3638" else "#FCEAE6")
        val rowEvenBg = Color.parseColor(if (dark) "#1F1F1F" else "#FFFFFF")
        val rowOddBg = Color.parseColor(if (dark) "#262626" else "#F4F5F7")
        val borderCol = Color.parseColor(if (dark) "#3A3A3A" else "#D5D5D5")
        val primaryText = Color.parseColor(if (dark) "#E6E6E6" else "#2A2A2A")
        val accentText = Color.parseColor(if (dark) "#26C6DA" else "#D85A4A")

        fun makeTable(headers: List<String>, rows: List<List<String>>): View {
            val hScroll = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val tableCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * density
                    setStroke(1, borderCol)
                }
            }

            val colCount = headers.size
            val colChars = IntArray(colCount) { c -> headers[c].length.coerceAtLeast(6) }
            for (r in rows) {
                for (c in 0 until minOf(colCount, r.size)) {
                    colChars[c] = maxOf(colChars[c], r[c].length.coerceAtMost(28))
                }
            }

            fun makeRowView(cells: List<String>, isHeader: Boolean, rowIdx: Int): LinearLayout {
                return LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(
                        when {
                            isHeader -> hdrBg
                            rowIdx % 2 == 0 -> rowEvenBg
                            else -> rowOddBg
                        }
                    )
                    for (c in 0 until colCount) {
                        val cellText = cells.getOrNull(c).orEmpty()
                        val cellW = dp((colChars[c] * 7.8f + 20f).coerceAtLeast(64f))
                        val tv = TextView(ctx).apply {
                            text = cellText
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isHeader) 11.2f else 11f)
                            typeface = if (isHeader) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
                            setTextColor(if (isHeader) accentText else primaryText)
                            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
                            layoutParams = LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        addView(tv)
                    }
                }
            }

            tableCol.addView(makeRowView(headers, isHeader = true, rowIdx = -1))
            rows.take(50).forEachIndexed { idx, r ->
                tableCol.addView(makeRowView(r, isHeader = false, rowIdx = idx))
            }
            hScroll.addView(tableCol)
            return hScroll
        }

        if (vType == "map") {
            val entries = parseMapEntriesFromFormatted(rawValue)
            if (entries.isNotEmpty()) {
                return makeTable(
                    listOf("Key", "Value"),
                    entries.map { (k, v) -> listOf(k, v) }
                )
            }
        }

        val trimmed = rawValue.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            val inner = trimmed.substring(1, trimmed.length - 1).trim()
            if (inner.contains(";")) {
                val rawRows = inner.split(";").map { r ->
                    r.trim().removePrefix("[").removeSuffix("]").split(",").map { it.trim() }
                }
                val cols = rawRows.maxOfOrNull { it.size } ?: 1
                val headers = listOf("Row") + (0 until cols).map { "Col $it" }
                val rows = rawRows.mapIndexed { rIdx, rCells -> listOf("[$rIdx]") + rCells }
                return makeTable(headers, rows)
            }
            val topElems = splitTopLevelCommas(inner)
            if (topElems.isNotEmpty() && topElems.first().trim().startsWith("{")) {
                val parsedMaps = topElems.map { parseMapEntriesFromFormatted(it) }.filter { it.isNotEmpty() }
                if (parsedMaps.isNotEmpty()) {
                    val allKeys = LinkedHashSet<String>()
                    parsedMaps.forEach { m -> m.forEach { (k, _) -> allKeys.add(k) } }
                    val keyList = allKeys.toList()
                    val headers = listOf("#") + keyList
                    val rows = parsedMaps.mapIndexed { idx, m ->
                        val lookup = m.toMap()
                        listOf(idx.toString()) + keyList.map { k -> lookup[k] ?: "" }
                    }
                    return makeTable(headers, rows)
                }
            }
            if (topElems.isNotEmpty() && topElems.first().trim().startsWith("[")) {
                val rawRows = topElems.map { r ->
                    val ri = r.trim().removePrefix("[").removeSuffix("]")
                    splitTopLevelCommas(ri)
                }
                val cols = rawRows.maxOfOrNull { it.size } ?: 1
                val headers = listOf("Row") + (0 until cols).map { "Col $it" }
                val rows = rawRows.mapIndexed { rIdx, rCells -> listOf("[$rIdx]") + rCells }
                return makeTable(headers, rows)
            }
            if (topElems.isNotEmpty()) {
                val headers = listOf("Index", "Value")
                val rows = topElems.mapIndexed { idx, v -> listOf("[$idx]", v) }
                return makeTable(headers, rows)
            }
        }

        return TextView(ctx).apply {
            text = rawValue
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.2f)
            typeface = Typeface.MONOSPACE
            setTextColor(primaryText)
        }
    }

    // =========================================================================
    //  Interactive 2D Function & Vector Plotter Modal (`MathPlotView`)
    // =========================================================================

    private fun extractModuleFunctionAndVectorCandidates(code: String): List<String> {
        val out = LinkedHashSet<String>()
        val fnDeclRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\([^()]*\\)\\s*(?:->[^=]+)?=")
        val varDeclRegex = Regex("(?m)^\\s*(?:(?:pub|priv|const|mut)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::[^=]+)?(?::=|=)(?![=>])")
        for (m in fnDeclRegex.findAll(code)) {
            val name = m.groupValues[1]
            if (name !in setOf("if", "while", "switch", "for", "catch")) out.add(name)
        }
        for (m in varDeclRegex.findAll(code)) {
            val name = m.groupValues[1]
            out.add(name)
        }
        out.add("sin(x) * cos(2*x)")
        return out.take(10).toList()
    }

    private fun showFrostedPlotterModal(initialExpr: String? = null) {
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

        val cardBg = Color.parseColor(if (dark) "#2A2D32" else "#F5F7FA")
        val inputBg = Color.parseColor(if (dark) "#1C1E22" else "#FFFFFF")
        val strokeColor = Color.parseColor(if (dark) "#444850" else "#D0D7DE")
        val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#24292F")
        val secondaryText = Color.parseColor(if (dark) "#9BA4B0" else "#57606A")
        val accentColor = Color.parseColor(if (dark) "#26C6DA" else "#E58A7B")
        val scrimColor = Color.parseColor(if (dark) "#96101216" else "#88E6E6E6")

        val code = etModuleEditor?.text?.toString().orEmpty()
        val modFile = activeModuleFileName
        val modId = MathEngineManager.deriveModuleName(modFile, code)
        val candidates = extractModuleFunctionAndVectorCandidates(code)
        val defaultTarget = initialExpr ?: candidates.firstOrNull() ?: "sin(x)"

        val frostBmp = Frost.frozenBackdrop(outerFrame, downscale = 6, radius = 8)
        activeModalBlurBitmap = frostBmp
        Frost.tryLiveBlur(rootLayout, 18f * density)

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
            overlay.addView(ImageView(ctx).apply {
                setImageBitmap(frostBmp)
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.55f else 0.94f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
        overlay.addView(View(ctx).apply {
            setBackgroundColor(scrimColor)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        val availWidth = if (outerFrame.width > 0) outerFrame.width else resources.displayMetrics.widthPixels
        val cardWidth = minOf(availWidth - dp(20f), dp(410f)).coerceAtLeast(dp(280f))

        val dialogCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                cardWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            elevation = dp(32f).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(cardBg)
                setStroke(dp(1.2f).coerceAtLeast(1), strokeColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        val hdrRow = LinearLayout(ctx).apply {
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
        titleCol.addView(TextView(ctx).apply {
            text = "📈 Interactive 2D Plotter"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
        })
        titleCol.addView(TextView(ctx).apply {
            text = "Module [$modId] • Pinch to zoom, drag to pan, tap curve for roots/extrema"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(accentColor)
        })
        hdrRow.addView(titleCol)
        hdrRow.addView(NeumorphicKeyView(ctx).apply {
            text = "✕"
            textSize = 13f
            cornerRadiusDp = 14f
            shadowPadDp = 2.5f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f))
            setOnClickListener { dismissWorkspaceModalDialog() }
        })
        dialogCard.addView(hdrRow)

        val controlsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }

        fun styleEditBox(et: EditText) {
            et.setSingleLine(true)
            et.typeface = Typeface.MONOSPACE
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.2f)
            et.setTextColor(primaryText)
            et.setHintTextColor(secondaryText)
            et.setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            et.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(inputBg)
                setStroke(1, strokeColor)
            }
        }

        val etExpr = EditText(ctx).apply {
            setText(defaultTarget)
            hint = "f(x) or vec"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f).apply {
                marginEnd = dp(4f)
            }
            styleEditBox(this)
        }
        val etXMin = EditText(ctx).apply {
            setText("-5")
            hint = "xMin"
            layoutParams = LinearLayout.LayoutParams(dp(52f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4f)
            }
            styleEditBox(this)
        }
        val etXMax = EditText(ctx).apply {
            setText("5")
            hint = "xMax"
            layoutParams = LinearLayout.LayoutParams(dp(52f), LinearLayout.LayoutParams.WRAP_CONTENT)
            styleEditBox(this)
        }
        controlsRow.addView(etExpr)
        controlsRow.addView(etXMin)
        controlsRow.addView(etXMax)
        dialogCard.addView(controlsRow)

        val candScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30f)
            ).apply { bottomMargin = dp(6f) }
        }
        val candRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candScroll.addView(candRow)
        dialogCard.addView(candScroll)

        val plotView = MathPlotView(ctx).apply {
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(230f)
            ).apply { bottomMargin = dp(6f) }
        }
        dialogCard.addView(plotView)

        val tvReadout = TextView(ctx).apply {
            text = "Tap or drag on the curve to inspect (x, y), roots, and local extrema."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setTextColor(secondaryText)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8f) }
        }
        plotView.onInspectPointChanged = { ix, iy, tag ->
            val badge = tag?.let { "[$it] " } ?: ""
            tvReadout.text = String.format("📍 %sx = %.5g,  y = %.5g", badge, ix, iy)
        }
        dialogCard.addView(tvReadout)

        fun triggerPlot() {
            val target = etExpr.text?.toString().orEmpty().trim()
            val xMin = etXMin.text?.toString()?.toDoubleOrNull() ?: -5.0
            val xMax = etXMax.text?.toString()?.toDoubleOrNull() ?: 5.0
            tvReadout.text = "⏳ Sampling '$target' in [$modId]..."
            MathEngineManager.samplePlotPoints(modId, target, xMin, xMax, 180) { ok, label, pts, err ->
                activity.runOnUiThread {
                    if (ok && pts.isNotEmpty()) {
                        plotView.setSeriesData(label, pts)
                        tvReadout.text = "✅ Plotted $label (${pts.size} pts). Tap curve to inspect."
                    } else {
                        plotView.setSeriesData(label, emptyList())
                        tvReadout.text = "⚠ ${err ?: "Could not plot '$target' (run module first if it is a module symbol)"}"
                    }
                }
            }
        }

        candidates.forEach { cand ->
            val w = (cand.length * 7.2f + 22f).coerceAtLeast(62f)
            candRow.addView(NeumorphicKeyView(ctx).apply {
                text = cand
                textSize = 10.2f
                cornerRadiusDp = 9f
                shadowPadDp = 2f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(26f)).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    etExpr.setText(cand)
                    triggerPlot()
                }
            })
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnRow.addView(NeumorphicKeyView(ctx).apply {
            text = "📈 Plot Curve"
            textSize = 12f
            cornerRadiusDp = 13f
            shadowPadDp = 2.8f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.2f).apply { marginEnd = dp(5f) }
            setOnClickListener {
                springPulse(this, 0.06f)
                triggerPlot()
            }
        })
        btnRow.addView(NeumorphicKeyView(ctx).apply {
            text = "Reset Zoom"
            textSize = 11.5f
            cornerRadiusDp = 13f
            shadowPadDp = 2.8f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.0f).apply { marginEnd = dp(5f) }
            setOnClickListener {
                springPulse(this, 0.06f)
                plotView.resetViewport()
            }
        })
        btnRow.addView(NeumorphicKeyView(ctx).apply {
            text = "Close"
            textSize = 11.5f
            cornerRadiusDp = 13f
            shadowPadDp = 2.8f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(74f), dp(38f))
            setOnClickListener { dismissWorkspaceModalDialog() }
        })
        dialogCard.addView(btnRow)

        overlay.addView(dialogCard)
        outerFrame.addView(overlay)
        activeModalOverlay = overlay
        isModalDialogOpen = true
        triggerPlot()
    }

    // =========================================================================
    //  Stack Bytecode VM Disassembler Modal (`bc_disasm`)
    // =========================================================================

    private fun showFrostedBytecodeDisasmModal(initialFn: String? = null) {
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

        val cardBg = Color.parseColor(if (dark) "#2A2D32" else "#F5F7FA")
        val inputBg = Color.parseColor(if (dark) "#181A1F" else "#FFFFFF")
        val strokeColor = Color.parseColor(if (dark) "#444850" else "#D0D7DE")
        val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#24292F")
        val secondaryText = Color.parseColor(if (dark) "#9BA4B0" else "#57606A")
        val accentColor = Color.parseColor(if (dark) "#26C6DA" else "#E58A7B")
        val scrimColor = Color.parseColor(if (dark) "#96101216" else "#88E6E6E6")

        val code = etModuleEditor?.text?.toString().orEmpty()
        val modFile = activeModuleFileName
        val modId = MathEngineManager.deriveModuleName(modFile, code)

        val fnRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*([a-zA-Z_][a-zA-Z0-9_]*(?:::[a-zA-Z_][a-zA-Z0-9_]*)?)\\s*\\([^()]*\\)\\s*(?:->[^=]+)?=")
        val fnCandidates = LinkedHashSet<String>()
        for (m in fnRegex.findAll(code)) {
            val fnName = m.groupValues[1].substringAfter("::")
            if (fnName !in setOf("if", "while", "switch", "for", "catch")) {
                fnCandidates.add(fnName)
            }
        }
        fnCandidates.add("x => x^2 + 2*x + 1")
        val defaultTarget = initialFn ?: fnCandidates.first()

        val frostBmp = Frost.frozenBackdrop(outerFrame, downscale = 6, radius = 8)
        activeModalBlurBitmap = frostBmp
        Frost.tryLiveBlur(rootLayout, 18f * density)

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
            overlay.addView(ImageView(ctx).apply {
                setImageBitmap(frostBmp)
                scaleType = ImageView.ScaleType.FIT_XY
                alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.55f else 0.94f
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
        overlay.addView(View(ctx).apply {
            setBackgroundColor(scrimColor)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })

        val availWidth = if (outerFrame.width > 0) outerFrame.width else resources.displayMetrics.widthPixels
        val cardWidth = minOf(availWidth - dp(20f), dp(400f)).coerceAtLeast(dp(276f))

        val dialogCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                cardWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            elevation = dp(32f).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(cardBg)
                setStroke(dp(1.2f).coerceAtLeast(1), strokeColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { }
        }

        val hdrRow = LinearLayout(ctx).apply {
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
        titleCol.addView(TextView(ctx).apply {
            text = "⚙ Stack Bytecode VM Disassembler"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryText)
        })
        titleCol.addView(TextView(ctx).apply {
            text = "Module [$modId] • bc_disasm(fn) instruction dump"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(accentColor)
        })
        hdrRow.addView(titleCol)
        hdrRow.addView(NeumorphicKeyView(ctx).apply {
            text = "✕"
            textSize = 13f
            cornerRadiusDp = 14f
            shadowPadDp = 2.5f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(34f), dp(34f))
            setOnClickListener { dismissWorkspaceModalDialog() }
        })
        dialogCard.addView(hdrRow)

        val etTarget = EditText(ctx).apply {
            setText(defaultTarget)
            hint = "Function name or lambda (e.g. height_at)"
            setSingleLine(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(10f), dp(7f), dp(10f), dp(7f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(inputBg)
                setStroke(1, strokeColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }
        dialogCard.addView(etTarget)

        val candScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30f)
            ).apply { bottomMargin = dp(6f) }
        }
        val candRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candScroll.addView(candRow)
        dialogCard.addView(candScroll)

        val disasmScroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(210f)
            ).apply { bottomMargin = dp(8f) }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(inputBg)
                setStroke(1, strokeColor)
            }
        }
        val tvDisasm = TextView(ctx).apply {
            text = "Select a function above or tap 'Disassemble'..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setTextColor(primaryText)
            setTextIsSelectable(true)
        }
        disasmScroll.addView(tvDisasm)
        dialogCard.addView(disasmScroll)

        fun runDisasm() {
            val target = etTarget.text?.toString().orEmpty().trim()
            tvDisasm.text = "Disassembling '$target' in [$modId]..."
            MathEngineManager.disassembleInModule(modId, target) { ok, outText ->
                activity.runOnUiThread {
                    tvDisasm.text = if (ok) {
                        outText
                    } else {
                        "⚠ $outText\n\nTip: Tap '▶ Run / Link' first so '$target' is linked in [$modId], or enter an inline lambda such as: (n) => { s := 0; (i in 1...n) { s += i }; s }"
                    }
                }
            }
        }

        fnCandidates.forEach { fnName ->
            val w = (fnName.length * 7.2f + 22f).coerceAtLeast(64f)
            candRow.addView(NeumorphicKeyView(ctx).apply {
                text = fnName
                textSize = 10.2f
                cornerRadiusDp = 9f
                shadowPadDp = 2f
                isAccentKey = false
                isDarkThemeMode = dark
                layoutParams = LinearLayout.LayoutParams(dp(w), dp(26f)).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    etTarget.setText(fnName)
                    runDisasm()
                }
            })
        }

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        btnRow.addView(NeumorphicKeyView(ctx).apply {
            text = "⚙ Disassemble"
            textSize = 12f
            cornerRadiusDp = 13f
            shadowPadDp = 2.8f
            isAccentKey = true
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(0, dp(38f), 1.3f).apply { marginEnd = dp(6f) }
            setOnClickListener {
                springPulse(this, 0.06f)
                runDisasm()
            }
        })
        btnRow.addView(NeumorphicKeyView(ctx).apply {
            text = "Close"
            textSize = 12f
            cornerRadiusDp = 13f
            shadowPadDp = 2.8f
            isAccentKey = false
            isDarkThemeMode = dark
            layoutParams = LinearLayout.LayoutParams(dp(84f), dp(38f))
            setOnClickListener { dismissWorkspaceModalDialog() }
        })
        dialogCard.addView(btnRow)

        overlay.addView(dialogCard)
        outerFrame.addView(overlay)
        activeModalOverlay = overlay
        isModalDialogOpen = true
        runDisasm()
    }
}
