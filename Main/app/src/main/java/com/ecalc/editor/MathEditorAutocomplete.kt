package com.ecalc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import android.view.View.MeasureSpec
import kotlin.math.max
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

internal fun MathCodeEditText.extractTypingPrefix(textStr: CharSequence, cursor: Int): Pair<String, IntRange>? {
    if (cursor <= 0 || cursor > textStr.length) return null
    val lineStart = textStr.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
    val lineSlice = textStr.subSequence(lineStart, cursor).toString()

    // Ignore if typing inside a `#` comment
    var inQuotes = false
    for (i in lineSlice.indices) {
        val c = lineSlice[i]
        if (c == '"' && (i == 0 || lineSlice[i - 1] != '\\')) inQuotes = !inQuotes
        else if (c == '#' && !inQuotes) return null
    }
    if (inQuotes) return null

    // Check for `<[dep` prefix at start of line
    val depMatch = Regex("^\\s*(<\\[[a-zA-Z0-9_]*)$").find(lineSlice)
    if (depMatch != null) {
        val tok = depMatch.groupValues[1]
        val startIdx = cursor - tok.length
        return tok to (startIdx until cursor)
    }

    // Check for `@directive`, `$type`, or identifier prefix before cursor
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

internal fun MathCodeEditText.collectLiveBufferSymbols(src: String, excludeRange: IntRange): List<SuggestionItem> {
    val out = mutableListOf<SuggestionItem>()
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
                SuggestionItem(
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
                SuggestionItem(
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
    val combined = ArrayList<SuggestionItem>(liveSymbols.size + projectCustomSuggestions.size + MathCodeEditText.STATIC_SUGGESTIONS.size)
    val seenDisplay = HashSet<String>()

    for (srcList in listOf(liveSymbols, projectCustomSuggestions, MathCodeEditText.STATIC_SUGGESTIONS)) {
        for (item in srcList) {
            val keyLower = item.matchKey.lowercase()
            val matches = if (lowerPrefix.startsWith("<[") || lowerPrefix.startsWith("@") || lowerPrefix.startsWith("$")) {
                keyLower.startsWith(lowerPrefix)
            } else {
                !keyLower.startsWith("<[") &&
                    (keyLower.startsWith(lowerPrefix) || (lowerPrefix.length >= 2 && keyLower.contains(lowerPrefix))) &&
                    item.insertText != prefix
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

    // Rank exact prefix matches above substring matches
    combined.sortWith(
        compareBy<SuggestionItem> { !it.matchKey.lowercase().startsWith(lowerPrefix) }
            .thenBy {
                when (it.category) {
                    "entry" -> 0
                    "fn", "var", "proj" -> 1
                    "kw", "dir", "mod" -> 2
                    "type" -> 3
                    else -> 4
                }
            }
            .thenBy { it.matchKey.length }
    )

    activePrefixRange = range
    showSuggestionsPopupWindow(combined.take(7), prefix)
}

internal fun MathCodeEditText.ensureSuggestionPopupCreated(): PopupWindow {
    val dp = { v: Float -> (v * density).toInt() }
    if (suggestionPopupCard == null) {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            elevation = dp(24f).toFloat()
            isClickable = true
        }
        suggestionPopupCard = card

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
        card.addView(listScroll)
    }

    suggestionPopup?.let { return it }
    val popupW = (resources.displayMetrics.widthPixels - dp(40f)).coerceIn(dp(230f), dp(340f))
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

internal fun MathCodeEditText.showSuggestionsPopupWindow(items: List<SuggestionItem>, currentPrefix: String) {
    if (!isAttachedToWindow || width == 0 || height == 0) return
    ensureSuggestionPopupCreated()
    val card = suggestionPopupCard ?: return
    val container = suggestionListContainer ?: return
    val dp = { v: Float -> (v * density).toInt() }

    val dark = isDarkThemeMode
    val cardBg = Color.parseColor(if (dark) "#23262B" else "#F7F9FC")
    val rowBg = Color.parseColor(if (dark) "#2C3037" else "#EDF1F7")
    val strokeCol = Color.parseColor(if (dark) "#13959B" else "#E58A7B")
    val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#24292F")
    val secondaryText = Color.parseColor(if (dark) "#9BA4B0" else "#57606A")

    card.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 14f * density
        setColor(cardBg)
        setStroke(max(1, dp(1.2f)), strokeCol)
    }

    container.removeAllViews()

    // Header bar: [💡 Autocomplete "prefix"] + [✕]
    val hdrRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4f), 0, dp(4f), dp(4f))
    }
    dismissSuggestionDocPopup()

    val tvHdr = TextView(context).apply {
        text = "💡 Suggestions for \"$currentPrefix\" (tap: insert • hold: params & returns)"
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
                        v.postDelayed(holdDocRunnable, 280L)
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
                    MotionEvent.ACTION_UP -> {
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
                    MotionEvent.ACTION_CANCEL -> {
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
            "kw" -> colKeyword
            "mod" -> colModule
            "type" -> colType
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

        val tvLabel = TextView(context).apply {
            text = item.displayLabel
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.8f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(primaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f)
        }

        val tvDetail = TextView(context).apply {
            text = item.detail
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
            setTextColor(secondaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply {
                marginStart = dp(6f)
            }
        }

        row.addView(tvBadge)
        row.addView(tvLabel)
        row.addView(tvDetail)
        container.addView(row)
    }

    val popupW = (resources.displayMetrics.widthPixels - dp(36f)).coerceIn(dp(230f), dp(344f))
    val maxPopupH = dp(220f)
    card.measure(
        MeasureSpec.makeMeasureSpec(popupW, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(maxPopupH, MeasureSpec.AT_MOST)
    )
    val measuredH = card.measuredHeight.coerceIn(dp(64f), maxPopupH)

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
 * Displays a secondary elevated popup on top of the suggestions popup while a suggestion item
 * is held down, listing its parameters and return value in an ordered bulleted format with a
 * concise single-line summary for each bullet item. Disappears immediately on release.
 */
internal fun MathCodeEditText.showSuggestionDocPopup(anchorRow: View, item: SuggestionItem) {
    dismissSuggestionDocPopup()
    val dp = { v: Float -> (v * density).toInt() }
    val dark = isDarkThemeMode

    val cardBg = Color.parseColor(if (dark) "#191C20" else "#FFFFFF")
    val secBg = Color.parseColor(if (dark) "#22262C" else "#F3F6FA")
    val strokeCol = Color.parseColor(if (dark) "#26C6DA" else "#E58A7B")
    val subStrokeCol = Color.parseColor(if (dark) "#353B45" else "#D8DEE9")
    val primaryText = Color.parseColor(if (dark) "#ECEFF4" else "#1F2328")
    val secondaryText = Color.parseColor(if (dark) "#A5B0C0" else "#57606A")

    val badgeCol = when (item.category) {
        "entry", "dir" -> colIntrinsic
        "kw" -> colKeyword
        "mod" -> colModule
        "type" -> colType
        "fn", "proj" -> colOperator
        else -> colNumber
    }

    val docCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(11f), dp(9f), dp(11f), dp(9f))
        elevation = dp(38f).toFloat()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 13f * density
            setColor(cardBg)
            setStroke(max(1, dp(1.4f)), strokeCol)
        }
    }
    suggestionDocCard = docCard

    // 1. Top title row: [category badge] + signature + short detail
    val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
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
        setPadding(dp(5f), dp(2f), dp(5f), dp(2f))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 5f * density
            setColor(secBg)
            setStroke(1, badgeCol)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(7f) }
    }
    val tvSig = TextView(context).apply {
        text = item.displayLabel
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(primaryText)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tvShort = TextView(context).apply {
        text = item.detail
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.8f)
        setTextColor(strokeCol)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6f) }
    }
    titleRow.addView(tvBadge)
    titleRow.addView(tvSig)
    titleRow.addView(tvShort)
    docCard.addView(titleRow)

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
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(1.5f), 0, dp(1.5f))
        }
    }

    // 2. Ordered Parameters Section
    val paramsBox = makeSectionBox()
    val tvParamsHdr = TextView(context).apply {
        text = "PARAMETERS (IN ORDER)"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(strokeCol)
        setPadding(0, 0, 0, dp(2f))
    }
    paramsBox.addView(tvParamsHdr)

    val effectiveParams = item.paramBullets.ifEmpty {
        listOf("None — Takes no parameters")
    }
    effectiveParams.forEachIndexed { idx, pLine ->
        paramsBox.addView(makeBulletLine("${idx + 1}. $pLine", accentPrefix = true))
    }
    docCard.addView(paramsBox)

    // 3. Returns Section
    val retBox = makeSectionBox().apply {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
    }
    val tvRetHdr = TextView(context).apply {
        text = "RETURNS"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(strokeCol)
        setPadding(0, 0, 0, dp(2f))
    }
    retBox.addView(tvRetHdr)
    val effectiveRet = item.returnBullet.ifBlank {
        "${item.category} — ${item.detail}"
    }
    retBox.addView(makeBulletLine(effectiveRet, accentPrefix = true))
    docCard.addView(retBox)

    val popupW = (resources.displayMetrics.widthPixels - dp(32f)).coerceIn(dp(244f), dp(356f))
    docCard.measure(
        MeasureSpec.makeMeasureSpec(popupW, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    )
    val measuredH = docCard.measuredHeight.coerceAtLeast(dp(88f))

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
        val lp = FrameLayout.LayoutParams(popupW, measuredH).apply {
            leftMargin = xPos
            topMargin = yPos
        }
        hostFrame.addView(docCard, lp)
        docCard.bringToFront()
        return
    }

    val docPw = PopupWindow(docCard, popupW, measuredH, false).apply {
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

    runCatching {
        docPw.showAtLocation(this, Gravity.NO_GRAVITY, xPos, yPos)
    }
}

internal fun MathCodeEditText.applySuggestionItem(item: SuggestionItem) {
    val editable = editableText ?: return
    val range = activePrefixRange ?: return
    val st = range.first.coerceIn(0, editable.length)
    val en = (range.last + 1).coerceIn(st, editable.length)
    dismissSuggestionsPopup()

    isProgrammaticEdit = true
    try {
        editable.replace(st, en, item.insertText)
        val newCursor = (st + item.insertText.length - item.cursorBackOffset).coerceIn(0, editable.length)
        setSelection(newCursor)
    } finally {
        isProgrammaticEdit = false
    }
    requestFocus()
}
