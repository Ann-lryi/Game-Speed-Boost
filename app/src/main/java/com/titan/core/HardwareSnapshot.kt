package com.titan.core

/**
 * HARDWARE SNAPSHOT - Data class đại diện cho trạng thái phần cứng tại một thời điểm
 * Được đồng bộ hóa với cấu trúc HardwareSnapshot trong C++
 */
data class HardwareSnapshot(
    // Timestamp
    val timestampNs: Long = 0L,
    
    // Device Info
    val deviceModel: String = "",
    val socVendor: String = "",
    val socModel: String = "",
    val androidVersion: String = "",
    val kernelVersion: String = "",
    
    // CPU
    val totalCpuCores: Int = 0,
    val cpuClusters: List<CPUClusterData> = emptyList(),
    
    // GPU
    val gpuData: GPUData? = null,
    
    // Memory
    val memoryData: MemoryData? = null,
    
    // Thermal
    val thermalData: ThermalData? = null,
    
    // Display
    val displayData: DisplayData? = null,
    
    // Network
    val networkData: NetworkData? = null,
    
    // Derived Metrics
    val systemLoadIndex: Float = 0f,
    val thermalHeadroom: Float = 0f,
    val performancePotential: Float = 0f,
    val isThermalConstrained: Boolean = false,
    val isPowerConstrained: Boolean = false,
    
    // Quality Flags
    val dataQualityHigh: Boolean = true,
    val warnings: List<String> = emptyList()
) {
    /**
     * Tính điểm hiệu năng tổng hợp (0-100)
     */
    fun calculateScore(): Float {
        var score = 100f
        
        // Penalize high temperatures
        thermalData?.cpuTempMax?.let { temp ->
            if (temp > 80f) {
                score -= (temp - 80f) * 2f
            }
        }
        
        // Penalize thermal throttling
        if (isThermalConstrained) {
            score *= 0.7f
        }
        
        // Penalize low battery
        thermalData?.batteryLevel?.let { level ->
            if (level < 20) {
                score *= 0.8f
            }
        }
        
        // Bonus for cool operation
        if (thermalHeadroom > 30f) {
            score *= 1.1f
        }
        
        return score.coerceIn(0f, 100f)
    }
    
    /**
     * Phát hiện điểm nghẽn chính
     */
    fun detectBottleneck(): String {
        val cpuLoad = cpuClusters.filter { it.isOnline }.averageOrNull { it.loadPercent } ?: 0f
        val gpuLoad = gpuData?.loadPercent ?: 0f
        val ramUsage = memoryData?.ramUsagePercent ?: 0f
        
        return when {
            gpuLoad > 90f && gpuLoad > cpuLoad -> "gpu"
            cpuLoad > 90f && cpuLoad > gpuLoad -> "cpu"
            ramUsage > 90f -> "memory"
            memoryData?.storageType?.contains("eMMC") == true && systemLoadIndex > 7f -> "io"
            else -> "none"
        }
    }
    
    /**
     * Kiểm tra xem snapshot có hợp lệ không
     */
    fun isValid(): Boolean {
        return timestampNs > 0 && totalCpuCores > 0
    }
}

/**
 * Dữ liệu CPU Cluster
 */
data class CPUClusterData(
    val clusterType: String = "", // "prime", "performance", "efficiency"
    val coreIds: List<Int> = emptyList(),
    val freqTable: List<Long> = emptyList(),
    val currentFreq: Long = 0L,
    val minFreq: Long = 0L,
    val maxFreq: Long = 0L,
    val loadPercent: Float = 0f,
    val temperature: Float = 0f,
    val isOnline: Boolean = false,
    val loadHistory: List<Float> = emptyList()
)

/**
 * Dữ liệu GPU
 */
data class GPUData(
    val vendor: String = "",
    val modelName: String = "",
    val freqTable: List<Long> = emptyList(),
    val currentFreq: Long = 0L,
    val loadPercent: Float = 0f,
    val temperature: Float = 0f,
    val pwrlevel: Int = -1,
    val gpuBusyCycles: Long = 0L,
    val totalCycles: Long = 0L
)

/**
 * Dữ liệu Bộ nhớ
 */
data class MemoryData(
    val totalRamKb: Long = 0L,
    val availableRamKb: Long = 0L,
    val usedRamKb: Long = 0L,
    val ramUsagePercent: Float = 0f,
    val zramTotalKb: Long = 0L,
    val zramUsedKb: Long = 0L,
    val zramCompressionRatio: Float = 0f,
    val storageType: String = "",
    val readSpeedMbps: Long = 0L,
    val writeSpeedMbps: Long = 0L,
    val storageTemp: Float = 0f
)

/**
 * Dữ liệu Nhiệt độ & Nguồn
 */
data class ThermalData(
    val sensorTemps: Map<String, Float> = emptyMap(),
    val skinTemp: Float = 0f,
    val batteryTemp: Float = 0f,
    val cpuTempMax: Float = 0f,
    val gpuTemp: Float = 0f,
    val batteryLevel: Int = 0,
    val batteryStatus: Int = 0, // 0=None, 1=Charging, 2=Discharging
    val chargingCurrentMa: Float = 0f,
    val voltageMv: Float = 0f,
    val isThermalThrottling: Boolean = false
)

/**
 * Dữ liệu Màn hình
 */
data class DisplayData(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val refreshRateHz: Int = 0,
    val supportedRefreshRates: List<Int> = emptyList(),
    val touchSamplingRateHz: Float = 0f,
    val touchCount: Int = 0,
    val colorGamut: String = ""
)

/**
 * Dữ liệu Mạng
 */
data class NetworkData(
    val connectionType: String = "",
    val linkSpeedMbps: Int = 0,
    val signalStrengthDbm: Int = 0,
    val latencyMs: Float = 0f,
    val jitterMs: Float = 0f,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val tcpRetransmitRate: Float = 0f
)
