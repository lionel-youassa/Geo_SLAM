package com.example.geo_slam.ui.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.min

/**
 * MapCanvasView : Rendu complet avec Zones colorées réelles, Zoom/Pan et trajectoires Violet/Teal.
 */
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

    var vSlamPath: List<PointF> = emptyList()
        set(value) { field = value; invalidate() }

    var fusionPath: List<PointF> = emptyList()
        set(value) { field = value; invalidate() }

    var avatarPosition: PointF? = null
        set(value) { field = value; invalidate() }

    var avatarHeading: Float = 0f
        set(value) { field = value; invalidate() }

    var stepCount: Int = 0
        set(value) { field = value; invalidate() }

    var displayMode: DisplayMode = DisplayMode.FOOT_SLAM
        set(value) { field = value; invalidate() }

    var vSlamStatus: String = "INACTIVE"
        set(value) { field = value; invalidate() }

    // --- ÉTAT DU RENDU (ZOOM & PAN) ---
    private var scaleFactor = 60f
    private var offsetX = 0f
    private var offsetY = 0f
    private var isInitialized = false

    // --- PINCEAUX ---
    private val gridPaint = Paint().apply { color = Color.parseColor("#F5F5F5"); strokeWidth = 2f; style = Paint.Style.STROKE }
    private val wallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 12f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val innerWallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#616161"); strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    
    // FootSLAM : Violet (#673AB7)
    private val footSlamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#673AB7"); strokeWidth = 10f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    
    // vSLAM : Teal/Vert d'eau (#26A69A)
    private val vSlamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26A69A"); strokeWidth = 10f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    
    // Fusion : Bleu Indigo (#3F51B5)
    private val fusionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3F51B5"); strokeWidth = 12f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
    private val zoneTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 210; style = Paint.Style.FILL }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 35f; typeface = Typeface.MONOSPACE; style = Paint.Style.FILL }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val overlayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 180; style = Paint.Style.FILL }

    private val tempPath = Path()
    private val tempRect = RectF()

    // --- GESTES ---
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scaleFactor
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(15f, 3000f)
            offsetX = detector.focusX - (detector.focusX - offsetX) * (scaleFactor / oldScale)
            offsetY = detector.focusY - (detector.focusY - offsetY) * (scaleFactor / oldScale)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
            if (!scaleDetector.isInProgress) {
                offsetX -= dX; offsetY -= dY; invalidate()
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val worldX = (e.x - offsetX) / scaleFactor - floorPlan.doorX
            val worldY = floorPlan.doorY - (e.y - offsetY) / scaleFactor
            onInitialPositionSelected?.invoke(worldX, worldY)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        if (floorPlan.widthInMeters <= 0f) return
        if (!isInitialized) { centerMap(); isInitialized = true }

        drawMetricGrid(canvas)
        drawZones(canvas)
        drawWalls(canvas)

        // Trajectoires Violet / Teal
        if (displayMode == DisplayMode.FOOT_SLAM || displayMode == DisplayMode.FUSION) {
            drawTrajectory(canvas, footSlamPath, footSlamPaint)
        }
        if (displayMode == DisplayMode.VSLAM) {
            drawTrajectory(canvas, vSlamPath, vSlamPaint)
        }
        if (displayMode == DisplayMode.FUSION) {
            drawTrajectory(canvas, fusionPath, fusionPaint)
        }

        // Avatar
        avatarPosition?.let { pos ->
            val sx = worldToScreenX(pos.x); val sy = worldToScreenY(pos.y)
            
            // Cercle de base
            canvas.drawCircle(sx, sy, 26f, avatarPaint)
            
            // Flèche de direction
            val arrowPath = Path()
            val angleRad = Math.toRadians(avatarHeading.toDouble() - 90.0)
            val tipX = sx + 50f * Math.cos(angleRad).toFloat()
            val tipY = sy + 50f * Math.sin(angleRad).toFloat()
            
            val leftX = sx + 20f * Math.cos(angleRad - Math.PI/1.2).toFloat()
            val leftY = sy + 20f * Math.sin(angleRad - Math.PI/1.2).toFloat()
            
            val rightX = sx + 20f * Math.cos(angleRad + Math.PI/1.2).toFloat()
            val rightY = sy + 20f * Math.sin(angleRad + Math.PI/1.2).toFloat()
            
            arrowPath.moveTo(tipX, tipY)
            arrowPath.lineTo(leftX, leftY)
            arrowPath.lineTo(rightX, rightY)
            arrowPath.close()
            
            avatarPaint.style = Paint.Style.FILL
            canvas.drawPath(arrowPath, avatarPaint)
            avatarPaint.style = Paint.Style.STROKE
            avatarPaint.strokeWidth = 3f
            avatarPaint.color = Color.WHITE
            canvas.drawPath(arrowPath, avatarPaint)
            avatarPaint.color = Color.RED
            avatarPaint.style = Paint.Style.FILL
        }

        drawLabels(canvas)
        drawStatus(canvas)
        drawLargeOverlay(canvas)
    }

    private fun drawLargeOverlay(canvas: Canvas) {
        if (displayMode == DisplayMode.FOOT_SLAM) return
        
        val msg: String
        val color: Int
        val subMsg: String
        
        when (vSlamStatus) {
            "INITIALIZING", "INIT" -> {
                msg = "INITIALISATION VSLAM..."
                subMsg = "NE BOUGEZ PAS - RECHERCHE DE POINTS"
                color = Color.parseColor("#FFA726") // Orange
            }
            "TRACKING" -> {
                // On affiche un message bref au début du tracking (pendant les 2 premières secondes ou les 5 premiers points)
                if (vSlamPath.size in 1..8) {
                    msg = "V-SLAM ACTIF !"
                    subMsg = "MARCHEZ NORMALEMENT"
                    color = Color.parseColor("#26A69A") // Teal
                } else return
            }
            "LOST", "RECENTLY_LOST" -> {
                msg = "SIGNAL PERDU"
                subMsg = "BOUGEZ LENTEMENT POUR RECALIBRER"
                color = Color.RED
            }
            else -> return
        }

        // Dessin de la bannière en haut
        val bannerHeight = 160f
        canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, overlayBgPaint)
        
        overlayPaint.color = color
        overlayPaint.textSize = 50f
        canvas.drawText(msg, width / 2f, 70f, overlayPaint)
        
        overlayPaint.color = Color.WHITE
        overlayPaint.textSize = 30f
        canvas.drawText(subMsg, width / 2f, 120f, overlayPaint)
        
        // Petit cercle clignotant
        if ((System.currentTimeMillis() / 500) % 2 == 0L) {
            overlayPaint.color = color
            canvas.drawCircle(50f, 70f, 15f, overlayPaint)
        }
    }

    private fun drawStatus(canvas: Canvas) {
        val x = 40f
        val y = height - 60f
        
        statusPaint.color = Color.DKGRAY
        canvas.drawText("MODE: $displayMode", x, y - 80f, statusPaint)
        
        if (displayMode != DisplayMode.FOOT_SLAM) {
            statusPaint.color = when(vSlamStatus) {
                "TRACKING" -> Color.parseColor("#26A69A")
                "INITIALIZING" -> Color.parseColor("#FFA726")
                "LOST", "RECENTLY_LOST" -> Color.RED
                else -> Color.GRAY
            }
            canvas.drawText("vSLAM: $vSlamStatus", x, y - 40f, statusPaint)
        }
        
        statusPaint.color = Color.DKGRAY
        canvas.drawText("PAS: $stepCount", x, y, statusPaint)
    }

    private fun drawZones(canvas: Canvas) {
        val tw = floorPlan.widthInMeters
        val th = floorPlan.heightInMeters
        floorPlan.zones.forEach { zone ->
            zoneFillPaint.color = zone.color
            zoneFillPaint.alpha = 50
            when (zone.name) {
                "Quality Control" -> tempRect.set(mapToScreenX(0f), mapToScreenY(0f), mapToScreenX(4.81f), mapToScreenY(1.15f))
                "Salle de Contrôle" -> tempRect.set(mapToScreenX(4.81f), mapToScreenY(0f), mapToScreenX(4.81f + 4.39f), mapToScreenY(1.15f))
                "Gestion des stocks" -> tempRect.set(mapToScreenX(13.9f), mapToScreenY(0f), mapToScreenX(tw), mapToScreenY(5.2f))
                "Robotique IoT" -> tempRect.set(mapToScreenX(0f), mapToScreenY(8.78f), mapToScreenX(5.19f), mapToScreenY(th))
                "Maintenance VR" -> tempRect.set(mapToScreenX(5.19f), mapToScreenY(8.78f), mapToScreenX(5.19f + 4.67f), mapToScreenY(th))
                "War Room" -> tempRect.set(mapToScreenX(5.19f + 4.67f), mapToScreenY(8.78f), mapToScreenX(tw), mapToScreenY(th))
                "Process Production" -> {
                    if (floorPlan.processProductionPolygon.isNotEmpty()) {
                        tempPath.reset()
                        val first = floorPlan.processProductionPolygon[0]
                        tempPath.moveTo(mapToScreenX(first.x), mapToScreenY(first.y))
                        for (i in 1 until floorPlan.processProductionPolygon.size) {
                            val p = floorPlan.processProductionPolygon[i]
                            tempPath.lineTo(mapToScreenX(p.x), mapToScreenY(p.y))
                        }
                        tempPath.close()
                        canvas.drawPath(tempPath, zoneFillPaint.apply { alpha = 40 })
                    }
                    return@forEach
                }
                else -> tempRect.set(mapToScreenX(zone.cx - 1.5f), mapToScreenY(zone.cy - 1f), mapToScreenX(zone.cx + 1.5f), mapToScreenY(zone.cy + 1f))
            }
            canvas.drawRect(tempRect, zoneFillPaint)
        }
    }

    private fun drawTrajectory(canvas: Canvas, path: List<PointF>, paint: Paint) {
        if (path.size < 2) return
        tempPath.reset()
        tempPath.moveTo(worldToScreenX(path[0].x), worldToScreenY(path[0].y))
        for (i in 1 until path.size) {
            tempPath.lineTo(worldToScreenX(path[i].x), worldToScreenY(path[i].y))
        }
        canvas.drawPath(tempPath, paint)
    }

    private fun drawWalls(canvas: Canvas) {
        floorPlan.outerWalls.forEach { w -> canvas.drawLine(mapToScreenX(w.x1), mapToScreenY(w.y1), mapToScreenX(w.x2), mapToScreenY(w.y2), wallPaint) }
        floorPlan.innerWalls.forEach { w -> canvas.drawLine(mapToScreenX(w.x1), mapToScreenY(w.y1), mapToScreenX(w.x2), mapToScreenY(w.y2), innerWallPaint) }
    }

    private fun drawLabels(canvas: Canvas) {
        val dynamicSize = (scaleFactor * 0.4f).coerceIn(20f, 50f)
        zoneTextPaint.textSize = dynamicSize
        floorPlan.zones.forEach { zone ->
            val tx = mapToScreenX(zone.cx); val ty = mapToScreenY(zone.cy)
            val tw = zoneTextPaint.measureText(zone.name)
            tempRect.set(tx - tw/2 - 10, ty - dynamicSize, tx + tw/2 + 10, ty + 10)
            canvas.drawRoundRect(tempRect, 10f, 10f, labelBgPaint)
            canvas.drawText(zone.name, tx, ty, zoneTextPaint)
        }
    }

    private fun drawMetricGrid(canvas: Canvas) {
        val step = scaleFactor
        val startX = offsetX % step; val startY = offsetY % step
        for (x in -1..(width / step).toInt() + 1) canvas.drawLine(startX + x * step, 0f, startX + x * step, height.toFloat(), gridPaint)
        for (y in -1..(height / step).toInt() + 1) canvas.drawLine(0f, startY + y * step, width.toFloat(), startY + y * step, gridPaint)
    }

    private fun mapToScreenX(x: Float) = x * scaleFactor + offsetX
    private fun mapToScreenY(y: Float) = y * scaleFactor + offsetY
    private fun worldToScreenX(wx: Float) = (floorPlan.doorX + wx) * scaleFactor + offsetX
    private fun worldToScreenY(wy: Float) = (floorPlan.doorY - wy) * scaleFactor + offsetY

    private fun centerMap() {
        val padding = 0.8f
        scaleFactor = min((width * padding) / floorPlan.widthInMeters, (height * padding) / floorPlan.heightInMeters)
        offsetX = (width - floorPlan.widthInMeters * scaleFactor) / 2f
        offsetY = (height - floorPlan.heightInMeters * scaleFactor) / 2f
    }
}
