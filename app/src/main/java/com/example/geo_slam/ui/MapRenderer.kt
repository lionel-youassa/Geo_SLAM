package com.example.geo_slam.ui

import android.view.Surface

class MapRenderer {
    /**
     * Sonia : Cette méthode sera appelée pour dessiner la Map 3D
     */
    external fun renderFrame()

    /**
     * Lionel : Met à jour la position de la caméra basée sur le FootSLAM
     */
    external fun updateCameraPosition(x: Float, y: Float, z: Float)

    /**
     * Sonia : Initialise la surface d'affichage pour OpenGL
     */
    external fun setSurface(surface: Surface?)

    companion object {
        init {
            System.loadLibrary("geo_slam")
        }
    }
}