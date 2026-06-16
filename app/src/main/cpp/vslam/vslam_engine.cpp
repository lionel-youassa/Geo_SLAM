/**
 * vslam_engine.cpp — ORB-SLAM3-inspired monocular SLAM (OpenCV only, Android NDK)
 *
 * Composants implémentés :
 *   1. Initialisation   — Essential Matrix + triangulation initiale
 *   2. Tracking         — PnP RANSAC sur la Local Map + fallback VO
 *   3. Local Mapping    — triangulation de nouveaux MapPoints + élagage
 *   4. Loop Detection   — signature BoW à 256 dim + vérification Essential Matrix
 *   5. Loop Correction  — correction linéaire de trajectoire (sans g2o)
 *   6. Scale calibration— depuis FootSLAM (EMA)
 */

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cmath>
#include <vector>
#include <array>
#include <algorithm>
#include <numeric>
#include <mutex>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>

#define LOG_TAG "GeoSlam_vSLAM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// ══════════════════════════════════════════════════════════════════════════════
// Constantes
// ══════════════════════════════════════════════════════════════════════════════

static const int   ORB_N_FEATURES        = 300;
static const float ORB_SCALE_FACTOR      = 1.2f;
static const int   ORB_N_LEVELS          = 8;
static const int   INIT_MIN_FEATURES     = 80;
static const float INIT_MIN_PARALLAX     = 25.0f;   // pixels
static const int   INIT_MIN_MAPPOINTS    = 30;
static const int   INIT_MAX_ATTEMPTS     = 50;
static const int   PNP_RANSAC_ITERS      = 100;
static const float PNP_REPROJ_ERROR      = 3.0f;    // pixels
static const int   PNP_MIN_INLIERS       = 8;
static const int   PNP_GOOD_INLIERS      = 30;
static const int   KF_MIN_TRACKED        = 60;      // MapPoints trackés min avant nouveau KF
static const int   KF_MIN_INTERVAL       = 6;       // frames min entre deux KF (évite l'explosion)
static const int   KF_MAX_INTERVAL       = 25;      // frames max entre deux KF
static const int   MATCH_HAMMING_THR     = 60;      // distance Hamming max pour un match
static const int   LOOP_TEMPORAL_GAP     = 25;      // KFs min entre deux KFs pour loop
static const float LOOP_SIG_THR          = 0.30f;   // seuil L1 normalisé de signature BoW
static const int   LOOP_MIN_MATCH        = 20;      // matches géométriques min pour valider
static const int   MP_MIN_OBS            = 2;       // observations min pour garder un MapPoint
static const float MP_FOUND_RATIO        = 0.25f;   // ratio found/visible min
static const int   MAX_LOST_FRAMES       = 30;
static const int   MAP_MAX_POINTS        = 3000;
static const int   MAP_MAX_KFS           = 400;

// ══════════════════════════════════════════════════════════════════════════════
// Structures
// ══════════════════════════════════════════════════════════════════════════════

struct MapPoint {
    int         id;
    cv::Point3f pos;
    cv::Mat     desc;        // 1×32 CV_8U
    int         obs;         // observations depuis des KeyFrames
    int         found;       // fois réellement retrouvé en tracking
    int         visible;     // fois projeté dans le frustum
    long        last_frame;
    bool        bad;
};

struct KeyFrame {
    int                       id;
    cv::Mat                   R_cw;    // 3×3 CV_32F
    cv::Mat                   t_cw;   // 3×1 CV_32F
    std::vector<cv::KeyPoint> kps;
    cv::Mat                   desc;   // N×32 CV_8U
    std::vector<int>          mp_ids; // index MapPoint par keypoint, -1 si non mappé
    cv::Mat                   bow_sig; // 256-dim CV_32F pour loop detection
    bool                      bad;
};

// ══════════════════════════════════════════════════════════════════════════════
// État global
// ══════════════════════════════════════════════════════════════════════════════
// g_scale_mtx : protège g_scale et g_vo_dist_accum (écrits depuis deux threads
// distincts — CameraThread via processFrame, SensorThread via updateFootSlamDisplacement)
static std::mutex g_scale_mtx;

enum class TState { INIT, INITIALIZING, TRACKING, RECENTLY_LOST, LOST };

static TState g_state          = TState::INIT;
static int    g_lost_count     = 0;
static int    g_reloc_attempts = 0;   // tentatives de relocalisation consécutives
static long   g_frame_count    = 0;
static long   g_last_kf_frame  = 0;

// Carte — accès toujours par index (jamais de référence/pointeur persistant)
static std::vector<MapPoint> g_mps;
static std::vector<KeyFrame> g_kfs;
static int g_next_mp_id = 0;
static int g_next_kf_id = 0;

// Pose courante (caméra → monde)
static cv::Mat g_R_cw = cv::Mat::eye(3, 3, CV_32F);
static cv::Mat g_t_cw = cv::Mat::zeros(3, 1, CV_32F);

// Trajectoire (positions monde de la caméra, pour nuage de points)
static std::vector<std::array<float, 3>> g_traj;

// Buffer d'initialisation
static std::vector<cv::KeyPoint> g_init_kps;
static cv::Mat                   g_init_desc;
static int                       g_init_attempts = 0;

// Calibration d'échelle depuis FootSLAM
static double g_scale          = -1.0;
static double g_vo_dist_accum  = 0.0;

// Intrinsèques
static double g_fx = 500.0, g_fy = 500.0;
static double g_cx = 320.0, g_cy = 240.0;
static int    g_W  = 640,   g_H  = 480;

// ORB et matcher (partagés, thread-safe en lecture)
static cv::Ptr<cv::ORB>       g_orb     = cv::ORB::create(ORB_N_FEATURES, ORB_SCALE_FACTOR, ORB_N_LEVELS);
static cv::Ptr<cv::BFMatcher> g_matcher = cv::BFMatcher::create(cv::NORM_HAMMING);

// JNI
static jobject   g_jobj      = nullptr;
static jmethodID g_pose_mid  = nullptr;
static jmethodID g_cloud_mid = nullptr;

// Dernière loop closure détectée (index KF)
static int g_last_loop_kf = -(LOOP_TEMPORAL_GAP * 2);

// ══════════════════════════════════════════════════════════════════════════════
// Helpers
// ══════════════════════════════════════════════════════════════════════════════

static cv::Mat getK() {
    return (cv::Mat_<double>(3, 3)
        << g_fx, 0,    g_cx,
           0,    g_fy, g_cy,
           0,    0,    1.0);
}

// Projette le MapPoint d'index mp_idx dans le frame de pose (R_cw, t_cw).
// Retourne false si derrière la caméra ou hors image.
static bool projectMP(int mp_idx, const cv::Mat& R_cw, const cv::Mat& t_cw,
                      float& u, float& v) {
    if (mp_idx < 0 || mp_idx >= (int)g_mps.size() || g_mps[mp_idx].bad)
        return false;
    const cv::Point3f& p = g_mps[mp_idx].pos;
    double X = (double)R_cw.at<float>(0,0)*p.x + R_cw.at<float>(0,1)*p.y + R_cw.at<float>(0,2)*p.z + t_cw.at<float>(0);
    double Y = (double)R_cw.at<float>(1,0)*p.x + R_cw.at<float>(1,1)*p.y + R_cw.at<float>(1,2)*p.z + t_cw.at<float>(1);
    double Z = (double)R_cw.at<float>(2,0)*p.x + R_cw.at<float>(2,1)*p.y + R_cw.at<float>(2,2)*p.z + t_cw.at<float>(2);
    if (Z < 0.01) return false;
    u = (float)(g_fx * X / Z + g_cx);
    v = (float)(g_fy * Y / Z + g_cy);
    return (u >= 0.0f && u < (float)g_W && v >= 0.0f && v < (float)g_H);
}

// Triangule un point 3D à partir de deux correspondances 2D et deux poses.
// Retourne false si la profondeur est négative dans l'une ou l'autre vue.
static bool triangulate(const cv::Point2f& p1, const cv::Point2f& p2,
                        const cv::Mat& R1, const cv::Mat& t1,
                        const cv::Mat& R2, const cv::Mat& t2,
                        cv::Point3f& out) {
    cv::Mat K = getK();
    cv::Mat R1d, t1d, R2d, t2d;
    R1.convertTo(R1d, CV_64F); t1.convertTo(t1d, CV_64F);
    R2.convertTo(R2d, CV_64F); t2.convertTo(t2d, CV_64F);

    cv::Mat Rt1(3, 4, CV_64F, cv::Scalar(0));
    cv::Mat Rt2(3, 4, CV_64F, cv::Scalar(0));
    R1d.copyTo(Rt1.colRange(0, 3)); t1d.copyTo(Rt1.col(3));
    R2d.copyTo(Rt2.colRange(0, 3)); t2d.copyTo(Rt2.col(3));

    cv::Mat P1 = K * Rt1;
    cv::Mat P2 = K * Rt2;

    cv::Mat pts4D;
    cv::triangulatePoints(P1, P2,
        std::vector<cv::Point2f>{p1},
        std::vector<cv::Point2f>{p2},
        pts4D);

    float w = pts4D.at<float>(3, 0);
    if (std::abs(w) < 1e-7f) return false;
    float X = pts4D.at<float>(0, 0) / w;
    float Y = pts4D.at<float>(1, 0) / w;
    float Z = pts4D.at<float>(2, 0) / w;
    if (Z < 0.0f) return false;

    // Vérifier profondeur positive dans la deuxième vue
    double Z2 = R2d.at<double>(2,0)*X + R2d.at<double>(2,1)*Y + R2d.at<double>(2,2)*Z
                + t2d.at<double>(2);
    if (Z2 < 0.0) return false;

    out = cv::Point3f(X, Y, Z);
    return true;
}

// Signature BoW simplifiée : fréquence de chaque bit parmi tous les descripteurs.
// Résultat : vecteur de 256 floats dans [0,1].
static cv::Mat computeBowSig(const cv::Mat& desc) {
    cv::Mat sig = cv::Mat::zeros(1, 256, CV_32F);
    if (desc.empty()) return sig;
    for (int i = 0; i < desc.rows; i++) {
        const uchar* row = desc.ptr<uchar>(i);
        for (int b = 0; b < 32; b++) {
            uchar byte = row[b];
            for (int bit = 0; bit < 8; bit++) {
                if (byte & (1 << bit))
                    sig.at<float>(0, b * 8 + bit) += 1.0f;
            }
        }
    }
    sig /= (float)desc.rows;
    return sig;
}

// Distance L1 normalisée entre deux signatures BoW (résultat dans [0,1]).
static float bowDist(const cv::Mat& a, const cv::Mat& b) {
    if (a.empty() || b.empty()) return 1.0f;
    float s = 0.0f;
    const float* pa = a.ptr<float>();
    const float* pb = b.ptr<float>();
    for (int i = 0; i < 256; i++) s += std::abs(pa[i] - pb[i]);
    return s / 256.0f;
}

// Position monde de la caméra, mise à l'échelle FootSLAM.
static std::array<float, 3> cameraPosWorld() {
    cv::Mat tw = -g_R_cw.t() * g_t_cw;
    double sc;
    { std::lock_guard<std::mutex> lk(g_scale_mtx); sc = (g_scale > 0) ? g_scale : 1.0; }
    return { (float)(tw.at<float>(0) * sc),
             (float)(tw.at<float>(1) * sc),
             (float)(tw.at<float>(2) * sc) };
}

// ══════════════════════════════════════════════════════════════════════════════
// Reset
// ══════════════════════════════════════════════════════════════════════════════

static void doReset() {
    g_mps.clear();  g_mps.reserve(MAP_MAX_POINTS);
    g_kfs.clear();  g_kfs.reserve(MAP_MAX_KFS);
    g_traj.clear();
    g_next_mp_id     = 0;
    g_next_kf_id     = 0;
    g_R_cw           = cv::Mat::eye(3, 3, CV_32F);
    g_t_cw           = cv::Mat::zeros(3, 1, CV_32F);
    g_state          = TState::INIT;
    g_lost_count     = 0;
    g_reloc_attempts = 0;
    g_frame_count    = 0;
    g_last_kf_frame  = 0;
    g_scale          = -1.0;
    g_vo_dist_accum  = 0.0;
    g_init_desc.release();
    g_init_kps.clear();
    g_init_attempts  = 0;
    g_last_loop_kf   = -(LOOP_TEMPORAL_GAP * 2);
    LOGI("SLAM réinitialisé.");
}

// ══════════════════════════════════════════════════════════════════════════════
// 1. Initialisation — Essential Matrix + triangulation
// ══════════════════════════════════════════════════════════════════════════════

static bool tryInitialize(const std::vector<cv::KeyPoint>& kps2, const cv::Mat& desc2) {
    // Matching avec filtre de ratio de Lowe
    std::vector<std::vector<cv::DMatch>> knn;
    g_matcher->knnMatch(g_init_desc, desc2, knn, 2);

    std::vector<cv::DMatch> good;
    std::vector<cv::Point2f> pts1, pts2;
    for (auto& m : knn) {
        if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
            good.push_back(m[0]);
            pts1.push_back(g_init_kps[m[0].queryIdx].pt);
            pts2.push_back(kps2[m[0].trainIdx].pt);
        }
    }
    if ((int)good.size() < 30) return false;

    // Vérification de la parallaxe médiane
    std::vector<float> pxs;
    pxs.reserve(pts1.size());
    for (size_t i = 0; i < pts1.size(); i++) {
        float dx = pts2[i].x - pts1[i].x, dy = pts2[i].y - pts1[i].y;
        pxs.push_back(std::sqrt(dx*dx + dy*dy));
    }
    std::nth_element(pxs.begin(), pxs.begin() + pxs.size()/2, pxs.end());
    if (pxs[pxs.size()/2] < INIT_MIN_PARALLAX) return false;

    // Essential Matrix
    cv::Mat mask_E, R21d, t21d;
    cv::Mat E = cv::findEssentialMat(pts1, pts2, getK(), cv::RANSAC, 0.999, 1.0, mask_E);
    if (E.empty()) return false;
    int n_in = cv::recoverPose(E, pts1, pts2, getK(), R21d, t21d, mask_E);
    if (n_in < 20) return false;

    cv::Mat R1f = cv::Mat::eye(3, 3, CV_32F);
    cv::Mat t1f = cv::Mat::zeros(3, 1, CV_32F);
    cv::Mat R2f, t2f;
    R21d.convertTo(R2f, CV_32F);
    t21d.convertTo(t2f, CV_32F);

    // Triangulation des inliers
    std::vector<int> mp_ids1(g_init_kps.size(), -1);
    std::vector<int> mp_ids2(kps2.size(), -1);
    int n_tri = 0;

    for (int i = 0; i < (int)good.size(); i++) {
        if (!mask_E.at<uchar>(i)) continue;
        if ((int)g_mps.size() >= MAP_MAX_POINTS) break;
        cv::Point3f pt3d;
        if (!triangulate(pts1[i], pts2[i], R1f, t1f, R2f, t2f, pt3d)) continue;

        MapPoint mp;
        mp.id         = g_next_mp_id++;
        mp.pos        = pt3d;
        g_init_desc.row(good[i].queryIdx).copyTo(mp.desc);
        mp.obs        = 2;
        mp.found      = 2;
        mp.visible    = 2;
        mp.last_frame = g_frame_count;
        mp.bad        = false;
        int mp_idx = (int)g_mps.size();
        g_mps.push_back(mp);

        mp_ids1[good[i].queryIdx] = mp_idx;
        mp_ids2[good[i].trainIdx] = mp_idx;
        n_tri++;
    }

    if (n_tri < INIT_MIN_MAPPOINTS) {
        // Rollback
        while ((int)g_mps.size() > 0 && g_mps.back().obs == 2 &&
               g_mps.back().last_frame == g_frame_count)
            g_mps.pop_back();
        g_next_mp_id -= n_tri;
        return false;
    }

    // KeyFrame 1 — pose identité
    {
        KeyFrame kf;
        kf.id = g_next_kf_id++;
        R1f.copyTo(kf.R_cw); t1f.copyTo(kf.t_cw);
        kf.kps    = g_init_kps;
        g_init_desc.copyTo(kf.desc);
        kf.mp_ids = mp_ids1;
        kf.bow_sig = computeBowSig(g_init_desc);
        kf.bad    = false;
        g_kfs.push_back(kf);
    }

    // KeyFrame 2 — pose relative R21, t21
    {
        KeyFrame kf;
        kf.id = g_next_kf_id++;
        R2f.copyTo(kf.R_cw); t2f.copyTo(kf.t_cw);
        kf.kps    = kps2;
        desc2.copyTo(kf.desc);
        kf.mp_ids = mp_ids2;
        kf.bow_sig = computeBowSig(desc2);
        kf.bad    = false;
        g_kfs.push_back(kf);
    }

    g_R_cw = R2f.clone();
    g_t_cw = t2f.clone();
    g_last_kf_frame = g_frame_count;

    LOGI("Init OK — %d MPs | parallax=%.1fpx | E-inliers=%d/%d",
         n_tri, pxs[pxs.size()/2], n_in, (int)good.size());
    return true;
}

// ══════════════════════════════════════════════════════════════════════════════
// 2a. Tracking — Local Map (PnP RANSAC)
// ══════════════════════════════════════════════════════════════════════════════

static bool trackLocalMap(const std::vector<cv::KeyPoint>& kps, const cv::Mat& desc,
                          cv::Mat& R_out, cv::Mat& t_out,
                          std::vector<int>& mp_ids_out) {
    mp_ids_out.assign(kps.size(), -1);

    // Collecter les MapPoints des 5 derniers KFs (local map)
    std::vector<int> local_ids;
    int kf_start = std::max(0, (int)g_kfs.size() - 5);
    for (int ki = kf_start; ki < (int)g_kfs.size(); ki++) {
        for (int id : g_kfs[ki].mp_ids) {
            if (id >= 0 && id < (int)g_mps.size() && !g_mps[id].bad)
                local_ids.push_back(id);
        }
    }
    std::sort(local_ids.begin(), local_ids.end());
    local_ids.erase(std::unique(local_ids.begin(), local_ids.end()), local_ids.end());

    // Projeter et matcher dans le frame courant
    const float R2 = 20.0f * 20.0f; // rayon de recherche au carré (pixels)

    std::vector<cv::Point3f> pts3d;
    std::vector<cv::Point2f> pts2d;
    std::vector<int>         mp_match, kp_match;

    for (int mp_id : local_ids) {
        float u, v;
        if (!projectMP(mp_id, g_R_cw, g_t_cw, u, v)) continue;
        g_mps[mp_id].visible++;

        int best_kp = -1, best_d = MATCH_HAMMING_THR;
        for (int ki = 0; ki < (int)kps.size(); ki++) {
            if (mp_ids_out[ki] >= 0) continue;
            float dx = kps[ki].pt.x - u, dy = kps[ki].pt.y - v;
            if (dx*dx + dy*dy > R2) continue;
            int d = (int)cv::norm(g_mps[mp_id].desc, desc.row(ki), cv::NORM_HAMMING);
            if (d < best_d) { best_d = d; best_kp = ki; }
        }
        if (best_kp >= 0) {
            mp_ids_out[best_kp] = mp_id;
            pts3d.push_back(g_mps[mp_id].pos);
            pts2d.push_back(kps[best_kp].pt);
            mp_match.push_back(mp_id);
            kp_match.push_back(best_kp);
        }
    }

    if ((int)pts3d.size() < PNP_MIN_INLIERS) return false;

    // PnP RANSAC avec initialisation depuis la pose courante
    cv::Mat rvec, tvec;
    cv::Rodrigues(g_R_cw, rvec);
    rvec.convertTo(rvec, CV_64F);
    g_t_cw.convertTo(tvec, CV_64F);

    cv::Mat inlier_mask;
    cv::Mat dist_coeffs = cv::Mat::zeros(4, 1, CV_64F);
    bool ok = false;
    try {
        ok = cv::solvePnPRansac(pts3d, pts2d, getK(), dist_coeffs,
                                rvec, tvec, true,
                                PNP_RANSAC_ITERS, PNP_REPROJ_ERROR,
                                0.99, inlier_mask);
    } catch (const cv::Exception& e) {
        LOGW("trackLocalMap solvePnPRansac: %s", e.what());
        return false;
    }
    if (!ok) return false;

    int n_in = cv::countNonZero(inlier_mask);
    if (n_in < PNP_MIN_INLIERS) return false;

    // Raffinement LM sur les inliers uniquement
    std::vector<cv::Point3f> pts3d_in;
    std::vector<cv::Point2f> pts2d_in;
    for (int i = 0; i < (int)inlier_mask.rows; i++) {
        if (inlier_mask.at<uchar>(i)) {
            pts3d_in.push_back(pts3d[i]);
            pts2d_in.push_back(pts2d[i]);
        }
    }
    try {
        cv::solvePnP(pts3d_in, pts2d_in, getK(), dist_coeffs,
                     rvec, tvec, true, cv::SOLVEPNP_ITERATIVE);
    } catch (const cv::Exception& e) {
        LOGW("trackLocalMap solvePnP refine: %s", e.what());
        // on garde le résultat RANSAC sans raffinement
    }

    cv::Mat R_new64;
    cv::Rodrigues(rvec, R_new64);
    R_new64.convertTo(R_out, CV_32F);
    tvec.convertTo(t_out, CV_32F);

    // Mise à jour des MapPoints
    for (int i = 0; i < (int)mp_match.size(); i++) {
        bool is_inlier = (i < inlier_mask.rows) && inlier_mask.at<uchar>(i);
        if (is_inlier) {
            g_mps[mp_match[i]].found++;
            g_mps[mp_match[i]].last_frame = g_frame_count;
        } else {
            mp_ids_out[kp_match[i]] = -1;
        }
    }

    if (g_frame_count % 30 == 0)
        LOGI("Frame %ld — PnP %d/%zu inliers | MPs: %zu",
             g_frame_count, n_in, pts3d.size(), g_mps.size());
    return true;
}

// ══════════════════════════════════════════════════════════════════════════════
// 2b. Fallback VO — frame-to-frame (quand la map est trop petite)
// ══════════════════════════════════════════════════════════════════════════════

static bool trackVO(const std::vector<cv::KeyPoint>& kps, const cv::Mat& desc) {
    if (g_kfs.empty()) return false;
    int ref_ki = (int)g_kfs.size() - 1;
    if (g_kfs[ref_ki].desc.empty() || g_kfs[ref_ki].desc.rows < 4) return false;
    if (desc.empty() || desc.rows < 4) return false;

    // Matching KNN avec filtre de Lowe
    std::vector<std::vector<cv::DMatch>> knn;
    try {
        g_matcher->knnMatch(g_kfs[ref_ki].desc, desc, knn, 2);
    } catch (const cv::Exception& e) {
        LOGW("trackVO knnMatch: %s", e.what());
        return false;
    }
    std::vector<cv::Point2f> pts1, pts2;
    for (auto& m : knn) {
        if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
            pts1.push_back(g_kfs[ref_ki].kps[m[0].queryIdx].pt);
            pts2.push_back(kps[m[0].trainIdx].pt);
        }
    }
    if ((int)pts1.size() < 15) return false;

    cv::Mat mask_E, R21d, t21d;
    cv::Mat E = cv::findEssentialMat(pts1, pts2, getK(), cv::RANSAC, 0.999, 1.0, mask_E);
    if (E.empty()) return false;
    int n_in = cv::recoverPose(E, pts1, pts2, getK(), R21d, t21d, mask_E);
    if (n_in < 10) return false;

    cv::Mat R21f, t21f;
    R21d.convertTo(R21f, CV_32F);
    t21d.convertTo(t21f, CV_32F);

    // Accumulation de pose
    g_R_cw = R21f * g_kfs[ref_ki].R_cw;
    g_t_cw = R21f * g_kfs[ref_ki].t_cw + t21f;

    g_vo_dist_accum += (float)cv::norm(t21f);
    return true;
}

// ══════════════════════════════════════════════════════════════════════════════
// Ajout d'un KeyFrame
// ══════════════════════════════════════════════════════════════════════════════

static int addKF(const cv::Mat& R, const cv::Mat& t,
                 const std::vector<cv::KeyPoint>& kps,
                 const cv::Mat& desc,
                 const std::vector<int>& mp_ids) {
    KeyFrame kf;
    kf.id = g_next_kf_id++;
    R.copyTo(kf.R_cw);
    t.copyTo(kf.t_cw);
    kf.kps    = kps;
    desc.copyTo(kf.desc);
    kf.mp_ids = mp_ids;
    kf.bow_sig = computeBowSig(desc);
    kf.bad    = false;
    g_kfs.push_back(kf);
    g_last_kf_frame = g_frame_count;
    return (int)g_kfs.size() - 1;
}

// ══════════════════════════════════════════════════════════════════════════════
// 3. Local Mapping — triangulation + élagage
// ══════════════════════════════════════════════════════════════════════════════

static void localMapping(int new_ki) {
    int n_new = 0;
    int kf_start = std::max(0, new_ki - 5);

    for (int ri = kf_start; ri < new_ki; ri++) {
        if ((int)g_mps.size() >= MAP_MAX_POINTS) break;
        // Construire les descripteurs non mappés de chaque KF
        // (copies locales pour éviter l'invalidation lors des push_back sur g_mps)
        std::vector<int> idx_new, idx_ref;
        cv::Mat desc_new_unm, desc_ref_unm;

        for (int j = 0; j < (int)g_kfs[new_ki].mp_ids.size(); j++) {
            if (g_kfs[new_ki].mp_ids[j] < 0) {
                idx_new.push_back(j);
                desc_new_unm.push_back(g_kfs[new_ki].desc.row(j));
            }
        }
        for (int j = 0; j < (int)g_kfs[ri].mp_ids.size(); j++) {
            if (g_kfs[ri].mp_ids[j] < 0) {
                idx_ref.push_back(j);
                desc_ref_unm.push_back(g_kfs[ri].desc.row(j));
            }
        }
        if (g_kfs[ri].desc.empty() || g_kfs[new_ki].desc.empty()) continue;
        if (desc_new_unm.empty() || desc_ref_unm.empty()) continue;

        std::vector<std::vector<cv::DMatch>> knn;
        g_matcher->knnMatch(desc_ref_unm, desc_new_unm, knn, 2);

        for (auto& m : knn) {
            if (m.size() < 2 || m[0].distance >= 0.75f * m[1].distance) continue;
            if ((int)g_mps.size() >= MAP_MAX_POINTS) break;

            int j_ref = idx_ref[m[0].queryIdx];
            int j_new = idx_new[m[0].trainIdx];

            cv::Point3f pt3d;
            if (!triangulate(g_kfs[ri].kps[j_ref].pt,
                             g_kfs[new_ki].kps[j_new].pt,
                             g_kfs[ri].R_cw,     g_kfs[ri].t_cw,
                             g_kfs[new_ki].R_cw, g_kfs[new_ki].t_cw,
                             pt3d)) continue;

            MapPoint mp;
            mp.id         = g_next_mp_id++;
            mp.pos        = pt3d;
            g_kfs[ri].desc.row(j_ref).copyTo(mp.desc);
            mp.obs        = 2;
            mp.found      = 2;
            mp.visible    = 2;
            mp.last_frame = g_frame_count;
            mp.bad        = false;

            int mp_idx = (int)g_mps.size();
            g_mps.push_back(mp);

            // Accès par index APRÈS le push_back (aucune référence périmée)
            g_kfs[ri].mp_ids[j_ref]     = mp_idx;
            g_kfs[new_ki].mp_ids[j_new] = mp_idx;
            n_new++;
        }
    }

    // Élagage des MapPoints insuffisamment observés
    for (auto& mp : g_mps) {
        if (mp.bad) continue;
        bool stale   = (mp.obs < MP_MIN_OBS) && ((g_frame_count - mp.last_frame) > 30);
        bool low_rat = (mp.visible > 10) && ((float)mp.found / mp.visible < MP_FOUND_RATIO);
        if (stale || low_rat) mp.bad = true;
    }

    if (n_new > 0)
        LOGI("LocalMapping — +%d MPs | total: %zu", n_new, g_mps.size());

    // Gestion mémoire des descripteurs.
    // Règle : garder les descripteurs pour —
    //   (a) les KF_DESC_KEEP derniers KFs  → tracking et local mapping
    //   (b) les KFs "ancres de boucle"     → un KF tous les LOOP_TEMPORAL_GAP
    //       pour que detectAndCorrectLoop() puisse toujours vérifier géométriquement.
    // La bow_sig est toujours conservée (mémoire négligeable).
    const int KF_DESC_KEEP = 10;
    int free_up_to = (int)g_kfs.size() - KF_DESC_KEEP;
    for (int i = 0; i < free_up_to; i++) {
        if (g_kfs[i].desc.empty()) continue;
        // Ancre de boucle : conserver 1 KF sur LOOP_TEMPORAL_GAP
        bool is_loop_anchor = (g_kfs[i].id % LOOP_TEMPORAL_GAP == 0);
        if (!is_loop_anchor) {
            g_kfs[i].desc.release();
            g_kfs[i].kps.clear();
            g_kfs[i].kps.shrink_to_fit();
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 4 & 5. Loop Detection + Loop Correction
// ══════════════════════════════════════════════════════════════════════════════

static void detectAndCorrectLoop(int cur_ki) {
    if (cur_ki < LOOP_TEMPORAL_GAP) return;
    if ((cur_ki - g_last_loop_kf) < 10) return;

    // Recherche du KF le plus similaire parmi les anciens
    float best_score = LOOP_SIG_THR;
    int   best_ki    = -1;

    for (int i = 0; i < cur_ki - LOOP_TEMPORAL_GAP; i++) {
        if (g_kfs[i].bad) continue;
        float s = bowDist(g_kfs[cur_ki].bow_sig, g_kfs[i].bow_sig);
        if (s < best_score) { best_score = s; best_ki = i; }
    }
    if (best_ki < 0) return;

    // Vérification géométrique (Essential Matrix)
    const auto& d1 = g_kfs[best_ki].desc;
    const auto& d2 = g_kfs[cur_ki].desc;
    if (d1.empty() || d2.empty()) return;
    if (d1.rows < 4 || d2.rows < 4) return;
    std::vector<std::vector<cv::DMatch>> knn;
    try {
        g_matcher->knnMatch(d1, d2, knn, 2);
    } catch (const cv::Exception& e) {
        LOGW("detectLoop knnMatch: %s", e.what());
        return;
    }
    std::vector<cv::Point2f> pts_loop, pts_cur;
    for (auto& m : knn) {
        if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
            pts_loop.push_back(g_kfs[best_ki].kps[m[0].queryIdx].pt);
            pts_cur.push_back(g_kfs[cur_ki].kps[m[0].trainIdx].pt);
        }
    }
    if ((int)pts_loop.size() < LOOP_MIN_MATCH) return;

    cv::Mat mask;
    cv::Mat E = cv::findEssentialMat(pts_loop, pts_cur, getK(), cv::RANSAC, 0.999, 1.0, mask);
    if (E.empty() || cv::countNonZero(mask) < LOOP_MIN_MATCH) return;

    // Loop closure confirmée — correction linéaire de trajectoire
    cv::Mat t_err = g_kfs[cur_ki].t_cw - g_kfs[best_ki].t_cw;
    double err_n  = cv::norm(t_err);

    LOGI("Loop closure : KF#%d ↔ KF#%d | score=%.3f | err=%.3fm",
         g_kfs[cur_ki].id, g_kfs[best_ki].id, best_score,
         (float)(err_n * (g_scale > 0 ? g_scale : 1.0)));

    // Rejeter les corrections aberrantes
    if (err_n < 0.01 || err_n > 100.0) return;

    // Distribuer l'erreur linéairement sur les KFs intermédiaires
    int n_kf = cur_ki - best_ki;
    for (int i = best_ki + 1; i <= cur_ki; i++) {
        float alpha = (float)(i - best_ki) / n_kf;
        g_kfs[i].t_cw -= alpha * t_err;
    }

    g_R_cw = g_kfs[cur_ki].R_cw.clone();
    g_t_cw = g_kfs[cur_ki].t_cw.clone();
    g_last_loop_kf = cur_ki;

    // Reconstruire la trajectoire corrigée
    g_traj.clear();
    double sc;
    { std::lock_guard<std::mutex> lk(g_scale_mtx); sc = (g_scale > 0) ? g_scale : 1.0; }
    for (auto& kf : g_kfs) {
        cv::Mat tw = -kf.R_cw.t() * kf.t_cw;
        g_traj.push_back({ (float)(tw.at<float>(0) * sc),
                           (float)(tw.at<float>(1) * sc),
                           (float)(tw.at<float>(2) * sc) });
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// JNI exports
// ══════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_setCameraIntrinsics(
        JNIEnv*, jobject, jdouble fx, jdouble fy, jdouble cx, jdouble cy) {
    g_fx = fx; g_fy = fy; g_cx = cx; g_cy = cy;
    LOGI("Intrinsèques — fx=%.1f fy=%.1f cx=%.1f cy=%.1f", fx, fy, cx, cy);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_updateFootSlamDisplacement(
        JNIEnv*, jobject, jfloat dx, jfloat dy, jfloat dz) {
    double d = std::sqrt((double)dx*dx + (double)dy*dy + (double)dz*dz);
    if (d < 0.01) return;
    std::lock_guard<std::mutex> lk(g_scale_mtx);
    if (g_vo_dist_accum > 0.05) {
        double c = d / g_vo_dist_accum;
        if (c > 1e-4 && c < 1e4)
            g_scale = (g_scale < 0) ? c : 0.85 * g_scale + 0.15 * c;
    }
    g_vo_dist_accum = 0.0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_resetTracking(JNIEnv*, jobject) {
    doReset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_nativeRelease(JNIEnv* env, jobject) {
    if (g_jobj) {
        env->DeleteGlobalRef(g_jobj);
        g_jobj      = nullptr;
        g_pose_mid  = nullptr;
        g_cloud_mid = nullptr;
        LOGI("GlobalRef JNI libéré.");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_getTrackingState(JNIEnv*, jobject) {
    switch (g_state) {
        case TState::INIT:          return 0;
        case TState::INITIALIZING:  return 1;
        case TState::TRACKING:      return 2;
        case TState::RECENTLY_LOST: return 3;
        case TState::LOST:          return 4;
        default:                    return -1;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_vslam_VSlamManager_processFrame(
        JNIEnv* env, jobject thiz,
        jbyteArray frameData, jint width, jint height, jint rowStride) {

    // Initialisation JNI (une seule fois)
    if (!g_jobj) {
        g_jobj      = env->NewGlobalRef(thiz);
        jclass cls  = env->GetObjectClass(thiz);
        g_pose_mid  = env->GetMethodID(cls, "onPoseEstimated",     "(FFF)V");
        g_cloud_mid = env->GetMethodID(cls, "onPointCloudUpdated", "([F)V");
        g_W = width; g_H = height;
        g_mps.reserve(MAP_MAX_POINTS);
        g_kfs.reserve(MAP_MAX_KFS);
        LOGI("VSlamManager JNI init — OpenCV %s | %dx%d", CV_VERSION, width, height);
    }
    g_frame_count++;

    // Décodage YUV → niveaux de gris
    jbyte* data = env->GetByteArrayElements(frameData, nullptr);
    if (!data) { LOGE("GetByteArrayElements échoué."); return; }
    cv::Mat gray(height, width, CV_8UC1,
                 reinterpret_cast<uint8_t*>(data), static_cast<size_t>(rowStride));
    std::vector<cv::KeyPoint> kps;
    cv::Mat desc;
    g_orb->detectAndCompute(gray, cv::noArray(), kps, desc);
    env->ReleaseByteArrayElements(frameData, data, JNI_ABORT);

    bool new_kf_added = false;

    // ── Machine d'états ──────────────────────────────────────────────────────

    if (g_state == TState::INIT) {
        if ((int)kps.size() >= INIT_MIN_FEATURES) {
            g_init_kps      = kps;
            desc.copyTo(g_init_desc);
            g_init_attempts = 0;
            g_state         = TState::INITIALIZING;
            LOGI("Frame %ld — Référence init sauvegardée (%zu kp).", g_frame_count, kps.size());
        }

    } else if (g_state == TState::INITIALIZING) {
        g_init_attempts++;
        if (tryInitialize(kps, desc)) {
            g_state      = TState::TRACKING;
            new_kf_added = true;
        } else if (g_init_attempts >= INIT_MAX_ATTEMPTS) {
            // Rafraîchir la frame de référence
            if ((int)kps.size() >= INIT_MIN_FEATURES) {
                g_init_kps      = kps;
                desc.copyTo(g_init_desc);
                g_init_attempts = 0;
                LOGI("Frame %ld — Référence rafraîchie.", g_frame_count);
            }
        }

    } else if (g_state == TState::TRACKING || g_state == TState::RECENTLY_LOST) {
        cv::Mat R_new, t_new;
        std::vector<int> mp_ids_new;
        bool tracked = false;

        // Tentative PnP sur la local map
        if ((int)g_mps.size() >= 10)
            tracked = trackLocalMap(kps, desc, R_new, t_new, mp_ids_new);

        // Fallback VO si PnP insuffisant
        if (!tracked) {
            tracked = trackVO(kps, desc);
            if (tracked) {
                R_new = g_R_cw.clone();
                t_new = g_t_cw.clone();
                mp_ids_new.assign(kps.size(), -1);
            }
        }

        if (tracked) {
            if (g_state == TState::RECENTLY_LOST)
                LOGI("Frame %ld — Récupération : RECENTLY_LOST → TRACKING.", g_frame_count);
            g_state      = TState::TRACKING;
            g_lost_count = 0;

            // Accumulation distance pour calibration d'échelle (lock car lu par updateFootSlamDisplacement)
            { std::lock_guard<std::mutex> lk(g_scale_mtx);
              g_vo_dist_accum += (float)cv::norm(t_new - g_t_cw); }

            g_R_cw = R_new.clone();
            g_t_cw = t_new.clone();

            // Décision d'ajout de KeyFrame
            int n_tracked = 0;
            for (int id : mp_ids_new) if (id >= 0) n_tracked++;
            long frames_since_kf = g_frame_count - g_last_kf_frame;
            bool need_kf = (frames_since_kf >= KF_MIN_INTERVAL) &&
                           ((n_tracked < KF_MIN_TRACKED) ||
                            (frames_since_kf >= KF_MAX_INTERVAL));

            if (need_kf) {
                int ki = addKF(g_R_cw, g_t_cw, kps, desc, mp_ids_new);
                localMapping(ki);
                detectAndCorrectLoop(ki);

                g_traj.push_back(cameraPosWorld());
                new_kf_added = true;
            }

        } else {
            g_lost_count++;
            if (g_state == TState::TRACKING) {
                g_state = TState::RECENTLY_LOST;
                LOGW("Frame %ld — TRACKING → RECENTLY_LOST.", g_frame_count);
            }
            if (g_lost_count >= MAX_LOST_FRAMES) {
                g_state = TState::LOST;
                LOGW("Frame %ld — RECENTLY_LOST → LOST.", g_frame_count);
            }
        }

    } else if (g_state == TState::LOST) {
        // ── Tentative de relocalisation BoW + PnP ─────────────────────────────
        // Stratégie : chercher parmi les derniers KF_RELOC_WINDOW KF ceux dont la
        // signature BoW est proche de la frame courante, puis tenter un solvePnP
        // pour récupérer la pose sans repartir de zéro.
        bool relocalized = false;

        if ((int)kps.size() >= INIT_MIN_FEATURES && !g_kfs.empty()) {
            cv::Mat cur_sig = computeBowSig(desc);

            // Fenêtre de recherche : derniers KF ayant encore leurs descripteurs
            const int KF_RELOC_WINDOW = 10;
            int kf_start = std::max(0, (int)g_kfs.size() - KF_RELOC_WINDOW);

            // Collecter les candidats par ordre de similarité BoW
            std::vector<std::pair<float, int>> cands;
            cands.reserve(KF_RELOC_WINDOW);
            for (int ki = kf_start; ki < (int)g_kfs.size(); ki++) {
                if (g_kfs[ki].bad || g_kfs[ki].desc.empty()) continue;
                float d = bowDist(cur_sig, g_kfs[ki].bow_sig);
                if (d < LOOP_SIG_THR) cands.push_back({d, ki});
            }
            std::sort(cands.begin(), cands.end());

            // Tenter PnP sur les 3 meilleurs KF candidats
            for (int ci = 0; ci < std::min((int)cands.size(), 3) && !relocalized; ci++) {
                int ki = cands[ci].second;
                const KeyFrame& kf = g_kfs[ki];

                // Validation stricte avant tout appel OpenCV
                if (kf.desc.empty() || kf.mp_ids.empty()) continue;
                if (kf.desc.rows < 4 || kf.desc.cols != 32) continue;
                if (desc.empty() || desc.rows < 4 || desc.cols != 32) continue;
                if (kf.desc.type() != CV_8U || desc.type() != CV_8U) continue;

                // Correspondances descriptor → MapPoint
                std::vector<std::vector<cv::DMatch>> knn;
                try {
                    g_matcher->knnMatch(kf.desc, desc, knn, 2);
                } catch (const cv::Exception& e) {
                    LOGW("Reloc knnMatch exception (KF%d): %s", ki, e.what());
                    continue;
                }

                std::vector<cv::Point3f> pts3d;
                std::vector<cv::Point2f> pts2d;
                pts3d.reserve(knn.size());
                pts2d.reserve(knn.size());

                for (auto& m : knn) {
                    if (m.size() < 2) continue;
                    if (m[0].distance > 0.75f * m[1].distance) continue;
                    if (m[0].distance > MATCH_HAMMING_THR) continue;
                    int kf_idx = m[0].queryIdx;
                    int fr_idx = m[0].trainIdx;
                    if (kf_idx >= (int)kf.mp_ids.size()) continue;
                    if (fr_idx >= (int)kps.size()) continue;
                    int mp_id = kf.mp_ids[kf_idx];
                    if (mp_id < 0 || mp_id >= (int)g_mps.size()) continue;
                    if (g_mps[mp_id].bad) continue;
                    const auto& pos = g_mps[mp_id].pos;
                    // Rejeter les points dégénérés
                    if (!std::isfinite(pos.x) || !std::isfinite(pos.y) || !std::isfinite(pos.z)) continue;
                    pts3d.push_back(pos);
                    pts2d.push_back(kps[fr_idx].pt);
                }

                if ((int)pts3d.size() < PNP_MIN_INLIERS * 2) continue;

                cv::Mat K = getK();
                cv::Mat rvec, tvec, inliers_mat;
                bool ok = false;
                try {
                    ok = cv::solvePnPRansac(
                        pts3d, pts2d, K, cv::noArray(),
                        rvec, tvec,
                        false,
                        PNP_RANSAC_ITERS,
                        PNP_REPROJ_ERROR,
                        0.99,
                        inliers_mat,
                        cv::SOLVEPNP_ITERATIVE
                    );
                } catch (const cv::Exception& e) {
                    LOGW("Reloc solvePnP exception (KF%d): %s", ki, e.what());
                    continue;
                }

                if (!ok || inliers_mat.rows < PNP_MIN_INLIERS) continue;

                // Pose récupérée — convertir en CV_32F
                cv::Mat R64, R32, t32;
                cv::Rodrigues(rvec, R64);
                R64.convertTo(R32, CV_32F);
                tvec.convertTo(t32, CV_32F);

                g_R_cw           = R32.clone();
                g_t_cw           = t32.clone();
                g_state          = TState::RECENTLY_LOST;
                g_lost_count     = MAX_LOST_FRAMES / 2;   // demi-fenêtre restante
                g_reloc_attempts = 0;
                relocalized      = true;
                LOGI("Frame %ld — RELOC réussie (KF%d, dist=%.3f, %d inliers) → RECENTLY_LOST.",
                     g_frame_count, ki, cands[ci].first, inliers_mat.rows);
            }
        }

        // Si relocalisation impossible, attendre RELOC_MAX_ATTEMPTS frames avant reset
        if (!relocalized) {
            g_reloc_attempts++;
            static constexpr int RELOC_MAX_ATTEMPTS = 5;
            if (g_reloc_attempts >= RELOC_MAX_ATTEMPTS &&
                (int)kps.size() >= INIT_MIN_FEATURES) {
                g_reloc_attempts = 0;
                doReset();
                g_init_kps      = kps;
                desc.copyTo(g_init_desc);
                g_init_attempts = 0;
                g_state         = TState::INITIALIZING;
                LOGI("Frame %ld — LOST → INITIALIZING (reloc échouée après %d tentatives).",
                     g_frame_count, RELOC_MAX_ATTEMPTS);
            } else {
                LOGW("Frame %ld — LOST : tentative reloc %d/%d.",
                     g_frame_count, g_reloc_attempts, RELOC_MAX_ATTEMPTS);
            }
        }
    }

    // ── Callbacks Kotlin ─────────────────────────────────────────────────────

    auto pos = cameraPosWorld();
    if (g_pose_mid)
        env->CallVoidMethod(g_jobj, g_pose_mid, pos[0], pos[1], pos[2]);

    // Nuage de MapPoints tous les 5 nouveaux KFs — envoyé à Kotlin pour cartographie
    if (g_cloud_mid && new_kf_added && (int)g_kfs.size() % 5 == 0) {
        double sc_snap;
        { std::lock_guard<std::mutex> lk(g_scale_mtx); sc_snap = (g_scale > 0) ? g_scale : 1.0; }
        std::vector<float> pts;
        pts.reserve(g_mps.size() * 3);
        for (const auto& mp : g_mps) {
            if (!mp.bad && mp.obs >= MP_MIN_OBS) {
                pts.push_back((float)(mp.pos.x * sc_snap));
                pts.push_back((float)(mp.pos.y * sc_snap));
                pts.push_back((float)(mp.pos.z * sc_snap));
            }
        }
        if (!pts.empty()) {
            jfloatArray arr = env->NewFloatArray((jsize)pts.size());
            if (arr) {
                env->SetFloatArrayRegion(arr, 0, (jsize)pts.size(), pts.data());
                env->CallVoidMethod(g_jobj, g_cloud_mid, arr);
                env->DeleteLocalRef(arr);
            }
        }
    }
}
