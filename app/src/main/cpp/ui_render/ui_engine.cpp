#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <mutex>

#define LOG_TAG "GeoSlam_UI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Structure partagée
struct {
    float x = 0, y = 0, z = 0;
    ANativeWindow* window = nullptr;
    std::mutex mutex;
} g_ui_state;

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_ui_MapRenderer_setSurface(JNIEnv* env, jobject thiz, jobject surface) {
    std::lock_guard<std::mutex> lock(g_ui_state.mutex);

    if (g_ui_state.window) {
        ANativeWindow_release(g_ui_state.window);
        g_ui_state.window = nullptr;
    }

    if (surface) {
        g_ui_state.window = ANativeWindow_fromSurface(env, surface);
        LOGI("Surface Sonia connectée au moteur natif.");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_ui_MapRenderer_updateCameraPosition(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z) {
    std::lock_guard<std::mutex> lock(g_ui_state.mutex);
    g_ui_state.x = x;
    g_ui_state.y = y;
    g_ui_state.z = z;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_ui_MapRenderer_renderFrame(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_ui_state.mutex);

    if (!g_ui_state.window) return;

    // Ici, Sonia pourra insérer son code de rendu OpenGL ES.
    // Pour l'instant, on prépare juste la structure pour que le point bouge.
}
