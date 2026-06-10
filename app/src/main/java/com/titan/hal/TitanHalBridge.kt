package com.titan.hal

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hardware Abstraction Layer (HAL) Bridge
 * Kết nối Kotlin với Native C++ Core để truy xuất phần cứng cấp thấp
 */
object TitanHalBridge {
    private const val TAG = "TitanHalBridge"
    
    // Load native library
    init {
        try {
            System.loadLibrary("titan_core")
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }
    
    /**
     * Quét thông tin phần cứng chi tiết
     * @return JSON string chứa thông tin CPU, GPU, RAM, UFS
     */
    suspend fun scanHardware(): HardwareSnapshot = withContext(Dispatchers.IO) {
        try {
            val json = nativeScanHardware()
            parseHardwareSnapshot(json)
        } catch (e: Exception) {
            Log.e(TAG, "Hardware scan failed: ${e.message}")
            HardwareSnapshot.empty()
        }
    }
    
    /**
     * Thiết lập CPU Governor cho cluster cụ thể
     */
    suspend fun setCpuGovernor(clusterId: Int, governor: String): Boolean = withContext(Dispatchers.IO) {
        nativeSetCpuGovernor(clusterId, governor)
    }
    
    /**
     * Đặt tần số CPU tối thiểu/tối đa
     */
    suspend fun setCpuFreqRange(clusterId: Int, minFreq: Long, maxFreq: Long): Boolean = 
        withContext(Dispatchers.IO) {
            nativeSetCpuFreqRange(clusterId, minFreq, maxFreq)
        }
    
    /**
     * Tối ưu GPU cho Adreno/Mali
     */
    suspend fun optimizeGpu(mode: GpuOptimizationMode): Boolean = withContext(Dispatchers.IO) {
        nativeOptimizeGpu(mode.ordinal)
    }
    
    /**
     * Điều chỉnh LMK (Low Memory Killer)
     */
    suspend fun adjustLmk(minfreeValues: IntArray): Boolean = withContext(Dispatchers.IO) {
        nativeAdjustLmk(minfreeValues)
    }
    
    /**
     * Đọc nhiệt độ từ thermal zones
     */
    suspend fun readThermalZones(): Map<String, Float> = withContext(Dispatchers.IO) {
        val json = nativeReadThermalZones()
        parseThermalZones(json)
    }
    
    /**
     * Kiểm tra xem thiết bị có root/Shizuku không
     */
    fun hasRootAccess(): Boolean = nativeHasRootAccess()
    
    fun hasShizukuAccess(): Boolean = nativeHasShizukuAccess()
    
    // Native methods declarations
    private external fun nativeScanHardware(): String
    private external fun nativeSetCpuGovernor(clusterId: Int, governor: String): Boolean
    private external fun nativeSetCpuFreqRange(clusterId: Int, minFreq: Long, maxFreq: Long): Boolean
    private external fun nativeOptimizeGpu(mode: Int): Boolean
    private external fun nativeAdjustLmk(minfreeValues: IntArray): Boolean
    private external fun nativeReadThermalZones(): String
    private external fun nativeHasRootAccess(): Boolean
    private external fun nativeHasShizukuAccess(): Boolean
    
    private fun parseHardwareSnapshot(json: String): HardwareSnapshot {
        // Simple JSON parsing (trong production dùng Gson/Moshi)
        return try {
            val cpuCores = extractIntValue(json, "cpu_cores") ?: 0
            val gpuModel = extractStringValue(json, "gpu_model") ?: "Unknown"
            val ramTotal = extractLongValue(json, "ram_total_mb") ?: 0L
            val ufsType = extractStringValue(json, "ufs_type") ?: "Unknown"
            val chipset = extractStringValue(json, "chipset") ?: Build.BOARD
            
            HardwareSnapshot(
                cpuCores = cpuCores,
                gpuModel = gpuModel,
                ramTotalMb = ramTotal,
                ufsType = ufsType,
                chipset = chipset,
                isRooted = hasRootAccess(),
                hasShizuku = hasShizukuAccess()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            HardwareSnapshot.empty()
        }
    }
    
    private fun parseThermalZones(json: String): Map<String, Float> {
        val map = mutableMapOf<String, Float>()
        // Parse đơn giản, trong production dùng JSON parser thực thụ
        return map
    }
    
    private fun extractIntValue(json: String, key: String): Int? {
        val regex = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractLongValue(json: String, key: String): Long? {
        val regex = "\"$key\"\\s*:\\s*(\\d+)".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }
    
    private fun extractStringValue(json: String, key: String): String? {
        val regex = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }
}

enum class GpuOptimizationMode {
    BALANCED,
    PERFORMANCE,
    BATTERY_SAVER,
    EXTREME
}

data class HardwareSnapshot(
    val cpuCores: Int,
    val gpuModel: String,
    val ramTotalMb: Long,
    val ufsType: String,
    val chipset: String,
    val isRooted: Boolean,
    val hasShizuku: Boolean
) {
    companion object {
        fun empty() = HardwareSnapshot(
            cpuCores = 0,
            gpuModel = "Unknown",
            ramTotalMb = 0L,
            ufsType = "Unknown",
            chipset = "Unknown",
            isRooted = false,
            hasShizuku = false
        )
    }
}
