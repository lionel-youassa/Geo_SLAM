// com/example/geo_slam/ui/map/FloorPlan.kt
package com.example.geo_slam.ui.map

data class WallSegment(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
data class ZoneLabel(val name: String, val cx: Float, val cy: Float, val color: Int)
data class Point2D(val x: Float, val y: Float)

data class FloorPlan(
    val widthInMeters: Float,
    val heightInMeters: Float,
    val doorX: Float,
    val doorY: Float,
    val outerWalls: List<WallSegment>,
    val innerWalls: List<WallSegment>,
    val zones: List<ZoneLabel>,
    val processProductionPolygon: List<Point2D> = emptyList()
) {
    companion object {
        fun empty() = FloorPlan(0f, 0f, 0f, 0f, emptyList(), emptyList(), emptyList())

        fun laboVectoriel(): FloorPlan {
            val totalW  = 19.1f    
            val totalH  = 13.4f    
            val splitX  = 12.65f   
            val rightH  =  9.17f   
            val gdsSep  = 13.9f    
            val qcW     =  4.81f   
            val sdcW    =  4.39f   
            val qcH     =  1.15f   // Correction: QC et SdC ont la même hauteur de 1.15m
            val sdcH    =  1.15f   
            val botSep  =  8.78f   
            val rIoTW   =  5.19f   
            val mainW   =  4.67f   
            val warW    =  2.73f   

            // ── 1. MURS EXTÉRIEURS ──────────────────────────────
            val outer = listOf(
                WallSegment(0f,     0f,     totalW, 0f    ),  
                WallSegment(0f,     0f,     0f,     totalH),  
                WallSegment(0f,     totalH, splitX, totalH),  
                WallSegment(splitX, totalH, splitX, rightH),  
                WallSegment(splitX, rightH, totalW, rightH),  
                WallSegment(totalW, rightH, totalW, 0f    ),  
            )

            // ── 2. CLOISONS INTERNES ────────────────────────────
            val inner = listOf(
                // ── Zone HAUTE GAUCHE : Quality Control ──
                WallSegment(qcW, 0f, qcW, qcH),             
                WallSegment(0f, qcH, qcW, qcH),             

                // ── Zone HAUTE MILIEU : Salle de Contrôle ──
                WallSegment(qcW + sdcW, 0f, qcW + sdcW, sdcH), 
                WallSegment(qcW, sdcH, qcW + sdcW, sdcH),      

                // ── Zone DROITE : Gestion des stocks ──
                WallSegment(gdsSep, 0f, gdsSep, 5.2f),         
                WallSegment(gdsSep, 5.2f, totalW, 5.2f),       

                // ── Zone BASSE : Robotique IoT | Maintenance VR | War Room ──
                WallSegment(0f, botSep, splitX, botSep),                 
                WallSegment(rIoTW, botSep, rIoTW, totalH),               
                WallSegment(rIoTW + mainW, botSep, rIoTW + mainW, totalH), 
            )

            // ── 3. POLYGONE PROCESS PRODUCTION (Mesures exactes en rouge) ──
            val ppRight = gdsSep - 1.2f // Gap de 1.2m avec Gestion des stocks
            val ppLeft = ppRight - 11.44f // Largeur haut de 11.44m
            val ppTop = qcH + 0.57f // 57cm en dessous de QC/SdC
            val ppBottomY = botSep - 0.60f // 60cm au dessus de Maintenance VR
            
            val processProductionPoly = listOf(
                Point2D(ppLeft, ppTop), // Coin Haut-Gauche
                Point2D(ppRight, ppTop), // Coin Haut-Droit
                Point2D(ppRight, ppTop + 3.27f), // Descend de 3.27m
                Point2D(ppRight - 4.51f, ppTop + 3.27f), // Va à gauche de 4.51m
                Point2D(ppRight - 4.51f, ppBottomY), // Descend jusqu'à 60cm du bas
                Point2D(ppRight - 4.51f - 2.76f, ppBottomY), // Va à gauche de 2.76m
                Point2D(ppRight - 4.51f - 2.76f, ppBottomY - 3.07f), // Remonte de 3.07m
                Point2D(ppLeft, ppBottomY - 3.07f) // Ferme à gauche
            )

            // ── 4. LABELS DE ZONES ──────────────────────────────
            val zones = listOf(
                ZoneLabel("Quality Control", qcW / 2f, qcH / 2f, 0x2542A5F5),
                ZoneLabel("Salle de Contrôle", qcW + sdcW / 2f, sdcH / 2f, 0x2566BB6A),
                ZoneLabel("Gestion des stocks", gdsSep + (totalW - gdsSep) / 2f, 5.2f / 2f, 0x25AB47BC),
                ZoneLabel("Robotique IoT", rIoTW / 2f, botSep + (totalH - botSep) / 2f, 0x2526C6DA),
                ZoneLabel("Maintenance VR", rIoTW + mainW / 2f, botSep + (totalH - botSep) / 2f, 0x25FFCA28),
                ZoneLabel("War Room", rIoTW + mainW + warW / 2f, botSep + (totalH - botSep) / 2f, 0x25EF5350),
                // Centrage approximatif du label dans la plus grande partie du polygone
                ZoneLabel("Process Production", ppLeft + 11.44f/2f, ppTop + 3.27f/2f, 0x25FFA726)
            )

            return FloorPlan(totalW, totalH, totalW, 6.5f, outer, inner, zones, processProductionPoly)
        }
    }
}

enum class DisplayMode { FOOT_SLAM, VSLAM, FUSION }