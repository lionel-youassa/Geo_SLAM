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
    private var isFirstPoint = true
    
    // Échelle : 100 pixels pour 1 mètre
    private val scale = 100f

    fun updatePosition(x: Float, y: Float) {
        currentX = x
        currentY = y
        
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
        canvas.drawCircle(drawX, drawY, 12f, pointPaint)
    }
}
