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

// Gestion du Callback vers Kotlin (Sonia)
static jobject g_manager_obj = nullptr;
static jmethodID g_callback_mid = nullptr;
static JavaVM* g_jvm = nullptr;

// Buffer circulaire pour RoNIN
const int WINDOW_SIZE = 200;
struct IMUData {
    float acc[3];
    float gyro[3];
};
std::vector<IMUData> imu_buffer(WINDOW_SIZE);
int buffer_index = 0;

// Sauvegarde de la JVM pour les callbacks asynchrones
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_loadModelNative(
        JNIEnv* env, jobject thiz, jobject asset_manager, jstring model_path) {

    // Mémorisation de l'objet pour le callback vers Sonia
    if (g_manager_obj != nullptr) env->DeleteGlobalRef(g_manager_obj);
    g_manager_obj = env->NewGlobalRef(thiz);
    jclass clazz = env->GetObjectClass(thiz);
    g_callback_mid = env->GetMethodID(clazz, "onPositionCalculated", "(FFF)V");

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);

    AAsset* asset = AAssetManager_open(mgr, path, AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Erreur : Impossible d'ouvrir le modèle %s", path);
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
        LOGE("Erreur : Échec initialisation TFLite");
        return JNI_FALSE;
    }

    LOGI("Lionel : Moteur IA prêt et Callback configuré pour Sonia.");
    env->ReleaseStringUTFChars(model_path, path);
    return JNI_TRUE;
}

void sendPositionToSonia(float x, float y, float z) {
    JNIEnv* env;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (g_manager_obj && g_callback_mid) {
            env->CallVoidMethod(g_manager_obj, g_callback_mid, x, y, z);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processAccelerometer(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong timestamp) {
    imu_buffer[buffer_index].acc[0] = x;
    imu_buffer[buffer_index].acc[1] = y;
    imu_buffer[buffer_index].acc[2] = z;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processGyroscope(
        JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong timestamp) {
    imu_buffer[buffer_index].gyro[0] = x;
    imu_buffer[buffer_index].gyro[1] = y;
    imu_buffer[buffer_index].gyro[2] = z;

    buffer_index = (buffer_index + 1) % WINDOW_SIZE;

    // Lionel : Simulation d'un calcul de position pour tester le lien avec Sonia
    if (buffer_index == 0) {
        sendPositionToSonia(1.0f, 2.0f, 0.0f);
    }
}