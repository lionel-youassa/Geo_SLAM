#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#define LOG_TAG "GeoSlam_FootSLAM_Stub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Stub actif car TFLite C++ headers non disponibles via Prefab.
// Lionel : remplace ce fichier en résolvant le setup TFLite NDK dans CMakeLists.txt.

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_loadModelNative(
        JNIEnv* env, jobject thiz, jobject asset_manager, jstring model_path) {
    LOGW("FootSLAM stub : loadModelNative appelé (TFLite C++ non disponible).");
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processAccelerometer(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong timestamp) {}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processGyroscope(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong timestamp) {}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processOrientation(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jfloat w, jlong timestamp) {}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_resetPositionNative(
        JNIEnv* env, jobject thiz) {}
