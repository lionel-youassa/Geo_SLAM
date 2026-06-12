#include <jni.h>
#include <android/log.h>

#define LOG_TAG "GeoSlam_UI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Structure pour stocker la position de la caméra partagée entre Lionel et Sonia
struct CameraPosition {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
} g_camera_pos;

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_ui_MapRenderer_renderFrame(
        JNIEnv* env, jobject thiz) {
    // Sonia : Ton code de rendu 3D (OpenGL ES / Filament) ira ici.
    // Tu peux utiliser g_camera_pos pour déplacer ton monde 3D.
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_ui_MapRenderer_updateCameraPosition(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z) {

    g_camera_pos.x = x;
    g_camera_pos.y = y;
    g_camera_pos.z = z;

    // LOGI("Mise à jour caméra UI : %.2f, %.2f, %.2f", x, y, z);
}
