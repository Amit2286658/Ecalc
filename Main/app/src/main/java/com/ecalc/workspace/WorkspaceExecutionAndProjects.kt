package com.ecalc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
 * with Left-Drawer Project & File Manager, App-Side Folders & File Search.
 */

// =========================================================================
//  App-Side Folder Hierarchy Persistence & Shrink-Up / Drop-Down Physics
// =========================================================================

private const val APP_FOLDERS_META_FILENAME = ".ecalc_folders_meta.txt"

internal fun MathWorkspaceFragment.normalizeFolderPath(raw: String): String {
    return raw.trim()
        .replace('\\', '/')
        .split('/')
        .map { seg ->
            seg.trim()
                .removeSuffix(".math")
                .replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_")
                .trim()
        }
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")
}

internal fun MathWorkspaceFragment.addFolderWithAncestors(state: ProjectFolderState, rawPath: String): String {
    val norm = normalizeFolderPath(rawPath)
    if (norm.isEmpty()) return ""
    val parts = norm.split('/')
    val running = StringBuilder()
    for (p in parts) {
        if (running.isNotEmpty()) running.append('/')
        running.append(p)
        state.folders.add(running.toString())
    }
    return norm
}

internal fun MathWorkspaceFragment.ensureProjectFolderStateLoaded(
    ctx: Context,
    projectName: String = activeProjectName,
    currentFiles: List<File>? = null
): ProjectFolderState {
    val safeProj = MathEngineManager.sanitizeProjectName(projectName)
    val state = projectFolderStates.getOrPut(safeProj) {
        val loaded = ProjectFolderState()
        val metaFile = File(modulesDir(ctx, safeProj), APP_FOLDERS_META_FILENAME)
        if (metaFile.isFile && metaFile.canRead()) {
            runCatching {
                metaFile.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    when {
                        line.startsWith("DIR|") -> {
                            val dirPath = normalizeFolderPath(line.removePrefix("DIR|"))
                            if (dirPath.isNotEmpty()) {
                                addFolderWithAncestors(loaded, dirPath)
                            }
                        }
                        line.startsWith("FILE|") -> {
                            val payload = line.removePrefix("FILE|")
                            val sepIdx = payload.indexOf('|')
                            if (sepIdx > 0) {
                                val fName = payload.substring(0, sepIdx).trim()
                                val dirPath = normalizeFolderPath(payload.substring(sepIdx + 1))
                                if (fName.isNotEmpty() && dirPath.isNotEmpty()) {
                                    addFolderWithAncestors(loaded, dirPath)
                                    loaded.fileToFolder[fName] = dirPath
                                }
                            }
                        }
                        line.startsWith("COLLAPSED|") -> {
                            val dirPath = normalizeFolderPath(line.removePrefix("COLLAPSED|"))
                            if (dirPath.isNotEmpty()) {
                                loaded.collapsedFolders.add(dirPath)
                            }
                        }
                    }
                }
            }
        }
        loaded
    }
    if (currentFiles != null) {
        val validNames = currentFiles.mapTo(HashSet()) { it.name }
        state.fileToFolder.keys.retainAll(validNames)
    }
    state.collapsedFolders.retainAll(state.folders)
    return state
}

internal fun MathWorkspaceFragment.saveProjectFolderState(
    ctx: Context,
    projectName: String = activeProjectName
) {
    val safeProj = MathEngineManager.sanitizeProjectName(projectName)
    val state = projectFolderStates[safeProj] ?: return
    val metaFile = File(modulesDir(ctx, safeProj), APP_FOLDERS_META_FILENAME)
    runCatching {
        val sb = StringBuilder()
        state.folders.sorted().forEach { dir ->
            sb.append("DIR|").append(dir).append('\n')
        }
        state.fileToFolder.entries.sortedBy { it.key }.forEach { (fName, dir) ->
            if (dir.isNotEmpty() && dir in state.folders) {
                sb.append("FILE|").append(fName).append('|').append(dir).append('\n')
            }
        }
        state.collapsedFolders.filter { it in state.folders }.sorted().forEach { dir ->
            sb.append("COLLAPSED|").append(dir).append('\n')
        }
        metaFile.writeText(sb.toString())
    }
}

internal fun MathWorkspaceFragment.expandAncestorFoldersForFile(
    ctx: Context,
    fileName: String,
    projectName: String = activeProjectName
) {
    val state = ensureProjectFolderStateLoaded(ctx, projectName)
    val folderPath = state.fileToFolder[fileName].orEmpty()
    if (folderPath.isEmpty()) return
    val parts = folderPath.split('/')
    val running = StringBuilder()
    var changed = false
    for (p in parts) {
        if (running.isNotEmpty()) running.append('/')
        running.append(p)
        if (state.collapsedFolders.remove(running.toString())) {
            changed = true
        }
    }
    if (changed) {
        saveProjectFolderState(ctx, projectName)
    }
}

/**
 * Animates a folder in the File Manager drawer shrinking up closed or dropping down open
 * with the app's signature damped-spring physics and proper indentation.
 */
internal fun MathWorkspaceFragment.animateFolderShrinkOrDrop(
    ctx: Context,
    folderPath: String,
    shortName: String,
    subFolderCount: Int,
    totalFilesCount: Int,
    indentedContainer: LinearLayout,
    tvTitle: TextView,
    tvBadge: TextView,
    tvSub: TextView,
    btnToggle: NeumorphicKeyView,
    accentText: Int,
    secondaryText: Int
) {
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    val density = resources.displayMetrics.density
    folderToggleAnimators.remove(folderPath)?.cancel()

    val willCollapse = folderPath !in state.collapsedFolders
    if (willCollapse) {
        state.collapsedFolders.add(folderPath)
    } else {
        state.collapsedFolders.remove(folderPath)
    }
    saveProjectFolderState(ctx, activeProjectName)

    val subDesc = if (subFolderCount > 0) "$subFolderCount sub • " else ""
    tvTitle.text = if (willCollapse) "▸ 📁 $shortName" else "▾ 📂 $shortName"
    tvBadge.text = "${subDesc}$totalFilesCount mod${if (totalFilesCount == 1) "" else "s"} • ${if (willCollapse) "CLOSED" else "OPEN"}"
    tvBadge.setTextColor(if (willCollapse) secondaryText else accentText)
    tvSub.text = "App folder /$folderPath • Tap to ${if (willCollapse) "drop down open" else "shrink up close"}"
    btnToggle.text = if (willCollapse) "▾ Drop" else "▴ Shrink"

    if (willCollapse) {
        val startH = indentedContainer.height.coerceAtLeast(1)
        indentedContainer.pivotX = indentedContainer.width / 2f
        indentedContainer.pivotY = 0f
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L
            interpolator = AccelerateInterpolator(0.85f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val curH = (startH * (1f - f)).toInt().coerceAtLeast(0)
                indentedContainer.layoutParams = indentedContainer.layoutParams.apply {
                    height = curH
                }
                indentedContainer.scaleY = 1f - 0.18f * f
                indentedContainer.translationY = (-8f * density) * f
                indentedContainer.alpha = (1f - f / 0.8f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    indentedContainer.visibility = View.GONE
                    indentedContainer.layoutParams = indentedContainer.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    indentedContainer.scaleY = 1f
                    indentedContainer.translationY = 0f
                    indentedContainer.alpha = 1f
                    folderToggleAnimators.remove(folderPath)
                }
            })
            start()
        }
        folderToggleAnimators[folderPath] = anim
    } else {
        val parentW = (indentedContainer.parent as? View)?.width?.takeIf { it > 0 }
            ?: (fileManagerDrawerPanel?.width ?: (292 * density).toInt())
        indentedContainer.visibility = View.VISIBLE
        indentedContainer.measure(
            View.MeasureSpec.makeMeasureSpec(parentW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetH = indentedContainer.measuredHeight.coerceAtLeast(1)
        indentedContainer.layoutParams = indentedContainer.layoutParams.apply {
            height = 0
        }
        indentedContainer.pivotX = parentW / 2f
        indentedContainer.pivotY = 0f
        indentedContainer.alpha = 0f

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                val openProgress = (1f - springFactor).coerceIn(0f, 1.08f)

                indentedContainer.layoutParams = indentedContainer.layoutParams.apply {
                    height = (targetH * openProgress.coerceAtMost(1f)).toInt().coerceAtLeast(1)
                }
                indentedContainer.translationY = (-10f * density) * springFactor
                indentedContainer.scaleY = 1f + 0.03f * pulse
                indentedContainer.alpha = (f / 0.24f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    indentedContainer.layoutParams = indentedContainer.layoutParams.apply {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    indentedContainer.translationY = 0f
                    indentedContainer.scaleY = 1f
                    indentedContainer.alpha = 1f
                    folderToggleAnimators.remove(folderPath)
                }
            })
            start()
        }
        folderToggleAnimators[folderPath] = anim
    }
}

internal fun MathWorkspaceFragment.promptNewFolderDialog(parentFolder: String? = null) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    val nextIdx = state.folders.size + 1
    val defaultLeaf = "folder_$nextIdx"
    val prefix = if (!parentFolder.isNullOrBlank()) "${normalizeFolderPath(parentFolder)}/" else ""
    val suggestions = if (prefix.isEmpty()) {
        listOf("simulations", "core", "utils", "experiments", defaultLeaf)
    } else {
        listOf("${prefix}sub", "${prefix}models", "${prefix}tests", "${prefix}utils")
    }

    showFrostedFileNamingModal(
        badgeIcon = "📁",
        title = if (prefix.isEmpty()) "New App Folder" else "New Subfolder in /$parentFolder",
        subtitle = "App-Side Folder Hierarchy • Purely UI Feature (Engine-Transparent)",
        description = "Creates a collapsible folder in [$activeProjectName] that can shrink up closed or drop down open with proper indentation. Use '/' for nested folders (e.g. simulations/orbital).",
        initialText = prefix,
        hintText = "$prefix$defaultLeaf",
        suggestions = suggestions,
        primaryButtonLabel = "📁 Create Folder",
        onPrimary = { rawInput ->
            val entered = rawInput.trim().ifBlank { "$prefix$defaultLeaf" }
            val fullPath = if (prefix.isNotEmpty() && !entered.contains('/')) {
                normalizeFolderPath("$prefix$entered")
            } else {
                normalizeFolderPath(entered)
            }
            if (fullPath.isNotEmpty()) {
                addFolderWithAncestors(state, fullPath)
                // Ensure newly created folder and its ancestors are dropped open
                val parts = fullPath.split('/')
                val running = StringBuilder()
                for (p in parts) {
                    if (running.isNotEmpty()) running.append('/')
                    running.append(p)
                    state.collapsedFolders.remove(running.toString())
                }
                saveProjectFolderState(ctx, activeProjectName)
                refreshFileManagerDrawer()
                tvModuleQuickStatus?.text = "📁 Created app-side folder '/$fullPath' in [$activeProjectName]."
                activity.showButtonToast("Created folder /$fullPath")
            }
        },
        extBadgeText = "📁 dir"
    )
}

internal fun MathWorkspaceFragment.promptRenameFolderDialog(oldFolderPath: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    val parentPrefix = oldFolderPath.substringBeforeLast('/', "").let { if (it == oldFolderPath) "" else "$it/" }
    val shortName = oldFolderPath.substringAfterLast('/')

    showFrostedFileNamingModal(
        badgeIcon = "✏️",
        title = "Rename Folder '/$oldFolderPath'",
        subtitle = "App-Side Folder • Updates Nested Subfolders & Assigned Files",
        description = "Enter a new folder name or path for '/$oldFolderPath'. All nested subfolders and assigned .math modules stay intact.",
        initialText = oldFolderPath,
        hintText = "${parentPrefix}${shortName}_v2",
        suggestions = listOf(
            "${parentPrefix}${shortName}_v2",
            "${parentPrefix}core",
            "${parentPrefix}simulations"
        ),
        primaryButtonLabel = "Rename Folder",
        onPrimary = { rawInput ->
            val newPath = normalizeFolderPath(rawInput)
            if (newPath.isNotEmpty() && newPath != oldFolderPath) {
                val updatedFolders = LinkedHashSet<String>()
                for (dir in state.folders) {
                    when {
                        dir == oldFolderPath -> updatedFolders.add(newPath)
                        dir.startsWith("$oldFolderPath/") -> updatedFolders.add(newPath + dir.removePrefix(oldFolderPath))
                        else -> updatedFolders.add(dir)
                    }
                }
                state.folders.clear()
                updatedFolders.forEach { addFolderWithAncestors(state, it) }

                val updatedCollapsed = LinkedHashSet<String>()
                for (c in state.collapsedFolders) {
                    when {
                        c == oldFolderPath -> updatedCollapsed.add(newPath)
                        c.startsWith("$oldFolderPath/") -> updatedCollapsed.add(newPath + c.removePrefix(oldFolderPath))
                        else -> updatedCollapsed.add(c)
                    }
                }
                state.collapsedFolders.clear()
                state.collapsedFolders.addAll(updatedCollapsed)

                for ((fName, assigned) in state.fileToFolder.entries.toList()) {
                    when {
                        assigned == oldFolderPath -> state.fileToFolder[fName] = newPath
                        assigned.startsWith("$oldFolderPath/") -> {
                            state.fileToFolder[fName] = newPath + assigned.removePrefix(oldFolderPath)
                        }
                    }
                }
                saveProjectFolderState(ctx, activeProjectName)
                refreshFileManagerDrawer()
                activity.showButtonToast("Renamed folder to /$newPath")
            }
        },
        extBadgeText = "📁 dir"
    )
}

internal fun MathWorkspaceFragment.confirmDeleteFolderDialog(folderPath: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    val parentFolder = if (folderPath.contains('/')) folderPath.substringBeforeLast('/') else ""
    val parentDesc = if (parentFolder.isEmpty()) "project root" else "/$parentFolder"

    showFrostedConfirmModal(
        badgeIcon = "📁",
        title = "Remove Folder '/$folderPath'?",
        subtitle = "App-Side Folder • .math Files Preserved",
        message = "Remove app-side folder '/$folderPath' (and its subfolders)? Any .math module files inside it will be moved safely to $parentDesc without deleting your code.",
        confirmLabel = "Remove Folder",
        onConfirm = {
            state.folders.removeAll { it == folderPath || it.startsWith("$folderPath/") }
            state.collapsedFolders.removeAll { it == folderPath || it.startsWith("$folderPath/") }
            for ((fName, assigned) in state.fileToFolder.entries.toList()) {
                if (assigned == folderPath || assigned.startsWith("$folderPath/")) {
                    if (parentFolder.isEmpty()) {
                        state.fileToFolder.remove(fName)
                    } else {
                        state.fileToFolder[fName] = parentFolder
                    }
                }
            }
            saveProjectFolderState(ctx, activeProjectName)
            refreshFileManagerDrawer()
            activity.showButtonToast("Removed folder /$folderPath")
        }
    )
}

internal fun MathWorkspaceFragment.promptMoveFileToFolderDialog(fileName: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    val curFolder = state.fileToFolder[fileName].orEmpty()
    val suggestions = buildList {
        add("(Root)")
        addAll(state.folders.sorted())
        if ("simulations" !in state.folders) add("simulations")
        if ("core" !in state.folders) add("core")
        if ("utils" !in state.folders) add("utils")
    }.distinct()

    showFrostedFileNamingModal(
        badgeIcon = "📁",
        title = "Move '$fileName' to Folder",
        subtitle = "Current Location: ${if (curFolder.isEmpty()) "/ (Root)" else "/$curFolder"}",
        description = "Select an existing folder chip below, type a new folder path (e.g. simulations/orbital) to create & move in one step, or choose '(Root)' to place at top level.",
        initialText = curFolder,
        hintText = "simulations (or leave blank for Root)",
        suggestions = suggestions,
        primaryButtonLabel = "📁 Move File",
        onPrimary = { rawInput ->
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty() || trimmed.equals("(Root)", ignoreCase = true) || trimmed == "/") {
                state.fileToFolder.remove(fileName)
                saveProjectFolderState(ctx, activeProjectName)
                refreshFileManagerDrawer()
                activity.showButtonToast("Moved $fileName to Root")
            } else {
                val targetFolder = addFolderWithAncestors(state, trimmed)
                if (targetFolder.isNotEmpty()) {
                    state.fileToFolder[fileName] = targetFolder
                    state.collapsedFolders.remove(targetFolder)
                    saveProjectFolderState(ctx, activeProjectName)
                    refreshFileManagerDrawer()
                    activity.showButtonToast("Moved $fileName to /$targetFolder")
                }
            }
        },
        secondaryButtonLabel = if (curFolder.isNotEmpty()) "Move to Root" else null,
        onSecondary = if (curFolder.isNotEmpty()) { _ ->
            state.fileToFolder.remove(fileName)
            saveProjectFolderState(ctx, activeProjectName)
            refreshFileManagerDrawer()
            activity.showButtonToast("Moved $fileName to Root")
        } else null,
        extBadgeText = "📁 dir"
    )
}

internal fun MathWorkspaceFragment.promptMoveFileIntoThisFolderDialog(targetFolderPath: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    val allFiles = listModuleFiles(ctx).map { it.name }
    val candidates = allFiles.filter { state.fileToFolder[it] != targetFolderPath }
    val suggestions = if (candidates.isNotEmpty()) candidates else allFiles

    showFrostedFileNamingModal(
        badgeIcon = "📥",
        title = "Move Module Into '/$targetFolderPath'",
        subtitle = "Folder '/$targetFolderPath' • Tap a Module Chip Below",
        description = "Tap any project .math module below (or enter its filename) to place it inside '/$targetFolderPath'.",
        initialText = candidates.firstOrNull().orEmpty(),
        hintText = "module_name.math",
        suggestions = suggestions,
        primaryButtonLabel = "Move Into Folder",
        onPrimary = { rawInput ->
            var fname = rawInput.trim().substringAfterLast('/')
            if (fname.isNotEmpty()) {
                if (!fname.endsWith(".math")) fname += ".math"
                val file = File(modulesDir(ctx), fname)
                if (file.exists()) {
                    addFolderWithAncestors(state, targetFolderPath)
                    state.fileToFolder[fname] = targetFolderPath
                    state.collapsedFolders.remove(targetFolderPath)
                    saveProjectFolderState(ctx, activeProjectName)
                    refreshFileManagerDrawer()
                    activity.showButtonToast("Moved $fname into /$targetFolderPath")
                } else {
                    activity.showButtonToast("Module '$fname' not found in $activeProjectName")
                }
            }
        },
        extBadgeText = ".math"
    )
}

// =========================================================================
//  File Manager Frosted Lens Search Overlay (Horizontal Spring Expansion,
//  Frosted Blur, Virtual Keyboard Avoidance & Live File/Folder Suggestions)
// =========================================================================

internal data class DrawerFileSuggestionItem(
    val isFolder: Boolean,
    val title: String,
    val badge: String,
    val subtitle: String,
    val fileName: String? = null,
    val folderPath: String? = null
)

internal fun MathWorkspaceFragment.toggleDrawerFileSearch(anchor: View) {
    if (drawerFileSearchOverlay != null) {
        dismissDrawerFileSearch()
    } else {
        showDrawerFileSearch(anchor)
    }
}

internal fun MathWorkspaceFragment.queryProjectFilesAndFoldersSuggestions(
    ctx: Context,
    rawQuery: String,
    limit: Int = 20
): List<DrawerFileSuggestionItem> {
    val allModFiles = listModuleFiles(ctx, activeProjectName)
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName, allModFiles)
    val activeEditorText = etModuleEditor?.text?.toString()
    val q = rawQuery.trim().lowercase()

    val results = ArrayList<Pair<Int, DrawerFileSuggestionItem>>()

    // 1. Score folders
    for (folderPath in state.folders.sorted()) {
        val shortName = folderPath.substringAfterLast('/')
        val pathLow = folderPath.lowercase()
        val shortLow = shortName.lowercase()
        val count = allModFiles.count {
            val assigned = state.fileToFolder[it.name].orEmpty()
            assigned == folderPath || assigned.startsWith("$folderPath/")
        }
        val score = when {
            q.isEmpty() -> 40
            shortLow == q || pathLow == q -> 2
            shortLow.startsWith(q) -> 12
            pathLow.contains(q) -> 28
            else -> -1
        }
        if (score >= 0) {
            results.add(
                score to DrawerFileSuggestionItem(
                    isFolder = true,
                    title = "📁 /$folderPath",
                    badge = "FOLDER ($count)",
                    subtitle = "App-side folder • Tap to open & expand /$folderPath",
                    folderPath = folderPath
                )
            )
        }
    }

    // 2. Score .math files
    for (file in allModFiles) {
        val fname = file.name
        val fnameLow = fname.lowercase()
        val folderPath = state.fileToFolder[fname].orEmpty()
        val folderLow = folderPath.lowercase()
        val isOpen = fname in openModuleFiles
        val isActive = (fname == activeModuleFileName && isOpen)
        val (lineCount, byteLen) = getCachedFileMeta(file)
        val rawSrc = if (isActive && activeEditorText != null) {
            activeEditorText
        } else {
            runCatching { file.readText() }.getOrDefault("")
        }
        val modId = MathEngineManager.deriveModuleName(fname, rawSrc)
        val modLow = modId.lowercase()
        val deps = MathEngineManager.extractModuleDependencies(rawSrc)

        val score = when {
            q.isEmpty() -> if (isActive) 0 else if (isOpen) 5 else 15
            fnameLow == q || fnameLow.removeSuffix(".math") == q || modLow == q -> 0
            fnameLow.startsWith(q) || modLow.startsWith(q) -> 8
            fnameLow.contains(q) || modLow.contains(q) -> 18
            folderLow.contains(q) -> 26
            deps.any { it.lowercase().contains(q) } -> 35
            rawSrc.lowercase().contains(q) -> 45
            else -> -1
        }
        if (score >= 0) {
            val locPrefix = if (folderPath.isNotEmpty()) "📁 /$folderPath • " else ""
            val statusBadge = when {
                isActive -> "ACTIVE [$modId]"
                isOpen -> "OPEN [$modId]"
                else -> "[$modId]"
            }
            val matchLinePreview = if (q.isNotEmpty() && !fnameLow.contains(q) && !modLow.contains(q)) {
                rawSrc.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.lowercase().contains(q) }
                    ?.take(48)
                    ?.let { " • \"$it\"" }
                    .orEmpty()
            } else ""

            results.add(
                score to DrawerFileSuggestionItem(
                    isFolder = false,
                    title = "🧩 $fname",
                    badge = statusBadge,
                    subtitle = "$locPrefix$lineCount lines ($byteLen B)$matchLinePreview",
                    fileName = fname,
                    folderPath = folderPath.ifEmpty { null }
                )
            )
        }
    }

    results.sortWith(compareBy<Pair<Int, DrawerFileSuggestionItem>> { it.first }.thenBy { it.second.title.lowercase() })
    return results.take(limit).map { it.second }
}

/**
 * Expands the File Manager search bar horizontally from [anchor] (`btnDrawerSearchFiles`, the lens icon
 * right after `+ Module` and `📥 Import`) across the full horizontal width of `filesHeaderActionRow`,
 * accompanied by the frosted-glass blur backdrop and damped-spring physics (`e^-7.5t · cos/sin(22t)`).
 * Tracks IME / virtual keyboard insets so the search bar and live suggestions list stay visible above
 * the virtual keyboard and are never hidden behind it.
 */
internal fun MathWorkspaceFragment.showDrawerFileSearch(anchor: View) {
    val ctx = context ?: return
    val act = activity as? MainActivity ?: return
    val outerFrame = workspaceRootFrame ?: return
    act.dismissButtonPopup()

    drawerFileSearchAnimators.forEach { it.cancel() }
    drawerFileSearchAnimators.clear()
    drawerFileSearchOverlay?.let { existing ->
        drawerFileSearchLayoutListener?.let { l ->
            if (existing.viewTreeObserver.isAlive) existing.viewTreeObserver.removeOnGlobalLayoutListener(l)
        }
        (existing.parent as? ViewGroup)?.removeView(existing)
    }
    drawerFileSearchOverlay = null
    drawerFileSearchLayoutListener = null

    val density = resources.displayMetrics.density
    val dp = { v: Float -> (v * density).toInt() }
    val dark = act.isDarkMode

    val barBgColor = Color.parseColor(if (dark) "#24282E" else "#F7F8FA")
    val suggCardBg = Color.parseColor(if (dark) "#22252A" else "#F4F6F9")
    val rowBgColor = Color.parseColor(if (dark) "#2C3038" else "#EAECEF")
    val folderRowBgColor = Color.parseColor(if (dark) "#1F3236" else "#F9EAE6")
    val strokeCol = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
    val subStrokeCol = Color.parseColor(if (dark) "#3C4048" else "#D5D9E0")
    val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#24292F")
    val secondaryText = Color.parseColor(if (dark) "#9AA4B2" else "#57606A")

    val frostBmp = Frost.frozenBackdrop(outerFrame, downscale = 6, radius = 6)
    Frost.tryLiveBlur(fileManagerDrawerPanel, 14f * density)

    val overlay = FrameLayout(ctx).apply {
        elevation = dp(30f).toFloat()
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = true
        setOnClickListener { dismissDrawerFileSearch() }
    }

    val frostView = if (frostBmp != null) {
        ImageView(ctx).apply {
            setImageBitmap(frostBmp)
            scaleType = ImageView.ScaleType.FIT_XY
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }.also { overlay.addView(it) }
    } else null

    val searchBarCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 11f * density
            setColor(barBgColor)
            setStroke(dp(1.4f).coerceAtLeast(1), strokeCol)
        }
        elevation = 16f * density
        setPadding(dp(9f), dp(3f), dp(6f), dp(3f))
        isClickable = true
        isFocusable = true
        setOnClickListener { }
        visibility = View.INVISIBLE
    }

    val ivLens = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_search_lens)
        setColorFilter(strokeCol)
        layoutParams = LinearLayout.LayoutParams(dp(17f), dp(17f)).apply {
            marginEnd = dp(7f)
        }
    }
    searchBarCard.addView(ivLens)

    val etSearch = EditText(ctx).apply {
        setText(drawerFileSearchQuery)
        setSelection(text.length)
        hint = "Search .math files & folders..."
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
        setTextColor(primaryText)
        setHintTextColor(secondaryText)
        background = null
        setPadding(0, dp(4f), dp(4f), dp(4f))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    drawerFileSearchEditText = etSearch
    searchBarCard.addView(etSearch)

    val tvCountBadge = TextView(ctx).apply {
        text = "0"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.2f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(strokeCol)
        setPadding(dp(5f), dp(2f), dp(5f), dp(2f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6.5f * density
            setColor(Color.parseColor(if (dark) "#1A2E32" else "#FCEAE6"))
            setStroke(1, strokeCol)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(4f) }
    }
    searchBarCard.addView(tvCountBadge)

    val btnClearText = NeumorphicKeyView(ctx).apply {
        text = "⌫"
        textSize = 10f
        cornerRadiusDp = 7.5f
        shadowPadDp = 1.5f
        isAccentKey = false
        isDarkThemeMode = dark
        visibility = if (drawerFileSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
        layoutParams = LinearLayout.LayoutParams(dp(26f), dp(25f)).apply {
            marginEnd = dp(3f)
        }
        setOnClickListener {
            springPulse(this, 0.06f)
            etSearch.setText("")
        }
    }
    searchBarCard.addView(btnClearText)

    val btnCloseSearch = NeumorphicKeyView(ctx).apply {
        text = "✕"
        textSize = 11f
        cornerRadiusDp = 8f
        shadowPadDp = 1.7f
        isAccentKey = true
        isDarkThemeMode = dark
        layoutParams = LinearLayout.LayoutParams(dp(27f), dp(25f))
        setOnClickListener {
            springPulse(this, 0.06f)
            dismissDrawerFileSearch()
        }
    }
    searchBarCard.addView(btnCloseSearch)

    val suggestionsPanel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12.5f * density
            setColor(suggCardBg)
            setStroke(dp(1.2f).coerceAtLeast(1), strokeCol)
        }
        elevation = 15f * density
        setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        isClickable = true
        isFocusable = true
        setOnClickListener { }
        visibility = View.INVISIBLE
    }

    val tvSuggHeader = TextView(ctx).apply {
        text = "💡 Matching Project Files & Folders — Tap to open"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(strokeCol)
        setPadding(dp(3f), 0, dp(3f), dp(4f))
    }
    suggestionsPanel.addView(tvSuggHeader)

    val suggScroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val suggListContainer = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    suggScroll.addView(suggListContainer)
    suggestionsPanel.addView(suggScroll)

    overlay.addView(
        suggestionsPanel,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
    )
    overlay.addView(
        searchBarCard,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(36f)
        )
    )
    outerFrame.addView(
        overlay,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    )
    drawerFileSearchOverlay = overlay
    btnDrawerSearchFiles?.isAccentKey = true

    var isEntranceRunning = true
    var lastKnownImeBottom = 0

    fun computeImeBottomPx(): Int {
        val rootH = if (outerFrame.height > 0) outerFrame.height else resources.displayMetrics.heightPixels
        val insetsIme = ViewCompat.getRootWindowInsets(outerFrame)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val rect = Rect()
        outerFrame.getWindowVisibleDisplayFrame(rect)
        val rootLoc = IntArray(2)
        outerFrame.getLocationOnScreen(rootLoc)
        val visibleBottomInRoot = rect.bottom - rootLoc[1]
        val frameIme = if (rect.bottom > rect.top && visibleBottomInRoot in 1..rootH) {
            (rootH - visibleBottomInRoot).coerceAtLeast(0)
        } else 0
        return maxOf(insetsIme, if (frameIme > rootH * 0.14f) frameIme else 0)
    }

    fun resolveTargetBounds(): Triple<Int, Int, Int> {
        val rootW = if (outerFrame.width > 0) outerFrame.width else resources.displayMetrics.widthPixels
        val rootLoc = IntArray(2)
        outerFrame.getLocationInWindow(rootLoc)
        val row = filesHeaderActionRow
        return if (row != null && row.width > 0) {
            val rowLoc = IntArray(2)
            row.getLocationInWindow(rowLoc)
            val tLeft = (rowLoc[0] - rootLoc[0]).coerceIn(dp(8f), (rootW - dp(120f)).coerceAtLeast(dp(8f)))
            val tWidth = row.width.coerceIn(dp(180f), rootW - tLeft - dp(8f))
            val tTop = (rowLoc[1] - rootLoc[1]).coerceAtLeast(dp(42f))
            Triple(tLeft, tWidth, tTop)
        } else {
            Triple(dp(12f), dp(292f).coerceAtMost(rootW - dp(24f)), dp(220f))
        }
    }

    fun updateVerticalLayoutForKeyboard(targetLeft: Int, targetWidth: Int, naturalRowTop: Int) {
        if (drawerFileSearchOverlay !== overlay) return
        val rootH = if (outerFrame.height > 0) outerFrame.height else resources.displayMetrics.heightPixels
        val imeBottom = computeImeBottomPx()
        lastKnownImeBottom = imeBottom

        val visibleBottom = (rootH - imeBottom - dp(8f)).coerceIn(dp(150f), rootH - dp(8f))
        val minTopLimit = dp(42f)
        val barH = dp(36f)
        val gap = dp(6f)

        val availForSugg = (visibleBottom - minTopLimit - barH - gap).coerceIn(dp(72f), dp(230f))
        suggestionsPanel.measure(
            View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(availForSugg, View.MeasureSpec.AT_MOST)
        )
        val suggH = suggestionsPanel.measuredHeight.coerceIn(dp(64f), availForSugg)
        val totalNeeded = barH + gap + suggH

        // Clamp so searchBarCard + suggestionsPanel are never hidden behind the virtual keyboard
        val desiredBarTop = if (imeBottom > dp(48f)) {
            minOf(naturalRowTop, visibleBottom - totalNeeded).coerceIn(minTopLimit, (visibleBottom - barH - dp(4f)).coerceAtLeast(minTopLimit))
        } else {
            minOf(naturalRowTop, visibleBottom - totalNeeded).coerceAtLeast(minTopLimit)
        }
        val desiredSuggTop = (desiredBarTop + barH + gap).coerceAtMost((visibleBottom - suggH).coerceAtLeast(desiredBarTop + barH + dp(2f)))

        val barLp = searchBarCard.layoutParams as FrameLayout.LayoutParams
        if (!isEntranceRunning) {
            barLp.leftMargin = targetLeft
            barLp.width = targetWidth
        }
        barLp.topMargin = desiredBarTop
        barLp.height = barH
        searchBarCard.layoutParams = barLp

        val suggLp = suggestionsPanel.layoutParams as FrameLayout.LayoutParams
        suggLp.leftMargin = targetLeft
        suggLp.topMargin = desiredSuggTop
        suggLp.width = targetWidth
        suggLp.height = suggH
        suggestionsPanel.layoutParams = suggLp
    }

    fun populateFileSuggestions(query: String, animateRows: Boolean) {
        val matches = queryProjectFilesAndFoldersSuggestions(ctx, query, limit = 24)
        btnClearText.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
        tvCountBadge.text = "${matches.size}"
        tvSuggHeader.text = if (query.isBlank()) {
            "💡 Files & Folders in [$activeProjectName] (${matches.size}) — Tap to open"
        } else {
            "💡 ${matches.size} match${if (matches.size == 1) "" else "es"} for \"${query.trim()}\" — Tap to open"
        }

        suggListContainer.removeAllViews()
        if (matches.isEmpty()) {
            val tvEmpty = TextView(ctx).apply {
                text = "No matching .math files or folders in [$activeProjectName] for \"${query.trim()}\"."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.8f)
                typeface = Typeface.MONOSPACE
                setTextColor(secondaryText)
                setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
            }
            suggListContainer.addView(tvEmpty)
        } else {
            matches.forEachIndexed { idx, item ->
                val rowDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 9f * density
                    setColor(if (item.isFolder) folderRowBgColor else rowBgColor)
                    setStroke(1, if (item.isFolder) strokeCol else subStrokeCol)
                }
                val rowCard = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rowDrawable
                    setPadding(dp(9f), dp(6f), dp(9f), dp(6f))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        if (idx < matches.lastIndex) bottomMargin = dp(4f)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        springPulse(this, 0.05f)
                        if (item.isFolder && item.folderPath != null) {
                            val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
                            state.collapsedFolders.remove(item.folderPath)
                            saveProjectFolderState(ctx, activeProjectName)
                            drawerFileSearchQuery = ""
                            dismissDrawerFileSearch()
                            refreshFileManagerDrawer()
                            act.showButtonToast("Opened folder /${item.folderPath}")
                        } else if (item.fileName != null) {
                            expandAncestorFoldersForFile(ctx, item.fileName, activeProjectName)
                            drawerFileSearchQuery = ""
                            dismissDrawerFileSearch()
                            openModuleFile(item.fileName, closeDrawerAfter = true)
                        }
                    }
                }

                val topRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val tvTitle = TextView(ctx).apply {
                    text = item.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.6f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(primaryText)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val tvBadge = TextView(ctx).apply {
                    text = item.badge
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(strokeCol)
                    setPadding(dp(5f), 0, 0, 0)
                }
                topRow.addView(tvTitle)
                topRow.addView(tvBadge)
                rowCard.addView(topRow)

                val tvSub = TextView(ctx).apply {
                    text = item.subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
                    typeface = Typeface.MONOSPACE
                    setTextColor(secondaryText)
                    maxLines = 1
                    setPadding(0, dp(1f), 0, 0)
                }
                rowCard.addView(tvSub)
                suggListContainer.addView(rowCard)
            }
        }

        val (tLeft, tWidth, tTop) = resolveTargetBounds()
        updateVerticalLayoutForKeyboard(tLeft, tWidth, tTop)

        if (animateRows && suggListContainer.childCount > 0) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300L
                interpolator = LinearInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    val t = f * 0.45
                    val pulse = (Math.exp(-7.5 * t) * Math.sin(t * 22.0)).toFloat()
                    val spring = (Math.exp(-7.5 * t) * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                    suggListContainer.translationY = (5f * density) * spring
                    suggListContainer.scaleY = 1f + 0.015f * pulse
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        suggListContainer.translationY = 0f
                        suggListContainer.scaleY = 1f
                    }
                })
                start()
                drawerFileSearchAnimators.add(this)
            }
        }
    }

    etSearch.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val q = s?.toString().orEmpty()
            drawerFileSearchQuery = q
            refreshFileManagerDrawer()
            populateFileSuggestions(q, animateRows = true)
        }
    })

    etSearch.setOnEditorActionListener { _, actionId, event ->
        val isEnter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
        if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
            val topMatch = queryProjectFilesAndFoldersSuggestions(ctx, etSearch.text?.toString().orEmpty(), limit = 1).firstOrNull()
            if (topMatch != null && !etSearch.text.isNullOrBlank()) {
                if (!topMatch.isFolder && topMatch.fileName != null) {
                    expandAncestorFoldersForFile(ctx, topMatch.fileName, activeProjectName)
                    drawerFileSearchQuery = ""
                    dismissDrawerFileSearch()
                    openModuleFile(topMatch.fileName, closeDrawerAfter = true)
                    return@setOnEditorActionListener true
                }
            }
            dismissDrawerFileSearch()
            true
        } else false
    }

    ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, windowInsets ->
        val (tLeft, tWidth, tTop) = resolveTargetBounds()
        updateVerticalLayoutForKeyboard(tLeft, tWidth, tTop)
        windowInsets
    }

    val globalListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (drawerFileSearchOverlay !== overlay || !isAdded) return@OnGlobalLayoutListener
        val curIme = computeImeBottomPx()
        if (curIme != lastKnownImeBottom) {
            val (tLeft, tWidth, tTop) = resolveTargetBounds()
            updateVerticalLayoutForKeyboard(tLeft, tWidth, tTop)
        }
    }
    drawerFileSearchLayoutListener = globalListener
    overlay.viewTreeObserver.addOnGlobalLayoutListener(globalListener)

    fun startHorizontalExpandPhysics() {
        if (drawerFileSearchOverlay !== overlay) return
        populateFileSuggestions(drawerFileSearchQuery, animateRows = false)

        val rootLoc = IntArray(2)
        outerFrame.getLocationInWindow(rootLoc)
        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val (targetLeft, targetWidth, naturalRowTop) = resolveTargetBounds()

        val startWidth = anchor.width.takeIf { it > 0 } ?: dp(36f)
        val startLeft = (anchorLoc[0] - rootLoc[0]).coerceIn(targetLeft, (targetLeft + targetWidth - startWidth).coerceAtLeast(targetLeft))

        updateVerticalLayoutForKeyboard(targetLeft, targetWidth, naturalRowTop)

        val barLp = searchBarCard.layoutParams as FrameLayout.LayoutParams
        barLp.leftMargin = startLeft
        barLp.width = startWidth
        searchBarCard.layoutParams = barLp
        searchBarCard.visibility = View.VISIBLE
        suggestionsPanel.visibility = View.VISIBLE
        suggestionsPanel.pivotX = targetWidth / 2f
        suggestionsPanel.pivotY = 0f

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 430L
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val t = f * 0.48
                val decay = Math.exp(-7.5 * t)
                val springFactor = (decay * (Math.cos(t * 22.0) + (7.5 / 22.0) * Math.sin(t * 22.0))).toFloat()
                val pulse = (decay * Math.sin(t * 22.0)).toFloat()
                val expandProgress = 1f - springFactor

                val curLeft = (startLeft + (targetLeft - startLeft) * expandProgress).toInt()
                val curW = (startWidth + (targetWidth - startWidth) * expandProgress).toInt().coerceAtLeast(dp(36f))

                val lp = searchBarCard.layoutParams as FrameLayout.LayoutParams
                lp.leftMargin = curLeft
                lp.width = curW
                searchBarCard.layoutParams = lp
                searchBarCard.scaleY = 1f + 0.045f * pulse
                searchBarCard.alpha = (f / 0.18f).coerceIn(0f, 1f)

                val suggScale = (1.0 - 0.32 * Math.exp(-4.0 * f) * Math.cos(f * 3.0 * Math.PI)).toFloat()
                suggestionsPanel.scaleX = suggScale
                suggestionsPanel.scaleY = suggScale
                suggestionsPanel.alpha = (f / 0.24f).coerceIn(0f, 1f)
                suggestionsPanel.translationY = (-10f * density) * (1f - f)

                frostView?.alpha = 0.90f * (f / 0.28f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isEntranceRunning = false
                    searchBarCard.scaleY = 1f
                    searchBarCard.alpha = 1f
                    suggestionsPanel.scaleX = 1f
                    suggestionsPanel.scaleY = 1f
                    suggestionsPanel.translationY = 0f
                    suggestionsPanel.alpha = 1f
                    val (tL, tW, tT) = resolveTargetBounds()
                    updateVerticalLayoutForKeyboard(tL, tW, tT)
                }
            })
            start()
        }
        drawerFileSearchAnimators.add(anim)

        etSearch.requestFocus()
        etSearch.post {
            runCatching {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    if (outerFrame.width > 0 && anchor.width > 0) {
        startHorizontalExpandPhysics()
    } else {
        overlay.post { startHorizontalExpandPhysics() }
    }
}

internal fun MathWorkspaceFragment.dismissDrawerFileSearch() {
    drawerFileSearchAnimators.forEach { it.cancel() }
    drawerFileSearchAnimators.clear()
    hideSoftKeyboardFrom(drawerFileSearchEditText)
    drawerFileSearchEditText = null

    Frost.clearLiveBlur(fileManagerDrawerPanel)

    val overlay = drawerFileSearchOverlay ?: return
    drawerFileSearchOverlay = null
    drawerFileSearchLayoutListener?.let { l ->
        if (overlay.viewTreeObserver.isAlive) overlay.viewTreeObserver.removeOnGlobalLayoutListener(l)
    }
    drawerFileSearchLayoutListener = null
    btnDrawerSearchFiles?.isAccentKey = drawerFileSearchQuery.isNotBlank()

    val anchor = btnDrawerSearchFiles
    val outerFrame = workspaceRootFrame
    val barCard = overlay.getChildAt(overlay.childCount - 1)
    val suggCard = if (overlay.childCount >= 2) overlay.getChildAt(overlay.childCount - 2) else null

    for (i in 0 until (overlay.childCount - 2).coerceAtLeast(0)) {
        overlay.getChildAt(i)?.animate()?.alpha(0f)?.setDuration(175L)?.start()
    }
    suggCard?.animate()
        ?.scaleX(0.72f)?.scaleY(0.72f)?.alpha(0f)
        ?.setDuration(175L)
        ?.setInterpolator(AccelerateInterpolator(0.85f))
        ?.start()

    if (barCard != null && anchor != null && outerFrame != null && barCard.width > 0) {
        val rootLoc = IntArray(2)
        val anchorLoc = IntArray(2)
        outerFrame.getLocationInWindow(rootLoc)
        anchor.getLocationInWindow(anchorLoc)
        val lp = barCard.layoutParams as FrameLayout.LayoutParams
        val startLeft = lp.leftMargin
        val startW = barCard.width
        val endLeft = anchorLoc[0] - rootLoc[0]
        val endW = anchor.width.coerceAtLeast(1)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 185L
            interpolator = AccelerateInterpolator(0.85f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                lp.leftMargin = (startLeft + (endLeft - startLeft) * f).toInt()
                lp.width = (startW + (endW - startW) * f).toInt().coerceAtLeast(endW)
                barCard.layoutParams = lp
                barCard.alpha = 1f - f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                }
            })
            start()
        }
    } else {
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }
}

// =========================================================================
//  Module Chips, Editor Loading & Module Lifecycle Operations
// =========================================================================

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

    val folderState = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    openModuleFiles.forEachIndexed { targetIdx, modName ->
        val isSelected = (modName == activeModuleFileName)
        val folderPrefix = folderState.fileToFolder[modName]?.takeIf { it.isNotEmpty() }?.let { "${it.substringAfterLast('/')}/" }.orEmpty()
        val displayChipText = "🧩 $folderPrefix$modName"
        val chip = NeumorphicKeyView(ctx).apply {
            text = displayChipText
            textSize = 11f
            cornerRadiusDp = 11f
            shadowPadDp = 2.6f
            isAccentKey = isSelected
            isDarkThemeMode = activity.isDarkMode
            val w = ((folderPrefix.length + modName.length) * 7.6f + 42f).coerceAtLeast(104f)
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
        val folderState = ensureProjectFolderStateLoaded(ctx, activeProjectName)
        val folderPrefix = folderState.fileToFolder[modFileName]?.takeIf { it.isNotEmpty() }?.let { "$it/" }.orEmpty()
        tvActiveModuleName?.text = "Project: [$activeProjectName]  •  File: $folderPrefix$modFileName  •  Module: [$modId]$depsSuffix"
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
        if (activeProjectName == MathEngineManager.DEFAULT_PROJECT_NAME) {
            MathEngineManager.updateLiveProject1Override(modFile, code)
        }
        etModuleEditor?.updateProjectAutocompleteContext(
            collectAllProjectModuleSources(ctx, modFile, code)
        )
        val modId = MathEngineManager.deriveModuleName(modFile, code)
        val deps = MathEngineManager.extractModuleDependencies(code)
        val depsSuffix = if (deps.isNotEmpty()) "  •  <[${deps.joinToString(", ")}]" else ""
        val folderState = ensureProjectFolderStateLoaded(ctx, activeProjectName)
        val folderPrefix = folderState.fileToFolder[modFile]?.takeIf { it.isNotEmpty() }?.let { "$it/" }.orEmpty()
        tvActiveModuleName?.text = "Project: [$activeProjectName]  •  File: $folderPrefix$modFile  •  Module: [$modId]$depsSuffix"
        if (showToast) {
            tvModuleQuickStatus?.text = "💾 Saved module '$folderPrefix$modFile' ([$modId])."
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
    expandAncestorFoldersForFile(ctx, fileName, activeProjectName)
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
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    val currentFolder = state.fileToFolder[targetName].orEmpty()
    val baseStem = targetName.removeSuffix(".math")
    showFrostedFileNamingModal(
        badgeIcon = "✏️",
        title = "Rename or Copy Module",
        subtitle = "Module '$targetName'${if (currentFolder.isNotEmpty()) " in /$currentFolder" else ""} • Executable .math Module",
        description = "Enter a new name (or 'folder/name.math' to place in a folder) to rename '$targetName', or tap 'Save Copy' to duplicate it.",
        initialText = if (currentFolder.isNotEmpty()) "$currentFolder/$targetName" else targetName,
        hintText = "${baseStem}_v2.math",
        suggestions = listOf(
            "${baseStem}_v2.math",
            "${baseStem}_copy.math",
            "${baseStem}_ext.math"
        ),
        primaryButtonLabel = "Rename",
        onPrimary = { rawInput ->
            val rawTrim = rawInput.trim().replace('\\', '/')
            if (rawTrim.isNotEmpty()) {
                val folderPart = if (rawTrim.contains('/')) normalizeFolderPath(rawTrim.substringBeforeLast('/')) else currentFolder
                var newName = rawTrim.substringAfterLast('/').trim()
                if (newName.isNotEmpty()) {
                    if (!newName.endsWith(".math")) newName += ".math"
                    val dir = modulesDir(ctx)
                    val srcFile = File(dir, targetName)
                    val dstFile = File(dir, newName)
                    if (srcFile.exists()) {
                        if (newName != targetName) {
                            dstFile.writeText(srcFile.readText())
                            srcFile.delete()
                            state.fileToFolder.remove(targetName)
                            val idx = openModuleFiles.indexOf(targetName)
                            if (idx >= 0) openModuleFiles[idx] = newName
                            if (activeModuleFileName == targetName) activeModuleFileName = newName
                        }
                        if (folderPart.isNotEmpty()) {
                            addFolderWithAncestors(state, folderPart)
                            state.fileToFolder[newName] = folderPart
                        } else if (!rawTrim.contains('/')) {
                            state.fileToFolder.remove(newName)
                        }
                        saveProjectFolderState(ctx, activeProjectName)
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
            val rawTrim = rawInput.trim().replace('\\', '/')
            if (rawTrim.isNotEmpty()) {
                val folderPart = if (rawTrim.contains('/')) normalizeFolderPath(rawTrim.substringBeforeLast('/')) else currentFolder
                var copyName = rawTrim.substringAfterLast('/').trim()
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
                        if (folderPart.isNotEmpty()) {
                            addFolderWithAncestors(state, folderPart)
                            state.fileToFolder[copyName] = folderPart
                            saveProjectFolderState(ctx, activeProjectName)
                        }
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
            val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
            if (state.fileToFolder.remove(fileName) != null) {
                saveProjectFolderState(ctx, activeProjectName)
            }
            if (activeProjectName == MathEngineManager.DEFAULT_PROJECT_NAME) {
                MathEngineManager.invalidateProject1CalculatorLink(fileName)
            }
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
    results: List<Pair<String, EvalOutcome>>,
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

    val exprValueLines = results.mapNotNull { (stmt, outcome) ->
        if (outcome.ok && outcome.type != "void" && !MathEngineManager.isDeclarationOrBindingStatement(stmt)) {
            val oneLine = stmt.replace(Regex("\\s+"), " ").trim()
            val u = if (outcome.unit != "none") " [${outcome.unit}]" else ""
            "$oneLine  ⟹  ${outcome.value}$u"
        } else null
    }
    val scopedFileKey = projectScopedKey(targetFile)
    val scopedModKey = projectScopedKey(modId)
    val fullLog = sb.toString().trimEnd()
    val rawConsole = consoleSb.toString().trimEnd()
    val consoleText = when {
        rawConsole.isNotEmpty() && exprValueLines.isNotEmpty() ->
            "$rawConsole\n\n--- Top-Level Expression Results ---\n" + exprValueLines.joinToString("\n")
        rawConsole.isNotEmpty() -> rawConsole
        exprValueLines.isNotEmpty() ->
            exprValueLines.joinToString("\n")
        else -> "(No print / println or standalone expression output emitted)"
    }
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

internal fun MathWorkspaceFragment.promptNewModuleDialog(targetFolder: String? = null) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    val state = ensureProjectFolderStateLoaded(ctx, activeProjectName)
    val nextNum = listModuleFiles(ctx).size + 1
    val defaultName = "module_${nextNum}.math"
    val folderPrefix = targetFolder?.takeIf { it.isNotBlank() }?.let { "${normalizeFolderPath(it)}/" }.orEmpty()

    showFrostedFileNamingModal(
        badgeIcon = "🧩",
        title = if (folderPrefix.isEmpty()) "New .math Module" else "New .math Module in /$targetFolder",
        subtitle = "Top-to-Bottom Executable [module] • Auto-appends .math",
        description = "Creates a top-to-bottom executable .math module. You can also prefix with 'folder/' (e.g. simulations/$defaultName) to place it in an app-side folder.",
        initialText = folderPrefix,
        hintText = "$folderPrefix$defaultName",
        suggestions = listOf(
            "$folderPrefix$defaultName",
            "${folderPrefix}custom_ops.math",
            "${folderPrefix}physics_lab.math",
            "${folderPrefix}finance_fx.math"
        ),
        primaryButtonLabel = "✨ Create & Open",
        onPrimary = { rawInput ->
            val rawTrim = rawInput.trim().replace('\\', '/')
            val chosenFolder = when {
                rawTrim.contains('/') -> normalizeFolderPath(rawTrim.substringBeforeLast('/'))
                !targetFolder.isNullOrBlank() -> normalizeFolderPath(targetFolder)
                else -> ""
            }
            var name = rawTrim.substringAfterLast('/').trim()
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
                    "# @desc Computes the scaled sum of squares (a² + b²) × SCALE.\n" +
                    "# @param a: real — First numeric operand\n" +
                    "# @param b: real — Second numeric operand\n" +
                    "# @return real — Scaled sum of squares (a² + b²) · SCALE\n" +
                    "pub square_sum(a: real, b: real) = (a^2 + b^2) * SCALE\n\n" +
                    "square_sum(base_x, 5)\n"
                )
            }
            if (chosenFolder.isNotEmpty()) {
                addFolderWithAncestors(state, chosenFolder)
                state.fileToFolder[name] = chosenFolder
                state.collapsedFolders.remove(chosenFolder)
                saveProjectFolderState(ctx, activeProjectName)
            }
            if (activeProjectName == MathEngineManager.DEFAULT_PROJECT_NAME) {
                MathEngineManager.updateLiveProject1Override(name, runCatching { newFile.readText() }.getOrNull())
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
            val locSuffix = if (chosenFolder.isNotEmpty()) " in /$chosenFolder" else " in $activeProjectName"
            activity.showButtonToast("Created module $name$locSuffix")
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
        saveCurrentModuleEditorState(showToast = false)
        if (closeDrawerAfter && isFileManagerDrawerOpen) {
            toggleFileManagerDrawer(open = false, animate = true)
        }
        return
    }

    // Save current editor buffer & close the outgoing project cleanly
    val outgoingProject = activeProjectName
    saveCurrentModuleEditorState(showToast = false)
    etModuleEditor?.dismissSuggestionsPopup()
    if (drawerFileSearchOverlay != null) {
        dismissDrawerFileSearch()
    }
    drawerFileSearchQuery = ""
    projectOpenModulesMap[outgoingProject] = openModuleFiles.toMutableList()
    projectActiveFileMap[outgoingProject] = activeModuleFileName
    openModuleFiles.clear()
    activeModuleFileName = null

    // Activate target project and flush outgoing project's modules on the shared engine
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
    tvModuleQuickStatus?.text = "📦 Switched to project '$targetProject' (recycled shared engine • stdlib+patches ${pStat.stdlibMathcBytes / 1024} KB)."
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
        subtitle = "Recyclable Project Workspace • Shared Engine (O(1) Module Flush)",
        description = "Creates a new project folder in math_projects/. Switching or running projects flushes previous user modules on the single shared engine in O(1) time.",
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
                    "# @desc Evaluates the quadratic module transfer function (v² + 2v) × PROJECT_SCALE.\n" +
                    "# @param v: real — Input scalar value v\n" +
                    "# @return real — Computed value (v² + 2v) · PROJECT_SCALE\n" +
                    "pub compute(v: real) = (v^2 + 2 * v) * PROJECT_SCALE\n\n" +
                    "pub main() = {\n" +
                    "    compute(x0)\n" +
                    "}\n"
                )
            }
            switchToProject(projName, closeDrawerAfter = false)
            activity.showButtonToast("Created project $projName")
        },
        extBadgeText = "📦 proj"
    )
}

internal fun MathWorkspaceFragment.promptRenameOrCopyProjectDialog(sourceProj: String) {
    val ctx = context ?: return
    val activity = activity as? MainActivity ?: return
    saveCurrentModuleEditorState(showToast = false)
    showFrostedFileNamingModal(
        badgeIcon = "📦",
        title = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) "Copy Project '$sourceProj'" else "Rename or Copy Project",
        subtitle = "Project [$sourceProj] • Clones .math, folders & data files",
        description = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) {
            "Default project1 holds the bundled Studio demos & examples. Enter a name below to clone project1 into a new project folder."
        } else {
            "Enter a new name to rename '$sourceProj', or tap 'Save Copy' to clone its .math modules, folders, and data files."
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
            if (!copyOnly) projectFolderStates.remove(sourceProj)
            projectFolderStates.remove(dstProj)
            switchToProject(dstProj, closeDrawerAfter = false)
            activity.showButtonToast(if (copyOnly) "Cloned to $dstProj" else "Renamed project to $dstProj")
        },
        secondaryButtonLabel = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) null else "Save Copy",
        onSecondary = if (sourceProj == MathEngineManager.DEFAULT_PROJECT_NAME) null else { rawInput ->
            val dstProj = MathEngineManager.renameOrCopyProject(ctx, sourceProj, rawInput, copyOnly = true)
            projectFolderStates.remove(dstProj)
            switchToProject(dstProj, closeDrawerAfter = false)
            activity.showButtonToast("Copied project to $dstProj")
        },
        extBadgeText = "📦 proj"
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
            tvModuleQuickStatus?.text = "↺ Flushed user modules on shared engine back to clean stdlib + patches baseline."
            activity.showButtonToast(
                if (ok) "Flushed user modules for $projectName"
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
        subtitle = "Project [$projectName] • Shared Recyclable Engine",
        message = if (isDefault) {
            "Restore '$projectName' to its default demos/examples and flush user modules on the shared engine?"
        } else {
            "Permanently delete project '$projectName' and its .math modules?"
        },
        confirmLabel = if (isDefault) "Reset project1" else "Delete Project",
        onConfirm = {
            val nextProj = MathEngineManager.deleteProject(ctx, projectName)
            projectOpenModulesMap.remove(projectName)
            projectActiveFileMap.remove(projectName)
            projectFolderStates.remove(projectName)
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
