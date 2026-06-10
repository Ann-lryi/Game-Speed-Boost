// TITAN SCANNER IMPLEMENTATION
// Implementation chi tiết cho việc quét phần cứng Android

#include "titan_scanner.h"
#include <fstream>
#include <sstream>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <unistd.h>
#include <sys/system_properties.h>

namespace titan {

// Static member initialization
std::vector<SafeExecutor::ChangeRecord> SafeExecutor::change_history_;

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

bool fileExists(const std::string& path) {
    return access(path.c_str(), F_OK) == 0;
}

std::string readSysFSString(const std::string& path) {
    std::ifstream file(path);
    if (!file.is_open()) return "";
    
    std::string content;
    std::getline(file, content);
    
    // Remove trailing newline
    if (!content.empty() && content.back() == '\n') {
        content.pop_back();
    }
    
    return content;
}

uint64_t readSysFSUint64(const std::string& path) {
    std::string content = readSysFSString(path);
    if (content.empty()) return 0;
    
    try {
        return std::stoull(content);
    } catch (...) {
        return 0;
    }
}

float readSysFSFloat(const std::string& path) {
    std::string content = readSysFSString(path);
    if (content.empty()) return 0.0f;
    
    try {
        return std::stof(content);
    } catch (...) {
        return 0.0f;
    }
}

std::vector<std::string> splitString(const std::string& str, char delimiter) {
    std::vector<std::string> tokens;
    std::stringstream ss(str);
    std::string token;
    
    while (std::getline(ss, token, delimiter)) {
        if (!token.empty()) {
            tokens.push_back(token);
        }
    }
    
    return tokens;
}

uint64_t getCurrentTimeNanos() {
    auto now = std::chrono::high_resolution_clock::now();
    auto duration = now.time_since_epoch();
    return std::chrono::duration_cast<std::chrono::nanoseconds>(duration).count();
}

// ============================================================================
// HARDWARE SCANNER IMPLEMENTATION
// ============================================================================

HardwareScanner::HardwareScanner() 
    : initialized_(false) {
}

HardwareScanner::~HardwareScanner() {
}

bool HardwareScanner::initialize() {
    if (initialized_) return true;
    
    // Identify SOC and GPU
    identifySOC();
    detectGPUVendor();
    
    // Cache sysfs paths for faster access
    // CPU frequency paths
    for (int i = 0; i < 8; i++) {
        std::string cpu_path = "/sys/devices/system/cpu/cpu" + std::to_string(i);
        if (fileExists(cpu_path)) {
            proc_paths_["cpu" + std::to_string(i) + "_load"] = 
                cpu_path + "/cpufreq/stats/time_in_state";
            sysfs_paths_["cpu" + std::to_string(i) + "_freq"] = 
                cpu_path + "/cpufreq/scaling_cur_freq";
            sysfs_paths_["cpu" + std::to_string(i) + "_max_freq"] = 
                cpu_path + "/cpufreq/scaling_max_freq";
            sysfs_paths_["cpu" + std::to_string(i) + "_min_freq"] = 
                cpu_path + "/cpufreq/scaling_min_freq";
        }
    }
    
    // GPU paths (Adreno)
    if (gpu_vendor_ == "adreno") {
        sysfs_paths_["gpu_freq"] = "/sys/class/kgsl/kgsl-3d0/gpuclk";
        sysfs_paths_["gpu_max_freq"] = "/sys/class/kgsl/kgsl-3d0/max_gpuclk";
        sysfs_paths_["gpu_pwrlevel"] = "/sys/class/kgsl/kgsl-3d0/pwrlevel";
        sysfs_paths_["gpu_busy"] = "/sys/class/kgsl/kgsl-3d0/gpubusy";
    }
    // GPU paths (Mali)
    else if (gpu_vendor_ == "mali") {
        sysfs_paths_["gpu_freq"] = "/sys/devices/platform/mali-devfreq/devfreq/mali-devfreq/cur_freq";
        sysfs_paths_["gpu_load"] = "/sys/devices/platform/mali-devfreq/devfreq/mali-devfreq/load";
    }
    
    // Thermal paths
    sysfs_paths_["thermal_zones"] = "/sys/class/thermal/";
    
    // Memory paths
    proc_paths_["meminfo"] = "/proc/meminfo";
    proc_paths_["vmstat"] = "/proc/vmstat";
    
    initialized_ = true;
    return true;
}

void HardwareScanner::identifySOC() {
    // Read from /proc/cpuinfo or system properties
    std::string cpuinfo = readSysFSString("/proc/cpuinfo");
    
    // Detect vendor from cpuinfo
    if (cpuinfo.find("Qualcomm") != std::string::npos || 
        cpuinfo.find("Snapdragon") != std::string::npos) {
        soc_vendor_ = "qualcomm";
    } else if (cpuinfo.find("MediaTek") != std::string::npos ||
               cpuinfo.find("MT") != std::string::npos) {
        soc_vendor_ = "mediatek";
    } else if (cpuinfo.find("Samsung") != std::string::npos ||
               cpuinfo.find("Exynos") != std::string::npos) {
        soc_vendor_ = "samsung";
    } else if (cpuinfo.find("Google") != std::string::npos ||
               cpuinfo.find("Tensor") != std::string::npos) {
        soc_vendor_ = "google";
    } else {
        soc_vendor_ = "unknown";
    }
    
    // Get model from system property
    char prop_value[PROP_VALUE_MAX];
    if (__system_property_get("ro.product.model", prop_value) > 0) {
        device_model_ = prop_value;
    }
    
    if (__system_property_get("ro.board.platform", prop_value) > 0) {
        soc_model_ = prop_value;
    } else if (__system_property_get("ro.hardware", prop_value) > 0) {
        soc_model_ = prop_value;
    }
}

void HardwareScanner::detectGPUVendor() {
    // Check for Adreno
    if (fileExists("/sys/class/kgsl/kgsl-3d0")) {
        gpu_vendor_ = "adreno";
        return;
    }
    
    // Check for Mali
    if (fileExists("/sys/devices/platform/mali-devfreq") ||
        fileExists("/sys/class/misc/mali0")) {
        gpu_vendor_ = "mali";
        return;
    }
    
    // Check for PowerVR
    if (fileExists("/sys/class/misc/pvrsrvkm")) {
        gpu_vendor_ = "powervr";
        return;
    }
    
    gpu_vendor_ = "unknown";
}

void HardwareScanner::scanCPU(CPUCoreData* clusters, int& total_cores) {
    total_cores = 0;
    
    // Initialize clusters
    for (int i = 0; i < 3; i++) {
        clusters[i].core_id = -1;
        clusters[i].is_online = false;
        clusters[i].load_percent = 0.0f;
        clusters[i].temperature = 0.0f;
    }
    
    // Scan each CPU core
    int efficiency_idx = 0, performance_idx = 0, prime_idx = 0;
    
    for (int cpu_id = 0; cpu_id < 8; cpu_id++) {
        std::string cpu_path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu_id);
        
        if (!fileExists(cpu_path)) continue;
        
        total_cores++;
        
        // Check if online
        std::string online = readSysFSString(cpu_path + "/online");
        bool is_online = (online != "0");
        
        if (!is_online) continue;
        
        // Get frequency info
        uint64_t cur_freq = readSysFSUint64(cpu_path + "/cpufreq/scaling_cur_freq");
        uint64_t max_freq = readSysFSUint64(cpu_path + "/cpufreq/scaling_max_freq");
        uint64_t min_freq = readSysFSUint64(cpu_path + "/cpufreq/scaling_min_freq");
        
        // Read frequency table
        std::string freq_table_str = readSysFSString(cpu_path + "/cpufreq/scaling_available_frequencies");
        std::vector<std::string> freqs = splitString(freq_table_str, ' ');
        std::vector<uint64_t> freq_table;
        for (const auto& f : freqs) {
            try {
                freq_table.push_back(std::stoull(f));
            } catch (...) {}
        }
        
        // Calculate load from stat file
        float load = 0.0f;
        std::string stat_path = cpu_path + "/cpufreq/stats/time_in_state";
        if (fileExists(stat_path)) {
            // Parse time_in_state to calculate load
            // Simplified: in real implementation, parse all frequencies and times
            load = 50.0f; // Placeholder
        }
        
        // Determine cluster type based on max frequency
        std::string cluster_type;
        int cluster_idx;
        
        if (max_freq >= 2800000) {
            cluster_type = "prime";
            cluster_idx = prime_idx++;
        } else if (max_freq >= 2000000) {
            cluster_type = "performance";
            cluster_idx = performance_idx++;
        } else {
            cluster_type = "efficiency";
            cluster_idx = efficiency_idx++;
        }
        
        // Update cluster data
        if (cluster_idx < 3) {
            clusters[cluster_idx].core_id = cpu_id;
            clusters[cluster_idx].cluster_type = cluster_type;
            clusters[cluster_idx].freq_table = freq_table;
            clusters[cluster_idx].current_freq = cur_freq;
            clusters[cluster_idx].max_freq = max_freq;
            clusters[cluster_idx].min_freq = min_freq;
            clusters[cluster_idx].load_percent = load;
            clusters[cluster_idx].is_online = true;
            
            // Add to history
            if (clusters[cluster_idx].load_history.size() >= 10) {
                clusters[cluster_idx].load_history.erase(clusters[cluster_idx].load_history.begin());
            }
            clusters[cluster_idx].load_history.push_back(load);
        }
    }
}

void HardwareScanner::scanGPU(GPUData& gpu) {
    gpu.vendor = gpu_vendor_;
    gpu.load_percent = 0.0f;
    gpu.temperature = 0.0f;
    gpu.current_freq = 0;
    gpu.pwrlevel = -1;
    gpu.job_slot_priority = 0;
    
    if (gpu_vendor_ == "adreno") {
        // Read Adreno GPU stats
        gpu.current_freq = readSysFSUint64(sysfs_paths_["gpu_freq"]);
        
        std::string max_freq_str = readSysFSString(sysfs_paths_["gpu_max_freq"]);
        if (!max_freq_str.empty()) {
            try {
                gpu.freq_table.push_back(std::stoull(max_freq_str));
            } catch (...) {}
        }
        
        // Read pwrlevel
        std::string pwr_str = readSysFSString(sysfs_paths_["gpu_pwrlevel"]);
        if (!pwr_str.empty()) {
            try {
                gpu.pwrlevel = std::stoi(pwr_str);
            } catch (...) {}
        }
        
        // Calculate GPU load from busy cycles
        std::string busy_str = readSysFSString(sysfs_paths_["gpu_busy"]);
        if (!busy_str.empty()) {
            auto parts = splitString(busy_str, ' ');
            if (parts.size() >= 2) {
                try {
                    gpu.gpu_busy_cycles = std::stoull(parts[0]);
                    gpu.total_cycles = std::stoull(parts[1]);
                    if (gpu.total_cycles > 0) {
                        gpu.load_percent = (float)(gpu.gpu_busy_cycles * 100) / gpu.total_cycles;
                    }
                } catch (...) {}
            }
        }
    }
    else if (gpu_vendor_ == "mali") {
        // Read Mali GPU stats
        gpu.current_freq = readSysFSUint64(sysfs_paths_["gpu_freq"]);
        
        std::string load_str = readSysFSString(sysfs_paths_["gpu_load"]);
        if (!load_str.empty()) {
            try {
                gpu.load_percent = std::stof(load_str);
            } catch (...) {}
        }
    }
}

void HardwareScanner::scanMemory(MemoryData& mem) {
    // Read /proc/meminfo
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) return;
    
    std::string line;
    uint64_t mem_total = 0, mem_free = 0, mem_available = 0;
    uint64_t buffers = 0, cached = 0;
    
    while (std::getline(meminfo, line)) {
        std::istringstream iss(line);
        std::string key;
        uint64_t value;
        std::string unit;
        
        iss >> key >> value >> unit;
        
        if (key == "MemTotal:") mem_total = value;
        else if (key == "MemFree:") mem_free = value;
        else if (key == "MemAvailable:") mem_available = value;
        else if (key == "Buffers:") buffers = value;
        else if (key == "Cached:") cached = value;
        else if (key == "ZramTotal:") mem.zram_total_kb = value;
        else if (key == "ZramUsed:") mem.zram_used_kb = value;
    }
    
    mem.total_ram_kb = mem_total;
    mem.available_ram_kb = mem_available;
    mem.used_ram_kb = mem_total - mem_available;
    mem.ram_usage_percent = (mem_total > 0) ? 
        ((float)(mem_total - mem_available) * 100.0f / mem_total) : 0.0f;
    
    // Calculate ZRAM compression ratio
    if (mem.zram_total_kb > 0) {
        mem.zram_compression_ratio = (float)mem.zram_used_kb / mem.zram_total_kb;
    }
    
    // Detect storage type
    if (fileExists("/sys/class/mmc_host/mmc0")) {
        mem.storage_type = "eMMC";
    } else if (fileExists("/sys/class/ufs")) {
        // Try to detect UFS version
        std::string ufs_version = readSysFSString("/sys/class/ufs/ufs0/device_descriptor/spec_version");
        if (ufs_version.find("0301") != std::string::npos) {
            mem.storage_type = "UFS 3.1";
        } else if (ufs_version.find("0400") != std::string::npos) {
            mem.storage_type = "UFS 4.0";
        } else {
            mem.storage_type = "UFS";
        }
    }
}

void HardwareScanner::scanThermal(ThermalData& thermal) {
    thermal.cpu_temp_max = 0.0f;
    thermal.gpu_temp = 0.0f;
    thermal.battery_temp = 0.0f;
    thermal.skin_temp = 0.0f;
    thermal.is_thermal_throttling = false;
    
    // Scan thermal zones
    for (int i = 0; i < 20; i++) {
        std::string zone_path = "/sys/class/thermal/thermal_zone" + std::to_string(i);
        
        if (!fileExists(zone_path)) continue;
        
        std::string type = readSysFSString(zone_path + "/type");
        std::string temp_str = readSysFSString(zone_path + "/temp");
        
        if (temp_str.empty()) continue;
        
        float temp = 0.0f;
        try {
            temp = std::stof(temp_str) / 1000.0f; // Convert from millidegrees
        } catch (...) {
            continue;
        }
        
        thermal.sensor_temps[type] = temp;
        
        // Categorize temperatures
        if (type.find("cpu") != std::string::npos || 
            type.find("cluster") != std::string::npos) {
            thermal.cpu_temp_max = std::max(thermal.cpu_temp_max, temp);
        }
        if (type.find("gpu") != std::string::npos) {
            thermal.gpu_temp = temp;
        }
        if (type.find("battery") != std::string::npos || 
            type.find("batt") != std::string::npos) {
            thermal.battery_temp = temp;
        }
        if (type.find("skin") != std::string::npos ||
            type.find("thermoskin") != std::string::npos) {
            thermal.skin_temp = temp;
        }
        
        // Check for throttling
        std::string policy = readSysFSString(zone_path + "/policy");
        if (policy.find("user_space") == std::string::npos) {
            std::string trip_mode = readSysFSString(zone_path + "/trip_point_0_mode");
            if (!trip_mode.empty()) {
                thermal.is_thermal_throttling = true;
            }
        }
    }
    
    // Read battery status
    std::string capacity = readSysFSString("/sys/class/power_supply/battery/capacity");
    if (!capacity.empty()) {
        try {
            thermal.battery_level = std::stoi(capacity);
        } catch (...) {}
    }
    
    std::string status = readSysFSString("/sys/class/power_supply/battery/status");
    if (status == "Charging") {
        thermal.battery_status = 1;
    } else if (status == "Discharging") {
        thermal.battery_status = 2;
    } else {
        thermal.battery_status = 0;
    }
}

void HardwareScanner::scanDisplay(DisplayData& display) {
    // Read display info from SurfaceFlinger or sysfs
    display.width_px = 1080; // Default
    display.height_px = 2400;
    display.refresh_rate_hz = 60;
    display.touch_sampling_rate_hz = 120.0f;
    display.touch_count = 0;
    display.touch_pressure = 0;
    
    // Try to get actual values from dumpsys
    // In real implementation, use JNI to call Android APIs
    
    // Supported refresh rates (common values)
    display.supported_refresh_rates[0] = 60;
    display.supported_refresh_rates[1] = 90;
    display.supported_refresh_rates[2] = 120;
    display.supported_refresh_rates[3] = 144;
    display.supported_refresh_rates[4] = 0;
    
    display.color_gamut = "DCI-P3";
    display.color_temperature = 6500.0f;
}

void HardwareScanner::scanNetwork(NetworkData& net) {
    net.connection_type = "WiFi";
    net.link_speed_mbps = 0;
    net.signal_strength_dbm = 0;
    net.latency_ms = 0.0f;
    net.jitter_ms = 0.0f;
    
    // Read from /proc/net or network interfaces
    // Simplified implementation
}

HardwareSnapshot HardwareScanner::scan(bool include_history) {
    HardwareSnapshot snapshot;
    snapshot.timestamp_ns = getCurrentTimeNanos();
    snapshot.device_model = device_model_;
    snapshot.soc_vendor = soc_vendor_;
    snapshot.soc_model = soc_model_;
    
    // Get Android version
    char prop_value[PROP_VALUE_MAX];
    if (__system_property_get("ro.build.version.release", prop_value) > 0) {
        snapshot.android_version = prop_value;
    }
    
    if (__system_property_get("ro.build.version.kernel", prop_value) > 0) {
        snapshot.kernel_version = prop_value;
    }
    
    // Scan all components
    scanCPU(snapshot.cpu_clusters, snapshot.total_cpu_cores);
    scanGPU(snapshot.gpu);
    scanMemory(snapshot.memory);
    scanThermal(snapshot.thermal);
    scanDisplay(snapshot.display);
    scanNetwork(snapshot.network);
    
    // Calculate derived metrics
    float cpu_load_avg = 0.0f;
    int active_cores = 0;
    for (int i = 0; i < 3; i++) {
        if (snapshot.cpu_clusters[i].is_online) {
            cpu_load_avg += snapshot.cpu_clusters[i].load_percent;
            active_cores++;
        }
    }
    if (active_cores > 0) cpu_load_avg /= active_cores;
    
    snapshot.system_load_index = (cpu_load_avg + snapshot.gpu.load_percent) / 2.0f;
    
    // Calculate thermal headroom
    float max_safe_temp = 85.0f; // Typical max temp
    float current_max_temp = std::max({snapshot.thermal.cpu_temp_max, 
                                       snapshot.thermal.gpu_temp,
                                       snapshot.thermal.battery_temp});
    snapshot.thermal_headroom = max_safe_temp - current_max_temp;
    
    snapshot.is_thermal_constrained = (snapshot.thermal_headroom < 10.0f);
    snapshot.is_power_constrained = (snapshot.thermal.battery_level < 20);
    
    // Performance potential
    if (snapshot.is_thermal_constrained) {
        snapshot.performance_potential = 0.5f;
    } else if (snapshot.is_power_constrained) {
        snapshot.performance_potential = 0.7f;
    } else {
        snapshot.performance_potential = 1.0f;
    }
    
    snapshot.data_quality_high = true;
    
    return snapshot;
}

HardwareSnapshot HardwareScanner::scanFast() {
    // Optimized scan for high-frequency calls
    // Only reads critical metrics: CPU/GPU freq, load, temp
    HardwareSnapshot snapshot;
    snapshot.timestamp_ns = getCurrentTimeNanos();
    
    // Quick CPU scan (only first core of each cluster)
    for (int i = 0; i < 3; i++) {
        if (snapshot.cpu_clusters[i].is_online) {
            std::string cpu_path = "/sys/devices/system/cpu/cpu" + 
                                  std::to_string(snapshot.cpu_clusters[i].core_id);
            snapshot.cpu_clusters[i].current_freq = 
                readSysFSUint64(cpu_path + "/cpufreq/scaling_cur_freq");
        }
    }
    
    // Quick GPU scan
    if (gpu_vendor_ == "adreno") {
        snapshot.gpu.current_freq = readSysFSUint64(sysfs_paths_["gpu_freq"]);
    }
    
    // Quick thermal scan
    snapshot.thermal.cpu_temp_max = 0.0f;
    for (int i = 0; i < 5; i++) {
        std::string temp_str = readSysFSString(
            "/sys/class/thermal/thermal_zone" + std::to_string(i) + "/temp");
        if (!temp_str.empty()) {
            try {
                float temp = std::stof(temp_str) / 1000.0f;
                snapshot.thermal.cpu_temp_max = std::max(snapshot.thermal.cpu_temp_max, temp);
            } catch (...) {}
        }
    }
    
    return snapshot;
}

bool HardwareScanner::checkPermissions(std::vector<std::string>& missing_permissions) {
    missing_permissions.clear();
    
    // Check common sysfs paths
    if (!fileExists("/sys/class/kgsl/kgsl-3d0")) {
        missing_permissions.push_back("GPU control (requires root/Shizuku)");
    }
    
    if (!fileExists("/proc/meminfo")) {
        missing_permissions.push_back("Memory stats access");
    }
    
    return missing_permissions.empty();
}

// ============================================================================
// VECTOR ANALYZER IMPLEMENTATION
// ============================================================================

float VectorAnalyzer::predictLoad(const std::vector<float>& history, int lookahead_ms) {
    if (history.empty()) return 0.5f;
    if (history.size() == 1) return history[0];
    
    // Simple linear prediction
    float slope = 0.0f;
    int n = history.size();
    
    for (size_t i = 1; i < history.size(); i++) {
        slope += (history[i] - history[i-1]);
    }
    slope /= (n - 1);
    
    // Predict future load
    float predicted = history.back() + slope * (lookahead_ms / 100.0f);
    
    // Clamp to valid range
    return std::max(0.0f, std::min(100.0f, predicted));
}

float VectorAnalyzer::calculateThermalCorrelation(const HardwareSnapshot& current,
                                                  const HardwareSnapshot& previous) {
    float temp_delta = current.thermal.cpu_temp_max - previous.thermal.cpu_temp_max;
    float perf_delta = current.performance_potential - previous.performance_potential;
    
    if (std::abs(temp_delta) < 0.1f) return 0.0f;
    
    return perf_delta / temp_delta;
}

std::string VectorAnalyzer::detectBottleneck(const HardwareSnapshot& snapshot) {
    float cpu_load = 0.0f;
    int active_cores = 0;
    
    for (int i = 0; i < 3; i++) {
        if (snapshot.cpu_clusters[i].is_online) {
            cpu_load += snapshot.cpu_clusters[i].load_percent;
            active_cores++;
        }
    }
    if (active_cores > 0) cpu_load /= active_cores;
    
    float gpu_load = snapshot.gpu.load_percent;
    float ram_usage = snapshot.memory.ram_usage_percent;
    
    // Determine primary bottleneck
    if (gpu_load > 90.0f && gpu_load > cpu_load) {
        return "gpu";
    } else if (cpu_load > 90.0f && cpu_load > gpu_load) {
        return "cpu";
    } else if (ram_usage > 90.0f) {
        return "memory";
    } else if (snapshot.memory.storage_type.find("eMMC") != std::string::npos &&
               snapshot.system_load_index > 7.0f) {
        return "io";
    }
    
    return "none";
}

float VectorAnalyzer::calculatePerformanceScore(const HardwareSnapshot& snapshot) {
    float score = 100.0f;
    
    // Penalize high temperatures
    if (snapshot.thermal.cpu_temp_max > 80.0f) {
        score -= (snapshot.thermal.cpu_temp_max - 80.0f) * 2.0f;
    }
    
    // Penalize thermal throttling
    if (snapshot.is_thermal_constrained) {
        score *= 0.7f;
    }
    
    // Penalize low battery
    if (snapshot.thermal.battery_level < 20) {
        score *= 0.8f;
    }
    
    // Bonus for cool operation
    if (snapshot.thermal_headroom > 30.0f) {
        score *= 1.1f;
    }
    
    return std::max(0.0f, std::min(100.0f, score));
}

int64_t VectorAnalyzer::predictThermalThrottle(const HardwareSnapshot& current,
                                               const HardwareSnapshot& previous) {
    float temp_current = current.thermal.cpu_temp_max;
    float temp_previous = previous.thermal.cpu_temp_max;
    
    float temp_delta = temp_current - temp_previous;
    if (temp_delta <= 0.0f) return -1; // Cooling or stable
    
    float throttle_threshold = 85.0f;
    float remaining_headroom = throttle_threshold - temp_current;
    
    if (remaining_headroom <= 0.0f) return 0; // Already throttling
    
    // Estimate time to throttle (assuming constant heating rate)
    // Time = headroom / heating_rate
    float heating_rate = temp_delta; // per scan interval (assume 100ms)
    int64_t time_to_throttle_ms = (int64_t)((remaining_headroom / heating_rate) * 100.0f);
    
    return time_to_throttle_ms;
}

// ============================================================================
// SAFE EXECUTOR IMPLEMENTATION
// ============================================================================

bool SafeExecutor::executeWithSafety(const std::string& command, bool dry_run) {
    if (dry_run) {
        // Validate command syntax
        if (command.find(";") != std::string::npos ||
            command.find("&&") != std::string::npos ||
            command.find("rm ") != std::string::npos ||
            command.find("echo") == std::string::npos) {
            return false; // Potentially dangerous
        }
        return true;
    }
    
    // Record original value for rollback
    // Execute command via popen
    FILE* pipe = popen(command.c_str(), "r");
    if (!pipe) return false;
    
    char buffer[256];
    std::string result;
    while (fgets(buffer, sizeof(buffer), pipe) != nullptr) {
        result += buffer;
    }
    
    int exit_code = pclose(pipe);
    return (exit_code == 0);
}

bool SafeExecutor::writeSysFSSafe(const std::string& path, const std::string& value,
                                  const std::string& min_value, 
                                  const std::string& max_value) {
    // Range checking
    if (!min_value.empty() || !max_value.empty()) {
        try {
            int val = std::stoi(value);
            if (!min_value.empty() && val < std::stoi(min_value)) return false;
            if (!max_value.empty() && val > std::stoi(max_value)) return false;
        } catch (...) {
            return false;
        }
    }
    
    // Read original value
    std::string original = readSysFSString(path);
    
    // Write new value
    std::ofstream file(path);
    if (!file.is_open()) return false;
    
    file << value;
    file.close();
    
    // Verify write
    std::string verify = readSysFSString(path);
    if (verify != value) {
        // Write failed, attempt rollback
        if (!original.empty()) {
            std::ofstream rollback_file(path);
            if (rollback_file.is_open()) {
                rollback_file << original;
            }
        }
        return false;
    }
    
    // Record change for rollback
    ChangeRecord record;
    record.path = path;
    record.original_value = original;
    record.new_value = value;
    record.timestamp = getCurrentTimeNanos();
    
    change_history_.push_back(record);
    
    // Limit history size
    if (change_history_.size() > MAX_HISTORY_SIZE) {
        change_history_.erase(change_history_.begin());
    }
    
    return true;
}

void SafeExecutor::rollbackLastChanges() {
    // Rollback in reverse order
    for (auto it = change_history_.rbegin(); it != change_history_.rend(); ++it) {
        if (!it->original_value.empty()) {
            writeSysFSSafe(it->path, it->original_value);
        }
    }
    
    change_history_.clear();
}

} // namespace titan
