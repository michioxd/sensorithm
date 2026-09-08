#include <jni.h>
#include <vector>
#include <cstdint>
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SensorithmJNI", __VA_ARGS__)

struct Sensor {
    int id;
    int x;
    int y;
    bool active;
    bool previousActive;
    bool stateChanged;
    float threshold;
    std::vector<float> referenceROI;
    int calibrated_half_sz_x;
    int calibrated_half_sz_y;
    float score;
};

Sensor g_sensors[6];
int g_configs[24];
int g_bootstrapDurationFrames = 5;
int g_bootstrapCounter = 0;

bool g_needs_recalibrate = true;
int g_width = 0;
int g_height = 0;

extern "C" JNIEXPORT void JNICALL
Java_ch_michioxd_sensorithm_SensorithmJNI_setAirConfig(
        JNIEnv* env, jobject, jintArray configs) {
    env->GetIntArrayRegion(configs, 0, 24, g_configs);
    g_needs_recalibrate = true;
}

extern "C" JNIEXPORT void JNICALL
Java_ch_michioxd_sensorithm_SensorithmJNI_setThreshold(JNIEnv* env, jobject, jint sensor_index, jfloat threshold) {
    if (sensor_index >= 0 && sensor_index < 6) {
        g_sensors[sensor_index].threshold = threshold;
    } else if (sensor_index == -1) {
        for (int i = 0; i < 6; i++) {
            g_sensors[i].threshold = threshold;
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_ch_michioxd_sensorithm_SensorithmJNI_recalibrate(JNIEnv* env, jobject) {
    g_needs_recalibrate = true;
    g_bootstrapCounter = 0;
}

extern "C" JNIEXPORT jbyte JNICALL
Java_ch_michioxd_sensorithm_SensorithmJNI_processFrame(
        JNIEnv* env, jobject,
        jobject yPlane, jint width, jint height, jint rowStride) {
    
    if (width <= 0 || height <= 0) return -1;
    
    if (width != g_width || height != g_height) {
        g_width = width;
        g_height = height;
        g_needs_recalibrate = true;
    }

    uint8_t* pixels = (uint8_t*)env->GetDirectBufferAddress(yPlane);
    if (!pixels) return -1;

    if (g_needs_recalibrate) {
        if (g_bootstrapCounter < g_bootstrapDurationFrames) {
            g_bootstrapCounter++;
            return -1;
        }
        
        for (int i = 0; i < 6; i++) {
            g_sensors[i].id = i;
            
            int X = g_configs[i * 4 + 0];
            int Y = g_configs[i * 4 + 1];
            int sizeX = g_configs[i * 4 + 2];
            int sizeY = g_configs[i * 4 + 3];
            
            g_sensors[i].x = X;
            g_sensors[i].y = Y;
            
            int half_sz_x = std::max(1, sizeX / 2);
            int half_sz_y = std::max(1, sizeY / 2);
            int actual_sz_x = half_sz_x * 2 + 1;
            int actual_sz_y = half_sz_y * 2 + 1;
            g_sensors[i].referenceROI.resize(actual_sz_x * actual_sz_y);
            g_sensors[i].calibrated_half_sz_x = half_sz_x;
            g_sensors[i].calibrated_half_sz_y = half_sz_y;
            
            int idx = 0;
            for(int r = Y - half_sz_y; r <= Y + half_sz_y; r++) {
                if (r < 0 || r >= height) continue;
                for(int c = X - half_sz_x; c <= X + half_sz_x; c++) {
                    if (c < 0 || c >= width) continue;
                    g_sensors[i].referenceROI[idx++] = pixels[r * rowStride + c] / 255.0f;
                }
            }
            g_sensors[i].active = false;
            g_sensors[i].previousActive = false;
            g_sensors[i].stateChanged = false;
        }
        g_needs_recalibrate = false;
        g_bootstrapCounter = 0;
        return 0;
    }

    jbyte mask = 0;

    for (int i = 0; i < 6; i++) {
        int X = g_sensors[i].x;
        int Y = g_sensors[i].y;
        int half_sz_x = g_sensors[i].calibrated_half_sz_x;
        int half_sz_y = g_sensors[i].calibrated_half_sz_y;
        int valid_pixels = 0;
        
        int hit_count = 0;
        int idx = 0;
        for(int r = Y - half_sz_y; r <= Y + half_sz_y; r++) {
            if (r < 0 || r >= height) continue;
            for(int c = X - half_sz_x; c <= X + half_sz_x; c++) {
                if (c < 0 || c >= width) continue;
                float current = pixels[r * rowStride + c] / 255.0f;
                float diff = current - g_sensors[i].referenceROI[idx++];
                
                if (std::abs(diff) * 255.0f > g_sensors[i].threshold) {
                    hit_count++;
                }
                valid_pixels++;
            }
        }
        
        const float AREA_TRIGGER_PERCENTAGE = 0.15f;
        g_sensors[i].score = hit_count;
        bool active = valid_pixels > 0 && hit_count > (valid_pixels * AREA_TRIGGER_PERCENTAGE);
        
        g_sensors[i].stateChanged = (active != g_sensors[i].previousActive);
        g_sensors[i].previousActive = active;
        g_sensors[i].active = active;
        
        if (active) {
            mask |= (1 << i);
        }
    }

    return mask;
}
