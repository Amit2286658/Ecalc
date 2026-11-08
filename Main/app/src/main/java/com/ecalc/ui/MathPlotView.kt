package com.ecalc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Interactive 2D Multi-Curve Function, Vector & Parametric (`vec<vec2>`) Canvas Plotter View.
 *
 * Features:
 * - Multi-curve overlay (`f(x); g(x); h(x)`) with distinct high-contrast colors and color-coded legend bar.
 * - Pinch-to-zoom & 1-finger pan with auto-formatted gridlines and axes (`x = 0`, `y = 0`).
 * - Automatic detection of roots (`y = 0` zero-crossings), local extrema (`min` / `max`), and curve intersections.
 * - Tap / drag crosshair inspection that snaps to curves and displays multi-series `(x, y)` coordinates.
 */
class MathPlotView(context: Context) : View(context) {

    data class SpecialMarker(
        val x: Double,
        val y: Double,
        val kind: String, // "ROOT", "MAX", "MIN", "INT"
        val seriesIdx: Int = 0
    )

    var isDarkThemeMode: Boolean = true
        set(value) {
            field = value
            updatePalette()
            invalidate()
        }

    var onInspectPointChanged: ((x: Double, y: Double, tag: String?) -> Unit)? = null

    private var seriesList: List<PlotSeries> = emptyList()
    private var specialMarkers: List<SpecialMarker> = emptyList()

    // World viewport bounds [xMin..xMax, yMin..yMax]
    private var viewXMin: Double = -5.0
    private var viewXMax: Double = 5.0
    private var viewYMin: Double = -5.0
    private var viewYMax: Double = 5.0

    private var defaultXMin: Double = -5.0
    private var defaultXMax: Double = 5.0
    private var defaultYMin: Double = -5.0
    private var defaultYMax: Double = 5.0

    private var inspectedPoint: Pair<Double, Double>? = null
    private var inspectedTag: String? = null
    private var inspectedSeriesIdx: Int = 0

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val darkSeriesColors = intArrayOf(
        Color.parseColor("#26C6DA"),
        Color.parseColor("#FF7043"),
        Color.parseColor("#FFCA28"),
        Color.parseColor("#AB47BC"),
        Color.parseColor("#66BB6A"),
        Color.parseColor("#42A5F5")
    )

    private val lightSeriesColors = intArrayOf(
        Color.parseColor("#E58A7B"),
        Color.parseColor("#1E88E5"),
        Color.parseColor("#43A047"),
        Color.parseColor("#8E24AA"),
        Color.parseColor("#FB8C00"),
        Color.parseColor("#00897B")
    )

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val rootMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val extremumMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10f * scaledDensity
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 10.5f * scaledDensity
    }
    private val legendDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 9.8f * scaledDensity
    }

    private val curvePath = Path()
    private val fillPath = Path()
    private val cardRect = RectF()
    private val badgeRect = RectF()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor.coerceIn(0.6f, 1.6f).toDouble()
                if (abs(factor - 1.0) < 1e-3) return false
                val focusX = screenToWorldX(detector.focusX)
                val focusY = screenToWorldY(detector.focusY)
                val newW = ((viewXMax - viewXMin) / factor).coerceIn(1e-5, 1e8)
                val newH = ((viewYMax - viewYMin) / factor).coerceIn(1e-5, 1e8)
                val rx = ((focusX - viewXMin) / (viewXMax - viewXMin)).coerceIn(0.0, 1.0)
                val ry = ((focusY - viewYMin) / (viewYMax - viewYMin)).coerceIn(0.0, 1.0)
                viewXMin = focusX - rx * newW
                viewXMax = viewXMin + newW
                viewYMin = focusY - ry * newH
                viewYMax = viewYMin + newH
                invalidate()
                return true
            }
        }
    )

    init {
        updatePalette()
    }

    private fun seriesColor(index: Int): Int {
        val palette = if (isDarkThemeMode) darkSeriesColors else lightSeriesColors
        return palette[index.coerceAtLeast(0) % palette.size]
    }

    private fun updatePalette() {
        if (isDarkThemeMode) {
            bgPaint.color = Color.parseColor("#1B1E23")
            borderPaint.color = Color.parseColor("#383E47")
            gridPaint.color = Color.parseColor("#2A2F37")
            axisPaint.color = Color.parseColor("#636E7B")
            curvePaint.color = Color.parseColor("#26C6DA")
            dotPaint.color = Color.parseColor("#4DD0E1")
            rootMarkerPaint.color = Color.parseColor("#FFD54F")
            extremumMarkerPaint.color = Color.parseColor("#FF8A65")
            crosshairPaint.color = Color.parseColor("#B0BEC5")
            labelPaint.color = Color.parseColor("#90A4AE")
            badgeBgPaint.color = Color.parseColor("#D8263238")
            badgeTextPaint.color = Color.parseColor("#E0F7FA")
            legendTextPaint.color = Color.parseColor("#ECEFF4")
        } else {
            bgPaint.color = Color.parseColor("#FFFFFF")
            borderPaint.color = Color.parseColor("#D0D7DE")
            gridPaint.color = Color.parseColor("#ECEFF1")
            axisPaint.color = Color.parseColor("#90A4AE")
            curvePaint.color = Color.parseColor("#E58A7B")
            dotPaint.color = Color.parseColor("#D85A4A")
            rootMarkerPaint.color = Color.parseColor("#F57C00")
            extremumMarkerPaint.color = Color.parseColor("#7B1FA2")
            crosshairPaint.color = Color.parseColor("#607D8B")
            labelPaint.color = Color.parseColor("#546E7A")
            badgeBgPaint.color = Color.parseColor("#E8ECEFF1")
            badgeTextPaint.color = Color.parseColor("#263238")
            legendTextPaint.color = Color.parseColor("#24292F")
        }
    }

    fun setSeriesData(label: String, points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) {
            setMultiSeriesData(emptyList())
        } else {
            setMultiSeriesData(listOf(PlotSeries(label, points)))
        }
    }

    fun setMultiSeriesData(series: List<PlotSeries>) {
        seriesList = series.filter { it.points.isNotEmpty() }
        inspectedPoint = null
        inspectedTag = null
        inspectedSeriesIdx = 0
        computeAutoBoundsAndMarkers(seriesList)
        resetViewport()
    }

    fun resetViewport() {
        viewXMin = defaultXMin
        viewXMax = defaultXMax
        viewYMin = defaultYMin
        viewYMax = defaultYMax
        invalidate()
    }

    private fun computeAutoBoundsAndMarkers(allSeries: List<PlotSeries>) {
        if (allSeries.isEmpty()) {
            defaultXMin = -5.0
            defaultXMax = 5.0
            defaultYMin = -5.0
            defaultYMax = 5.0
            specialMarkers = emptyList()
            return
        }
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        for (s in allSeries) {
            for ((x, y) in s.points) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        val spanX = if (maxX - minX < 1e-9) 2.0 else (maxX - minX)
        val spanY = if (maxY - minY < 1e-9) 2.0 else (maxY - minY)

        defaultXMin = if (maxX - minX < 1e-9) minX - 1.0 else minX - 0.04 * spanX
        defaultXMax = if (maxX - minX < 1e-9) maxX + 1.0 else maxX + 0.04 * spanX
        defaultYMin = if (maxY - minY < 1e-9) minY - 1.0 else minY - 0.12 * spanY
        defaultYMax = if (maxY - minY < 1e-9) maxY + 1.0 else maxY + (if (allSeries.size > 1) 0.22 else 0.14) * spanY

        val markers = mutableListOf<SpecialMarker>()
        allSeries.forEachIndexed { sIdx, s ->
            val pts = s.points
            for (i in 0 until pts.size - 1) {
                val (x0, y0) = pts[i]
                val (x1, y1) = pts[i + 1]
                if (abs(y0) <= 1e-9) {
                    markers.add(SpecialMarker(x0, 0.0, "ROOT", sIdx))
                } else if ((y0 < 0.0 && y1 > 0.0) || (y0 > 0.0 && y1 < 0.0)) {
                    val t = -y0 / (y1 - y0)
                    val rx = x0 + t * (x1 - x0)
                    markers.add(SpecialMarker(rx, 0.0, "ROOT", sIdx))
                }
            }
            for (i in 1 until pts.size - 1) {
                val (_, yPrev) = pts[i - 1]
                val (xc, yc) = pts[i]
                val (_, yNext) = pts[i + 1]
                if (yc > yPrev && yc >= yNext && yc - minY > 0.03 * spanY) {
                    markers.add(SpecialMarker(xc, yc, "MAX", sIdx))
                } else if (yc < yPrev && yc <= yNext && maxY - yc > 0.03 * spanY) {
                    markers.add(SpecialMarker(xc, yc, "MIN", sIdx))
                }
            }
        }
        // Detect intersections between curve 0 and curve 1 when 2+ curves share x-grid
        if (allSeries.size >= 2) {
            val p0 = allSeries[0].points
            val p1 = allSeries[1].points
            val n = min(p0.size, p1.size)
            for (i in 0 until n - 1) {
                val (x0, y0a) = p0[i]
                val (x1, y1a) = p0[i + 1]
                val (x0b, y0b) = p1[i]
                val (_, y1b) = p1[i + 1]
                if (abs(x0 - x0b) < 1e-6) {
                    val d0 = y0a - y0b
                    val d1 = y1a - y1b
                    if ((d0 < 0.0 && d1 > 0.0) || (d0 > 0.0 && d1 < 0.0)) {
                        val t = -d0 / (d1 - d0)
                        val ix = x0 + t * (x1 - x0)
                        val iy = y0a + t * (y1a - y0a)
                        markers.add(SpecialMarker(ix, iy, "INT", 0))
                    }
                }
            }
        }
        specialMarkers = markers.take(24)
    }

    private fun plotLeft(): Float = 10f * density
    private fun plotRight(): Float = (width - 10f * density).coerceAtLeast(plotLeft() + 10f)
    private fun plotTop(): Float = 10f * density
    private fun plotBottom(): Float = (height - 10f * density).coerceAtLeast(plotTop() + 10f)

    private fun worldToScreenX(x: Double): Float {
        val l = plotLeft()
        val r = plotRight()
        val span = (viewXMax - viewXMin).let { if (abs(it) < 1e-12) 1.0 else it }
        return (l + ((x - viewXMin) / span) * (r - l)).toFloat()
    }

    private fun worldToScreenY(y: Double): Float {
        val t = plotTop()
        val b = plotBottom()
        val span = (viewYMax - viewYMin).let { if (abs(it) < 1e-12) 1.0 else it }
        return (b - ((y - viewYMin) / span) * (b - t)).toFloat()
    }

    private fun screenToWorldX(px: Float): Double {
        val l = plotLeft()
        val r = plotRight()
        val frac = ((px - l) / max(1f, r - l)).toDouble()
        return viewXMin + frac * (viewXMax - viewXMin)
    }

    private fun screenToWorldY(py: Float): Double {
        val t = plotTop()
        val b = plotBottom()
        val frac = ((b - py) / max(1f, b - t)).toDouble()
        return viewYMin + frac * (viewYMax - viewYMin)
    }

    private fun niceStep(span: Double, targetTicks: Int = 5): Double {
        val raw = max(1e-12, span / max(1, targetTicks))
        val exp = floor(log10(raw))
        val base = 10.0.pow(exp)
        val frac = raw / base
        val mult = when {
            frac < 1.5 -> 1.0
            frac < 3.5 -> 2.0
            frac < 7.5 -> 5.0
            else -> 10.0
        }
        return mult * base
    }

    private fun formatCoord(v: Double): String {
        val av = abs(v)
        if (av < 1e-11) return "0"
        return if (av >= 10000.0 || av < 0.001) {
            String.format("%.3g", v)
        } else {
            String.format("%.4f", v).trimEnd('0').trimEnd('.')
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
                snapInspectToScreen(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (hypot(dx.toDouble(), dy.toDouble()) > 4.0 * density) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val worldDx = dx / max(1f, plotRight() - plotLeft()) * (viewXMax - viewXMin)
                        val worldDy = dy / max(1f, plotBottom() - plotTop()) * (viewYMax - viewYMin)
                        viewXMin -= worldDx
                        viewXMax -= worldDx
                        viewYMin += worldDy
                        viewYMax += worldDy
                        lastTouchX = event.x
                        lastTouchY = event.y
                        snapInspectToScreen(event.x, event.y)
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    snapInspectToScreen(event.x, event.y)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun snapInspectToScreen(px: Float, py: Float) {
        if (seriesList.isEmpty()) return
        // First check if tap is close to a special marker (ROOT / MAX / MIN / INT)
        for (m in specialMarkers) {
            val mx = worldToScreenX(m.x)
            val my = worldToScreenY(m.y)
            if (hypot((px - mx).toDouble(), (py - my).toDouble()) <= 18.0 * density) {
                inspectedPoint = m.x to m.y
                inspectedSeriesIdx = m.seriesIdx
                val sLabel = seriesList.getOrNull(m.seriesIdx)?.label
                val tag = if (seriesList.size > 1 && sLabel != null && m.kind != "INT") "${m.kind} $sLabel" else m.kind
                inspectedTag = tag
                onInspectPointChanged?.invoke(m.x, m.y, tag)
                invalidate()
                return
            }
        }
        var bestDist = Double.POSITIVE_INFINITY
        var bestPt = seriesList.first().points.first()
        var bestSIdx = 0
        seriesList.forEachIndexed { sIdx, s ->
            for (pt in s.points) {
                val sx = worldToScreenX(pt.first)
                val sy = worldToScreenY(pt.second)
                val d = abs(px - sx).toDouble() + 0.22 * abs(py - sy).toDouble()
                if (d < bestDist) {
                    bestDist = d
                    bestPt = pt
                    bestSIdx = sIdx
                }
            }
        }
        inspectedPoint = bestPt
        inspectedSeriesIdx = bestSIdx
        val multiTag = if (seriesList.size > 1) {
            seriesList.joinToString(" | ") { s ->
                val near = s.points.minByOrNull { abs(it.first - bestPt.first) }
                "${s.label}=${formatCoord(near?.second ?: 0.0)}"
            }
        } else null
        inspectedTag = multiTag
        onInspectPointChanged?.invoke(bestPt.first, bestPt.second, multiTag)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = 14f * density
        cardRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(cardRect, r, r, bgPaint)

        val saveCount = canvas.save()
        canvas.clipRect(plotLeft(), plotTop(), plotRight(), plotBottom())

        // 1. Vertical & Horizontal Gridlines
        val xStep = niceStep(viewXMax - viewXMin, 5)
        val firstGridX = ceil(viewXMin / xStep) * xStep
        var gx = firstGridX
        var guard = 0
        while (gx <= viewXMax && guard++ < 30) {
            val sx = worldToScreenX(gx)
            canvas.drawLine(sx, plotTop(), sx, plotBottom(), gridPaint)
            canvas.drawText(formatCoord(gx), sx + 3f * density, plotBottom() - 4f * density, labelPaint)
            gx += xStep
        }

        val yStep = niceStep(viewYMax - viewYMin, 4)
        val firstGridY = ceil(viewYMin / yStep) * yStep
        var gy = firstGridY
        guard = 0
        while (gy <= viewYMax && guard++ < 30) {
            val sy = worldToScreenY(gy)
            canvas.drawLine(plotLeft(), sy, plotRight(), sy, gridPaint)
            canvas.drawText(formatCoord(gy), plotLeft() + 4f * density, sy - 3f * density, labelPaint)
            gy += yStep
        }

        // 2. X=0 and Y=0 Axes
        if (0.0 in viewXMin..viewXMax) {
            val x0 = worldToScreenX(0.0)
            canvas.drawLine(x0, plotTop(), x0, plotBottom(), axisPaint)
        }
        if (0.0 in viewYMin..viewYMax) {
            val y0 = worldToScreenY(0.0)
            canvas.drawLine(plotLeft(), y0, plotRight(), y0, axisPaint)
        }

        // 3. Curves & Soft Gradient Fill
        if (seriesList.isNotEmpty()) {
            val baselineY = worldToScreenY(0.0.coerceIn(viewYMin, viewYMax))

            seriesList.forEachIndexed { sIdx, series ->
                val pts = series.points
                if (pts.isEmpty()) return@forEachIndexed
                val col = seriesColor(sIdx)
                curvePaint.color = col
                dotPaint.color = col

                curvePath.reset()
                fillPath.reset()
                var lastSx = 0f
                pts.forEachIndexed { idx, (wx, wy) ->
                    val sx = worldToScreenX(wx)
                    val sy = worldToScreenY(wy)
                    if (idx == 0) {
                        curvePath.moveTo(sx, sy)
                        fillPath.moveTo(sx, baselineY)
                        fillPath.lineTo(sx, sy)
                    } else {
                        curvePath.lineTo(sx, sy)
                        fillPath.lineTo(sx, sy)
                    }
                    lastSx = sx
                }
                fillPath.lineTo(lastSx, baselineY)
                fillPath.close()

                if (pts.size >= 2) {
                    if (seriesList.size == 1 || sIdx == 0) {
                        val topAlpha = if (seriesList.size == 1) 0x44 else 0x22
                        val topC = (col and 0x00FFFFFF) or (topAlpha shl 24)
                        fillPaint.shader = LinearGradient(
                            0f, plotTop(), 0f, plotBottom(),
                            topC, Color.TRANSPARENT, Shader.TileMode.CLAMP
                        )
                        canvas.drawPath(fillPath, fillPaint)
                    }
                    canvas.drawPath(curvePath, curvePaint)
                }

                if (pts.size <= 45) {
                    for ((wx, wy) in pts) {
                        canvas.drawCircle(worldToScreenX(wx), worldToScreenY(wy), 3.4f * density, dotPaint)
                    }
                }
            }

            // 4. Special markers (roots, local extrema & intersections)
            for (m in specialMarkers) {
                val mx = worldToScreenX(m.x)
                val my = worldToScreenY(m.y)
                val paint = if (m.kind == "ROOT" || m.kind == "INT") rootMarkerPaint else extremumMarkerPaint
                canvas.drawCircle(mx, my, 4.5f * density, paint)
            }

            // 5. Multi-curve color-coded legend bar (when 2+ curves are active)
            if (seriesList.size > 1) {
                var lx = plotLeft() + 8f * density
                val ly = plotTop() + 14f * density
                seriesList.forEachIndexed { idx, s ->
                    val col = seriesColor(idx)
                    legendDotPaint.color = col
                    val shortLbl = if (s.label.length > 18) s.label.take(16) + "…" else s.label
                    val tw = legendTextPaint.measureText(shortLbl)
                    if (lx + tw + 20f * density <= plotRight()) {
                        canvas.drawCircle(lx + 4f * density, ly - 3.5f * density, 4f * density, legendDotPaint)
                        canvas.drawText(shortLbl, lx + 11f * density, ly, legendTextPaint)
                        lx += tw + 22f * density
                    }
                }
            }

            // 6. Inspected crosshair & readout pill
            inspectedPoint?.let { (ix, iy) ->
                val sx = worldToScreenX(ix)
                val sy = worldToScreenY(iy)
                canvas.drawLine(sx, plotTop(), sx, plotBottom(), crosshairPaint)
                canvas.drawLine(plotLeft(), sy, plotRight(), sy, crosshairPaint)

                // Draw dots on all curves at inspected x
                seriesList.forEachIndexed { sIdx, s ->
                    val near = s.points.minByOrNull { abs(it.first - ix) }
                    if (near != null && abs(near.first - ix) <= (viewXMax - viewXMin) * 0.05) {
                        val nsy = worldToScreenY(near.second)
                        curvePaint.color = seriesColor(sIdx)
                        canvas.drawCircle(sx, nsy, 5.2f * density, curvePaint)
                        canvas.drawCircle(sx, nsy, 2.6f * density, rootMarkerPaint)
                    }
                }

                val text = "x=${formatCoord(ix)}, y=${formatCoord(iy)}"
                val tw = badgeTextPaint.measureText(text)
                val padH = 8f * density
                val padV = 5f * density
                val bx = min(max(plotLeft() + 6f * density, sx - tw / 2f - padH), plotRight() - tw - 2f * padH - 6f * density)
                val by = max(plotTop() + (if (seriesList.size > 1) 22f else 6f) * density, sy - 28f * density)
                badgeRect.set(bx, by, bx + tw + 2f * padH, by + 18f * density + padV)
                canvas.drawRoundRect(badgeRect, 8f * density, 8f * density, badgeBgPaint)
                canvas.drawText(text, bx + padH, by + 13.5f * density, badgeTextPaint)
            }
        } else {
            val msg = "No numeric points to plot"
            val tw = labelPaint.measureText(msg)
            canvas.drawText(msg, (width - tw) / 2f, height / 2f, labelPaint)
        }

        canvas.restoreToCount(saveCount)
        canvas.drawRoundRect(cardRect, r, r, borderPaint)
    }
}
