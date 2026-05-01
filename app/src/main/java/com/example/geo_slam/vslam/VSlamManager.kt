package com.example.geo_slam.vslam

import android.graphics.Bitmap

class VSlamManager {
    /**
     * Narcisse : Cette méthode enverra les frames caméra au moteur C++ (ORB-SLAM3)
     */
    external fun processFrame(frameAddr: Long)

    companion object {
        init {
            System.loadLibrary("geo_slam")
        }
    }
}