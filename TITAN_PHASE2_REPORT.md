# 📘 TITAN ENGINE - BÁO CÁO GIAI ĐOẠN 2

## ✅ HOÀN THÀNH

### 1. **Rule Engine (RuleEngine.kt)**
- **Chức năng**: Bộ não điều phối, phân tích vector metrics thời gian thực
- **Thuật toán chính**:
  - **Vector Smoothing**: Lọc nhiễu bằng trung bình động 10 mẫu
  - **Emergency Stop Watchdog**: Dừng khẩn cấp khi nhiệt độ > 85°C hoặc FPS < 15 + GPU 95%
  - **Fuzzy Decision Maker**: Ra quyết định dựa trên mối quan hệ FPS ↔ Nhiệt ↔ Tải
  - **Adaptive Throttle/Boost**: Tính toán hệ số điều chỉnh phi tuyến tính
  
- **Các chế độ hoạt động**:
  - `THERMAL_THROTTLE`: Giảm xung khi quá nhiệt (giảm 10%/độ vượt ngưỡng)
  - `PERFORMANCE_BOOST`: Tăng xung khi FPS thấp (tối đa 20%)
  - `GPU_PRIORITY_BOOST`: Ưu tiên GPU khi load cao nhưng FPS ổn
  - `OPTIMIZE_EFFICIENCY`: Tiết kiệm pin khi đủ điều kiện
  - `MAINTAIN`: Giữ nguyên trạng thái

### 2. **Game Profile Model (GameProfile.kt)**
- **Cấu trúc dữ liệu JSON** cho từng game:
  - **CPUConfig**: Vector tần số, governor, core mask, boost on touch
  - **GPUConfig**: Tần số, power level, fast ramp
  - **RAMConfig**: LMK adjustment, ZRAM, swappiness
  - **ChipSpecifics**: Cấu hình đặc thù cho Qualcomm, MediaTek, Samsung, Google, Huawei
  
- **Hỗ trợ Multi-Chip**:
  - Ánh xạ node sysfs riêng cho từng vendor
  - Thermal profiles theo từng bước nhiệt độ
  - Version driver GPU tối ưu

### 3. **Sample Profile (genshin_impact.json)**
- Profile mẫu cho Genshin Impact với:
  - Target 60 FPS, max temp 80°C
  - Cấu hình chi tiết cho Snapdragon và MediaTek
  - 3 bậc thermal throttling (75°C, 80°C, 85°C)

---

## 🔧 KIẾN TRÚC DỮ LIỆU VECTOR

```
RealTimeMetrics Vector:
├── fps: Float
├── temperature: Float
├── gpuLoad: Float
├── cpuLoad: Float
├── ramUsage: Long
├── batteryLevel: Int
└── touchPressure: Float

Decision Flow:
Input Vector → Buffer (10 samples) → Average → Check Emergency → Decide Action → Execute
```

---

## 📊 THUẬT TOÁN CHÍNH

### 1. **Calculate Throttle Factor** (Phi tuyến tính)
```kotlin
delta = currentTemp - maxTemp
reduction = clamp(delta * 0.1, 0.1, 0.5)  // 10%/độ, tối đa 50%
return 1.0 - reduction
```

### 2. **Calculate Boost Factor** (Tỷ lệ nghịch)
```kotlin
ratio = currentFps / targetFps
boost = clamp((1.0 - ratio) * 0.3, 0.05, 0.2)  // Tối đa 20%
return 1.0 + boost
```

### 3. **Emergency Stop Conditions** (AND logic)
```
(temp > 85) OR 
(fps < 15 AND gpuLoad > 95) OR 
(cpuLoad > 98 AND avgTemp > 75)
```

---

## 🎯 GIAI ĐOẠN TIẾP THEO

### **Giai đoạn 3: Hardware Abstraction Layer (HAL) - Native C++**
- Viết module `titan_hal.cpp` giao tiếp trực tiếp với kernel
- Implement các hàm:
  - `readSysFS(path)`: Đọc giá trị từ sysfs
  - `writeSysFS(path, value)`: Ghi giá trị (với validation)
  - `getCPUFreq()`, `getGPUFreq()`, `getTemperature()`
  - `setCPUGovernor()`, `setGPUPowerLevel()`
  
- **Yêu cầu**:
  - Thread-safe với mutex
  - Error handling với rollback
  - Hard limits validation trước khi ghi

### **Giai đoạn 4: Integration & Testing**
- Kết nối RuleEngine ↔ TitanCore ↔ HAL
- Viết unit tests cho các thuật toán
- Test trên thiết bị thật (Snapdragon 8 Gen 2/3, Dimensity 9200+)

---

## ⚠️ LƯU Ý AN TOÀN

1. **Hard Limits** luôn được kiểm tra trước khi ghi vào sysfs
2. **Rollback Mechanism** tự động khôi phục nếu ghi thất bại
3. **Emergency Stop** ưu tiên cao nhất, bỏ qua mọi logic khác
4. **Buffer Smoothing** tránh decision sai do nhiễu cảm biến

---

## 📁 CẤU TRÚC THƯ MỤC HIỆN TẠI

```
app/src/main/
├── java/com/titan/
│   ├── core/
│   │   ├── TitanCore.kt (đã có)
│   │   └── TitanError.kt (đã có)
│   ├── engine/
│   │   ├── rules/
│   │   │   └── RuleEngine.kt (✅ MỚI)
│   │   └── models/
│   │       └── GameProfile.kt (✅ MỚI)
│   └── hal/
│       └── (chờ Giai đoạn 3)
└── assets/
    └── profiles/
        └── genshin_impact.json (✅ MỚI)
```

---

**SẴN SÀNG CHO GIAI ĐOẠN 3: NATIVE HAL IMPLEMENTATION?**
