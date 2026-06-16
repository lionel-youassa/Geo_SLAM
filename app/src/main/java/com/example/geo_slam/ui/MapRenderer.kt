package com.example.geo_slam.ui

import android.view.Surface

class MapRenderer {
    /**
     * Sonia : Cette méthode dessine la Map 3D (OpenGL ES)
     */
    external fun renderFrame()

    /**
     * Lionel : Met à jour la position de la caméra
     */
    external fun updateCameraPosition(x: Float, y: Float, z: Float)

    /**
     * Permet de lier l'interface Android au moteur de rendu natif
     */
    external fun setSurface(surface: Surface?)

    companion object {
        init {
            System.loadLibrary("geo_slam")
        }
    }
}