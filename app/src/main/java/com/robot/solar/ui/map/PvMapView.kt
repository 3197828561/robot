package com.robot.solar.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.robot.solar.map.MapPosition
import com.robot.solar.map.Point2D
import com.robot.solar.map.PvMap
import kotlin.math.max
import kotlin.math.min

class PvMapView(context: Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    private val cellFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 92, 145); style = Paint.Style.FILL }
    private val disabledFill = Paint(cellFill).apply { color = Color.rgb(123, 139, 154) }
    private val cellStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 220, 240); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val bridgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 255, 154, 55); style = Paint.Style.FILL }
    private val bridgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(221, 112, 22); style = Paint.Style.STROKE; strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(105, 230, 241, 250); style = Paint.Style.STROKE; strokeWidth = 0.8f }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(22, 45, 67); textSize = 13f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 184, 101); style = Paint.Style.STROKE; strokeWidth = 3f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val robotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 184, 101); style = Paint.Style.FILL }
    private val transform = Matrix()
    private val inverse = Matrix()
    private var map: PvMap? = null
    private var robot: MapPosition? = null
    private var trail: List<MapPosition> = emptyList()
    private var baseScale = 1f
    private var userScale = 1f
    private var translateX = 0f
    private var translateY = 0f
    var interactionEnabled: Boolean = true
    var showLabels: Boolean = true

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!interactionEnabled) return false
            val before = screenToMap(detector.focusX, detector.focusY)
            userScale = (userScale * detector.scaleFactor).coerceIn(1f, 8f)
            updateMatrix()
            before?.let {
                val after = mapToScreen(it.u, it.v)
                translateX += detector.focusX - after.x
                translateY += detector.focusY - after.y
            }
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!interactionEnabled) return false
            translateX -= distanceX
            translateY -= distanceY
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetViewport()
            return true
        }
    })

    fun setMap(value: PvMap?) {
        map = value
        robot = null
        trail = emptyList()
        resetViewport()
    }

    fun setRobot(position: MapPosition?, history: List<MapPosition>) {
        robot = position
        trail = history
        invalidate()
    }

    fun resetViewport() {
        userScale = 1f
        translateX = 0f
        translateY = 0f
        updateMatrix()
        invalidate()
    }

    fun centerRobot(): Boolean {
        val position = robot ?: return false
        val screen = mapToScreen(position.point.u, position.point.v)
        translateX += width / 2f - screen.x
        translateY += height / 2f - screen.y
        invalidate()
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactionEnabled) return false
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = map ?: return
        updateMatrix()
        canvas.save()
        canvas.concat(transform)
        current.bridges.forEach { bridge ->
            drawPolygon(canvas, bridge.polygonPoints(), bridgeFill)
            drawPolyline(canvas, bridge.centerlinePoints(), bridgeLine)
        }
        current.cells.forEach { cell ->
            val fill = if (current.blocksById[cell.blockId]?.cleanable == false) disabledFill else cellFill
            drawPolygon(canvas, cell.points(), fill)
            drawPolygon(canvas, cell.points(), cellStroke)
            if (userScale >= 2.2f) drawInnerGrid(canvas, cell.points(), current.cellModel.innerRows, current.cellModel.innerCols)
        }
        drawTrail(canvas)
        drawRobot(canvas)
        canvas.restore()
        if (showLabels) drawBlockLabels(canvas, current)
    }

    private fun updateMatrix() {
        val current = map ?: return
        val points = current.cells.flatMap { it.points() } + current.bridges.flatMap { it.polygonPoints() }
        if (points.isEmpty() || width == 0 || height == 0) return
        val minU = points.minOf(Point2D::u)
        val maxU = points.maxOf(Point2D::u)
        val minV = points.minOf(Point2D::v)
        val maxV = points.maxOf(Point2D::v)
        val padding = 24f * resources.displayMetrics.density
        baseScale = min((width - 2 * padding) / max(1.0, maxU - minU), (height - 2 * padding) / max(1.0, maxV - minV)).toFloat()
        val scale = baseScale * userScale
        val contentW = (maxU - minU).toFloat() * scale
        val contentH = (maxV - minV).toFloat() * scale
        transform.reset()
        transform.postTranslate((-minU).toFloat(), (-minV).toFloat())
        transform.postScale(scale, -scale)
        transform.postTranslate((width - contentW) / 2f + translateX, (height + contentH) / 2f + translateY)
        transform.invert(inverse)
    }

    private fun drawPolygon(canvas: Canvas, points: List<Point2D>, paint: Paint) {
        if (points.size < 3) return
        val path = Path().apply {
            moveTo(points[0].u.toFloat(), points[0].v.toFloat())
            points.drop(1).forEach { lineTo(it.u.toFloat(), it.v.toFloat()) }
            close()
        }
        canvas.drawPath(path, paint)
    }

    private fun drawPolyline(canvas: Canvas, points: List<Point2D>, paint: Paint) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].u.toFloat(), points[0].v.toFloat())
            points.drop(1).forEach { lineTo(it.u.toFloat(), it.v.toFloat()) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawInnerGrid(canvas: Canvas, p: List<Point2D>, rows: Int, cols: Int) {
        for (col in 1 until cols) {
            val ratio = col.toDouble() / cols
            drawPolyline(canvas, listOf(lerp(p[0], p[1], ratio), lerp(p[3], p[2], ratio)), gridPaint)
        }
        for (row in 1 until rows) {
            val ratio = row.toDouble() / rows
            drawPolyline(canvas, listOf(lerp(p[0], p[3], ratio), lerp(p[1], p[2], ratio)), gridPaint)
        }
    }

    private fun drawBlockLabels(canvas: Canvas, current: PvMap) {
        current.blocks.forEach { block ->
            val cells = block.cellIds.mapNotNull(current.cellsById::get)
            if (cells.isEmpty()) return@forEach
            val points = cells.flatMap { it.points() }
            val center = mapToScreen(points.map(Point2D::u).average(), points.map(Point2D::v).average())
            canvas.drawText("B${block.blockId}", center.x, center.y + labelPaint.textSize / 3f, labelPaint)
        }
    }

    private fun drawTrail(canvas: Canvas) = drawPolyline(canvas, trail.map(MapPosition::point), scaledPaint(trailPaint))

    private fun drawRobot(canvas: Canvas) {
        val position = robot ?: return
        val radius = 9f / max(baseScale * userScale, 0.001f)
        canvas.save()
        canvas.translate(position.point.u.toFloat(), position.point.v.toFloat())
        canvas.rotate(position.headingDegrees.toFloat())
        val path = Path().apply {
            moveTo(radius * 1.5f, 0f)
            lineTo(-radius, radius)
            lineTo(-radius * 0.5f, 0f)
            lineTo(-radius, -radius)
            close()
        }
        canvas.drawPath(path, robotPaint)
        canvas.restore()
    }

    private fun scaledPaint(source: Paint) = Paint(source).apply { strokeWidth = source.strokeWidth / max(baseScale * userScale, 0.001f) }
    private fun lerp(a: Point2D, b: Point2D, ratio: Double) = Point2D(a.u + (b.u - a.u) * ratio, a.v + (b.v - a.v) * ratio)
    private fun mapToScreen(u: Double, v: Double): PointF {
        val point = floatArrayOf(u.toFloat(), v.toFloat())
        transform.mapPoints(point)
        return PointF(point[0], point[1])
    }

    private fun screenToMap(x: Float, y: Float): Point2D {
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        return Point2D(point[0].toDouble(), point[1].toDouble())
    }
}
