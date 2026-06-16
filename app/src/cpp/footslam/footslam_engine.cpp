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
#include <atomic>

#include <tensorflow/lite/c/c_api.h>

#define LOG_TAG "GeoSlam_Native"

static JavaVM* g_jvm = nullptr;
static jobject g_manager_global = nullptr;
static jmethodID g_callback_mid = nullptr;

static TfLiteModel* g_model = nullptr;
static TfLiteInterpreter* g_interpreter = nullptr;
static void* g_model_buffer = nullptr;
static std::recursive_mutex g_mutex;
static std::atomic<bool> g_is_inferring(false);

struct Wall { float x1, y1, x2, y2; };
static std::vector<Wall> g_walls;

struct EKFState {
    float x = 0, y = 0, z = 0;
    float yaw = 0;
    bool calibrated = false;
    int samples = 0;
    long long last_inference_ts = 0;

    float acc_bias[3] = {0,0,0}, gyro_bias[3] = {0,0,0};
    float acc_sum[3] = {0,0,0}, gyro_sum[3] = {0,0,0};

    // --- CONFIGURATION NAVIGATION PROFESSIONNELLE ---
    float VAR_MIN = 0.08f;
    float SPEED_THRESHOLD = 0.04f;
    float SCALE_FACTOR = 1.0f;
    float alpha = 0.35f;

    float min_x = -20.0f; float max_x = 20.0f;
    float min_y = -20.0f; float max_y = 20.0f;
    float doorX = 0.0f;   float doorY = 0.0f;

    float last_vx = 0, last_vy = 0;
} g_ekf;

bool checkCollision(float mx1, float my1, float mx2, float my2) {
    return std::any_of(g_walls.begin(), g_walls.end(), [&](const Wall& w) {
        float den = (w.y2 - w.y1) * (mx2 - mx1) - (w.x2 - w.x1) * (my2 - my1);
        if (std::abs(den) < 1e-6) return false;
        float ua = ((w.x2 - w.x1) * (my1 - w.y1) - (w.y2 - w.y1) * (mx1 - w.x1)) / den;
        float ub = ((mx2 - mx1) * (my1 - w.y1) - (my2 - my1) * (mx1 - w.x1)) / den;
        return (ua >= 0.0f && ua <= 1.0f && ub >= 0.0f && ub <= 1.0f);
    });
}

const int BUFFER_SIZE = 200;
struct IMUFrame {
    float acc[3] = {0,0,0};
    float gyro[3] = {0,0,0};
    bool has_acc = false;
    bool has_gyro = false;
};
static std::vector<IMUFrame> g_buffer(BUFFER_SIZE);
static IMUFrame g_pending_frame;
static int g_idx = 0;
static int g_downsample_counter = 0;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

void notifyJava(float x, float y, float z) {
    if (!g_jvm || !g_manager_global || !g_callback_mid) return;
    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        env->CallVoidMethod(g_manager_global, g_callback_mid, (jfloat)x, (jfloat)y, (jfloat)z);
        g_jvm->DetachCurrentThread();
    } else {
        env->CallVoidMethod(g_manager_global, g_callback_mid, (jfloat)x, (jfloat)y, (jfloat)z);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_setWallsNative(JNIEnv* env, jobject thiz, jfloatArray walls, jfloat width, jfloat height, jfloat dx, jfloat dy) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    jsize len = env->GetArrayLength(walls);
    float* coords = env->GetFloatArrayElements(walls, nullptr);
    g_walls.clear();
    for (int i = 0; i < len; i += 4) {
        g_walls.push_back({coords[i], coords[i+1], coords[i+2], coords[i+3]});
    }
    env->ReleaseFloatArrayElements(walls, coords, JNI_ABORT);

    g_ekf.doorX = dx;
    g_ekf.doorY = dy;
    g_ekf.min_x = -dx;
    g_ekf.max_x = width - dx;
    g_ekf.min_y = dy - height;
    g_ekf.max_y = dy;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_setMovementScaleNative(JNIEnv* env, jobject thiz, jfloat scale) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    g_ekf.SCALE_FACTOR = scale;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_loadModelNative(JNIEnv* env, jobject thiz, jobject am, jstring path) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (g_manager_global) env->DeleteGlobalRef(g_manager_global);
    g_manager_global = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_callback_mid = env->GetMethodID(cls, "onPositionCalculated", "(FFF)V");
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
    g_model = TfLiteModelCreate((const char*)g_model_buffer, size);
    TfLiteInterpreterOptions* opts = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(opts, 4);
    g_interpreter = TfLiteInterpreterCreate(g_model, opts);
    TfLiteInterpreterOptionsDelete(opts);
    if (g_interpreter && TfLiteInterpreterAllocateTensors(g_interpreter) == kTfLiteOk) {
        float old_dx = g_ekf.doorX; float old_dy = g_ekf.doorY;
        float old_min_x = g_ekf.min_x; float old_max_x = g_ekf.max_x;
        float old_min_y = g_ekf.min_y; float old_max_y = g_ekf.max_y;
        g_ekf = EKFState();
        g_ekf.doorX = old_dx; g_ekf.doorY = old_dy;
        g_ekf.min_x = old_min_x; g_ekf.max_x = old_max_x;
        g_ekf.min_y = old_min_y; g_ekf.max_y = old_max_y;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

void runInference(long long ts_ns) {
    if (g_is_inferring.exchange(true)) return;
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (!g_ekf.calibrated || !g_interpreter) { g_is_inferring = false; return; }

    TfLiteTensor* input = TfLiteInterpreterGetInputTensor(g_interpreter, 0);
    float* data = (float*)TfLiteTensorData(input);
    int window = TfLiteTensorDim(input, 1);
    int features = TfLiteTensorDim(input, 2);

    float acc_mag_sum = 0, acc_mag_sq_sum = 0;
    for (int i = 0; i < window; ++i) {
        int bi = (g_idx - window + i + BUFFER_SIZE) % BUFFER_SIZE;
        size_t base = (size_t)i * features;
        data[base+0] = g_buffer[bi].acc[0]; data[base+1] = g_buffer[bi].acc[1]; data[base+2] = g_buffer[bi].acc[2];
        if (features >= 6) {
            data[base+3] = g_buffer[bi].gyro[0]; data[base+4] = g_buffer[bi].gyro[1]; data[base+5] = g_buffer[bi].gyro[2];
        }
        float mag = std::sqrt(data[base+0]*data[base+0] + data[base+1]*data[base+1] + data[base+2]*data[base+2]);
        acc_mag_sum += mag; acc_mag_sq_sum += mag * mag;
    }

    float avg_mag = acc_mag_sum / (float)window;
    float acc_var = (acc_mag_sq_sum / (float)window) - (avg_mag * avg_mag);

    if (acc_var < g_ekf.VAR_MIN) {
        g_ekf.last_vx = 0; g_ekf.last_vy = 0;
        g_is_inferring = false; return;
    }

    if (TfLiteInterpreterInvoke(g_interpreter) == kTfLiteOk) {
        const TfLiteTensor* output = TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
        const float* out_vel = (const float*)TfLiteTensorData(output);
        float speed = std::sqrt(out_vel[0]*out_vel[0] + out_vel[1]*out_vel[1]);

        if (speed > g_ekf.SPEED_THRESHOLD) {
            float dt = (g_ekf.last_inference_ts > 0) ? (float)(ts_ns - g_ekf.last_inference_ts) / 1e9f : 0.05f;
            g_ekf.last_inference_ts = ts_ns;
            g_ekf.last_vx = g_ekf.alpha * out_vel[0] + (1.0f - g_ekf.alpha) * g_ekf.last_vx;
            g_ekf.last_vy = g_ekf.alpha * out_vel[1] + (1.0f - g_ekf.alpha) * g_ekf.last_vy;

            float cos_a = std::cos(g_ekf.yaw); float sin_a = std::sin(g_ekf.yaw);
            float dx = (g_ekf.last_vx * cos_a + g_ekf.last_vy * sin_a) * dt * g_ekf.SCALE_FACTOR;
            float dy = (-g_ekf.last_vx * sin_a + g_ekf.last_vy * cos_a) * dt * g_ekf.SCALE_FACTOR;

            float next_x = g_ekf.x + dx; float next_y = g_ekf.y + dy;

            if (!checkCollision(g_ekf.x + g_ekf.doorX, g_ekf.doorY - g_ekf.y, next_x + g_ekf.doorX, g_ekf.doorY - next_y)) {
                g_ekf.x = std::max(g_ekf.min_x, std::min(g_ekf.max_x, next_x));
                g_ekf.y = std::max(g_ekf.min_y, std::min(g_ekf.max_y, next_y));
            }

            notifyJava(g_ekf.x, g_ekf.y, g_ekf.z);
        }
    }
    g_is_inferring = false;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processAccelerometer(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong ts) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (!g_ekf.calibrated) { g_ekf.acc_sum[0] += x; g_ekf.acc_sum[1] += y; g_ekf.acc_sum[2] += z; return; }
    g_pending_frame.acc[0] = x - g_ekf.acc_bias[0]; g_pending_frame.acc[1] = y - g_ekf.acc_bias[1]; g_pending_frame.acc[2] = z - g_ekf.acc_bias[2];
    g_pending_frame.has_acc = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_geo_1slam_footslam_FootSlamManager_processGyroscope(JNIEnv* env, jobject thiz, jfloat x, jfloat y, jfloat z, jlong ts) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex);
    if (!g_ekf.calibrated) {
        g_ekf.gyro_sum[0] += x; g_ekf.gyro_sum[1] += y; g_ekf.gyro_sum[2] += z;
        if (++g_ekf.samples >= 300) {
            for(int i=0;i<3;i++) { g_ekf.acc_bias[i] = g_ekf.acc_sum[i] / 300.0f; g_ekf.gyro_bias[i] = g_ekf.gyro_sum[i] / 300.0f; }
            g_ekf.calibrated = true;
        }
        return;
    }
    g_pending_frame.gyro[0] = x - g_ekf.gyro_bias[0]; g_pending_frame.gyro[1] = y - g_ekf.gyro_bias[1]; g_pending_frame.gyro[2] = z - g_ekf.gyro_bias[2];
    g_pending_frame.has_gyro = true;
    if (g_pending_frame.has_acc && g_pending_frame.has_gyro) {
        g_buffer[g_idx] = g_pending_frame; g_idx = (g_idx + 1) % BUFFER_SIZE;
        g_pending_frame.has_acc = false; g_pending_frame.has_gyro = false;
        if (++g_downsample_counter >= 5) { g_downsample_counter = 0; runInference(ts); }
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_footslam_FootSlamManager_processYawNative(JNIEnv* env, jobject thiz, jfloat yaw, jlong ts) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex); g_ekf.yaw = yaw;
}
extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_footslam_FootSlamManager_setInitialPositionNative(JNIEnv* env, jobject thiz, jfloat x, jfloat y) {
    std::lock_guard<std::recursive_mutex> lock(g_mutex); g_ekf.x = x; g_ekf.y = y; g_ekf.last_vx = 0; g_ekf.last_vy = 0;
}
extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_footslam_FootSlamManager_processPressure(JNIEnv* env, jobject thiz, jfloat p, jlong ts) {}
extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_footslam_FootSlamManager_processStepDetectorNative(JNIEnv* env, jobject thiz, jlong ts) {}
extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_footslam_FootSlamManager_setStepDetectorSupportedNative(JNIEnv* env, jobject thiz, jboolean supported) {}
extern "C" JNIEXPORT void JNICALL Java_com_example_geo_1slam_footslam_FootSlamManager_resetPositionNative(JNIEnv* env, jobject thiz) {}
