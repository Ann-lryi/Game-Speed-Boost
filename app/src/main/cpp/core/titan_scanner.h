// TITAN VECTOR CORE - Native C++ Implementation
// Mục tiêu: Quét, phân tích và điều phối dữ liệu phần cứng ở cấp độ vector/scalar
// Tác giả: Titan Core Team
// License: Proprietary

#ifndef TITAN_SCANNER_H
#define TITAN_SCANNER_H

#include <string>
#include <vector>
#include <map>
#include <cstdint>
#include <memory>
#include <functional>

namespace titan {

// ============================================================================
// DATA STRUCTURES - Biểu diễn phần cứng dưới dạng Vector/Scalar
// ============================================================================

/**
 * Cấu trúc lưu trữ thông số CPU Core
 * Mỗi core được biểu diễn bằng vector các trạng thái: [freq_min, freq_max, freq_current, temp, load]
 */
struct CPUCoreData {
    int core_id;
    std::string cluster_type; // "prime", "performance", "efficiency"
    std::vector<uint64_t> freq_table; // Bảng tần số khả dụng
    uint64_t current_freq;
    uint64_t min_freq;
    uint64_t max_freq;
    float load_percent; // 0.0 - 100.0
    float temperature; // Celsius
    bool is_online;
    
    // Vector trạng thái thời gian thực để phân tích xu hướng
    std::vector<float> load_history; // Last 10 samples
    std::vector<uint64_t> freq_history;
};

/**
 * Cấu trúc GPU Data - Adreno/Mali đều được chuẩn hóa
 */
struct GPUData {
    std::string vendor; // "adreno", "mali", "powervr", "xclipse"
    std::string model_name;
    std::vector<uint64_t> freq_table;
    uint64_t current_freq;
    float load_percent;
    float temperature;
    uint64_t gpu_busy_cycles;
    uint64_t total_cycles;
    
    // Adreno-specific
    int pwrlevel; // 0-7 (0 = max perf)
    
    // Mali-specific
    int job_slot_priority;
};

/**
 * Memory & Storage Data
 */
struct MemoryData {
    uint64_t total_ram_kb;
    uint64_t available_ram_kb;
    uint64_t used_ram_kb;
    float ram_usage_percent;
    
    // ZRAM/Swap
    uint64_t zram_total_kb;
    uint64_t zram_used_kb;
    float zram_compression_ratio;
    
    // UFS Storage
    std::string storage_type; // "UFS 3.1", "UFS 4.0", "eMMC"
    uint64_t read_speed_mbps;
    uint64_t write_speed_mbps;
    float storage_temp;
    
    // I/O Stats
    uint64_t io_read_sectors;
    uint64_t io_write_sectors;
};

/**
 * Thermal & Power Data
 */
struct ThermalData {
    std::map<std::string, float> sensor_temps; // name -> temperature
    float skin_temp;
    float battery_temp;
    float cpu_temp_max;
    float gpu_temp;
    
    // Power
    int battery_level;
    int battery_status; // Charging/Discharging
    float charging_current_ma;
    float voltage_mv;
    
    // Thermal throttling state
    bool is_thermal_throttling;
    int thermal_zone_id;
};

/**
 * Display & Touch Data
 */
struct DisplayData {
    int width_px;
    int height_px;
    int refresh_rate_hz;
    int supported_refresh_rates[8]; // Array các refresh rate hỗ trợ
    float touch_sampling_rate_hz;
    int touch_pressure;
    int touch_count; // Số điểm chạm đồng thời
    
    // Color gamut
    float color_temperature;
    std::string color_gamut; // "sRGB", "DCI-P3", "Rec.2020"
};

/**
 * Network Data
 */
struct NetworkData {
    std::string connection_type; // "WiFi", "5G", "4G", "Ethernet"
    int link_speed_mbps;
    int signal_strength_dbm;
    float latency_ms;
    float jitter_ms;
    uint64_t tx_bytes;
    uint64_t rx_bytes;
    uint64_t tx_packets;
    uint64_t rx_packets;
    
    // TCP buffer stats
    uint64_t tcp_retransmits;
    float tcp_retransmit_rate;
};

/**
 * Tổng hợp tất cả dữ liệu phần cứng - "Vector Trạng Thái Hệ Thống"
 */
struct HardwareSnapshot {
    uint64_t timestamp_ns; // Nanosecond precision
    std::string device_model;
    std::string android_version;
    std::string kernel_version;
    std::string soc_vendor; // "qualcomm", "mediatek", "samsung", "google"
    std::string soc_model; // "sm8650", "dimensity9300", etc.
    
    CPUCoreData cpu_clusters[3]; // [efficiency, performance, prime]
    int total_cpu_cores;
    
    GPUData gpu;
    MemoryData memory;
    ThermalData thermal;
    DisplayData display;
    NetworkData network;
    
    // Derived metrics - Các chỉ số tính toán từ dữ liệu thô
    float system_load_index; // 0.0 - 10.0 (tổng hợp CPU+GPU+Thermal)
    float thermal_headroom; // Khoảng cách trước khi throttling
    float performance_potential; // % hiệu năng còn khả dụng
    bool is_thermal_constrained;
    bool is_power_constrained;
    
    // Quality flags
    bool data_quality_high;
    std::vector<std::string> warnings;
};

// ============================================================================
// HARDWARE SCANNER CLASS - Quét và chuẩn hóa dữ liệu
// ============================================================================

class HardwareScanner {
public:
    HardwareScanner();
    ~HardwareScanner();
    
    /**
     * Khởi tạo scanner, phát hiện capabilities của thiết bị
     * @return true nếu thành công
     */
    bool initialize();
    
    /**
     * Quét toàn bộ hệ thống và trả về snapshot
     * @param include_history Có bao gồm historical data không
     * @return HardwareSnapshot đã được chuẩn hóa
     */
    HardwareSnapshot scan(bool include_history = false);
    
    /**
     * Quét nhanh chỉ các chỉ số quan trọng (FPS-critical)
     * Tối ưu cho việc gọi lặp lại 60-120 lần/giây
     */
    HardwareSnapshot scanFast();
    
    /**
     * Lấy thông tin SOC chi tiết
     */
    std::string getSOCVendor() const { return soc_vendor_; }
    std::string getSOCModel() const { return soc_model_; }
    bool hasAdrenoGPU() const { return gpu_vendor_ == "adreno"; }
    bool hasMaliGPU() const { return gpu_vendor_ == "mali"; }
    
    /**
     * Kiểm tra quyền truy cập sysfs cần thiết
     */
    bool checkPermissions(std::vector<std::string>& missing_permissions);
    
private:
    // Private methods for scanning individual components
    void scanCPU(CPUCoreData* clusters, int& total_cores);
    void scanGPU(GPUData& gpu);
    void scanMemory(MemoryData& mem);
    void scanThermal(ThermalData& thermal);
    void scanDisplay(DisplayData& display);
    void scanNetwork(NetworkData& net);
    
    // Helper functions
    uint64_t readSysFSUint64(const std::string& path);
    float readSysFSFloat(const std::string& path);
    std::string readSysFSString(const std::string& path);
    bool fileExists(const std::string& path);
    std::vector<std::string> splitString(const std::string& str, char delimiter);
    uint64_t getCurrentTimeNanos();
    
    // Device identification
    void identifySOC();
    void detectGPUVendor();
    
    // Cache for fast scanning
    bool initialized_;
    std::string soc_vendor_;
    std::string soc_model_;
    std::string gpu_vendor_;
    std::string device_model_;
    
    // Paths cache (tránh lookup nhiều lần)
    std::map<std::string, std::string> sysfs_paths_;
    std::map<std::string, std::string> proc_paths_;
};

// ============================================================================
// VECTOR ANALYZER - Phân tích xu hướng và dự đoán
// ============================================================================

class VectorAnalyzer {
public:
    /**
     * Phân tích xu hướng tải CPU/GPU từ historical data
     * @return Predicted load trong 100ms tới (0.0 - 1.0)
     */
    static float predictLoad(const std::vector<float>& history, int lookahead_ms = 100);
    
    /**
     * Tính toán hệ số tương quan giữa nhiệt độ và hiệu năng
     * Giúp dự đoán thermal throttling
     */
    static float calculateThermalCorrelation(const HardwareSnapshot& current, 
                                            const HardwareSnapshot& previous);
    
    /**
     * Phát hiện bottleneck (CPU-bound, GPU-bound, Memory-bound, IO-bound)
     * @return "cpu", "gpu", "memory", "io", hoặc "none"
     */
    static std::string detectBottleneck(const HardwareSnapshot& snapshot);
    
    /**
     * Tính chỉ số hiệu năng tổng hợp (0-100)
     */
    static float calculatePerformanceScore(const HardwareSnapshot& snapshot);
    
    /**
     * Dự đoán thời điểm thermal throttling sẽ xảy ra
     * @return milliseconds until throttling, hoặc -1 nếu không dự đoán được
     */
    static int64_t predictThermalThrottle(const HardwareSnapshot& current,
                                         const HardwareSnapshot& previous);
};

// ============================================================================
// SAFE EXECUTOR - Thực thi lệnh với safety checks
// ============================================================================

class SafeExecutor {
public:
    /**
     * Thực thi lệnh shell với validation và rollback capability
     * @param command Lệnh cần thực thi
     * @param dry_run Nếu true, chỉ validate không thực thi
     * @return true nếu thành công
     */
    static bool executeWithSafety(const std::string& command, bool dry_run = false);
    
    /**
     * Ghi giá trị vào sysfs với range checking
     */
    static bool writeSysFSSafe(const std::string& path, const std::string& value,
                              const std::string& min_value = "", 
                              const std::string& max_value = "");
    
    /**
     * Rollback thay đổi gần đây
     */
    static void rollbackLastChanges();
    
private:
    struct ChangeRecord {
        std::string path;
        std::string original_value;
        std::string new_value;
        uint64_t timestamp;
    };
    
    static std::vector<ChangeRecord> change_history_;
    static const int MAX_HISTORY_SIZE = 100;
};

} // namespace titan

#endif // TITAN_SCANNER_H
