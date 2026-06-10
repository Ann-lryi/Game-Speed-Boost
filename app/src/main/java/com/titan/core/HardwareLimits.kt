package com.titan.core

/**
 * Giới hạn cứng phần cứng để đảm bảo an toàn tuyệt đối.
 */
object HardwareLimits {
    const val MAX_SAFE_TEMP_CPU = 85.0f
    const val MAX_SAFE_TEMP_GPU = 80.0f
    const val MAX_GPU_VOLTAGE_OFFSET = 50
    const val MIN_CPU_FREQ = 800000
    const val MAX_CPU_FREQ_PRIME = 3200000
    
    fun isTempSafe(temp: Float): Boolean = temp < MAX_SAFE_TEMP_CPU
    fun isGpuTempSafe(temp: Float): Boolean = temp < MAX_SAFE_TEMP_GPU
}
