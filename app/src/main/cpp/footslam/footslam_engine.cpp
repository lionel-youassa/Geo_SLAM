#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <cstdlib>
#include <vector>
#include <mutex>
#include <cmath>
#include <algorithm>
#include <cstring>

#include <tensorflow/lite/c/c_api.h>

#define LOG_TAG "GeoSlam_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;
static jobject g_manager_global = nullptr;
static jmethodID g_callback_mid = nullptr;

static TfLiteModel* g_model = nullptr;
static TfLiteInterpreter* g_interpreter = nullptr;
static void* g_model_buffer = nullptr;
static size_t g_model_size = 0;
static std::recursive_mutex g_mutex;

struct EKFState {
    float x = 0, y = 0, z = 0;
    bool calibrated = false;
    int samples = 0;
    int stabilization_counter = 0;
    long long last_ts = 0;
    float acc_bias[3] = {0,0,0}, gyro_bias[3] = {0,0,0};
    float acc_sum[3] = {0,0,0}, gyro_sum[3] = {0,0,0};
    float ref_pressure = -1.0f;

    // RÉGLAGES FINAUX DE PRÉCISION
    float VAR_THRESHOLD = 0.12f;
    float SPEED_MIN = 0.20f;
    float SCALE_FACTOR = 0.85f;    // Ajusté pour une marche naturelle (proportionnelle)
} g_ekf;

const int BUFFER_SIZE = 200;
struct IMUItem {
    float acc[3] = {0,0,0};
    float gyro[3] = {0,0,0};
    float ori[4] = {0,0,0,1};
};
static std::vector<IMUItem> g_buffer(BUFFER_SIZE);
static int g_idx = 0;
static int g_downsample_counter = 0;
static IMUItem g_current_frame;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

void notifyJava(float x, float y, float z) {
    if (!g_jvm || !g_manager_global || !g_callback_mid) return;
    JNIEnv* env = nullptr;
    jint res = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    bool attached = false;
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (res != JNI_OK) return;

    env->CallVoidMethod(g_manager_global, g_callback_mid, (jfloat)x, (jfloat)y, (jfloat)z);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_jvm->DetachCurrentThread();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_loadModelNative(JNIEnv* env, jobject thiz, jobject am, jstring path) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (g_manager_global) env->DeleteGlobalRef(g_manager_global);
    g_manager_global = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_callback_mid = env->GetMethodID(cls, "onPositionCalculated", "(FFF)V");
    env->DeleteLocalRef(cls);

    const char* c_path = env->GetStringUTFChars(path, nullptr);
    AAssetManager* mgr = AAssetManager_fromJava(env, am);
    AAsset* asset = AAssetManager_open(mgr, c_path, AASSET_MODE_BUFFER);
    env->ReleaseStringUTFChars(path, c_path);
    if (!asset) return JNI_FALSE;

    size_t size = AAsset_getLength(asset);
    if (g_model_buffer) free(g_model_buffer);
    posix_memalign(&g_model_buffer, 64, size);
    AAsset_read(asset, g_model_buffer, size);
    AAsset_close(asset);

    if (g_interpreter) TfLiteInterpreterDelete(g_interpreter);
    if (g_model) TfLiteModelDelete(g_model);
    g_model = TfLiteModelCreate((const char*)g_model_buffer, size);
    TfLiteInterpreterOptions* opts = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(opts, 2);
    g_interpreter = TfLiteInterpreterCreate(g_model, opts);
    TfLiteInterpreterOptionsDelete(opts);
    TfLiteInterpreterAllocateTensors(g_interpreter);

    g_ekf = EKFState();
    std::fill(g_buffer.begin(), g_buffer.end(), IMUItem());
    LOGI("FootSlam: Moteur chargé et prêt.");
    return JNI_TRUE;
}

void runInference(long long ts_ns) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (!g_ekf.calibrated || !g_interpreter) return;

    TfLiteTensor* input = TfLiteInterpreterGetInputTensor(g_interpreter, 0);
    float* data = (float*)TfLiteTensorData(input);
    int window = TfLiteTensorDim(input, 1);
    int features = TfLiteTensorDim(input, 2);

    float acc_mag_sum = 0, acc_mag_sq_sum = 0;
    for (int i = 0; i < window; ++i) {
        int bi = (g_idx - window + i + BUFFER_SIZE) % BUFFER_SIZE;
        size_t base = (size_t)i * features;
        data[base+0] = g_buffer[bi].acc[0];
        data[base+1] = g_buffer[bi].acc[1];
        data[base+2] = g_buffer[bi].acc[2];
        data[base+3] = g_buffer[bi].gyro[0];
        data[base+4] = g_buffer[bi].gyro[1];
        data[base+5] = g_buffer[bi].gyro[2];
        float mag = std::sqrt(data[base+0]*data[base+0] + data[base+1]*data[base+1] + data[base+2]*data[base+2]);
        acc_mag_sum += mag; acc_mag_sq_sum += mag * mag;
    }

    float variance = (acc_mag_sq_sum / (float)window) - std::pow(acc_mag_sum / (float)window, 2);
    float dt = (g_ekf.last_ts > 0) ? (float)(ts_ns - g_ekf.last_ts) / 1e9f : 0.5f;
    if (dt > 1.0f) dt = 0.5f;
    g_ekf.last_ts = ts_ns;

    if (g_ekf.stabilization_counter < 40) {
        g_ekf.stabilization_counter++;
        g_ekf.x = 0; g_ekf.y = 0;
    } else if (variance > g_ekf.VAR_THRESHOLD) {
        if (TfLiteInterpreterInvoke(g_interpreter) == kTfLiteOk) {
            const TfLiteTensor* output = TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
            const float* out_vel = (const float*)TfLiteTensorData(output);
            float speed = std::sqrt(out_vel[0]*out_vel[0] + out_vel[1]*out_vel[1]);
            if (speed > g_ekf.SPEED_MIN) {
                float dx = out_vel[0] * dt * g_ekf.SCALE_FACTOR;
                float dy = out_vel[1] * dt * g_ekf.SCALE_FACTOR;
                int last = (g_idx - 1 + BUFFER_SIZE) % BUFFER_SIZE;
                float* q = g_buffer[last].ori;
                float yaw = std::atan2(2.0f*(q[3]*q[2]+q[0]*q[1]), 1.0f-2.0f*(q[1]*q[1]+q[2]*q[2]));
                g_ekf.x += dx * std::cos(yaw) - dy * std::sin(yaw);
                g_ekf.y += dx * std::sin(yaw) + dy * std::cos(yaw);

                LOGI("FootSlam: Vitesse IA=%.2f m/s, Dist_ajoutee=%.3f m", speed, std::sqrt(dx*dx+dy*dy));
            }
        }
    }
    notifyJava(g_ekf.x, g_ekf.y, g_ekf.z);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processAccelerometer(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong ts) {
    if (!std::isfinite(x)) return;
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (!g_ekf.calibrated) {
        g_ekf.acc_sum[0] += x; g_ekf.acc_sum[1] += y; g_ekf.acc_sum[2] += z;
        return;
    }
    g_current_frame.acc[0] = x - g_ekf.acc_bias[0];
    g_current_frame.acc[1] = y - g_ekf.acc_bias[1];
    g_current_frame.acc[2] = z - g_ekf.acc_bias[2];
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processOrientation(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jfloat w, jlong ts) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    g_current_frame.ori[0] = x; g_current_frame.ori[1] = y;
    g_current_frame.ori[2] = z; g_current_frame.ori[3] = w;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processGyroscope(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong ts) {
    if (!std::isfinite(x)) return;
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (!g_ekf.calibrated) {
        g_ekf.gyro_sum[0] += x; g_ekf.gyro_sum[1] += y; g_ekf.gyro_sum[2] += z;
        if (++g_ekf.samples >= 400) {
            g_ekf.acc_bias[0]=g_ekf.acc_sum[0]/400.0f; g_ekf.acc_bias[1]=g_ekf.acc_sum[1]/400.0f; g_ekf.acc_bias[2]=g_ekf.acc_sum[2]/400.0f;
            g_ekf.gyro_bias[0]=g_ekf.gyro_sum[0]/400.0f; g_ekf.gyro_bias[1]=g_ekf.gyro_sum[1]/400.0f; g_ekf.gyro_bias[2]=g_ekf.gyro_sum[2]/400.0f;
            g_ekf.calibrated = true;
            g_ekf.x = 0; g_ekf.y = 0; g_ekf.last_ts = ts;
            std::fill(g_buffer.begin(), g_buffer.end(), IMUItem());
            LOGI("FootSlam: Calibrage fini.");
        }
        return;
    }

    g_current_frame.gyro[0] = x - g_ekf.gyro_bias[0];
    g_current_frame.gyro[1] = y - g_ekf.gyro_bias[1];
    g_current_frame.gyro[2] = z - g_ekf.gyro_bias[2];

    if (++g_downsample_counter >= 5) {
        g_downsample_counter = 0;
        g_buffer[g_idx] = g_current_frame;
        g_idx = (g_idx + 1) % BUFFER_SIZE;
        if (g_idx % 10 == 0) runInference(ts);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processPressure(JNIEnv* env, jobject thiz, jfloat p, jlong ts) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (g_ekf.ref_pressure < 0) { g_ekf.ref_pressure = p; return; }
    g_ekf.z = 0.9f * g_ekf.z + 0.1f * (44330.0f * (1.0f - pow(p / g_ekf.ref_pressure, 0.1903f)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_resetPositionNative(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    g_ekf.x = 0; g_ekf.y = 0; g_ekf.z = 0;
    g_ekf.stabilization_counter = 0;
}
