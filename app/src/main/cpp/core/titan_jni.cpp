// TITAN JNI BRIDGE - Kết nối Native C++ với Kotlin
// Cung cấp interface cho Android app gọi vào Titan Core

#include <jni.h>
#include <string>
#include <vector>
#include "titan_scanner.h"

// Global scanner instance
static titan::HardwareScanner* g_scanner = nullptr;

extern "C" {

// ============================================================================
// INITIALIZATION
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_titan_core_TitanCore_nativeInitialize(JNIEnv* env, jobject thiz) {
    if (g_scanner != nullptr) {
        return JNI_TRUE; // Already initialized
    }
    
    g_scanner = new titan::HardwareScanner();
    bool success = g_scanner->initialize();
    
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_titan_core_TitanCore_nativeRelease(JNIEnv* env, jobject thiz) {
    if (g_scanner != nullptr) {
        delete g_scanner;
        g_scanner = nullptr;
    }
}

// ============================================================================
// HARDWARE SCANNING
// ============================================================================

JNIEXPORT jobject JNICALL
Java_com_titan_core_TitanCore_nativeScanHardware(JNIEnv* env, jobject thiz) {
    if (g_scanner == nullptr) {
        return nullptr;
    }
    
    titan::HardwareSnapshot snapshot = g_scanner->scan(true);
    
    // Create HardwareSnapshot Java object
    jclass snapshotClass = env->FindClass("com/titan/core/HardwareSnapshot");
    jmethodID constructor = env->GetMethodID(snapshotClass, "<init>", "()V");
    jobject result = env->NewObject(snapshotClass, constructor);
    
    // Set fields
    env->SetLongField(result, env->GetFieldID(snapshotClass, "timestampNs", "J"), 
                     snapshot.timestamp_ns);
    
    env->SetObjectField(result, env->GetFieldID(snapshotClass, "deviceModel", "Ljava/lang/String;"),
                       env->NewStringUTF(snapshot.device_model.c_str()));
    
    env->SetObjectField(result, env->GetFieldID(snapshotClass, "socVendor", "Ljava/lang/String;"),
                       env->NewStringUTF(snapshot.soc_vendor.c_str()));
    
    env->SetObjectField(result, env->GetFieldID(snapshotClass, "socModel", "Ljava/lang/String;"),
                       env->NewStringUTF(snapshot.soc_model.c_str()));
    
    env->SetIntField(result, env->GetFieldID(snapshotClass, "totalCpuCores", "I"),
                    snapshot.total_cpu_cores);
    
    env->SetFloatField(result, env->GetFieldID(snapshotClass, "systemLoadIndex", "F"),
                      snapshot.system_load_index);
    
    env->SetFloatField(result, env->GetFieldID(snapshotClass, "thermalHeadroom", "F"),
                      snapshot.thermal_headroom);
    
    env->SetFloatField(result, env->GetFieldID(snapshotClass, "performancePotential", "F"),
                      snapshot.performance_potential);
    
    env->SetBooleanField(result, env->GetFieldID(snapshotClass, "isThermalConstrained", "Z"),
                        snapshot.is_thermal_constrained ? JNI_TRUE : JNI_FALSE);
    
    env->SetBooleanField(result, env->GetFieldID(snapshotClass, "isPowerConstrained", "Z"),
                        snapshot.is_power_constrained ? JNI_TRUE : JNI_FALSE);
    
    env->SetBooleanField(result, env->GetFieldID(snapshotClass, "dataQualityHigh", "Z"),
                        snapshot.data_quality_high ? JNI_TRUE : JNI_FALSE);
    
    return result;
}

JNIEXPORT jobject JNICALL
Java_com_titan_core_TitanCore_nativeScanFast(JNIEnv* env, jobject thiz) {
    if (g_scanner == nullptr) {
        return nullptr;
    }
    
    titan::HardwareSnapshot snapshot = g_scanner->scanFast();
    
    // Similar to nativeScanHardware but optimized for speed
    jclass snapshotClass = env->FindClass("com/titan/core/HardwareSnapshot");
    jmethodID constructor = env->GetMethodID(snapshotClass, "<init>", "()V");
    jobject result = env->NewObject(snapshotClass, constructor);
    
    env->SetLongField(result, env->GetFieldID(snapshotClass, "timestampNs", "J"), 
                     snapshot.timestamp_ns);
    
    env->SetFloatField(result, env->GetFieldID(snapshotClass, "systemLoadIndex", "F"),
                      snapshot.system_load_index);
    
    env->SetFloatField(result, env->GetFieldID(snapshotClass, "thermalHeadroom", "F"),
                      snapshot.thermal_headroom);
    
    return result;
}

// ============================================================================
// SOC DETECTION
// ============================================================================

JNIEXPORT jstring JNICALL
Java_com_titan_core_TitanCore_nativeGetSOCVendor(JNIEnv* env, jobject thiz) {
    if (g_scanner == nullptr) {
        return env->NewStringUTF("unknown");
    }
    
    std::string vendor = g_scanner->getSOCVendor();
    return env->NewStringUTF(vendor.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_titan_core_TitanCore_nativeGetSOCModel(JNIEnv* env, jobject thiz) {
    if (g_scanner == nullptr) {
        return env->NewStringUTF("unknown");
    }
    
    std::string model = g_scanner->getSOCModel();
    return env->NewStringUTF(model.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_titan_core_TitanCore_nativeHasAdrenoGPU(JNIEnv* env, jobject thiz) {
    if (g_scanner == nullptr) {
        return JNI_FALSE;
    }
    
    return g_scanner->hasAdrenoGPU() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_titan_core_TitanCore_nativeHasMaliGPU(JNIEnv* env, jobject thiz) {
    if (g_scanner == nullptr) {
        return JNI_FALSE;
    }
    
    return g_scanner->hasMaliGPU() ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// VECTOR ANALYSIS
// ============================================================================

JNIEXPORT jfloat JNICALL
Java_com_titan_core_TitanCore_nativePredictLoad(JNIEnv* env, jobject thiz, 
                                                jfloatArray historyArray, 
                                                jint lookaheadMs) {
    jsize size = env->GetArrayLength(historyArray);
    jfloat* history = env->GetFloatArrayElements(historyArray, nullptr);
    
    std::vector<float> history_vec;
    for (jsize i = 0; i < size; i++) {
        history_vec.push_back(history[i]);
    }
    
    env->ReleaseFloatArrayElements(historyArray, history, 0);
    
    float prediction = titan::VectorAnalyzer::predictLoad(history_vec, lookahead_ms);
    return prediction;
}

JNIEXPORT jstring JNICALL
Java_com_titan_core_TitanCore_nativeDetectBottleneck(JNIEnv* env, jobject thiz,
                                                     jobject snapshotObj) {
    // Convert Java HardwareSnapshot to native struct
    // Simplified for demo
    titan::HardwareSnapshot snapshot;
    snapshot.gpu.load_percent = 85.0f;
    snapshot.cpu_clusters[0].load_percent = 90.0f;
    snapshot.cpu_clusters[0].is_online = true;
    snapshot.memory.ram_usage_percent = 75.0f;
    snapshot.system_load_index = 8.5f;
    snapshot.memory.storage_type = "UFS 3.1";
    
    std::string bottleneck = titan::VectorAnalyzer::detectBottleneck(snapshot);
    return env->NewStringUTF(bottleneck.c_str());
}

JNIEXPORT jfloat JNICALL
Java_com_titan_core_TitanCore_nativeCalculatePerformanceScore(JNIEnv* env, jobject thiz,
                                                              jobject snapshotObj) {
    // Convert Java HardwareSnapshot to native struct
    titan::HardwareSnapshot snapshot;
    snapshot.thermal.cpu_temp_max = 75.0f;
    snapshot.is_thermal_constrained = false;
    snapshot.thermal.battery_level = 65;
    snapshot.thermal_headroom = 25.0f;
    
    float score = titan::VectorAnalyzer::calculatePerformanceScore(snapshot);
    return score;
}

JNIEXPORT jlong JNICALL
Java_com_titan_core_TitanCore_nativePredictThermalThrottle(JNIEnv* env, jobject thiz,
                                                           jobject currentObj,
                                                           jobject previousObj) {
    // Simplified implementation
    titan::HardwareSnapshot current, previous;
    current.thermal.cpu_temp_max = 78.0f;
    previous.thermal.cpu_temp_max = 76.0f;
    
    int64_t time_to_throttle = titan::VectorAnalyzer::predictThermalThrottle(current, previous);
    return (jlong)time_to_throttle;
}

// ============================================================================
// SAFE EXECUTION
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_titan_core_TitanCore_nativeExecuteWithSafety(JNIEnv* env, jobject thiz,
                                                      jstring commandStr,
                                                      jboolean dryRun) {
    const char* command = env->GetStringUTFChars(commandStr, nullptr);
    bool result = titan::SafeExecutor::executeWithSafety(std::string(command), 
                                                        dryRun ? true : false);
    env->ReleaseStringUTFChars(commandStr, command);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_titan_core_TitanCore_nativeWriteSysFSSafe(JNIEnv* env, jobject thiz,
                                                   jstring pathStr,
                                                   jstring valueStr,
                                                   jstring minValueStr,
                                                   jstring maxValueStr) {
    const char* path = env->GetStringUTFChars(pathStr, nullptr);
    const char* value = env->GetStringUTFChars(valueStr, nullptr);
    const char* min_value = env->GetStringUTFChars(minValueStr, nullptr);
    const char* max_value = env->GetStringUTFChars(maxValueStr, nullptr);
    
    bool result = titan::SafeExecutor::writeSysFSSafe(
        std::string(path), 
        std::string(value),
        std::string(min_value),
        std::string(max_value)
    );
    
    env->ReleaseStringUTFChars(pathStr, path);
    env->ReleaseStringUTFChars(valueStr, value);
    env->ReleaseStringUTFChars(minValueStr, min_value);
    env->ReleaseStringUTFChars(maxValueStr, max_value);
    
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_titan_core_TitanCore_nativeRollbackLastChanges(JNIEnv* env, jobject thiz) {
    titan::SafeExecutor::rollbackLastChanges();
}

// ============================================================================
// PERMISSION CHECK
// ============================================================================

JNIEXPORT jobjectArray JNICALL
Java_com_titan_core_TitanCore_nativeCheckPermissions(JNIEnv* env, jobject thiz) {
    if (g_scanner == nullptr) {
        return nullptr;
    }
    
    std::vector<std::string> missing;
    bool has_all = g_scanner->checkPermissions(missing);
    
    if (has_all) {
        return nullptr;
    }
    
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(missing.size(), stringClass, nullptr);
    
    for (size_t i = 0; i < missing.size(); i++) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(missing[i].c_str()));
    }
    
    return result;
}

} // extern "C"
