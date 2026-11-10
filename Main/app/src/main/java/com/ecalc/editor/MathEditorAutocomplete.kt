package com.ecalc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

internal fun MathCodeEditText.extractTypingPrefix(textStr: CharSequence, cursor: Int): Pair<String, IntRange>? {
    if (cursor <= 0 || cursor > textStr.length) return null
    val lineStart = textStr.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
    val lineSlice = textStr.subSequence(lineStart, cursor).toString()

    // Ignore if typing inside a `#` comment, or inside quotes outside an `{expr}` interpolation block
    var inQuotes = false
    var interpBraceDepth = 0
    for (i in lineSlice.indices) {
        val c = lineSlice[i]
        if (c == '"' && (i == 0 || lineSlice[i - 1] != '\\')) {
            inQuotes = !inQuotes
            if (!inQuotes) interpBraceDepth = 0
        } else if (inQuotes) {
            if (c == '{') interpBraceDepth++
            else if (c == '}' && interpBraceDepth > 0) interpBraceDepth--
        } else if (c == '#') {
            return null
        }
    }
    if (inQuotes && interpBraceDepth <= 0) return null

    // Check for `<[dep` prefix at start of line
    val depMatch = Regex("^\\s*(<\\[[a-zA-Z0-9_]*)$").find(lineSlice)
    if (depMatch != null) {
        val tok = depMatch.groupValues[1]
        val startIdx = cursor - tok.length
        return tok to (startIdx until cursor)
    }

    // Check for `@directive`, `$type`, or identifier/operator prefix before cursor
    var start = cursor
    while (start > lineStart) {
        val ch = textStr[start - 1]
        if (ch.isLetterOrDigit() || ch == '_' || ch == '@' || ch == '$') {
            start--
            if (ch == '@' || ch == '$') break
        } else {
            break
        }
    }
    if (start >= cursor) return null
    val prefix = textStr.subSequence(start, cursor).toString()
    if (prefix.length == 1 && !prefix[0].isLetter() && prefix[0] != '@' && prefix[0] != '$') {
        return null
    }
    if (prefix[0].isDigit()) return null
    return prefix to (start until cursor)
}

internal fun MathCodeEditText.buildParamBulletsFromSignature(paramsRaw: String, roleHint: String): List<String> {
    val trimmed = paramsRaw.trim()
    if (trimmed.isEmpty()) {
        return listOf("None — Takes no parameters")
    }
    return trimmed.split(",").mapIndexed { idx, rawPart ->
        val part = rawPart.trim()
        val colonIdx = part.indexOf(':')
        if (colonIdx > 0) {
            val pName = part.substring(0, colonIdx).trim()
            val pType = part.substring(colonIdx + 1).trim().ifEmpty { "any" }
            "$pName: $pType — Argument #${idx + 1} ($roleHint)"
        } else {
            "$part: any — Argument #${idx + 1} ($roleHint)"
        }
    }
}

internal fun MathCodeEditText.collectLiveBufferSymbols(src: String, excludeRange: IntRange): List<MathCodeEditText.SuggestionItem> {
    val out = mutableListOf<MathCodeEditText.SuggestionItem>()
    val seen = HashSet<String>()
    val fnRegex = Regex("(?m)^\\s*(?:(?:pub|priv)\\s+)*(?:\\$?fn\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^()\"]*)\\)\\s*(?:->\\s*([^=:]+)|:\\s*([a-zA-Z0-9_,\\s]+))?(?::=|=)(?![=>])")
    val varRegex = Regex("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?::\\s*([a-zA-Z_][a-zA-Z0-9_<>,\\s]*))?(?::=|=)(?![=>])")

    for (m in fnRegex.findAll(src)) {
        if (m.range.first in excludeRange) continue
        val fn = m.groupValues[1]
        val params = m.groupValues[2].trim()
        val retType = m.groupValues.getOrNull(3)?.trim()?.ifEmpty { null }
            ?: m.groupValues.getOrNull(4)?.trim()?.ifEmpty { null }
            ?: "any"
        if (fn !in MathCodeEditText.KEYWORDS && seen.add(fn)) {
            out.add(
                MathCodeEditText.SuggestionItem(
                    matchKey = fn,
                    displayLabel = "$fn($params)",
                    insertText = "$fn()",
                    cursorBackOffset = if (params.isNotEmpty()) 1 else 0,
                    category = "fn",
                    detail = "Current module fn",
                    paramBullets = buildParamBulletsFromSignature(params, "Parameter of $fn"),
                    returnBullet = "$retType — Computed result of $fn(...) in active module"
                )
            )
        }
    }
    for (m in varRegex.findAll(src)) {
        if (m.range.first in excludeRange) continue
        val v = m.groupValues[1]
        val vType = m.groupValues.getOrNull(2)?.trim()?.ifEmpty { null } ?: "real | any"
        if (v !in MathCodeEditText.KEYWORDS && v !in MathCodeEditText.BUILTIN_TYPES && seen.add(v)) {
            out.add(
                MathCodeEditText.SuggestionItem(
                    matchKey = v,
                    displayLabel = v,
                    insertText = v,
                    cursorBackOffset = 0,
                    category = "var",
                    detail = "Current module var",
                    paramBullets = listOf("None — Local or module variable in current buffer"),
                    returnBullet = "$vType — Current bound value of $v"
                )
            )
        }
    }
    return out
}

internal fun MathCodeEditText.updateSuggestionsForCurrentCursor(editable: Editable) {
    if (selectionStart != selectionEnd || selectionStart < 0) {
        dismissSuggestionsPopup()
        return
    }
    val cursor = selectionStart
    val extracted = extractTypingPrefix(editable, cursor)
    if (extracted == null) {
        dismissSuggestionsPopup()
        return
    }
    val (prefix, range) = extracted
    val lowerPrefix = prefix.lowercase()

    val liveSymbols = collectLiveBufferSymbols(editable.toString(), range)
    val liveEngineCatalog = MATH_EDITOR_LIVE_ENGINE_SUGGESTIONS
    val combined = ArrayList<MathCodeEditText.SuggestionItem>(
        liveSymbols.size + projectCustomSuggestions.size + MathCodeEditText.STATIC_SUGGESTIONS.size + liveEngineCatalog.size
    )
    val seenDisplay = HashSet<String>()

    for (srcList in listOf(liveSymbols, projectCustomSuggestions, MathCodeEditText.STATIC_SUGGESTIONS, liveEngineCatalog)) {
        for (item in srcList) {
            val keyLower = item.matchKey.lowercase()
            val matches = if (lowerPrefix.startsWith("<[") || lowerPrefix.startsWith("@") || lowerPrefix.startsWith("$")) {
                keyLower.startsWith(lowerPrefix)
            } else {
                !keyLower.startsWith("<[") &&
                    (keyLower.startsWith(lowerPrefix) || (lowerPrefix.length >= 2 && keyLower.contains(lowerPrefix))) &&
                    !(item.category != "op" && item.insertText == prefix)
            }
            if (matches && seenDisplay.add(item.displayLabel)) {
                combined.add(item)
            }
        }
    }

    if (combined.isEmpty()) {
        dismissSuggestionsPopup()
        return
    }

    // Rank exact/prefix matches on operators ("into", "in", "by", ...) and keywords at the top
    combined.sortWith(
        compareBy<MathCodeEditText.SuggestionItem> { !it.matchKey.lowercase().startsWith(lowerPrefix) }
            .thenBy { item ->
                val isExact = item.matchKey.equals(prefix, ignoreCase = true)
                when {
                    isExact && (item.category == "op" || item.category == "kw" || item.category == "unit") -> -2
                    item.category == "op" && item.matchKey.lowercase().startsWith(lowerPrefix) -> -1
                    item.category == "entry" -> 0
                    item.category == "kw" || item.category == "dir" || item.category == "mod" -> 1
                    item.category == "unit" -> 2
                    item.category == "fn" || item.category == "var" || item.category == "proj" -> 3
                    item.category == "type" -> 4
                    else -> 5
                }
            }
            .thenBy { it.matchKey.length }
    )

    activePrefixRange = range
    showSuggestionsPopupWindow(combined.take(10), prefix)
}

/**
 * Captures and blurs the exact background region behind the held suggestion documentation
 * popup (`(leftPx, topPx, widthPx, heightPx)` in [sourceRoot] coordinates) so that ONLY
 * the held suggestion info popup has a frosted-glass background blur of whatever
 * suggestions list rows and editor code sit behind it, while the suggestions popup itself
 * remains a clean, non-blurred solid surface.
 */
internal fun captureDocPopupFrostBackdrop(
    sourceRoot: View,
    leftPx: Int,
    topPx: Int,
    widthPx: Int,
    heightPx: Int,
    extraPopupCard: View? = null
): Bitmap? {
    val rootW = if (sourceRoot.width > 0) sourceRoot.width else sourceRoot.resources.displayMetrics.widthPixels
    val rootH = if (sourceRoot.height > 0) sourceRoot.height else sourceRoot.resources.displayMetrics.heightPixels
    if (rootW <= 0 || rootH <= 0 || widthPx <= 0 || heightPx <= 0) return null
    return try {
        val downscale = 3
        val blurRadius = 5
        val padPx = blurRadius * downscale * 2
        val capLeft = (leftPx - padPx).coerceAtLeast(0)
        val capTop = (topPx - padPx).coerceAtLeast(0)
        val capRight = (leftPx + widthPx + padPx).coerceAtMost(rootW)
        val capBottom = (topPx + heightPx + padPx).coerceAtMost(rootH)
        val capW = (capRight - capLeft).coerceAtLeast(1)
        val capH = (capBottom - capTop).coerceAtLeast(1)

        val bw = (capW / downscale).coerceAtLeast(8)
        val bh = (capH / downscale).coerceAtLeast(8)
        val paddedBmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBmp)
        canvas.scale(bw.toFloat() / capW.toFloat(), bh.toFloat() / capH.toFloat())
        canvas.translate(-capLeft.toFloat(), -capTop.toFloat())

        // Draw the root view hierarchy (which includes the editor AND suggestionPopupCard
        // when suggestionPopupCard is attached to hostFrame). Keep suggestionPopupCard
        // visible so the held info popup blurs the suggestion rows & badges behind it.
        sourceRoot.draw(canvas)

        // If suggestionPopupCard is shown via the PopupWindow fallback (not a child of sourceRoot),
        // composite it at its screen offset so the info popup still blurs the suggestion rows behind it.
        if (extraPopupCard != null && extraPopupCard.isShown && extraPopupCard.parent !== sourceRoot) {
            val rootLoc = IntArray(2)
            val cardLoc = IntArray(2)
            sourceRoot.getLocationOnScreen(rootLoc)
            extraPopupCard.getLocationOnScreen(cardLoc)
            val dx = (cardLoc[0] - rootLoc[0]).toFloat()
            val dy = (cardLoc[1] - rootLoc[1]).toFloat()
            canvas.save()
            canvas.translate(dx, dy)
            extraPopupCard.draw(canvas)
            canvas.restore()
        }

        Frost.blurBitmapInPlace(paddedBmp, blurRadius)

        val sx = (((leftPx - capLeft).toFloat() / capW) * bw).toInt().coerceIn(0, (bw - 1).coerceAtLeast(0))
        val sy = (((topPx - capTop).toFloat() / capH) * bh).toInt().coerceIn(0, (bh - 1).coerceAtLeast(0))
        val sw = ((widthPx.toFloat() / capW) * bw).toInt().coerceIn(1, (bw - sx).coerceAtLeast(1))
        val sh = ((heightPx.toFloat() / capH) * bh).toInt().coerceIn(1, (bh - sy).coerceAtLeast(1))

        val cropped = Bitmap.createBitmap(paddedBmp, sx, sy, sw, sh)
        val smoothW = (widthPx / 2).coerceIn(16, 480)
        val smoothH = (heightPx / 2).coerceIn(16, 480)
        val upscaled = Bitmap.createScaledBitmap(cropped, smoothW, smoothH, true)
        Frost.blurBitmapInPlace(upscaled, 2)
        upscaled
    } catch (_: Throwable) {
        null
    }
}

internal fun applyRoundedOutlineClip(view: View, cornerRadiusPx: Float) {
    view.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, outline: Outline) {
            if (v.width > 0 && v.height > 0) {
                outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusPx)
            }
        }
    }
    view.clipToOutline = true
}

internal fun MathCodeEditText.ensureSuggestionPopupCreated(): PopupWindow {
    val dp = { v: Float -> (v * density).toInt() }
    if (suggestionPopupCard == null) {
        val cardFrame = FrameLayout(context).apply {
            elevation = dp(24f).toFloat()
            isClickable = true
            applyRoundedOutlineClip(this, 14f * density)
        }

        val contentWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val listScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val listCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        suggestionListContainer = listCol
        listScroll.addView(listCol)
        contentWrap.addView(listScroll)
        cardFrame.addView(contentWrap)
        suggestionPopupCard = cardFrame
    }

    suggestionPopup?.let { return it }
    val popupW = (resources.displayMetrics.widthPixels - dp(32f)).coerceIn(dp(240f), dp(360f))
    val pw = PopupWindow(suggestionPopupCard, popupW, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
        isFocusable = false
        isTouchable = true
        isOutsideTouchable = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
    suggestionPopup = pw
    return pw
}

internal fun MathCodeEditText.showSuggestionsPopupWindow(items: List<MathCodeEditText.SuggestionItem>, currentPrefix: String) {
    if (!isAttachedToWindow || width == 0 || height == 0) return
    ensureSuggestionPopupCreated()
    val card = suggestionPopupCard ?: return
    val container = suggestionListContainer ?: return
    val dp = { v: Float -> (v * density).toInt() }

    val dark = isDarkThemeMode
    // Solid (non-frosted) surface for the suggestions popup itself; only the held info popup uses frost blur
    val cardSolidBg = Color.parseColor(if (dark) "#1E2228" else "#F7F9FC")
    val rowBg = Color.parseColor(if (dark) "#2A2E36" else "#EAEFF7")
    val strokeCol = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
    val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#24292F")
    val secondaryText = Color.parseColor(if (dark) "#B0BAC6" else "#4E5762")

    val contentWrap = (card.getChildAt(0) as? LinearLayout)
    contentWrap?.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 14f * density
        setColor(cardSolidBg)
        setStroke(max(1, dp(1.3f)), strokeCol)
    }

    container.removeAllViews()
    dismissSuggestionDocPopup()

    // Header bar: [💡 Autocomplete "prefix"] + [✕]
    val hdrRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4f), 0, dp(4f), dp(4f))
    }
    val tvHdr = TextView(context).apply {
        text = "💡 Suggestions for \"$currentPrefix\" (tap: insert • hold: full args & returns)"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(strokeCol)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvDismiss = TextView(context).apply {
        text = "✕"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(secondaryText)
        setPadding(dp(6f), 0, dp(2f), 0)
        setOnClickListener { dismissSuggestionsPopup() }
    }
    hdrRow.addView(tvHdr)
    hdrRow.addView(tvDismiss)
    container.addView(hdrRow)

    val heldRowBg = Color.parseColor(if (dark) "#1D3638" else "#FCEAE6")
    val touchSlopPx = dp(10f).toFloat()

    items.forEach { item ->
        var isHoldingDoc = false
        var downX = 0f
        var downY = 0f
        val rowDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 9f * density
            setColor(rowBg)
        }
        lateinit var holdDocRunnable: Runnable
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3f) }
            background = rowDrawable
            isClickable = true
            isLongClickable = true
            holdDocRunnable = Runnable {
                if (isAttachedToWindow) {
                    isHoldingDoc = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    rowDrawable.setColor(heldRowBg)
                    rowDrawable.setStroke(max(1, dp(1f)), strokeCol)
                    showSuggestionDocPopup(this, item)
                }
            }
            setOnClickListener {
                if (!isHoldingDoc) {
                    applySuggestionItem(item)
                }
            }
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        isHoldingDoc = false
                        downX = event.x
                        downY = event.y
                        v.removeCallbacks(holdDocRunnable)
                        v.postDelayed(holdDocRunnable, 250L)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isHoldingDoc) {
                            val dx = kotlin.math.abs(event.x - downX)
                            val dy = kotlin.math.abs(event.y - downY)
                            if (dx > touchSlopPx || dy > touchSlopPx) {
                                v.removeCallbacks(holdDocRunnable)
                            }
                        } else {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.removeCallbacks(holdDocRunnable)
                        if (isHoldingDoc) {
                            dismissSuggestionDocPopup()
                            rowDrawable.setColor(rowBg)
                            rowDrawable.setStroke(0, Color.TRANSPARENT)
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                            isHoldingDoc = false
                            v.isPressed = false
                            return@setOnTouchListener true
                        }
                    }
                }
                false
            }
        }

        val badgeCol = when (item.category) {
            "entry", "dir" -> colIntrinsic
            "op", "kw" -> colKeyword
            "mod" -> colModule
            "type", "unit" -> colType
            "fn", "proj" -> colOperator
            else -> colNumber
        }

        val tvBadge = TextView(context).apply {
            text = item.category
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.2f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(badgeCol)
            setPadding(dp(5f), dp(2f), dp(5f), dp(2f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * density
                setColor(Color.parseColor(if (dark) "#1A1D21" else "#FFFFFF"))
                setStroke(1, badgeCol)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(7f) }
        }

        // Stack signature + multi-line detail cleanly so descriptions never get cut off
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvLabel = TextView(context).apply {
            text = item.displayLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.6f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(primaryText)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val retPreview = item.returnBullet.substringBefore("—").trim().let {
            if (it.isNotEmpty() && it != item.detail) "${item.detail} • returns $it" else item.detail
        }
        val tvDetail = TextView(context).apply {
            text = retPreview
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.6f)
            setTextColor(secondaryText)
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        textCol.addView(tvLabel)
        textCol.addView(tvDetail)
        row.addView(tvBadge)
        row.addView(textCol)
        container.addView(row)
    }

    val popupW = (resources.displayMetrics.widthPixels - dp(28f)).coerceIn(dp(244f), dp(364f))
    val maxPopupH = dp(236f)
    card.measure(
        View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(maxPopupH, View.MeasureSpec.AT_MOST)
    )
    val measuredH = card.measuredHeight.coerceIn(dp(68f), maxPopupH)

    // Compute cursor line coordinates in screen/window space
    val editLoc = IntArray(2)
    getLocationInWindow(editLoc)
    val visibleFrame = Rect()
    getWindowVisibleDisplayFrame(visibleFrame)
    val frameBottom = if (visibleFrame.bottom > visibleFrame.top + dp(120f)) {
        visibleFrame.bottom
    } else {
        resources.displayMetrics.heightPixels
    }

    val textLayout = layout
    var lineTopInEdit = dp(12f)
    var lineBottomInEdit = dp(34f)
    var primXInEdit = currentGutterWidthPx + dp(6f)
    if (textLayout != null) {
        val cur = selectionStart.coerceIn(0, text?.length ?: 0)
        val line = textLayout.getLineForOffset(cur)
        lineTopInEdit = (textLayout.getLineTop(line) - scrollY + paddingTop).coerceIn(0, height)
        lineBottomInEdit = (textLayout.getLineBottom(line) - scrollY + paddingTop).coerceIn(0, height)
        primXInEdit = (textLayout.getPrimaryHorizontal(cur) - scrollX + paddingLeft).toInt()
    }

    val hostFrame = findHostOverlayContainer()
    if (hostFrame != null) {
        val hostLoc = IntArray(2)
        hostFrame.getLocationInWindow(hostLoc)
        val hostW = if (hostFrame.width > 0) hostFrame.width else resources.displayMetrics.widthPixels
        val hostH = if (hostFrame.height > 0) hostFrame.height else resources.displayMetrics.heightPixels
        val visibleBottomInHost = (frameBottom - hostLoc[1]).coerceIn(dp(140f), hostH)

        val cursorScreenX = editLoc[0] + primXInEdit
        val lineTopInHost = editLoc[1] - hostLoc[1] + lineTopInEdit
        val lineBottomInHost = editLoc[1] - hostLoc[1] + lineBottomInEdit

        val leftMargin = (cursorScreenX - hostLoc[0]).coerceIn(
            dp(10f),
            (hostW - popupW - dp(10f)).coerceAtLeast(dp(10f))
        )
        val belowY = lineBottomInHost + dp(6f)
        val aboveY = lineTopInHost - measuredH - dp(6f)
        val topMargin = when {
            belowY + measuredH <= visibleBottomInHost - dp(6f) -> belowY
            aboveY >= dp(42f) -> aboveY
            else -> belowY.coerceIn(dp(42f), (visibleBottomInHost - measuredH - dp(6f)).coerceAtLeast(dp(42f)))
        }

        val lp = FrameLayout.LayoutParams(popupW, measuredH).apply {
            this.leftMargin = leftMargin
            this.topMargin = topMargin
        }
        if (card.parent !== hostFrame) {
            (card.parent as? ViewGroup)?.removeView(card)
            hostFrame.addView(card, lp)
        } else {
            card.layoutParams = lp
            card.bringToFront()
        }
        card.visibility = View.VISIBLE
        return
    }

    // Fallback to PopupWindow if no host FrameLayout is found
    val pw = suggestionPopup ?: return
    val screenX = (editLoc[0] + primXInEdit).coerceIn(
        dp(10f),
        (resources.displayMetrics.widthPixels - popupW - dp(10f)).coerceAtLeast(dp(10f))
    )
    val screenBelowY = editLoc[1] + lineBottomInEdit + dp(6f)
    val screenAboveY = editLoc[1] + lineTopInEdit - measuredH - dp(6f)
    val screenY = if (screenBelowY + measuredH <= frameBottom - dp(6f) || screenAboveY < dp(42f)) {
        screenBelowY
    } else {
        screenAboveY
    }
    runCatching {
        pw.width = popupW
        pw.height = measuredH
        if (pw.isShowing) {
            pw.update(screenX, screenY, popupW, measuredH)
        } else {
            pw.showAtLocation(this, Gravity.NO_GRAVITY, screenX, screenY)
        }
    }
}

/**
 * Displays a frosted-glass secondary elevated popup on top of the suggestions popup while a
 * suggestion item is held down, listing its full multi-line parameters and return value
 * descriptions without truncation. Only this held info window has a background frost blur
 * effect (blurring the suggestions popup and editor behind it); the suggestions popup itself
 * remains a solid surface. Disappears immediately on release.
 */
internal fun MathCodeEditText.showSuggestionDocPopup(anchorRow: View, item: MathCodeEditText.SuggestionItem) {
    dismissSuggestionDocPopup()
    val dp = { v: Float -> (v * density).toInt() }
    val dark = isDarkThemeMode

    // Translucent frosted-glass tint so the blurred background (suggestions + code) shines vividly through
    val cardTintBg = Color.parseColor(if (dark) "#58141820" else "#64F6F9FE")
    val secBg = Color.parseColor(if (dark) "#28222834" else "#30E4ECF7")
    val badgeBg = Color.parseColor(if (dark) "#52181C24" else "#78FFFFFF")
    val strokeCol = Color.parseColor(if (dark) "#26C6DA" else "#E58A7B")
    val subStrokeCol = Color.parseColor(if (dark) "#485262" else "#C8D4E3")
    val primaryText = Color.parseColor(if (dark) "#F5F8FC" else "#161A20")
    val secondaryText = Color.parseColor(if (dark) "#D0DCEB" else "#38414C")
    val textShadowCol = Color.parseColor(if (dark) "#B0000000" else "#90FFFFFF")

    val badgeCol = when (item.category) {
        "entry", "dir" -> colIntrinsic
        "op", "kw" -> colKeyword
        "mod" -> colModule
        "type", "unit" -> colType
        "fn", "proj" -> colOperator
        else -> colNumber
    }

    val docFrame = FrameLayout(context).apply {
        elevation = dp(38f).toFloat()
        applyRoundedOutlineClip(this, 13f * density)
    }
    val docFrostImg = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        alpha = 1.0f
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    docFrame.addView(docFrostImg)

    val docCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(11f), dp(9f), dp(11f), dp(9f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 13f * density
            setColor(cardTintBg)
            setStroke(max(1, dp(1.4f)), strokeCol)
        }
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }
    docFrame.addView(docCard)
    suggestionDocCard = docFrame

    // 1. Top title row: [category badge] + full multi-line signature + detail
    val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6f) }
    }
    val tvBadge = TextView(context).apply {
        text = item.category.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(badgeCol)
        setShadowLayer(2f * density, 0f, 1f * density, textShadowCol)
        setPadding(dp(5f), dp(2f), dp(5f), dp(2f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 5f * density
            setColor(badgeBg)
            setStroke(1, badgeCol)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(7f); topMargin = dp(1f) }
    }
    val titleCol = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvSig = TextView(context).apply {
        text = item.displayLabel
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(primaryText)
        setShadowLayer(2.5f * density, 0f, 1f * density, textShadowCol)
        // Multi-line enabled so long signatures never truncate
        isSingleLine = false
    }
    val tvShort = TextView(context).apply {
        text = item.detail
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
        setTextColor(strokeCol)
        setShadowLayer(2f * density, 0f, 1f * density, textShadowCol)
        isSingleLine = false
    }
    titleCol.addView(tvSig)
    titleCol.addView(tvShort)
    titleRow.addView(tvBadge)
    titleRow.addView(titleCol)
    docCard.addView(titleRow)

    val docScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val docScrollInner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    fun makeSectionBox(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8.5f * density
                setColor(secBg)
                setStroke(1, subStrokeCol)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5f) }
        }
    }

    fun makeBulletLine(bulletText: String, accentPrefix: Boolean = false): TextView {
        return TextView(context).apply {
            text = "• $bulletText"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.4f)
            typeface = Typeface.MONOSPACE
            setTextColor(if (accentPrefix) primaryText else secondaryText)
            setShadowLayer(2.2f * density, 0f, 1f * density, textShadowCol)
            // Full multi-line wrapping enabled so long parameter & return descriptions never get cut off
            isSingleLine = false
            setLineSpacing(dp(1.5f).toFloat(), 1.0f)
            setPadding(0, dp(2f), 0, dp(2f))
        }
    }

    // 2. Ordered Parameters Section
    val paramsBox = makeSectionBox()
    val tvParamsHdr = TextView(context).apply {
        text = "PARAMETERS / OPERANDS (IN ORDER)"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(strokeCol)
        setShadowLayer(2f * density, 0f, 1f * density, textShadowCol)
        setPadding(0, 0, 0, dp(2f))
    }
    paramsBox.addView(tvParamsHdr)

    val effectiveParams = item.paramBullets.ifEmpty {
        listOf("None — Takes no parameters")
    }
    effectiveParams.forEachIndexed { idx, pLine ->
        paramsBox.addView(makeBulletLine("${idx + 1}. $pLine", accentPrefix = true))
    }
    docScrollInner.addView(paramsBox)

    // 3. Returns Section
    val retBox = makeSectionBox().apply {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
    }
    val tvRetHdr = TextView(context).apply {
        text = "RETURNS"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(strokeCol)
        setShadowLayer(2f * density, 0f, 1f * density, textShadowCol)
        setPadding(0, 0, 0, dp(2f))
    }
    retBox.addView(tvRetHdr)
    val effectiveRet = item.returnBullet.ifBlank {
        "${item.category} — ${item.detail}"
    }
    retBox.addView(makeBulletLine(effectiveRet, accentPrefix = true))
    docScrollInner.addView(retBox)

    docScroll.addView(docScrollInner)
    docCard.addView(docScroll)

    val popupW = (resources.displayMetrics.widthPixels - dp(28f)).coerceIn(dp(250f), dp(368f))
    val maxDocH = dp(280f)
    docCard.measure(
        View.MeasureSpec.makeMeasureSpec(popupW, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(maxDocH, View.MeasureSpec.AT_MOST)
    )
    val measuredH = docCard.measuredHeight.coerceIn(dp(92f), maxDocH)

    val hostFrame = findHostOverlayContainer()
    if (hostFrame != null) {
        val hostLoc = IntArray(2)
        val rowLoc = IntArray(2)
        hostFrame.getLocationInWindow(hostLoc)
        anchorRow.getLocationInWindow(rowLoc)
        val hostW = if (hostFrame.width > 0) hostFrame.width else resources.displayMetrics.widthPixels
        val hostH = if (hostFrame.height > 0) hostFrame.height else resources.displayMetrics.heightPixels

        val rowLeftInHost = rowLoc[0] - hostLoc[0]
        val rowTopInHost = rowLoc[1] - hostLoc[1]
        val xPos = (rowLeftInHost + (anchorRow.width - popupW) / 2).coerceIn(
            dp(10f),
            (hostW - popupW - dp(10f)).coerceAtLeast(dp(10f))
        )
        val candidateAboveY = rowTopInHost - measuredH - dp(6f)
        val yPos = if (candidateAboveY >= dp(36f)) {
            candidateAboveY
        } else {
            (rowTopInHost + anchorRow.height + dp(6f)).coerceIn(
                dp(36f),
                (hostH - measuredH - dp(10f)).coerceAtLeast(dp(36f))
            )
        }

        // Capture Frosted Glass blur backdrop for the held doc popup with suggestionPopupCard
        // kept visible so the blurred backdrop reflects the suggestion rows & editor behind it.
        val croppedFrost = captureDocPopupFrostBackdrop(
            sourceRoot = hostFrame,
            leftPx = xPos,
            topPx = yPos,
            widthPx = popupW,
            heightPx = measuredH,
            extraPopupCard = suggestionPopupCard
        )
        docFrostImg.setImageBitmap(croppedFrost)
        Frost.tryLiveBlur(docFrostImg, 4.5f * density)

        val lp = FrameLayout.LayoutParams(popupW, measuredH).apply {
            leftMargin = xPos
            topMargin = yPos
        }
        hostFrame.addView(docFrame, lp)
        docFrame.bringToFront()
        return
    }

    val docPw = PopupWindow(docFrame, popupW, measuredH, false).apply {
        isFocusable = false
        isTouchable = false
        isOutsideTouchable = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
        elevation = dp(38f).toFloat()
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
    suggestionDocPopup = docPw

    val rowLoc = IntArray(2)
    anchorRow.getLocationOnScreen(rowLoc)
    val screenW = resources.displayMetrics.widthPixels
    val xPos = (rowLoc[0] + (anchorRow.width - popupW) / 2).coerceIn(dp(12f), (screenW - popupW - dp(12f)).coerceAtLeast(dp(12f)))
    val candidateAboveY = rowLoc[1] - measuredH - dp(6f)
    val yPos = if (candidateAboveY >= dp(36f)) {
        candidateAboveY
    } else {
        rowLoc[1] + anchorRow.height + dp(6f)
    }

    val activityCtx = run {
        var c: Context? = context
        while (c != null && c !is Activity) c = (c as? ContextWrapper)?.baseContext
        c
    }
    val contentRoot = activityCtx?.findViewById<View>(android.R.id.content)
    if (contentRoot != null) {
        val rootLoc = IntArray(2)
        contentRoot.getLocationOnScreen(rootLoc)
        val croppedFrost = captureDocPopupFrostBackdrop(
            sourceRoot = contentRoot,
            leftPx = xPos - rootLoc[0],
            topPx = yPos - rootLoc[1],
            widthPx = popupW,
            heightPx = measuredH,
            extraPopupCard = suggestionPopupCard
        )
        docFrostImg.setImageBitmap(croppedFrost)
        Frost.tryLiveBlur(docFrostImg, 4.5f * density)
    }

    runCatching {
        docPw.showAtLocation(this, Gravity.NO_GRAVITY, xPos, yPos)
    }
}

internal fun MathCodeEditText.applySuggestionItem(item: MathCodeEditText.SuggestionItem) {
    val editable = editableText ?: return
    val range = activePrefixRange ?: return
    val st = range.first.coerceIn(0, editable.length)
    val en = (range.last + 1).coerceIn(st, editable.length)
    dismissSuggestionsPopup()

    // For infix word operators ("into", "in", "by", "and", "or") without parens,
    // ensure clean single spaces before and after without duplicating existing spaces.
    val isBareWordOp = (item.category == "op" && !item.insertText.contains('('))
    val baseInsert = item.insertText.trim()
    val finalInsert = if (isBareWordOp) {
        val needLeadSpace = st > 0 && !editable[st - 1].isWhitespace() && editable[st - 1] != '(' && editable[st - 1] != '{' && editable[st - 1] != '['
        val needTrailSpace = en >= editable.length || (!editable[en].isWhitespace() && editable[en] != ')' && editable[en] != '}')
        buildString {
            if (needLeadSpace) append(' ')
            append(baseInsert)
            if (needTrailSpace) append(' ')
        }
    } else {
        item.insertText
    }

    isProgrammaticEdit = true
    try {
        editable.replace(st, en, finalInsert)
        val newCursor = (st + finalInsert.length - item.cursorBackOffset).coerceIn(0, editable.length)
        setSelection(newCursor)
        ensureCursorVisible()
    } finally {
        isProgrammaticEdit = false
    }
    requestFocus()
}
