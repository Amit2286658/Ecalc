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

internal fun MathWorkspaceFragment.refreshModuleChips() {
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

internal fun MathWorkspaceFragment.loadModuleStateIntoEditor(
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
        tvActiveModuleName?.text = "Project: [$activeProjectName]  •  File: $modFileName  •  Module: [$modId]$depsSuffix"
        etModuleEditor?.updateProjectAutocompleteContext(
            collectAllProjectModuleSources(ctx, modFileName, code)
        )
        etModuleEditor?.setText(code)
        etModuleEditor?.dismissSuggestionsPopup()
        val scopedFileKey = projectScopedKey(modFileName)
        val scopedModKey = projectScopedKey(modId)
        tvVarsHeader?.text = "Project [$activeProjectName] • Module [$modId] Variables & Constants"
        tvVarsContent?.text = moduleVarsCache[scopedModKey].orEmpty().ifBlank {
            "No variables bound yet in [$modId] ($activeProjectName). Tap '▶ Run / Link' in the Editor tab."
        }
        val savedLog = moduleLogs[scopedFileKey]
            ?: "=== Project [$activeProjectName] • Module [$modId] ($modFileName) ===\nReady. Tap '▶ Run / Link' in the Editor tab to execute top-to-bottom."
        tvModuleLogOutput?.text = savedLog
        tvConsoleOutput?.text = moduleConsoleLogs[scopedFileKey] ?: "(No print / println output yet)"
        val errPair = moduleErrorLines[scopedFileKey]
        if (errPair != null) {
            etModuleEditor?.highlightErrorLine(errPair.first, errPair.second)
        } else {
            etModuleEditor?.clearErrorHighlight()
        }
        refreshStructuredInspector(modFileName)

        val reqProj = activeProjectName
        MathEngineManager.inspectModuleVariables(modId, reqProj) { summary ->
            activity?.runOnUiThread {
                moduleVarsCache[projectScopedKey(modId, reqProj)] = summary
                val curFile = activeModuleFileName
                if (curFile == modFileName) {
                    tvVarsContent?.text = summary.ifBlank {
                        "No variables bound yet in [$modId]. Tap '▶ Run / Link' in the Editor tab."
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

internal fun MathWorkspaceFragment.saveCurrentModuleEditorState(showToast: Boolean): Boolean {
    val ctx = context ?: return false
    val activity = activity as? MainActivity ?: return false
    val code = etModuleEditor?.text?.toString() ?: return false
    val modFile = activeModuleFileName

    if (modFile != null && modFile in openModuleFiles) {
        val file = File(modulesDir(ctx), modFile)
        file.writeText(code)
        getCachedFileMeta(file)
        etModuleEditor?.updateProjectAutocompleteContext(
            collectAllProjectModuleSources(ctx, modFile, code)
        )
        val modId = MathEngineManager.deriveModuleName(modFile, code)
        val deps = MathEngineManager.extractModuleDependencies(code)
        val depsSuffix = if (deps.isNotEmpty()) "  •  <[${deps.joinToString(", ")}]" else ""
        tvActiveModuleName?.text = "Project: [$activeProjectName]  •  File: $modFile  •  Module: [$modId]$depsSuffix"
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

internal fun MathWorkspaceFragment.openModuleFile(fileName: String, closeDrawerAfter: Boolean) {
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

internal fun MathWorkspaceFragment.closeSpecificModuleFile(fileName: String) {
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

internal fun MathWorkspaceFragment.promptRenameOrCopyModuleFileDialog(targetName: String) {
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

internal fun MathWorkspaceFragment.confirmDeleteSpecificModuleFile(fileName: String) {
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
            moduleLogs.remove(projectScopedKey(fileName))
            moduleConsoleLogs.remove(projectScopedKey(fileName))
            moduleErrorLines.remove(projectScopedKey(fileName))
            moduleInspectableItems.remove(projectScopedKey(fileName))
            moduleVarsCache.remove(projectScopedKey(modId))
            MathEngineManager.resetModule(modId, activeProjectName) { }
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

/**
 * Executes the active `.math` module file strictly top-to-bottom on its own
 * (without auto-invoking `main()` / `@entry`) and updates its variables & log.
 */
internal fun MathWorkspaceFragment.runCurrentModuleFile(switchToLogAfterRun: Boolean) {
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
        resetBeforeRun = true,
        projectName = activeProjectName
    ) { modId, results, varsSummary ->
        activity.runOnUiThread {
            applyExecutionResultsToUi(
                headerBanner = "=== Executed $targetFile (Module [$modId]) Top-to-Bottom ===",
                targetFile = targetFile,
                modId = modId,
                results = results,
                varsSummary = varsSummary,
                switchToLogAfterRun = switchToLogAfterRun,
                toastPrefix = "Executed [$modId] ($targetFile)"
            )
        }
    }
}

/**
 * Runs the project-level `main()` or `@entry(fn)` function located anywhere in any `.math` file
 * of [projectName]. If no `main` / `@entry` exists in any file of that project, the project
 * itself cannot run while individual files can still run top-to-bottom on their own.
 */
internal fun MathWorkspaceFragment.runSpecificProject(projectName: String, closeDrawerAfter: Boolean) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val targetProj = MathEngineManager.sanitizeProjectName(projectName)

    // Save any unsaved edits in the current editor first
    saveCurrentModuleEditorState(showToast = false)

    val projectSources = collectAllProjectModuleSources(
        ctx,
        activeFileOverride = if (targetProj == activeProjectName) activeModuleFileName else null,
        activeCodeOverride = if (targetProj == activeProjectName) etModuleEditor?.text?.toString() else null,
        projectName = targetProj
    )
    val preferredFile = if (targetProj == activeProjectName) activeModuleFileName else projectActiveFileMap[targetProj]
    val entryPoint = MathEngineManager.findProjectEntryPoint(projectSources, preferredActiveFile = preferredFile)

    if (entryPoint == null) {
        val noEntryMsg = "⚠ Project [$targetProj] cannot run: no main() or @entry function in any file. Individual files can run top-to-bottom."
        tvModuleQuickStatus?.text = noEntryMsg
        moduleQuickStatusCard?.let { springPulse(it, 0.05f) }
        activity.showButtonToast("Project '$targetProj' has no main() or @entry function")
        refreshFileManagerDrawer()
        return
    }

    if (targetProj != activeProjectName) {
        switchToProject(targetProj, closeDrawerAfter = false)
    }
    if (entryPoint.fileName !in openModuleFiles) {
        openModuleFiles.add(entryPoint.fileName)
    }
    if (activeModuleFileName != entryPoint.fileName) {
        activeModuleFileName = entryPoint.fileName
        refreshModuleChips()
        loadModuleStateIntoEditor(animate = false)
    }
    if (closeDrawerAfter && isFileManagerDrawerOpen) {
        toggleFileManagerDrawer(open = false, animate = true)
    }

    MathEngineManager.runProject(
        projectName = targetProj,
        projectSourcesByFileName = projectSources,
        preferredActiveFile = entryPoint.fileName
    ) { ep, results, varsSummary ->
        activity.runOnUiThread {
            if (ep == null) {
                val msg = results.firstOrNull()?.second?.error
                    ?: "Project '$targetProj' cannot run: no main() or @entry function found."
                tvModuleQuickStatus?.text = "⚠ $msg"
                activity.showButtonToast(msg)
                return@runOnUiThread
            }
            applyExecutionResultsToUi(
                headerBanner = "=== Executed Project [$targetProj] Entry: ${ep.fileName} [${ep.modName}] :: ${ep.entryFn}() ===",
                targetFile = ep.fileName,
                modId = ep.modName,
                results = results,
                varsSummary = varsSummary,
                switchToLogAfterRun = false,
                toastPrefix = "Project [$targetProj] ▶ ${ep.entryFn}()"
            )
            refreshFileManagerDrawer()
        }
    }
}

internal fun MathWorkspaceFragment.applyExecutionResultsToUi(
    headerBanner: String,
    targetFile: String,
    modId: String,
    results: List<Pair<String, MathEngineManager.EvalOutcome>>,
    varsSummary: String,
    switchToLogAfterRun: Boolean,
    toastPrefix: String
) {
    val activity = activity as? MainActivity ?: return
    val sb = StringBuilder()
    val consoleSb = StringBuilder()
    val inspectables = mutableListOf<Triple<String, String, String>>()
    sb.append(headerBanner).append("\n")
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

    val scopedFileKey = projectScopedKey(targetFile)
    val scopedModKey = projectScopedKey(modId)
    val fullLog = sb.toString().trimEnd()
    val consoleText = consoleSb.toString().trimEnd().ifEmpty { "(No print / println output emitted)" }
    moduleLogs[scopedFileKey] = fullLog
    moduleConsoleLogs[scopedFileKey] = consoleText
    moduleVarsCache[scopedModKey] = varsSummary
    moduleInspectableItems[scopedFileKey] = inspectables

    if (hadError && firstErrorLine != null) {
        moduleErrorLines[scopedFileKey] = firstErrorLine!! to (firstErrorMsg ?: "Error")
        etModuleEditor?.highlightErrorLine(firstErrorLine, firstErrorMsg)
    } else {
        moduleErrorLines.remove(scopedFileKey)
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
        if (!hadError) toastPrefix else "Error on line ${firstErrorLine ?: 1} in [$modId]"
    )
}

internal fun MathWorkspaceFragment.resetActiveModuleState() {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val modFile = activeModuleFileName ?: return
    val code = etModuleEditor?.text?.toString()
        ?: runCatching { File(modulesDir(ctx), modFile).readText() }.getOrDefault("")
    val modId = MathEngineManager.deriveModuleName(modFile, code)

    val curProj = activeProjectName
    MathEngineManager.resetModule(modId, curProj) { ok ->
        activity.runOnUiThread {
            moduleVarsCache[projectScopedKey(modId, curProj)] = ""
            val cleared = "=== Project [$curProj] • Module [$modId] ($modFile) ===\n(Module [$modId] state & variables reset)"
            moduleLogs[projectScopedKey(modFile, curProj)] = cleared
            tvVarsContent?.text = "No variables bound in [$modId] (module reset)."
            tvModuleLogOutput?.text = cleared
            tvModuleQuickStatus?.text = "↺ Reset module [$modId]. Tap '▶ Run / Link' to re-execute $modFile."
            varsCard?.let { springPulse(it, 0.05f) }
            moduleLogCard?.let { springPulse(it, 0.04f) }
            activity.showButtonToast(if (ok) "Reset module [$modId]" else "Reset [$modId]")
        }
    }
}

internal fun MathWorkspaceFragment.promptNewModuleDialog() {
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
            activity.showButtonToast("Created module $name in $activeProjectName")
        }
    )
}

// =========================================================================
//  Multi-Project Lifecycle Operations (Create, Switch, Rename/Copy, Reset, Delete)
// =========================================================================

internal fun MathWorkspaceFragment.switchToProject(targetProjectRaw: String, closeDrawerAfter: Boolean) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val targetProject = MathEngineManager.sanitizeProjectName(targetProjectRaw)
    if (targetProject == activeProjectName) {
        if (closeDrawerAfter && isFileManagerDrawerOpen) {
            toggleFileManagerDrawer(open = false, animate = true)
        }
        return
    }

    // Save current editor buffer & open tab list for the outgoing project
    saveCurrentModuleEditorState(showToast = false)
    projectOpenModulesMap[activeProjectName] = openModuleFiles.toMutableList()
    projectActiveFileMap[activeProjectName] = activeModuleFileName

    activeProjectName = targetProject
    MathEngineManager.setActiveProject(ctx, targetProject)

    val projMods = listModuleFiles(ctx, targetProject).map { it.name }
    val savedOpen = projectOpenModulesMap[targetProject]?.filter { it in projMods }.orEmpty()
    openModuleFiles.clear()
    if (savedOpen.isNotEmpty()) {
        openModuleFiles.addAll(savedOpen)
    } else {
        openModuleFiles.addAll(projMods)
    }
    val preferredActive = projectActiveFileMap[targetProject]
    activeModuleFileName = when {
        preferredActive != null && preferredActive in openModuleFiles -> preferredActive
        "physics_sim.math" in openModuleFiles -> "physics_sim.math"
        else -> openModuleFiles.firstOrNull()
    }

    isModuleEditorSubTab = true
    refreshModuleChips()
    loadModuleStateIntoEditor(animate = true, fromDirection = 1, distance = 2)
    updateModuleSubTabVisibility(animate = false)
    refreshFileManagerDrawer()

    val pStat = MathEngineManager.getProjectBinaryStatus(ctx, targetProject)
    tvModuleQuickStatus?.text = "📦 Switched to project '$targetProject' (isolated engine.so & stdlib.mathc • ${pStat.stdlibMathcBytes / 1024} KB)."
    activity.showButtonToast("Project: $targetProject")
    if (closeDrawerAfter && isFileManagerDrawerOpen) {
        toggleFileManagerDrawer(open = false, animate = true)
    }
}

internal fun MathWorkspaceFragment.promptNewProjectDialog() {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    saveCurrentModuleEditorState(showToast = false)
    val existingProjects = MathEngineManager.listProjects(ctx)
    var nextIdx = (existingProjects.size + 1).coerceAtLeast(2)
    while ("project$nextIdx" in existingProjects) nextIdx++
    val defaultProjName = "project$nextIdx"

    showFrostedFileNamingModal(
        badgeIcon = "📦",
        title = "New .math Project",
        subtitle = "Isolated Workspace • Own engine.so & stdlib.mathc",
        description = "Creates a new isolated project folder in math_projects/ accompanied by its own engine.so and stdlib.mathc so compiled modules never interfere across projects.",
        initialText = "",
        hintText = defaultProjName,
        suggestions = listOf(
            defaultProjName,
            "physics_project",
            "finance_lab",
            "signal_dsp"
        ),
        primaryButtonLabel = "✨ Create Project",
        onPrimary = { rawInput ->
            val projName = MathEngineManager.sanitizeProjectName(rawInput.ifBlank { defaultProjName })
            val projDir = MathEngineManager.createProject(ctx, projName)
            val existingMods = listModuleFiles(ctx, projName)
            if (existingMods.isEmpty()) {
                val starterName = "main.math"
                val starterModId = "${projName}_main".replace(Regex("[^a-zA-Z0-9_]"), "_")
                File(projDir, starterName).writeText(
                    "# Project [$projName] — Isolated engine.so & stdlib.mathc (with global patches)\n" +
                    "[$starterModId]\n" +
                    "<[arith]\n" +
                    "<[trig]\n\n" +
                    "pub const PROJECT_SCALE = 1.0\n" +
                    "pub x0 := 12.0\n" +
                    "pub compute(v: real) = (v^2 + 2 * v) * PROJECT_SCALE\n\n" +
                    "pub main() = {\n" +
                    "    compute(x0)\n" +
                    "}\n"
                )
            }
            switchToProject(projName, closeDrawerAfter = false)
            activity.showButtonToast("Created project $projName (with engine.so & stdlib.mathc)")
        }
    )
}

internal fun MathWorkspaceFragment.promptRenameOrCopyProjectDialog(sourceProj: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    saveCurrentModuleEditorState(showToast = false)
    showFrostedFileNamingModal(
        badgeIcon = "📦",
        title = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) "Copy Project '$sourceProj'" else "Rename or Copy Project",
        subtitle = "Project [$sourceProj] • Clones .math files, engine.so & stdlib.mathc",
        description = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) {
            "Default project1 is always kept with all Studio demos, tests & examples (while project0 is dedicated to the Basic Calculator). Enter a name below to clone project1 into a new isolated project."
        } else {
            "Enter a new name to rename '$sourceProj', or tap 'Save Copy' to clone its modules, engine.so, and stdlib.mathc."
        },
        initialText = "${sourceProj}_copy",
        hintText = "${sourceProj}_v2",
        suggestions = listOf(
            "${sourceProj}_v2",
            "${sourceProj}_copy",
            "project_lab"
        ),
        primaryButtonLabel = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) "Clone Project" else "Rename",
        onPrimary = { rawInput ->
            val copyOnly = (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME)
            val dstProj = MathEngineManager.renameOrCopyProject(ctx, sourceProj, rawInput, copyOnly = copyOnly)
            switchToProject(dstProj, closeDrawerAfter = false)
            activity.showButtonToast(if (copyOnly) "Cloned to $dstProj" else "Renamed project to $dstProj")
        },
        secondaryButtonLabel = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) null else "Save Copy",
        onSecondary = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) null else { rawInput ->
            val dstProj = MathEngineManager.renameOrCopyProject(ctx, sourceProj, rawInput, copyOnly = true)
            switchToProject(dstProj, closeDrawerAfter = false)
            activity.showButtonToast("Copied project to $dstProj")
        }
    )
}

internal fun MathWorkspaceFragment.resetActiveProjectIsolatedEngine() {
    resetSpecificProjectEngine(activeProjectName)
}

internal fun MathWorkspaceFragment.resetSpecificProjectEngine(projectName: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    MathEngineManager.resetProjectEngine(ctx, projectName) { ok ->
        activity.runOnUiThread {
            moduleVarsCache.clear()
            refreshFileManagerDrawer()
            loadModuleStateIntoEditor(animate = false)
            tvModuleQuickStatus?.text = "↺ Restored clean stdlib.mathc & restarted engine.so for project [$projectName]."
            activity.showButtonToast(
                if (ok) "Reset engine & stdlib.mathc for $projectName"
                else "Reset $projectName"
            )
        }
    }
}

internal fun MathWorkspaceFragment.confirmDeleteOrResetProject(projectName: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val isDefault = (projectName == MathEngineManager.DEFAULT_PROJECT_NAME)
    showFrostedConfirmModal(
        badgeIcon = if (isDefault) "↺" else "🗑",
        title = if (isDefault) "Reset Default '$projectName'?" else "Delete Project '$projectName'?",
        subtitle = "Project [$projectName] • Isolated engine.so & stdlib.mathc",
        message = if (isDefault) {
            "Restore '$projectName' to its default demos/examples and fresh engine.so + stdlib.mathc?"
        } else {
            "Permanently delete project '$projectName', its .math modules, and its isolated engine.so & stdlib.mathc?"
        },
        confirmLabel = if (isDefault) "Reset project1" else "Delete Project",
        onConfirm = {
            val nextProj = MathEngineManager.deleteProject(ctx, projectName)
            projectOpenModulesMap.remove(projectName)
            projectActiveFileMap.remove(projectName)
            if (isDefault) {
                openModuleFiles.clear()
                ensureSampleAndMigratedModuleFiles(ctx)
            }
            activeProjectName = nextProj
            MathEngineManager.setActiveProject(ctx, nextProj)
            val avail = listModuleFiles(ctx, nextProj).map { it.name }
            openModuleFiles.clear()
            openModuleFiles.addAll(avail)
            activeModuleFileName = if ("physics_sim.math" in openModuleFiles) "physics_sim.math" else openModuleFiles.firstOrNull()
            refreshModuleChips()
            loadModuleStateIntoEditor(animate = true, fromDirection = -1)
            refreshFileManagerDrawer()
            activity.showButtonToast(if (isDefault) "Reset project1 to defaults" else "Deleted project $projectName")
        }
    )
}

// =========================================================================
//  Per-Module .math File Import In / Export Out
// =========================================================================

internal fun MathWorkspaceFragment.launchImportMathFileForModules() {
    val activity = activity as? MainActivity ?: return
    saveCurrentModuleEditorState(showToast = false)
    try {
        importMathFileLauncher.launch(arrayOf("*/*"))
    } catch (t: Throwable) {
        activity.showButtonToast("Could not open file picker: ${t.message}")
    }
}

internal fun MathWorkspaceFragment.handleImportedMathFileUri(uri: Uri) {
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

internal fun MathWorkspaceFragment.exportSpecificModuleFile(fileName: String) {
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

internal fun MathWorkspaceFragment.handleExportedMathFileUri(uri: Uri) {
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
