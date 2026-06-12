// com/example/geo_slam/ui/map/MapCanvasView.kt
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

    var floorPlan: FloorPlan = FloorPlan.empty()
        set(value) { field = value; isInitialized = false; invalidate() }

    var footSlamPath: List<PointF> = emptyList()
        set(value) { field = value; invalidate() }

    var vslamPath: List<PointF> = emptyList()
        set(value) { field = value; invalidate() }

    var avatarPosition: PointF? = null
        set(value) { field = value; invalidate() }

    var avatarHeading: Float = 0f
        set(value) { field = value; invalidate() }

    var displayMode: DisplayMode = DisplayMode.FUSION
        set(value) { field = value; invalidate() }

    private var scaleFactor = 40f
    private var offsetX = 0f
    private var offsetY = 0f
    private var isInitialized = false

    // --- PINCEAUX ---

    private val outerWallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 12f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; strokeWidth = 4f; style = Paint.Style.STROKE
        // Effet pointillé pour les délimitations de zones (comme sur ton plan technique)
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val zoneTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = 26f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 10f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val innerWallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E"); strokeWidth = 5f; style = Paint.Style.STROKE
    }
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#534AB7"); strokeWidth = 6f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val vslamPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00"); strokeWidth = 5f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(18f, 8f), 0f)
    }
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E91E63") }

    // --- GESTES ---
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(10f, 500f)
            invalidate(); return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
            offsetX -= dX; offsetY -= dY; invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event); gestureDetector.onTouchEvent(event); return true
    }

    private val polyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
        color = 0x25FFA726 // Orange transparent
        style = Paint.Style.FILL 
    }
    private val polyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
        color = Color.RED 
        strokeWidth = 6f 
        style = Paint.Style.STROKE 
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (floorPlan.widthInMeters <= 0f) return
        if (!isInitialized) { centerMap(); isInitialized = true }

        // 1. Les zones colorées (Fond)
        floorPlan.zones.forEach { zone ->
            // On ne dessine pas le fond rectangulaire pour Process Production car on a son polygone
            if (zone.name != "Process Production") {
                zoneFillPaint.color = zone.color
                val rect = getZoneRect(zone)
                canvas.drawRect(rect, zoneFillPaint)
            }
            canvas.drawText(zone.name, mapToScreenX(zone.cx), mapToScreenY(zone.cy), zoneTextPaint)
        }

        // 2. Polygone de Process Production
        if (floorPlan.processProductionPolygon.isNotEmpty()) {
            val path = Path()
            val firstPoint = floorPlan.processProductionPolygon.first()
            path.moveTo(mapToScreenX(firstPoint.x), mapToScreenY(firstPoint.y))
            
            for (i in 1 until floorPlan.processProductionPolygon.size) {
                val point = floorPlan.processProductionPolygon[i]
                path.lineTo(mapToScreenX(point.x), mapToScreenY(point.y))
            }
            path.close()
            
            canvas.drawPath(path, polyFillPaint)
            canvas.drawPath(path, polyStrokePaint)
        }

        // 3. Les murs extérieurs (Épais)
        floorPlan.outerWalls.forEach { w ->
            canvas.drawLine(mapToScreenX(w.x1), mapToScreenY(w.y1), mapToScreenX(w.x2), mapToScreenY(w.y2), outerWallPaint)
        }

        // 4. Les délimitations de secteurs (Fines/Pointillés)
        floorPlan.innerWalls.forEach { w ->
            canvas.drawLine(mapToScreenX(w.x1), mapToScreenY(w.y1), mapToScreenX(w.x2), mapToScreenY(w.y2), sectorPaint)
        }

        // 5. Marquer la porte d'entrée
        val doorPaint = Paint().apply { color = Color.RED; strokeWidth = 15f }
        canvas.drawPoint(mapToScreenX(floorPlan.doorX), mapToScreenY(floorPlan.doorY), doorPaint)

        // 6. Trajectoires et avatar
        drawPath(canvas)
        drawVslamPath(canvas)
        drawAvatar(canvas)
    }

    // Fonction utilitaire pour dessiner un petit rectangle autour du label
    private fun getZoneRect(zone: ZoneLabel): RectF {
        val w = 1.5f * scaleFactor
        val h = 0.8f * scaleFactor
        val cx = mapToScreenX(zone.cx)
        val cy = mapToScreenY(zone.cy)
        return RectF(cx - w, cy - h, cx + w, cy + h)
    }

    private fun drawPath(canvas: Canvas) {
        if (footSlamPath.size < 2) return
        val path = Path()
        path.moveTo(worldToScreenX(footSlamPath[0].x), worldToScreenY(footSlamPath[0].y))
        for (i in 1 until footSlamPath.size) {
            path.lineTo(worldToScreenX(footSlamPath[i].x), worldToScreenY(footSlamPath[i].y))
        }
        canvas.drawPath(path, pathPaint)
    }

    private fun drawVslamPath(canvas: Canvas) {
        if (vslamPath.size < 2) return
        val path = Path()
        path.moveTo(worldToScreenX(vslamPath[0].x), worldToScreenY(vslamPath[0].y))
        for (i in 1 until vslamPath.size) {
            path.lineTo(worldToScreenX(vslamPath[i].x), worldToScreenY(vslamPath[i].y))
        }
        canvas.drawPath(path, vslamPathPaint)
    }

    private fun drawAvatar(canvas: Canvas) {
        val pos = avatarPosition ?: return
        val sx = worldToScreenX(pos.x); val sy = worldToScreenY(pos.y)
        canvas.drawCircle(sx, sy, 20f, avatarPaint)
    }

    // --- CALCULS DE COORDONNÉES ---
    private fun mapToScreenX(x: Float) = x * scaleFactor + offsetX
    private fun mapToScreenY(y: Float) = y * scaleFactor + offsetY

    // SLAM (0,0) à la porte -> Conversion en coordonnées Plan
    private fun worldToScreenX(wx: Float) = (floorPlan.doorX + wx) * scaleFactor + offsetX
    private fun worldToScreenY(wy: Float) = (floorPlan.doorY + wy) * scaleFactor + offsetY

    private fun centerMap() {
        val padding = 0.85f
        scaleFactor = min((width * padding) / floorPlan.widthInMeters, (height * padding) / floorPlan.heightInMeters)
        offsetX = (width - floorPlan.widthInMeters * scaleFactor) / 2f
        offsetY = (height - floorPlan.heightInMeters * scaleFactor) / 2f
    }
}