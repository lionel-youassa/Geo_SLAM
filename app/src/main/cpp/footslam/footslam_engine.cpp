#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstdlib>
#include <vector>
#include <memory>

// TensorFlow Lite Headers
#include "tensorflow/lite/model.h"
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/kernels/register.h"

#define LOG_TAG "GeoSlam_FootSLAM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Globales pour Lionel (IA FootSLAM)
std::unique_ptr<tflite::Interpreter> interpreter;
std::unique_ptr<tflite::FlatBufferModel> model;

// --- Semaine 2 & 3 : État de la Trajectoire ---
static float current_x = 0.0f;
static float current_y = 0.0f;
static float current_z = 0.0f;

// Lissage sortie IA
static float smoothed_dx = 0.0f;
static float smoothed_dy = 0.0f;
const float ALPHA_OUT = 0.2f;

// --- Semaine 2 : Filtrage Bruit Thermique (IMU Raw) ---
static float filtered_acc[3] = {0.0f, 0.0f, 0.0f};
static float filtered_gyro[3] = {0.0f, 0.0f, 0.0f};
const float ALPHA_IMU = 0.8f; // Coefficient pour le filtre passe-bas IMU

// --- Semaine 3 : Mise en mémoire de la trajectoire ---
struct Point3D {
    float x, y, z;
};
std::vector<Point3D> trajectory_history;

// Gestion du Callback vers Kotlin (Sonia)
static jobject g_manager_obj = nullptr;
static jmethodID g_callback_mid = nullptr;
static JavaVM* g_jvm = nullptr;

// Buffer circulaire pour RoNIN
const int WINDOW_SIZE = 200;
struct IMUData {
    float acc[3];
    float gyro[3];
    float ori[4];
};
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
 * Lionel : Fonction d'inférence RoNIN
 */
void runInference() {
    if (!interpreter) return;

    float* input_tensor = interpreter->typed_input_tensor<float>(0);
    if (!input_tensor) return;

    for (int i = 0; i < WINDOW_SIZE; ++i) {
        int idx = (buffer_index + i) % WINDOW_SIZE;
        input_tensor[i * 10 + 0] = imu_buffer[idx].acc[2]; // Z
        input_tensor[i * 10 + 1] = imu_buffer[idx].acc[1]; // Y
        input_tensor[i * 10 + 2] = imu_buffer[idx].acc[0]; // X
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
        // Lissage de la sortie
        smoothed_dx = ALPHA_OUT * output_tensor[0] + (1.0f - ALPHA_OUT) * smoothed_dx;
        smoothed_dy = ALPHA_OUT * output_tensor[1] + (1.0f - ALPHA_OUT) * smoothed_dy;

        // Intégration
        current_x += smoothed_dx;
        current_y += smoothed_dy;

        // --- Semaine 3 : Sauvegarde dans l'historique ---
        trajectory_history.push_back({current_x, current_y, current_z});

        sendPositionToSonia(current_x, current_y, current_z);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_resetPositionNative(JNIEnv* env, jobject thiz) {
    current_x = 0.0f;
    current_y = 0.0f;
    current_z = 0.0f;
    smoothed_dx = 0.0f;
    smoothed_dy = 0.0f;
    trajectory_history.clear();
    LOGI("Lionel : Trajectoire et historique réinitialisés.");
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
    if (!asset) {
        env->ReleaseStringUTFChars(model_path, path);
        return JNI_FALSE;
    }

    size_t model_size = AAsset_getLength(asset);
    std::vector<char> model_buffer(model_size);
    AAsset_read(asset, model_buffer.data(), model_size);
    AAsset_close(asset);

    model = tflite::FlatBufferModel::BuildFromBuffer(model_buffer.data(), model_size);
    if (!model) return JNI_FALSE;

    tflite::ops::builtin::BuiltinOpResolver resolver;
    tflite::InterpreterBuilder(*model, resolver)(&interpreter);

    if (!interpreter || interpreter->AllocateTensors() != kTfLiteOk) {
        return JNI_FALSE;
    }

    env->ReleaseStringUTFChars(model_path, path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processAccelerometer(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong timestamp) {

    // --- Semaine 2 : Filtrage passe-bas (Bruit thermique) ---
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

    // --- Semaine 2 : Filtrage passe-bas ---
    filtered_gyro[0] = ALPHA_IMU * x + (1.0f - ALPHA_IMU) * filtered_gyro[0];
    filtered_gyro[1] = ALPHA_IMU * y + (1.0f - ALPHA_IMU) * filtered_gyro[1];
    filtered_gyro[2] = ALPHA_IMU * z + (1.0f - ALPHA_IMU) * filtered_gyro[2];

    imu_buffer[buffer_index].gyro[0] = filtered_gyro[0];
    imu_buffer[buffer_index].gyro[1] = filtered_gyro[1];
    imu_buffer[buffer_index].gyro[2] = filtered_gyro[2];

    buffer_index = (buffer_index + 1) % WINDOW_SIZE;

    if (buffer_index % 10 == 0) {
        runInference();
    }
}