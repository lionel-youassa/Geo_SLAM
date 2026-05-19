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

// --- Structure EKF Améliorée (Lionel - Version Finale) ---
struct EKFState {
    float x = 0.0f, y = 0.0f, z = 0.0f;
    float vx = 0.0f, vy = 0.0f;
    float P = 1.0f;
    long long last_timestamp_ns = 0;

    // Baromètre
    float reference_pressure = -1.0f;

    // ZUPT (Zero Velocity Update)
    bool is_stationary = true;
    const float STILLNESS_THRESHOLD_GYRO = 0.05f;
    const float STILLNESS_THRESHOLD_ACC = 0.2f;

    // Calibration
    bool is_calibrated = false;
    int calib_samples_count = 0;
    const int SAMPLES_FOR_CALIB = 200;
    float acc_bias[3] = {0.0f, 0.0f, 0.0f};
    float gyro_bias[3] = {0.0f, 0.0f, 0.0f};
    float sum_acc[3] = {0.0f, 0.0f, 0.0f};
    float sum_gyro[3] = {0.0f, 0.0f, 0.0f};

    const float R = 0.5f;
    const float Q = 0.01f;
};

static EKFState g_ekf;
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
 * Transformation Body-to-World via Quaternion
 */
void rotateVectorByQuaternion(float dx, float dy, const float q[4], float& outX, float& outY) {
    // Calcul du Yaw (Lacet) : q = [x, y, z, w]
    float siny_cosp = 2.0f * (q[3] * q[2] + q[0] * q[1]);
    float cosy_cosp = 1.0f - 2.0f * (q[1] * q[1] + q[2] * q[2]);
    float yaw = std::atan2(siny_cosp, cosy_cosp);

    outX = dx * std::cos(yaw) - dy * std::sin(yaw);
    outY = dx * std::sin(yaw) + dy * std::cos(yaw);
}

void checkStillness() {
    float gyro_mag = std::sqrt(filtered_gyro[0]*filtered_gyro[0] + filtered_gyro[1]*filtered_gyro[1] + filtered_gyro[2]*filtered_gyro[2]);
    float acc_mag = std::sqrt(filtered_acc[0]*filtered_acc[0] + filtered_acc[1]*filtered_acc[1] + filtered_acc[2]*filtered_acc[2]);
    float acc_diff = std::abs(acc_mag - 9.81f);
    g_ekf.is_stationary = (gyro_mag < g_ekf.STILLNESS_THRESHOLD_GYRO && acc_diff < g_ekf.STILLNESS_THRESHOLD_ACC);
    if (g_ekf.is_stationary) { g_ekf.vx = 0; g_ekf.vy = 0; }
}

void ekfPredict(long long timestamp_ns) {
    if (g_ekf.last_timestamp_ns == 0) { g_ekf.last_timestamp_ns = timestamp_ns; return; }
    float dt = (timestamp_ns - g_ekf.last_timestamp_ns) / 1e9f;
    if (dt > 0 && dt < 0.1 && !g_ekf.is_stationary) {
        g_ekf.x += g_ekf.vx * dt;
        g_ekf.y += g_ekf.vy * dt;
        g_ekf.P += g_ekf.Q * dt;
    }
    g_ekf.last_timestamp_ns = timestamp_ns;
}

void ekfUpdate(float dx_world, float dy_world) {
    float K = g_ekf.P / (g_ekf.P + g_ekf.R);
    g_ekf.x += K * dx_world;
    g_ekf.y += K * dy_world;
    if (std::abs(dx_world) > 0.01f) {
        g_ekf.vx = dx_world / 0.1f;
        g_ekf.vy = dy_world / 0.1f;
        g_ekf.is_stationary = false;
    }
    g_ekf.P = (1.0f - K) * g_ekf.P;
}

void updateAltitude(float pressure) {
    if (g_ekf.reference_pressure < 0) { g_ekf.reference_pressure = pressure; return; }
    float altitude = 44330.0f * (1.0f - pow(pressure / g_ekf.reference_pressure, 0.1903f));
    g_ekf.z = 0.9f * g_ekf.z + 0.1f * altitude;
}

void runInference() {
    if (!g_ekf.is_calibrated || !interpreter) return;
    float* input = interpreter->typed_input_tensor<float>(0);
    for (int i = 0; i < WINDOW_SIZE; ++i) {
        int idx = (buffer_index + i) % WINDOW_SIZE;
        for(int j=0; j<3; j++) input[i*10+j] = imu_buffer[idx].acc[j];
        for(int j=0; j<3; j++) input[i*10+3+j] = imu_buffer[idx].gyro[j];
        for(int j=0; j<4; j++) input[i*10+6+j] = imu_buffer[idx].ori[j];
    }
    if (interpreter->Invoke() != kTfLiteOk) return;
    float* output = interpreter->typed_output_tensor<float>(0);
    float dx_world, dy_world;
    int last_idx = (buffer_index + WINDOW_SIZE - 1) % WINDOW_SIZE;
    rotateVectorByQuaternion(output[0], output[1], imu_buffer[last_idx].ori, dx_world, dy_world);
    ekfUpdate(dx_world, dy_world);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processPressure(JNIEnv* env, jobject thiz, jfloat p, jlong ts) {
    updateAltitude(p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_resetPositionNative(JNIEnv* env, jobject thiz) {
    g_ekf.x = 0; g_ekf.y = 0; g_ekf.z = 0; g_ekf.vx = 0; g_ekf.vy = 0;
    g_ekf.P = 1.0f; g_ekf.last_timestamp_ns = 0; g_ekf.reference_pressure = -1.0f;
    g_ekf.is_stationary = true; g_ekf.is_calibrated = false; g_ekf.calib_samples_count = 0;
    trajectory_history.clear();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_loadModelNative(JNIEnv* env, jobject thiz, jobject am, jstring path) {
    if (g_manager_obj) env->DeleteGlobalRef(g_manager_obj);
    g_manager_obj = env->NewGlobalRef(thiz);
    g_callback_mid = env->GetMethodID(env->GetObjectClass(thiz), "onPositionCalculated", "(FFF)V");
    const char* p = env->GetStringUTFChars(path, nullptr);
    AAsset* asset = AAssetManager_open(AAssetManager_fromJava(env, am), p, AASSET_MODE_BUFFER);
    if (!asset) return JNI_FALSE;
    size_t size = AAsset_getLength(asset);
    std::vector<char> buf(size);
    AAsset_read(asset, buf.data(), size);
    AAsset_close(asset);
    model = tflite::FlatBufferModel::BuildFromBuffer(buf.data(), size);
    tflite::ops::builtin::BuiltinOpResolver res;
    tflite::InterpreterBuilder(*model, res)(&interpreter);
    interpreter->AllocateTensors();
    env->ReleaseStringUTFChars(path, p);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processAccelerometer(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong ts) {
    if (!g_ekf.is_calibrated) { g_ekf.sum_acc[0]+=x; g_ekf.sum_acc[1]+=y; g_ekf.sum_acc[2]+=(z-9.81f); return; }
    filtered_acc[0] = ALPHA_IMU*(x-g_ekf.acc_bias[0]) + (1-ALPHA_IMU)*filtered_acc[0];
    filtered_acc[1] = ALPHA_IMU*(y-g_ekf.acc_bias[1]) + (1-ALPHA_IMU)*filtered_acc[1];
    filtered_acc[2] = ALPHA_IMU*(z-g_ekf.acc_bias[2]) + (1-ALPHA_IMU)*filtered_acc[2];
    checkStillness();
    ekfPredict(ts);
    imu_buffer[buffer_index].acc[0]=filtered_acc[0]; imu_buffer[buffer_index].acc[1]=filtered_acc[1]; imu_buffer[buffer_index].acc[2]=filtered_acc[2];
    sendPositionToSonia(g_ekf.x, g_ekf.y, g_ekf.z);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processOrientation(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jfloat w, jlong ts) {
    imu_buffer[buffer_index].ori[0]=x; imu_buffer[buffer_index].ori[1]=y; imu_buffer[buffer_index].ori[2]=z; imu_buffer[buffer_index].ori[3]=w;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processGyroscope(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong ts) {
    if (!g_ekf.is_calibrated) {
        g_ekf.sum_gyro[0]+=x; g_ekf.sum_gyro[1]+=y; g_ekf.sum_gyro[2]+=z; g_ekf.calib_samples_count++;
        if (g_ekf.calib_samples_count >= 200) {
            for(int i=0; i<3; i++) { g_ekf.acc_bias[i]=g_ekf.sum_acc[i]/200; g_ekf.gyro_bias[i]=g_ekf.sum_gyro[i]/200; }
            g_ekf.is_calibrated = true;
        }
        return;
    }
    filtered_gyro[0]=ALPHA_IMU*(x-g_ekf.gyro_bias[0])+(1-ALPHA_IMU)*filtered_gyro[0];
    filtered_gyro[1]=ALPHA_IMU*(y-g_ekf.gyro_bias[1])+(1-ALPHA_IMU)*filtered_gyro[1];
    filtered_gyro[2]=ALPHA_IMU*(z-g_ekf.gyro_bias[2])+(1-ALPHA_IMU)*filtered_gyro[2];
    imu_buffer[buffer_index].gyro[0]=filtered_gyro[0]; imu_buffer[buffer_index].gyro[1]=filtered_gyro[1]; imu_buffer[buffer_index].gyro[2]=filtered_gyro[2];
    buffer_index = (buffer_index + 1) % WINDOW_SIZE;
    if (buffer_index % 10 == 0) runInference();
}
