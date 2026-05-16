#include <jni.h>
#include <android/log.h>
#include <cstdint>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>

#define LOG_TAG "GeoSlam_vSLAM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static jobject   g_vslam_obj  = nullptr;
static jmethodID g_pose_mid   = nullptr;
static long      g_frame_count = 0;

// Détecteur ORB — 500 features, bon compromis vitesse/précision sur mobile
static cv::Ptr<cv::ORB> g_orb = cv::ORB::create(500);

// Reçoit chaque frame YUV depuis Camera2 sous forme de ByteArray Kotlin
// rowStride peut différer de width sur certains devices (padding hardware)
extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_processFrame(
        JNIEnv* env, jobject thiz,
        jbyteArray frameData, jint width, jint height, jint rowStride) {

    // Enregistre la référence JNI au premier appel
    if (g_vslam_obj == nullptr) {
        g_vslam_obj = env->NewGlobalRef(thiz);
        jclass clazz = env->GetObjectClass(thiz);
        g_pose_mid = env->GetMethodID(clazz, "onPoseEstimated", "(FFF)V");
        LOGI("VSlamManager JNI initialisé — OpenCV %s", CV_VERSION);
    }

    g_frame_count++;

    jbyte* data = env->GetByteArrayElements(frameData, nullptr);
    if (data == nullptr) {
        LOGE("processFrame : GetByteArrayElements échoué.");
        return;
    }

    jsize arrayLen = env->GetArrayLength(frameData);

    // Wrap du plan Y (luminance) avec rowStride correct pour éviter les artefacts
    cv::Mat gray(height, width, CV_8UC1, reinterpret_cast<uint8_t*>(data),
                 static_cast<size_t>(rowStride));

    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    g_orb->detectAndCompute(gray, cv::noArray(), keypoints, descriptors);

    env->ReleaseByteArrayElements(frameData, data, JNI_ABORT);

    // TODO Semaine 3 : matching inter-frames + estimation de pose (Essential Matrix)
    // TODO Semaine 4 : intégration ORB-SLAM3 complet

    if (g_frame_count % 30 == 0) {
        int nonZero = cv::countNonZero(gray);
        double minVal, maxVal;
        cv::minMaxLoc(gray, &minVal, &maxVal);
        LOGI("Frame %ld — %zu keypoints | nonZero=%d | min=%.0f max=%.0f | arrayLen=%d | stride=%d",
             g_frame_count, keypoints.size(), nonZero, minVal, maxVal, arrayLen, rowStride);
    }

    // Renvoie pose nulle (à remplacer par l'estimation réelle)
    if (g_pose_mid != nullptr) {
        env->CallVoidMethod(g_vslam_obj, g_pose_mid, 0.0f, 0.0f, 0.0f);
    }
}
