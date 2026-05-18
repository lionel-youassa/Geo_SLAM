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

// --- Structure EKF Améliorée (Semaine 5 : Altitude & Pression) ---
struct EKFState {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;       // Dimension verticale
    float vx = 0.0f;
    float vy = 0.0f;
    float P = 1.0f;       // Incertitude
    long long last_timestamp_ns = 0;

    // Baromètre
    float reference_pressure = -1.0f; // Pression au point de départ

    const float R = 0.5f;  // Bruit de mesure (IA)
    const float Q = 0.01f; // Bruit de processus
};

static EKFState g_ekf;

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
 * Lionel : Phase de Prédiction (100Hz)
 */
void ekfPredict(long long timestamp_ns) {
    if (g_ekf.last_timestamp_ns == 0) {
        g_ekf.last_timestamp_ns = timestamp_ns;
        return;
    }

    float dt = (timestamp_ns - g_ekf.last_timestamp_ns) / 1000000000.0f;
    if (dt <= 0 || dt > 0.1) {
        g_ekf.last_timestamp_ns = timestamp_ns;
        return;
    }

    g_ekf.x += g_ekf.vx * dt;
    g_ekf.y += g_ekf.vy * dt;
    g_ekf.P += g_ekf.Q * dt;

    g_ekf.last_timestamp_ns = timestamp_ns;
}

/**
 * Lionel : Correction via IA (RoNIN)
 */
void ekfUpdate(float dx_ia, float dy_ia) {
    float K = g_ekf.P / (g_ekf.P + g_ekf.R);
    g_ekf.x += K * dx_ia;
    g_ekf.y += K * dy_ia;
    g_ekf.vx = dx_ia / 0.1f;
    g_ekf.vy = dy_ia / 0.1f;
    g_ekf.P = (1.0f - K) * g_ekf.P;
}

/**
 * Semaine 5 : Calcul de l'altitude relative
 * h = 44330 * (1 - (P/P0)^(1/5.255))
 */
void updateAltitude(float pressure) {
    if (g_ekf.reference_pressure < 0) {
        g_ekf.reference_pressure = pressure;
        g_ekf.z = 0.0f;
        return;
    }

    // Calcul de l'altitude relative en mètres
    float altitude = 44330.0f * (1.0f - pow(pressure / g_ekf.reference_pressure, 0.1903f));

    // Lissage simple pour le Z (Filtre passe-bas)
    g_ekf.z = 0.9f * g_ekf.z + 0.1f * altitude;
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
        ekfUpdate(dx, dy);
        trajectory_history.push_back({g_ekf.x, g_ekf.y, g_ekf.z});
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processPressure(
        JNIEnv* env, jobject thiz, jfloat pressure, jlong timestamp) {
    updateAltitude(pressure);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_resetPositionNative(JNIEnv* env, jobject thiz) {
    g_ekf.x = 0.0f; g_ekf.y = 0.0f; g_ekf.z = 0.0f;
    g_ekf.vx = 0.0f; g_ekf.vy = 0.0f;
    g_ekf.P = 1.0f; g_ekf.last_timestamp_ns = 0;
    g_ekf.reference_pressure = -1.0f;
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
    ekfPredict(timestamp);
    filtered_acc[0] = ALPHA_IMU * x + (1.0f - ALPHA_IMU) * filtered_acc[0];
    filtered_acc[1] = ALPHA_IMU * y + (1.0f - ALPHA_IMU) * filtered_acc[1];
    filtered_acc[2] = ALPHA_IMU * z + (1.0f - ALPHA_IMU) * filtered_acc[2];
    imu_buffer[buffer_index].acc[0] = filtered_acc[0];
    imu_buffer[buffer_index].acc[1] = filtered_acc[1];
    imu_buffer[buffer_index].acc[2] = filtered_acc[2];
    sendPositionToSonia(g_ekf.x, g_ekf.y, g_ekf.z);
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