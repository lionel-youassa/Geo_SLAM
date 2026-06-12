package com.example.geo_slam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Vue 2D top-down de la cartographie vSLAM.
 * - Points gris  : MapPoints 3D projetés sur le plan X-Z (sol de l'usine)
 * - Ligne bleue  : trajectoire de la caméra
 * - Point rouge  : position courante
 * - Grille       : repère en mètres (si échelle FootSLAM active)
 *
 * Reçoit les données depuis MainActivity via [updateMapPoints] et [updateCurrentPosition].
 * Utilisation intérimaire jusqu'à l'implémentation complète de MapRenderer (Sonia).
 */
class TrajectoryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // MapPoints 3D (triplets x,y,z) reçus depuis vslam_engine
    private var mapPoints = FloatArray(0)

    // Trajectoire de la caméra (accumulée dans cette vue)
    private val cameraPath = mutableListOf<Pair<Float, Float>>()
    private var currentX = 0f
    private var currentZ = 0f

    // ── Styles ───────────────────────────────────────────────────────────────

    private val mapPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#607D8B")  // bleu-gris
        style = Paint.Style.FILL
    }

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0")  // bleu foncé
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val currentPosPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")  // rouge
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33000000")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88000000")
        textSize = 24f
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66000000")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1565C0")
        textSize = 28f
        isFakeBoldText = true
    }

    // ── API publique ─────────────────────────────────────────────────────────

    fun updateMapPoints(pts: FloatArray) {
        mapPoints = pts
        invalidate()
    }

    fun updateCurrentPosition(x: Float, y: Float, z: Float) {
        currentX = x
        currentZ = z
        if (cameraPath.isEmpty() ||
            kotlin.math.abs(cameraPath.last().first - x) > 0.02f ||
            kotlin.math.abs(cameraPath.last().second - z) > 0.02f) {
            cameraPath.add(Pair(x, z))
        }
        invalidate()
    }

    fun reset() {
        mapPoints = FloatArray(0)
        cameraPath.clear()
        currentX = 0f; currentZ = 0f
        invalidate()
    }

    // ── Dessin ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        canvas.drawColor(Color.parseColor("#F5F5F5"))

        // Calculer les bornes du monde (X-Z) depuis mapPoints + trajectoire
        var minX = -2f; var maxX = 2f
        var minZ = -2f; var maxZ = 2f

        for (i in mapPoints.indices step 3) {
            minX = minOf(minX, mapPoints[i])
            maxX = maxOf(maxX, mapPoints[i])
            minZ = minOf(minZ, mapPoints[i + 2])
            maxZ = maxOf(maxZ, mapPoints[i + 2])
        }
        for ((px, pz) in cameraPath) {
            minX = minOf(minX, px); maxX = maxOf(maxX, px)
            minZ = minOf(minZ, pz); maxZ = maxOf(maxZ, pz)
        }
        // Marges
        val padX = (maxX - minX) * 0.15f + 0.5f
        val padZ = (maxZ - minZ) * 0.15f + 0.5f
        minX -= padX; maxX += padX
        minZ -= padZ; maxZ += padZ

        val rangeX = maxX - minX
        val rangeZ = maxZ - minZ
        val scaleX = w / rangeX
        val scaleZ = h / rangeZ
        val scale  = minOf(scaleX, scaleZ)

        // Centre le contenu
        val offX = (w - rangeX * scale) / 2f - minX * scale
        val offZ = (h - rangeZ * scale) / 2f - minZ * scale

        fun toScreenX(wx: Float) = wx * scale + offX
        fun toScreenZ(wz: Float) = wz * scale + offZ

        // Grille 1 m
        val gridStep = bestGridStep(rangeX, rangeZ)
        val gStartX = kotlin.math.floor(minX / gridStep).toInt()
        val gEndX   = kotlin.math.ceil(maxX  / gridStep).toInt()
        val gStartZ = kotlin.math.floor(minZ / gridStep).toInt()
        val gEndZ   = kotlin.math.ceil(maxZ  / gridStep).toInt()

        for (i in gStartX..gEndX) {
            val sx = toScreenX(i * gridStep)
            canvas.drawLine(sx, 0f, sx, h, gridPaint)
            canvas.drawText("${i * gridStep.toInt()}m", sx + 2, h - 4, labelPaint)
        }
        for (j in gStartZ..gEndZ) {
            val sz = toScreenZ(j * gridStep)
            canvas.drawLine(0f, sz, w, sz, gridPaint)
        }

        // Axes X et Z à l'origine
        canvas.drawLine(toScreenX(0f), 0f, toScreenX(0f), h, axisPaint)
        canvas.drawLine(0f, toScreenZ(0f), w, toScreenZ(0f), axisPaint)

        // MapPoints (landmarks 3D projetés X-Z)
        val mpRadius = (3f * scale / 100f).coerceIn(2f, 6f)
        for (i in mapPoints.indices step 3) {
            canvas.drawCircle(
                toScreenX(mapPoints[i]),
                toScreenZ(mapPoints[i + 2]),
                mpRadius, mapPointPaint
            )
        }

        // Trajectoire caméra
        if (cameraPath.size > 1) {
            val path = Path()
            path.moveTo(toScreenX(cameraPath[0].first), toScreenZ(cameraPath[0].second))
            for (idx in 1 until cameraPath.size) {
                path.lineTo(toScreenX(cameraPath[idx].first), toScreenZ(cameraPath[idx].second))
            }
            canvas.drawPath(path, pathPaint)
        }

        // Position courante
        canvas.drawCircle(toScreenX(currentX), toScreenZ(currentZ), 10f, currentPosPaint)

        // Info overlay
        val mpCount = mapPoints.size / 3
        val label = "Pts: $mpCount  |  KF: ${cameraPath.size}"
        canvas.drawText(label, 12f, 36f, infoPaint)
    }

    private fun bestGridStep(rangeX: Float, rangeZ: Float): Float {
        val maxRange = maxOf(rangeX, rangeZ)
        return when {
            maxRange <= 5f   -> 1f
            maxRange <= 20f  -> 2f
            maxRange <= 50f  -> 5f
            maxRange <= 100f -> 10f
            else             -> 20f
        }
    }
}
