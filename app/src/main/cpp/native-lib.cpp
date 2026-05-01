#include <jni.h>
#include <string>
#include <android/log.h>

#define LOG_TAG "GeoSlam_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_MainActivity_processAccelerometer(
        JNIEnv* env,
        jobject /* this */,
        jfloat x, jfloat y, jfloat z, jlong timestamp) {
    // Logique pour Track A : FootSLAM IA
    // Ici on recevra les données à 100Hz pour les injecter dans le modèle RoNIN
    // LOGI("Accel: %f, %f, %f at %lld", x, y, z, timestamp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_MainActivity_processGyroscope(
        JNIEnv* env,
        jobject /* this */,
        jfloat x, jfloat y, jfloat z, jlong timestamp) {
    // Logique pour Track A : FootSLAM IA
    // LOGI("Gyro: %f, %f, %f at %lld", x, y, z, timestamp);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_geo_1slam_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Geo-SLAM Native Engine Ready";
    return env->NewStringUTF(hello.c_str());
}