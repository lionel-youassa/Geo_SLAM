#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstdlib>
#include <vector>
#include <memory>
#include <cmath>

// TensorFlow Lite Headers
#include "tensorflow/lite/model.h"
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/kernels/register.h"

#define LOG_TAG "GeoSlam_FootSLAM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Globales pour Lionel (Lead IA)
std::unique_ptr<tflite::Interpreter> interpreter;
std::unique_ptr<tflite::FlatBufferModel> model;

// --- Structure EKF (Semaine 4) ---
struct EKFState {
    float x = 0.0f;
    float y = 0.0f;
    float P = 1.0f;       // Incertitude initiale
    const float R = 0.5f; // Bruit de mesure (IA)
    const float Q = 0.05f; // Bruit de processus (mouvement)
};

static EKFState g_ekf;
static float current_z = 0.0f;

// --- Semaine 3 : Contraintes Physiques ---
const float MAX_STEP_LIMIT = 2.0f;

// Stockage IMU & Historique
static float filtered_acc[3] = {0.0f}, filtered_gyro[3] = {0.0f};
const float ALPHA_IMU = 0.8f;

struct Point3D { float x, y, z; };
std::vector<Point3D> trajectory_history;

// Gestion Callback JNI
static jobject g_manager_obj = nullptr;
static jmethodID g_callback_mid = nullptr;
static JavaVM* g_jvm = nullptr;

const int WINDOW_SIZE = 200;
struct IMUData { float acc[3]; float gyro[3]; float ori[4]; };
std::vector<IMUData> imu_buffer(WINDOW_SIZE);
int buffer_index = 0;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

void sendPositionToSonia(float x, float y, float z) {
    JNIEnv* env;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (g_manager_obj && g_callback_mid) {
            env->CallVoidMethod(g_manager_obj, g_callback_mid, x, y, z);
        }
    }
}

/**
 * Lionel : Détection d'incohérence physique
 */
void applyPhysicalConstraints(float& dx, float& dy) {
    float step_dist = std::sqrt(dx * dx + dy * dy);
    if (step_dist > MAX_STEP_LIMIT) {
        LOGE("Incohérence physique détectée : déplacement de %.2fm ignoré.", step_dist);
        dx = 0.0f;
        dy = 0.0f;
    }
}

/**
 * Filtre de Kalman (Étape de Correction)
 * Fusionne la prédiction de l'IA avec l'état actuel.
 */
void ekfUpdate(float dx, float dy) {
    // 1. Prédiction (très simple ici : x = x + dx_ia)
    // Dans un vrai EKF, on prédirait via l'IMU, mais ici on traite la sortie RoNIN

    // 2. Gain de Kalman : K = P / (P + R)
    float K = g_ekf.P / (g_ekf.P + g_ekf.R);

    // 3. Correction de l'état (Position)
    g_ekf.x += K * dx;
    g_ekf.y += K * dy;

    // 4. Mise à jour de l'incertitude : P = (1 - K) * P + Q
    g_ekf.P = (1.0f - K) * g_ekf.P + g_ekf.Q;
}

void runInference() {
    if (!interpreter) return;

    float* input_tensor = interpreter->typed_input_tensor<float>(0);
    if (!input_tensor) return;

    for (int i = 0; i < WINDOW_SIZE; ++i) {
        int idx = (buffer_index + i) % WINDOW_SIZE;
        input_tensor[i * 10 + 0] = imu_buffer[idx].acc[2];
        input_tensor[i * 10 + 1] = imu_buffer[idx].acc[1];
        input_tensor[i * 10 + 2] = imu_buffer[idx].acc[0];
        input_tensor[i * 10 + 3] = imu_buffer[idx].gyro[2];
        input_tensor[i * 10 + 4] = imu_buffer[idx].gyro[1];
        input_tensor[i * 10 + 5] = imu_buffer[idx].gyro[0];
        input_tensor[i * 10 + 6] = imu_buffer[idx].ori[2];
        input_tensor[i * 10 + 7] = imu_buffer[idx].ori[1];
        input_tensor[i * 10 + 8] = imu_buffer[idx].ori[0];
        input_tensor[i * 10 + 9] = imu_buffer[idx].ori[3];
    }

    if (interpreter->Invoke() != kTfLiteOk) return;

    float* output_tensor = interpreter->typed_output_tensor<float>(0);
    if (output_tensor) {
        float dx = output_tensor[0];
        float dy = output_tensor[1];

        // Vérification de cohérence physique (Semaine 3)
        applyPhysicalConstraints(dx, dy);

        // --- Mise à jour EKF (Semaine 4) ---
        ekfUpdate(dx, dy);

        trajectory_history.push_back({g_ekf.x, g_ekf.y, current_z});
        sendPositionToSonia(g_ekf.x, g_ekf.y, current_z);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_resetPositionNative(JNIEnv* env, jobject thiz) {
    g_ekf.x = 0.0f;
    g_ekf.y = 0.0f;
    g_ekf.P = 1.0f;
    trajectory_history.clear();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_loadModelNative(
        JNIEnv* env, jobject thiz, jobject asset_manager, jstring model_path) {

    if (g_manager_obj != nullptr) env->DeleteGlobalRef(g_manager_obj);
    g_manager_obj = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    g_callback_mid = env->GetMethodID(clazz, "onPositionCalculated", "(FFF)V");

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    AAsset* asset = AAssetManager_open(mgr, path, AASSET_MODE_BUFFER);
    if (!asset) return JNI_FALSE;

    size_t model_size = AAsset_getLength(asset);
    std::vector<char> model_buffer(model_size);
    AAsset_read(asset, model_buffer.data(), model_size);
    AAsset_close(asset);

    model = tflite::FlatBufferModel::BuildFromBuffer(model_buffer.data(), model_size);
    tflite::ops::builtin::BuiltinOpResolver resolver;
    tflite::InterpreterBuilder(*model, resolver)(&interpreter);
    interpreter->AllocateTensors();

    env->ReleaseStringUTFChars(model_path, path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processAccelerometer(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong timestamp) {
    filtered_acc[0] = ALPHA_IMU * x + (1.0f - ALPHA_IMU) * filtered_acc[0];
    filtered_acc[1] = ALPHA_IMU * y + (1.0f - ALPHA_IMU) * filtered_acc[1];
    filtered_acc[2] = ALPHA_IMU * z + (1.0f - ALPHA_IMU) * filtered_acc[2];
    imu_buffer[buffer_index].acc[0] = filtered_acc[0];
    imu_buffer[buffer_index].acc[1] = filtered_acc[1];
    imu_buffer[buffer_index].acc[2] = filtered_acc[2];
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processOrientation(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jfloat w, jlong timestamp) {
    imu_buffer[buffer_index].ori[0] = x;
    imu_buffer[buffer_index].ori[1] = y;
    imu_buffer[buffer_index].ori[2] = z;
    imu_buffer[buffer_index].ori[3] = w;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processGyroscope(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong timestamp) {
    filtered_gyro[0] = ALPHA_IMU * x + (1.0f - ALPHA_IMU) * filtered_gyro[0];
    filtered_gyro[1] = ALPHA_IMU * y + (1.0f - ALPHA_IMU) * filtered_gyro[1];
    filtered_gyro[2] = ALPHA_IMU * z + (1.0f - ALPHA_IMU) * filtered_gyro[2];
    imu_buffer[buffer_index].gyro[0] = filtered_gyro[0];
    imu_buffer[buffer_index].gyro[1] = filtered_gyro[1];
    imu_buffer[buffer_index].gyro[2] = filtered_gyro[2];

    buffer_index = (buffer_index + 1) % WINDOW_SIZE;
    if (buffer_index % 10 == 0) runInference();
}