#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>

#define LOG_TAG "GeoSlam_vSLAM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const int    MIN_INLIERS     = 8;
static const double MIN_AVG_MOTION  = 5.0;  // pixels — ignore si caméra quasi-statique

// ── Détecteur & matcher ────────────────────────────────────────────────────
static cv::Ptr<cv::ORB>       g_orb     = cv::ORB::create(500);
static cv::Ptr<cv::BFMatcher> g_matcher = cv::BFMatcher::create(cv::NORM_HAMMING);

// ── État inter-frames ──────────────────────────────────────────────────────
static cv::Mat g_prev_descriptors;
static std::vector<cv::KeyPoint> g_prev_keypoints;

// ── Pose accumulée ─────────────────────────────────────────────────────────
static cv::Mat g_R_world = cv::Mat::eye(3, 3, CV_64F);
static cv::Mat g_t_world = cv::Mat::zeros(3, 1, CV_64F);

// ── Intrinsèques caméra ───────────────────────────────────────────────────
static double g_fx = 500.0, g_fy = 500.0;
static double g_cx = 320.0, g_cy = 240.0;

// ── Références JNI ────────────────────────────────────────────────────────
static jobject   g_vslam_obj  = nullptr;
static jmethodID g_pose_mid   = nullptr;
static long      g_frame_count = 0;

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_setCameraIntrinsics(
        JNIEnv*, jobject, jdouble fx, jdouble fy, jdouble cx, jdouble cy) {
    g_fx = fx; g_fy = fy; g_cx = cx; g_cy = cy;
    LOGI("Intrinsèques mises à jour — fx=%.1f fy=%.1f cx=%.1f cy=%.1f", fx, fy, cx, cy);
}

// Calcule le déplacement pixel moyen entre deux ensembles de points
static double avgPixelMotion(const std::vector<cv::Point2f>& a,
                             const std::vector<cv::Point2f>& b) {
    double sum = 0;
    for (size_t i = 0; i < a.size(); i++) {
        double dx = b[i].x - a[i].x, dy = b[i].y - a[i].y;
        sum += std::sqrt(dx*dx + dy*dy);
    }
    return a.empty() ? 0 : sum / a.size();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_processFrame(
        JNIEnv* env, jobject thiz,
        jbyteArray frameData, jint width, jint height, jint rowStride) {

    if (g_vslam_obj == nullptr) {
        g_vslam_obj = env->NewGlobalRef(thiz);
        jclass clazz = env->GetObjectClass(thiz);
        g_pose_mid = env->GetMethodID(clazz, "onPoseEstimated", "(FFF)V");
        LOGI("VSlamManager JNI initialisé — OpenCV %s", CV_VERSION);
    }

    g_frame_count++;

    jbyte* data = env->GetByteArrayElements(frameData, nullptr);
    if (!data) { LOGE("GetByteArrayElements échoué."); return; }

    cv::Mat gray(height, width, CV_8UC1,
                 reinterpret_cast<uint8_t*>(data),
                 static_cast<size_t>(rowStride));

    // ── 1. Extraction ORB ────────────────────────────────────────────────
    std::vector<cv::KeyPoint> keypoints;
    cv::Mat descriptors;
    g_orb->detectAndCompute(gray, cv::noArray(), keypoints, descriptors);

    env->ReleaseByteArrayElements(frameData, data, JNI_ABORT);

    float pose_x = (float)g_t_world.at<double>(0);
    float pose_y = (float)g_t_world.at<double>(1);
    float pose_z = (float)g_t_world.at<double>(2);

    if (!g_prev_descriptors.empty() && !descriptors.empty()
            && keypoints.size() >= 5 && g_prev_keypoints.size() >= 5) {

        // ── 2. Matching ORB + test de ratio de Lowe ──────────────────────
        std::vector<std::vector<cv::DMatch>> knn_matches;
        g_matcher->knnMatch(g_prev_descriptors, descriptors, knn_matches, 2);

        std::vector<cv::DMatch> good;
        for (auto& m : knn_matches) {
            if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance)
                good.push_back(m[0]);
        }

        if ((int)good.size() >= MIN_INLIERS) {
            std::vector<cv::Point2f> pts_prev, pts_curr;
            pts_prev.reserve(good.size());
            pts_curr.reserve(good.size());
            for (auto& m : good) {
                pts_prev.push_back(g_prev_keypoints[m.queryIdx].pt);
                pts_curr.push_back(keypoints[m.trainIdx].pt);
            }

            // ── 3a. Vérification du mouvement minimum ────────────────────
            double avgMotion = avgPixelMotion(pts_prev, pts_curr);
            if (avgMotion < MIN_AVG_MOTION) {
                if (g_frame_count % 30 == 0)
                    LOGI("Frame %ld — caméra quasi-statique (%.1fpx) — pose non mise à jour.",
                         g_frame_count, avgMotion);
                goto update_prev;
            }

            {
                cv::Mat K = (cv::Mat_<double>(3,3)
                    << g_fx, 0,    g_cx,
                       0,    g_fy, g_cy,
                       0,    0,    1);

                // ── 3b. Essential Matrix (mouvement général) ──────────────
                cv::Mat mask_E;
                cv::Mat E = cv::findEssentialMat(pts_prev, pts_curr, K,
                                                 cv::RANSAC, 0.999, 1.0, mask_E);

                int inliers_E = 0;
                cv::Mat R_E, t_E;
                if (!E.empty()) {
                    inliers_E = cv::recoverPose(E, pts_prev, pts_curr, K,
                                                R_E, t_E, mask_E);
                }

                // ── 3c. Homographie (scène planaire / rotation pure) ──────
                cv::Mat mask_H;
                cv::Mat H = cv::findHomography(pts_prev, pts_curr,
                                               cv::RANSAC, 3.0, mask_H);
                int inliers_H = 0;
                if (!H.empty())
                    inliers_H = cv::countNonZero(mask_H);

                // ── 3d. Sélection du meilleur modèle ─────────────────────
                bool use_essential = (inliers_E >= MIN_INLIERS) &&
                                     (inliers_E >= inliers_H * 0.9);

                if (use_essential && inliers_E >= MIN_INLIERS) {
                    // Translation unitaire (échelle monoculaire relative)
                    g_t_world += g_R_world * t_E;
                    g_R_world  = R_E * g_R_world;

                    pose_x = (float)g_t_world.at<double>(0);
                    pose_y = (float)g_t_world.at<double>(1);
                    pose_z = (float)g_t_world.at<double>(2);

                    if (g_frame_count % 30 == 0)
                        LOGI("Frame %ld — %zu kp | %zu good | E:%d H:%d inliers | motion=%.1fpx | pos=(%.2f,%.2f,%.2f)",
                             g_frame_count, keypoints.size(), good.size(),
                             inliers_E, inliers_H, avgMotion,
                             pose_x, pose_y, pose_z);
                } else if (g_frame_count % 30 == 0) {
                    // Homographie dominante = rotation/scène planaire
                    LOGI("Frame %ld — %zu kp | E:%d H:%d | motion=%.1fpx | modèle H dominant (rotation/plan) — pos stable.",
                         g_frame_count, keypoints.size(), inliers_E, inliers_H, avgMotion);
                }
            }
        } else if (g_frame_count % 30 == 0) {
            LOGI("Frame %ld — %zu kp | %zu good matches (< %d requis).",
                 g_frame_count, keypoints.size(), good.size(), MIN_INLIERS);
        }
    } else if (g_frame_count % 30 == 0) {
        LOGI("Frame %ld — %zu kp | initialisation.", g_frame_count, keypoints.size());
    }

    update_prev:
    // ── 4. Mise à jour état précédent ────────────────────────────────────
    g_prev_keypoints = keypoints;
    descriptors.copyTo(g_prev_descriptors);

    // TODO Semaine 4 : intégration ORB-SLAM3 complet (DBoW2, g2o, loop closure)

    if (g_pose_mid != nullptr) {
        env->CallVoidMethod(g_vslam_obj, g_pose_mid, pose_x, pose_y, pose_z);
    }
}
