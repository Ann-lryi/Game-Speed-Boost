package com.titan.engine.rules

import com.titan.core.TitanCore
import com.titan.core.TitanError
import com.titan.engine.models.GameProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * TITAN RULE ENGINE - GIAI ĐOẠN 2
 * 
 * Bộ não điều phối: Đọc cấu hình JSON -> Phân tích Metric thời gian thực -> Ra lệnh cho Titan Core.
 * Hỗ trợ cập nhật nóng (Hot-reload) cấu hình từ Cloud mà không cần restart app.
 * 
 * TRIẾT LÝ: "MỌI THỨ LÀ DỮ LIỆU" - Xử lý vector, scalar để điều phối phần cứng mượt mà.
 */
class RuleEngine(private val titanCore: TitanCore) {

    // Trạng thái hiện tại của engine
    private val _engineState = MutableStateFlow<EngineState>(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    // Profile đang hoạt động
    private var activeProfile: GameProfile? = null
    
    // Bộ đệm metric để tính trung bình động (tránh nhiễu)
    // Lưu trữ dưới dạng Vector<Float> để tối ưu xử lý
    private val metricBuffer = mutableMapOf<String, MutableList<Float>>()
    private val BUFFER_SIZE = 10 // Lấy trung bình 10 mẫu gần nhất

    /**
     * Khởi tạo engine với cấu hình mặc định hoặc load từ assets
     */
    fun initialize() {
        titanCore.initialize()
        _engineState.value = EngineState.INITIALIZING
        // Load default safety rules
        applySafetyDefaults()
        _engineState.value = EngineState.READY
    }

    /**
     * Load profile game từ JSON (có thể từ Assets hoặc Network)
     * JSON chứa các vector ngưỡng, hệ số điều chỉnh cho từng loại chip
     */
    fun loadProfile(jsonString: String): Result<GameProfile> {
        return try {
            val jsonObject = JSONObject(jsonString)
            val profile = GameProfile.fromJson(jsonObject)
            activeProfile = profile
            _engineState.value = EngineState.PROFILE_LOADED(profile.gameName)
            Result.success(profile)
        } catch (e: Exception) {
            _engineState.value = EngineState.ERROR(TitanError.PROFILE_PARSE_ERROR(e.message ?: "Unknown"))
            Result.failure(e)
        }
    }

    /**
     * Vòng lặp chính của Rule Engine (Gọi từ Coroutine loop)
     * Phân tích dữ liệu và ra quyết định dựa trên vector metrics
     */
    suspend fun tick(metrics: RealTimeMetrics) {
        if (_engineState.value !is EngineState.ACTIVE) return

        // 1. Buffer metrics để làm mượt dữ liệu (Vector smoothing)
        bufferMetric("fps", metrics.fps)
        bufferMetric("temp", metrics.temperature)
        bufferMetric("gpuLoad", metrics.gpuLoad)
        bufferMetric("cpuLoad", metrics.cpuLoad)

        // 2. Kiểm tra an toàn khẩn cấp (Ưu tiên cao nhất)
        if (checkEmergencyStop(metrics)) {
            triggerEmergencyStop()
            return
        }

        // 3. Áp dụng quy tắc thích nghi (Adaptive Logic)
        activeProfile?.let { profile ->
            val decision = decideAction(metrics, profile)
            executeDecision(decision)
        }
    }

    /**
     * Bộ lọc nhiễu: Tính trung bình động trên vector
     * Giúp loại bỏ các giá trị outlier (nhiễu) từ cảm biến
     */
    private fun bufferMetric(key: String, value: Float) {
        val list = metricBuffer.getOrPut(key) { mutableListOf() }
        list.add(value)
        if (list.size > BUFFER_SIZE) list.removeAt(0)
    }

    private fun getAverage(key: String): Float {
        val list = metricBuffer[key] ?: return 0f
        return list.average().toFloat()
    }

    /**
     * KIỂM TRA KHẨN CẤP (Safety Watchdog nội bộ)
     * Ngắt mọi hoạt động nếu vượt ngưỡng nguy hiểm
     * Sử dụng logic vector: Nếu [temp > threshold] VÀ [gpuLoad > 95%] => Dừng khẩn cấp
     */
    private fun checkEmergencyStop(metrics: RealTimeMetrics): Boolean {
        // Nhiệt độ quá cao (> 85°C) - Ngưỡng an toàn tuyệt đối
        if (metrics.temperature > 85.0f) return true
        
        // FPS tụt quá sâu kèm theo GPU load 100% (Dấu hiệu nghẽn cổ chai nghiêm trọng)
        if (metrics.fps < 15.0f && metrics.gpuLoad > 95.0f) return true
        
        // CPU load 100% trong thời gian dài (nguy cơ quá nhiệt ẩn)
        if (metrics.cpuLoad > 98.0f && getAverage("temp") > 75.0f) return true
        
        return false
    }

    private fun triggerEmergencyStop() {
        titanCore.rollbackChanges() // Rollback thay vì resetToSafeMode (chưa có)
        _engineState.value = EngineState.EMERGENCY_STOP
    }

    /**
     * BỘ NÃO RA QUYẾT ĐỊNH (Decision Maker)
     * Sử dụng logic mờ (Fuzzy Logic đơn giản) kết hợp ngưỡng vector
     * Phân tích mối quan hệ giữa: FPS <-> Nhiệt độ <-> Tải GPU/CPU
     */
    private fun decideAction(metrics: RealTimeMetrics, profile: GameProfile): Action {
        val avgFps = getAverage("fps")
        val avgTemp = getAverage("temp")
        val avgGpuLoad = getAverage("gpuLoad")
        
        // Kịch bản 1: Quá nóng -> Giảm xung ngay (Ưu tiên bảo vệ phần cứng)
        if (avgTemp > profile.maxTemperatureThreshold) {
            val throttleFactor = calculateThrottleFactor(avgTemp, profile.maxTemperatureThreshold)
            return Action.THERMAL_THROTTLE(percentage = throttleFactor)
        }

        // Kịch bản 2: FPS thấp hơn mục tiêu -> Tăng xung
        if (avgFps < profile.targetFps * 0.9f) {
            // Chỉ tăng nếu nhiệt độ còn an toàn (cách ngưỡng 5 độ)
            if (avgTemp < profile.maxTemperatureThreshold - 5.0f) {
                // Tăng xung tỷ lệ nghịch với khoảng cách FPS
                val boostFactor = calculateBoostFactor(avgFps, profile.targetFps)
                return Action.PERFORMANCE_BOOST(percentage = boostFactor)
            }
        }

        // Kịch bản 3: Ổn định -> Tối ưu hiệu suất năng lượng (Efficiency Mode)
        // Nếu FPS đạt mục tiêu và nhiệt độ thấp, giảm nhẹ xung để tiết kiệm pin
        if (avgFps >= profile.targetFps && avgTemp < profile.maxTemperatureThreshold - 10.0f) {
            return Action.OPTIMIZE_EFFICIENCY()
        }

        // Kịch bản 4: GPU Load cao nhưng FPS ổn -> Có thể tăng nhẹ GPU clock
        if (avgGpuLoad > 90.0f && avgFps >= profile.targetFps) {
            return Action.GPU_PRIORITY_BOOST(percentage = 1.05f)
        }

        return Action.MAINTAIN()
    }

    /**
     * Tính toán hệ số giảm xung dựa trên mức độ vượt nhiệt
     * Temp càng cao -> Giảm càng mạnh (Phi tuyến tính)
     */
    private fun calculateThrottleFactor(currentTemp: Float, maxTemp: Float): Float {
        val delta = currentTemp - maxTemp
        // Giảm 10% cho mỗi độ vượt quá, tối đa giảm 50%
        val reduction = (delta * 0.1f).coerceIn(0.1f, 0.5f)
        return 1.0f - reduction
    }

    /**
     * Tính toán hệ số tăng xung dựa trên khoảng cách FPS
     * FPS càng thấp -> Tăng càng mạnh (nhưng có giới hạn)
     */
    private fun calculateBoostFactor(currentFps: Float, targetFps: Float): Float {
        val ratio = currentFps / targetFps
        // Tăng tối đa 20%, giảm dần khi tiến gần mục tiêu
        return 1.0f + ((1.0f - ratio) * 0.3f).coerceIn(0.05f, 0.2f)
    }

    private fun executeDecision(action: Action) {
        when (action) {
            is Action.THERMAL_THROTTLE -> {
                titanCore.writeSysFS("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq", "1500000") // Ví dụ giảm xung
            }
            is Action.PERFORMANCE_BOOST -> {
                titanCore.writeSysFS("/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq", "2400000") // Ví dụ tăng xung
            }
            is Action.GPU_PRIORITY_BOOST -> {
                // Tăng ưu tiên GPU (giả lập)
                titanCore.writeSysFS("/sys/class/kgsl/kgsl-3d0/pwrlevel", "0")
            }
            is Action.OPTIMIZE_EFFICIENCY -> {
                // Tối ưu hiệu suất năng lượng
                titanCore.writeSysFS("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor", "schedutil")
            }
            is Action.MAINTAIN -> {
                // Không làm gì cả, giữ nguyên trạng thái
            }
        }
    }

    private fun applySafetyDefaults() {
        // Thiết lập các giới hạn cứng mặc định cho mọi thiết bị
        // Giả lập bằng cách ghi các giá trị an toàn
        titanCore.writeSysFS("/sys/class/thermal/thermal_zone0/mode", "enabled")
    }

    fun startGameLoop() {
        _engineState.value = EngineState.ACTIVE
        metricBuffer.clear() // Reset buffer khi bắt đầu game mới
    }

    fun stopGameLoop() {
        titanCore.rollbackChanges()
        _engineState.value = EngineState.READY
        metricBuffer.clear()
    }

    // --- STATES & MODELS ---

    sealed class EngineState {
        object IDLE : EngineState()
        object INITIALIZING : EngineState()
        data class PROFILE_LOADED(val name: String) : EngineState()
        object READY : EngineState()
        object ACTIVE : EngineState()
        object EMERGENCY_STOP : EngineState()
        data class ERROR(val error: TitanError) : EngineState()
    }

    /**
     * Vector Metrics: Chứa tất cả dữ liệu thời gian thực
     * Mỗi trường là một scalar trong vector tổng thể
     */
    data class RealTimeMetrics(
        val fps: Float,
        val temperature: Float,
        val gpuLoad: Float,
        val cpuLoad: Float,
        val ramUsage: Long,
        val batteryLevel: Int = 100,
        val touchPressure: Float = 0.0f // Áp lực chạm (nếu có cảm biến)
    )

    sealed class Action {
        data class THERMAL_THROTTLE(val percentage: Float) : Action()
        data class PERFORMANCE_BOOST(val percentage: Float) : Action()
        data class GPU_PRIORITY_BOOST(val percentage: Float) : Action()
        object OPTIMIZE_EFFICIENCY : Action()
        object MAINTAIN : Action()
    }

    enum class ClockMode {
        SAFE,       // Ưu tiên an toàn nhiệt
        AGGRESSIVE, // Ưu tiên hiệu năng tối đa
        BALANCED    // Cân bằng
    }
}
