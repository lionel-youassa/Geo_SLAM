package com.example.geo_slam.ui.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var onInitialPositionSelected: ((Float, Float) -> Unit)? = null

    // --- DONNÉES ---
    var floorPlan: FloorPlan = FloorPlan.empty()
        set(value) {
            if (field.widthInMeters != value.widthInMeters || field.heightInMeters != value.heightInMeters) {
                field = value
                isInitialized = false
                invalidate()
            }
        }

    var footSlamPath: List<PointF> = emptyList()
        set(value) { field = value; invalidate() }

    var avatarPosition: PointF? = null
        set(value) { field = value; invalidate() }

    var avatarHeading: Float = 0f
        set(value) { field = value; invalidate() }

    var stepCount: Int = 0
        set(value) { field = value; invalidate() }

    var displayMode: DisplayMode = DisplayMode.FUSION
        set(value) { field = value; invalidate() }

    // --- ÉTAT DU RENDU ---
    private var scaleFactor = 60f
    private var offsetX = 0f
    private var offsetY = 0f
    private var isInitialized = false

    // --- PINCEAUX ---
    private val gridPaint = Paint().apply { color = Color.parseColor("#EEEEEE"); strokeWidth = 1.5f; style = Paint.Style.STROKE }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 12f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val innerWallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 8f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val polyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x30FFA726; style = Paint.Style.FILL }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3498DB"); strokeWidth = 12f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E91E63") }
    private val zoneTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 230; style = Paint.Style.FILL }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 35f; typeface = Typeface.MONOSPACE }

    private val tempPath = Path()
    private val tempRect = RectF()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(10f, 1500f)
            invalidate(); return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
            offsetX -= dX; offsetY -= dY; invalidate(); return true
        }
        override fun onLongPress(e: MotionEvent) {
            val worldX = (e.x - offsetX) / scaleFactor - floorPlan.doorX
            val worldY = floorPlan.doorY - (e.y - offsetY) / scaleFactor
            onInitialPositionSelected?.invoke(worldX, worldY)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event); gestureDetector.onTouchEvent(event); return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        if (floorPlan.widthInMeters <= 0f) return
        if (!isInitialized) { centerMap(); isInitialized = true }

        // 1. Grille technique
        drawMetricGrid(canvas)

        // 2. Zones de couleur
        floorPlan.zones.forEach { zone ->
            if (zone.name != "Process Production") {
                zoneFillPaint.color = zone.color
                zoneFillPaint.alpha = 50
                val w = 3.5f * scaleFactor; val h = 2.5f * scaleFactor
                tempRect.set(mapToScreenX(zone.cx) - w/2, mapToScreenY(zone.cy) - h/2, mapToScreenX(zone.cx) + w/2, mapToScreenY(zone.cy) + h/2)
                canvas.drawRect(tempRect, zoneFillPaint)
            }
        }

        // 3. Polygone Process Production
        if (floorPlan.processProductionPolygon.isNotEmpty()) {
            tempPath.reset()
            val first = floorPlan.processProductionPolygon[0]
            tempPath.moveTo(mapToScreenX(first.x), mapToScreenY(first.y))
            for (i in 1 until floorPlan.processProductionPolygon.size) {
                val p = floorPlan.processProductionPolygon[i]
                tempPath.lineTo(mapToScreenX(p.x), mapToScreenY(p.y))
            }
            tempPath.close()
            canvas.drawPath(tempPath, polyPaint)
        }

        // 4. MURS
        floorPlan.outerWalls.forEach { w ->
            canvas.drawLine(mapToScreenX(w.x1), mapToScreenY(w.y1), mapToScreenX(w.x2), mapToScreenY(w.y2), wallPaint)
        }
        floorPlan.innerWalls.forEach { w ->
            canvas.drawLine(mapToScreenX(w.x1), mapToScreenY(w.y1), mapToScreenX(w.x2), mapToScreenY(w.y2), innerWallPaint)
        }

        // 5. TRAJECTOIRE
        if (footSlamPath.size >= 2) {
            tempPath.reset()
            tempPath.moveTo(worldToScreenX(footSlamPath[0].x), worldToScreenY(footSlamPath[0].y))
            for (i in 1 until footSlamPath.size) {
                tempPath.lineTo(worldToScreenX(footSlamPath[i].x), worldToScreenY(footSlamPath[i].y))
            }
            canvas.drawPath(tempPath, pathPaint)
        }

        // 6. AVATAR
        avatarPosition?.let { pos ->
            val sx = worldToScreenX(pos.x); val sy = worldToScreenY(pos.y)
            canvas.drawCircle(sx, sy, 32f, avatarPaint)
            val drawAngle = avatarHeading - 90.0
            val hx = sx + 50f * Math.cos(Math.toRadians(drawAngle)).toFloat()
            val hy = sy + 50f * Math.sin(Math.toRadians(drawAngle)).toFloat()
            canvas.drawLine(sx, sy, hx, hy, avatarPaint.apply { strokeWidth = 10f })
        }

        // 7. LABELS
        val dynamicTextSize = (scaleFactor * 0.45f).coerceIn(25f, 70f)
        zoneTextPaint.textSize = dynamicTextSize
        floorPlan.zones.forEach { zone ->
            val tx = mapToScreenX(zone.cx); val ty = mapToScreenY(zone.cy)
            val textWidth = zoneTextPaint.measureText(zone.name)
            tempRect.set(tx - textWidth / 2 - 15, ty - dynamicTextSize, tx + textWidth / 2 + 15, ty + 15)
            canvas.drawRoundRect(tempRect, 15f, 15f, labelBgPaint)
            canvas.drawText(zone.name, tx, ty, zoneTextPaint)
        }

        // 8. INFOS ET RÈGLE
        drawOverlayInfo(canvas)
        drawRuler(canvas)
    }

    private fun drawOverlayInfo(canvas: Canvas) {
        val x = 40f
        val y = height - 120f
        canvas.drawText("Pas: $stepCount", x, y, infoPaint)
        canvas.drawText("1m = ${scaleFactor.toInt()}px", x, y + 50f, infoPaint)
    }

    private fun drawRuler(canvas: Canvas) {
        val rulerM = 2f
        val px = rulerM * scaleFactor
        val x = width - px - 40f; val y = height - 40f
        canvas.drawLine(x, y, x + px, y, wallPaint)
        canvas.drawLine(x, y, x, y - 15f, wallPaint)
        canvas.drawLine(x + px, y, x + px, y - 15f, wallPaint)
        canvas.drawText("${rulerM.toInt()}m", x + px / 2, y - 25f, zoneTextPaint.apply { textSize = 25f })
    }

    private fun drawMetricGrid(canvas: Canvas) {
        val step = scaleFactor
        val startX = offsetX % step; val startY = offsetY % step
        for (x in 0..(width / step).toInt() + 1) canvas.drawLine(startX + x * step, 0f, startX + x * step, height.toFloat(), gridPaint)
        for (y in 0..(height / step).toInt() + 1) canvas.drawLine(0f, startY + y * step, width.toFloat(), startY + y * step, gridPaint)
    }

    private fun mapToScreenX(x: Float) = x * scaleFactor + offsetX
    private fun mapToScreenY(y: Float) = y * scaleFactor + offsetY
    private fun worldToScreenX(wx: Float) = (floorPlan.doorX + wx) * scaleFactor + offsetX
    private fun worldToScreenY(wy: Float) = (floorPlan.doorY - wy) * scaleFactor + offsetY

    private fun centerMap() {
        val padding = 0.85f
        scaleFactor = min((width * padding) / floorPlan.widthInMeters, (height * padding) / floorPlan.heightInMeters)
        offsetX = (width - floorPlan.widthInMeters * scaleFactor) / 2f
        offsetY = (height - floorPlan.heightInMeters * scaleFactor) / 2f
    }
}
