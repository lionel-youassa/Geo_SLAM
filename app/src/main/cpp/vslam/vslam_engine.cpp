/**
 * vslam_engine.cpp — Moteur vSLAM optimisé pour OpenCV 5.0.
 * Correction du drift : Ajout d'un seuil de parallaxe pour l'immobilité.
 */

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <vector>
#include <mutex>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/geometry.hpp>

#define LOG_TAG "GeoSlam_vSLAM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

static std::recursive_mutex g_slam_mtx;

enum class TState { INIT = 0, INITIALIZING = 1, TRACKING = 2, LOST = 4 };
static TState g_state = TState::INIT;

static cv::Mat g_prev_desc;
static std::vector<cv::KeyPoint> g_prev_kps;

static double g_fx = 500.0, g_fy = 500.0, g_cx = 320.0, g_cy = 240.0;
static double g_scale = 0.5;
static double g_accum_real_dist = 0.0;
static double g_accum_vis_dist = 0.0;
static float  g_curr_accel = 0.0f; // Stockage de l'accélération linéaire actuelle

static cv::Ptr<cv::ORB> g_orb = cv::ORB::create(3000);
static cv::Ptr<cv::BFMatcher> g_matcher = cv::BFMatcher::create(cv::NORM_HAMMING);

static int g_lost_frames = 0;
static const int MAX_LOST_FRAMES = 25;

static jobject   g_jobj = nullptr;
static jmethodID g_pose_mid = nullptr;

static cv::Mat getK() {
    cv::Mat K = cv::Mat::eye(3, 3, CV_64F);
    K.at<double>(0, 0) = g_fx; K.at<double>(0, 2) = g_cx;
    K.at<double>(1, 1) = g_fy; K.at<double>(1, 2) = g_cy;
    return K;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_vslam_VSlamManager_setCameraIntrinsics(JNIEnv*, jobject, jdouble fx, jdouble fy, jdouble cx, jdouble cy) {
    std::lock_guard<std::recursive_mutex> lock(g_slam_mtx);
    g_fx = fx; g_fy = fy; g_cx = cx; g_cy = cy;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_vslam_VSlamManager_updateFootSlamDisplacement(JNIEnv*, jobject, jfloat dx, jfloat dy, jfloat dz) {
    std::lock_guard<std::recursive_mutex> lock(g_slam_mtx);
    float d = std::sqrt(dx*dx + dy*dy + dz*dz);
    g_curr_accel = d; // On utilise le delta du footslam comme indicateur de mouvement physique
    g_accum_real_dist += d;
    if (g_accum_real_dist > 0.1 && g_accum_vis_dist > 0.5) {
        double new_scale = g_accum_real_dist / g_accum_vis_dist;
        if (new_scale > 0.001 && new_scale < 5.0) g_scale = 0.7 * g_scale + 0.3 * new_scale;
        g_accum_real_dist = 0; g_accum_vis_dist = 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_vslam_VSlamManager_resetTracking(JNIEnv*, jobject) {
    std::lock_guard<std::recursive_mutex> lock(g_slam_mtx);
    g_state = TState::INIT;
    g_lost_frames = 0;
    g_prev_desc.release();
}

extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_vslam_VSlamManager_nativeRelease(JNIEnv* env, jobject) {
    std::lock_guard<std::recursive_mutex> lock(g_slam_mtx);
    if (g_jobj) { env->DeleteGlobalRef(g_jobj); g_jobj = nullptr; }
}

extern "C" JNIEXPORT jint JNICALL Java_com_example_geo_1slam_vslam_VSlamManager_getTrackingState(JNIEnv*, jobject) {
    return (int)g_state;
}

extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_vslam_VSlamManager_processFrame(JNIEnv* env, jobject thiz, jbyteArray frameData, jint width, jint height, jint rowStride) {
    std::lock_guard<std::recursive_mutex> lock(g_slam_mtx);
    if (!g_jobj) {
        g_jobj = env->NewGlobalRef(thiz);
        jclass cls = env->GetObjectClass(thiz);
        g_pose_mid = env->GetMethodID(cls, "onPoseEstimated", "(FFF)V");
    }

    jbyte* data = env->GetByteArrayElements(frameData, nullptr);
    if (!data) return;
    cv::Mat gray(height, width, CV_8UC1, reinterpret_cast<uint8_t*>(data), (size_t)rowStride);
    std::vector<cv::KeyPoint> kps;
    cv::Mat desc;
    try {
        g_orb->detectAndCompute(gray, cv::noArray(), kps, desc);
    } catch (...) {
        env->ReleaseByteArrayElements(frameData, data, JNI_ABORT);
        return;
    }
    env->ReleaseByteArrayElements(frameData, data, JNI_ABORT);

    float dx = 0, dy = 0, dz = 0;

    if (desc.empty() || kps.size() < 25) {
        g_lost_frames++;
        if (g_lost_frames > MAX_LOST_FRAMES) g_state = TState::LOST;
    } else {
        if (g_state == TState::INIT || g_state == TState::LOST || g_prev_desc.empty()) {
            g_prev_desc = desc.clone(); g_prev_kps = kps;
            g_state = TState::INITIALIZING;
            g_lost_frames = 0;
        } else {
            std::vector<std::vector<cv::DMatch>> knn_matches;
            g_matcher->knnMatch(g_prev_desc, desc, knn_matches, 2);
            std::vector<cv::Point2f> pts_prev, pts_curr;
            float total_dist = 0;
            for (auto& m : knn_matches) {
                if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
                    cv::Point2f p1 = g_prev_kps[m[0].queryIdx].pt;
                    cv::Point2f p2 = kps[m[0].trainIdx].pt;
                    pts_prev.push_back(p1);
                    pts_curr.push_back(p2);
                    total_dist += cv::norm(p1 - p2);
                }
            }

            // SEUIL DE BRUIT (Parallaxe minimale)
            float avg_parallax = pts_curr.empty() ? 0 : (total_dist / pts_curr.size());

            // CONDITION DE MOUVEMENT : Parallaxe suffisante ET accélération physique détectée
            bool is_physically_moving = g_curr_accel > 0.005f;

            if (pts_curr.size() >= 12 && avg_parallax > 2.2f && is_physically_moving) {
                cv::Mat E, mask;
                E = cv::findEssentialMat(pts_curr, pts_prev, getK(), cv::RANSAC, 0.999, 1.0, 100, mask);
                if (!E.empty() && E.rows == 3) {
                    cv::Mat R_rel, t_rel;
                    int inliers = cv::recoverPose(E, pts_curr, pts_prev, getK(), R_rel, t_rel, mask);
                    if (inliers > 10) {
                        float l_dx = t_rel.at<double>(0);
                        float l_dy = t_rel.at<double>(1);
                        float l_dz = t_rel.at<double>(2);

                        if (l_dz < -0.05f) l_dz = 0;

                        // La magnitude est maintenant indexée sur la parallaxe pour éviter les sauts
                        float magnitude = (avg_parallax - 1.8f) * 0.025f;
                        if (magnitude < 0) magnitude = 0;

                        dx = l_dx * magnitude * (float)g_scale;
                        dy = l_dy * magnitude * (float)g_scale;
                        dz = l_dz * magnitude * (float)g_scale;

                        g_accum_vis_dist += std::sqrt(dx*dx + dy*dy + dz*dz);
                        g_prev_desc = desc.clone(); g_prev_kps = kps;
                        g_lost_frames = 0;
                        g_state = TState::TRACKING;
                    }
                }
            } else {
                // On est immobile (soit visuellement, soit physiquement)
                g_lost_frames = 0;
                if (g_state != TState::INITIALIZING) g_state = TState::TRACKING;
                dx = 0; dy = 0; dz = 0;
            }
        }
    }

    if (g_pose_mid && g_jobj) {
        // Correction de la vitesse (+70% par rapport au réglage précédent)
        // 0.45f * 1.70 = ~0.77f
        env->CallVoidMethod(g_jobj, g_pose_mid, dx * 0.77f, dy * 0.77f, dz * 0.77f);
    }
}
