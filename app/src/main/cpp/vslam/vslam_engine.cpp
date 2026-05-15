#include <jni.h>
#include <android/log.h>
#include <cstdint>

#define LOG_TAG "GeoSlam_vSLAM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static jobject   g_vslam_obj  = nullptr;
static jmethodID g_pose_mid   = nullptr;
static long      g_frame_count = 0;

// Retourne l'adresse native d'un DirectByteBuffer Java
extern "C" JNIEXPORT jlong JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_getBufferAddress(
        JNIEnv* env, jobject thiz, jobject buffer) {
    return reinterpret_cast<jlong>(env->GetDirectBufferAddress(buffer));
}

// Reçoit chaque frame YUV depuis Camera2
extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_processFrame(
        JNIEnv* env, jobject thiz, jlong frameAddr) {

    if (frameAddr == 0) {
        LOGE("processFrame : adresse frame nulle, ignorée.");
        return;
    }

    // Enregistre la référence JNI au premier appel
    if (g_vslam_obj == nullptr) {
        g_vslam_obj = env->NewGlobalRef(thiz);
        jclass clazz = env->GetObjectClass(thiz);
        g_pose_mid = env->GetMethodID(clazz, "onPoseEstimated", "(FFF)V");
        LOGI("VSlamManager JNI initialisé.");
    }

    g_frame_count++;

    // TODO Semaine 2 : passer le buffer YUV à ORB-SLAM3
    // uint8_t* yuv_data = reinterpret_cast<uint8_t*>(frameAddr);
    // orb_slam3->TrackMonocular(yuv_data, timestamp);

    // Stub : renvoie une pose nulle pour valider le pipeline
    if (g_pose_mid != nullptr) {
        env->CallVoidMethod(g_vslam_obj, g_pose_mid, 0.0f, 0.0f, 0.0f);
    }

    if (g_frame_count % 30 == 0) {
        LOGI("Pipeline vSLAM actif — %ld frames reçues.", g_frame_count);
    }
}
