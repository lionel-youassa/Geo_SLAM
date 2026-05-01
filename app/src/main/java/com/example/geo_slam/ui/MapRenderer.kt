package com.example.geo_slam.ui

import android.view.Surface

class MapRenderer {
    /**
     * Sonia : Cette méthode sera appelée pour dessiner la Map 3D
     * On peut passer la Surface ou gérer le contexte EGL ici.
     */
    external fun renderFrame()

    companion object {
        init {
            System.loadLibrary("geo_slam")
        }
    }
}