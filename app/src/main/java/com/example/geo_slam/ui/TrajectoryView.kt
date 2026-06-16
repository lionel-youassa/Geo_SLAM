package com.example.geo_slam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class TrajectoryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val pathPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#D3D3D3")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val trajectoryPath = Path()
    private var currentX = 0f
    private var currentY = 0f
    private var currentZ = 0f
    private var isFirstPoint = true
    
    // Échelle : 100 pixels pour 1 mètre
    private val scale = 100f

    /**
     * Mise à jour avec prise en charge de l'altitude Z
     */
    fun updatePosition(x: Float, y: Float, z: Float) {
        currentX = x
        currentY = y
        currentZ = z
        
        val centerX = width / 2f
        val centerY = height / 2f
        val drawX = centerX + currentX * scale
        val drawY = centerY - currentY * scale

        if (isFirstPoint) {
            trajectoryPath.moveTo(drawX, drawY)
            isFirstPoint = false
        } else {
            trajectoryPath.lineTo(drawX, drawY)
        }
        
        invalidate()
    }

    fun clear() {
        trajectoryPath.reset()
        isFirstPoint = true
        currentX = 0f
        currentY = 0f
        currentZ = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f

        // Dessin d'une grille (tous les 1 mètre)
        for (i in -20..20) {
            canvas.drawLine(0f, centerY + i * scale, width.toFloat(), centerY + i * scale, gridPaint)
            canvas.drawLine(centerX + i * scale, 0f, centerX + i * scale, height.toFloat(), gridPaint)
        }

        // Dessin du chemin parcouru
        canvas.drawPath(trajectoryPath, pathPaint)

        // Dessin de la position actuelle (Point rouge)
        val drawX = centerX + currentX * scale
        val drawY = centerY - currentY * scale
        
        // VARIATION VISUELLE SELON Z :
        // On fait varier le rayon du cercle entre 8f et 25f selon l'altitude
        val radiusZ = 12f + (currentZ * 5f)
        val finalRadius = radiusZ.coerceIn(5f, 30f)
        
        // Changement de couleur si on change d'étage (Z > 2m)
        if (currentZ > 2.0f) {
            pointPaint.color = Color.GREEN // Étage supérieur
        } else if (currentZ < -1.0f) {
            pointPaint.color = Color.YELLOW // Sous-sol
        } else {
            pointPaint.color = Color.RED // Rez-de-chaussée
        }

        canvas.drawCircle(drawX, drawY, finalRadius, pointPaint)
    }
}
