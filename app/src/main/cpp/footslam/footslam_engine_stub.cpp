#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#define LOG_TAG "GeoSlam_FootSLAM_Stub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Ce stub est utilisé si TensorFlow Lite n'est pas trouvé par CMake au moment de la compilation.
// Il permet à l'application de démarrer sans crash (UnsatisfiedLinkError) même si le moteur IA est inactif.

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_loadModelNative(
        JNIEnv* env, jobject thiz, jobject am, jstring path) {
    LOGW("FootSLAM stub : loadModelNative appelé (Moteur IA non compilé).");
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processAccelerometer(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong ts) {}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processGyroscope(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong ts) {}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processYawNative(
        JNIEnv* env, jobject thiz, jfloat yaw, jlong ts) {}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processPressure(
        JNIEnv* env, jobject thiz, jfloat p, jlong ts) {}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processStepDetectorNative(
        JNIEnv* env, jobject thiz, jlong ts) {}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_setStepDetectorSupportedNative(
        JNIEnv* env, jobject thiz, jboolean supported) {
    LOGW("FootSLAM stub : setStepDetectorSupportedNative appelé.");
}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_resetPositionNative(
        JNIEnv* env, jobject thiz) {}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_setInitialPositionNative(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y) {}

JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_setWallsNative(
        JNIEnv* env, jobject thiz, jfloatArray walls) {}

}
