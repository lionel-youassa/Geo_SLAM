#include <jni.h>

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processInertialData(
        JNIEnv* env, jobject thiz, jfloatArray accel, jfloatArray gyro, jlong timestamp) {
    // Lionel : Ton code FootSLAM IA (RoNIN / TLIO)
}