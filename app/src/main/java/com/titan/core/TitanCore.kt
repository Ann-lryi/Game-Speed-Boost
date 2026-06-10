package com.titan.core

import android.util.Log

/**
 * TITAN CORE - Kotlin Interface
 * Lớp điều khiển chính cho Titan Engine, kết nối với Native C++ qua JNI
 */
class TitanCore {
    
    companion object {
        private const val TAG = "TitanCore"
        
        // Load native library
        init {
            try {
                System.loadLibrary("titan_core")
                Log.i(TAG, "Titan Core native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load Titan Core native library", e)
            }
        }
    }
    
    // Native methods declaration
    external fun nativeInitialize(): Boolean
    external fun nativeRelease()
    external fun nativeScanHardware(): HardwareSnapshot?
    external fun nativeScanFast(): HardwareSnapshot?
    external fun nativeGetSOCVendor(): String
    external fun nativeGetSOCModel(): String
    external fun nativeHasAdrenoGPU(): Boolean
    external fun nativeHasMaliGPU(): Boolean
    external fun nativePredictLoad(history: FloatArray, lookaheadMs: Int): Float
    external fun nativeDetectBottleneck(snapshot: HardwareSnapshot): String
    external fun nativeCalculatePerformanceScore(snapshot: HardwareSnapshot): Float
    external fun nativePredictThermalThrottle(current: HardwareSnapshot, previous: HardwareSnapshot): Long
    external fun nativeExecuteWithSafety(command: String, dryRun: Boolean): Boolean
    external fun nativeWriteSysFSSafe(path: String, value: String, minValue: String, maxValue: String): Boolean
    external fun nativeRollbackLastChanges()
    external fun nativeCheckPermissions(): Array<String>?
    
    // High-level API
    
    /**
     * Khởi tạo Titan Core
     * @return true nếu thành công
     */
    fun initialize(): Boolean {
        val success = nativeInitialize()
        if (success) {
            Log.i(TAG, "Titan Core initialized")
        } else {
            Log.e(TAG, "Titan Core initialization failed")
        }
        return success
    }
    
    /**
     * Giải phóng tài nguyên
     */
    fun release() {
        nativeRelease()
        Log.i(TAG, "Titan Core released")
    }
    
    /**
     * Quét toàn bộ phần cứng
     * @param includeHistory Có bao gồm dữ liệu lịch sử không
     * @return HardwareSnapshot hoặc null nếu thất bại
     */
    fun scanHardware(includeHistory: Boolean = false): HardwareSnapshot? {
        return nativeScanHardware()
    }
    
    /**
     * Quét nhanh các chỉ số quan trọng (60-120 Hz)
     * @return HardwareSnapshot rút gọn
     */
    fun scanFast(): HardwareSnapshot? {
        return nativeScanFast()
    }
    
    /**
     * Lấy thông tin nhà sản xuất SOC
     */
    fun getSOCInfo(): SOCInfo {
        val vendor = nativeGetSOCVendor()
        val model = nativeGetSOCModel()
        val isAdreno = nativeHasAdrenoGPU()
        val isMali = nativeHasMaliGPU()
        
        return SOCInfo(vendor, model, isAdreno, isMali)
    }
    
    /**
     * Dự đoán tải hệ thống
     * @param history Lịch sử tải (tối thiểu 2 samples)
     * @param lookaheadMs Thời gian dự đoán trước (ms)
     * @return Tải dự đoán (0.0 - 100.0)
     */
    fun predictLoad(history: FloatArray, lookaheadMs: Int = 100): Float {
        if (history.size < 2) {
            Log.w(TAG, "PredictLoad requires at least 2 history samples")
            return 50.0f
        }
        return nativePredictLoad(history, lookaheadMs)
    }
    
    /**
     * Phát hiện điểm nghẽn hiệu năng
     * @return "cpu", "gpu", "memory", "io", hoặc "none"
     */
    fun detectBottleneck(snapshot: HardwareSnapshot): String {
        return nativeDetectBottleneck(snapshot)
    }
    
    /**
     * Tính điểm hiệu năng tổng hợp (0-100)
     */
    fun calculatePerformanceScore(snapshot: HardwareSnapshot): Float {
        return nativeCalculatePerformanceScore(snapshot)
    }
    
    /**
     * Dự đoán thời gian trước khi thermal throttling
     * @return milliseconds, hoặc -1 nếu không dự đoán được
     */
    fun predictThermalThrottle(current: HardwareSnapshot, previous: HardwareSnapshot): Long {
        return nativePredictThermalThrottle(current, previous)
    }
    
    /**
     * Thực thi lệnh shell an toàn
     * @param command Lệnh cần thực thi
     * @param dryRun Nếu true, chỉ validate không thực thi
     * @return true nếu thành công
     */
    fun executeCommand(command: String, dryRun: Boolean = false): Boolean {
        Log.d(TAG, "Executing command: $command (dryRun=$dryRun)")
        return nativeExecuteWithSafety(command, dryRun)
    }
    
    /**
     * Ghi giá trị vào sysfs an toàn với range checking
     */
    fun writeSysFS(path: String, value: String, 
                   minValue: String = "", maxValue: String = ""): Boolean {
        Log.d(TAG, "Writing to $path: $value")
        return nativeWriteSysFSSafe(path, value, minValue, maxValue)
    }
    
    /**
     * Rollback tất cả thay đổi gần đây
     */
    fun rollbackChanges() {
        Log.w(TAG, "Rolling back all changes")
        nativeRollbackLastChanges()
    }
    
    /**
     * Kiểm tra quyền truy cập cần thiết
     * @return Danh sách quyền thiếu, hoặc null nếu đủ
     */
    fun checkPermissions(): List<String> {
        val missing = nativeCheckPermissions()
        return missing?.toList() ?: emptyList()
    }
    
    /**
     * Tối ưu hóa cho gaming - Tự động áp dụng cấu hình tối ưu
     * @param gamePackageName Package name của game
     * @param performanceMode Chế độ hiệu năng: "balanced", "performance", "extreme"
     * @return true nếu thành công
     */
    fun optimizeForGaming(gamePackageName: String, performanceMode: String = "balanced"): Boolean {
        Log.i(TAG, "Optimizing for game: $gamePackageName, mode: $performanceMode")
        
        // Scan hardware first
        val snapshot = scanHardware() ?: return false
        
        // Detect bottleneck
        val bottleneck = detectBottleneck(snapshot)
        Log.d(TAG, "Detected bottleneck: $bottleneck")
        
        // Apply optimizations based on SOC and bottleneck
        val socInfo = getSOCInfo()
        
        when {
            socInfo.isAdreno -> {
                // Adreno GPU optimizations
                if (!applyAdrenoOptimizations(performanceMode)) {
                    return false
                }
            }
            socInfo.isMali -> {
                // Mali GPU optimizations
                if (!applyMaliOptimizations(performanceMode)) {
                    return false
                }
            }
        }
        
        // CPU optimizations based on cluster
        if (!applyCPUOptimizations(performanceMode, snapshot)) {
            return false
        }
        
        // Memory optimizations
        applyMemoryOptimizations(performanceMode)
        
        Log.i(TAG, "Gaming optimization completed successfully")
        return true
    }
    
    // Private helper methods
    
    private fun applyAdrenoOptimizations(mode: String): Boolean {
        val pwrlevel = when (mode) {
            "extreme" -> "0"  // Max performance
            "performance" -> "2"
            "balanced" -> "4"
            else -> "4"
        }
        
        // Set GPU power level
        val success = writeSysFS(
            "/sys/class/kgsl/kgsl-3d0/pwrlevel",
            pwrlevel,
            "0",
            "7"
        )
        
        if (!success) {
            Log.w(TAG, "Failed to set Adreno pwrlevel")
        }
        
        return success
    }
    
    private fun applyMaliOptimizations(mode: String): Boolean {
        // Mali-specific optimizations
        // Implementation depends on specific Mali driver
        Log.d(TAG, "Applying Mali optimizations for mode: $mode")
        return true
    }
    
    private fun applyCPUOptimizations(mode: String, snapshot: HardwareSnapshot): Boolean {
        // Adjust CPU governor based on mode
        val governor = when (mode) {
            "extreme", "performance" -> "performance"
            "balanced" -> "schedutil"
            else -> "schedutil"
        }
        
        // Apply to all online cores
        for (i in 0 until snapshot.totalCpuCores) {
            val path = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor"
            writeSysFS(path, governor)
        }
        
        return true
    }
    
    private fun applyMemoryOptimizations(mode: String) {
        // Adjust LMK (Low Memory Killer) parameters
        // Adjust ZRAM compression
        Log.d(TAG, "Applying memory optimizations for mode: $mode")
    }
}

/**
 * Data class chứa thông tin SOC
 */
data class SOCInfo(
    val vendor: String,
    val model: String,
    val isAdreno: Boolean,
    val isMali: Boolean
) {
    val gpuType: String
        get() = when {
            isAdreno -> "Adreno"
            isMali -> "Mali"
            else -> "Unknown"
        }
}
