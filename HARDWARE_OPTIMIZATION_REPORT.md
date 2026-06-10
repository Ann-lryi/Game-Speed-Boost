# BÁO CÁO KỸ THUẬT: TỐI ƯU HÓA PHẦN CỨM ANDROID SÂU (DEEP HARDWARE OPTIMIZATION)
**Dự án:** Game Speed Boost - Private Enterprise Edition
**Phiên bản:** 1.0 (Pre-Development Analysis)
**Đối tượng:** Đội ngũ phát triển Core, Kiến trúc sư hệ thống
**Mục tiêu:** Đạt hiệu năng tối đa trên quy mô phần cứng đa dạng mà không gây mất ổn định hệ thống.

---

## 1. TỔNG QUAN KIẾN TRÚC PHẦN CỨM ANDROID
Để tối ưu hóa thực sự, chúng ta không thể coi Android là một khối đen. Chúng ta phải tương tác với từng lớp (Layer) của ngăn xếp phần cứng:

```
[Ứng dụng Game/Booster] 
       ⬇️ (JNI/NDK)
[Hardware Abstraction Layer - HAL]
       ⬇️ (Kernel Interfaces / Sysfs / Devfreq)
[Linux Kernel & Drivers]
       ⬇️ (Direct Hardware Control)
[SoC (System on Chip): CPU, GPU, NPU, ISP, DSP]
       ⬇️ (Bus Interconnects)
[Bộ nhớ (RAM/UFS), Cảm biến, Màn hình, Pin]
```

**Chiến lược cốt lõi:** Ứng dụng sẽ hoạt động như một "Nhạc trưởng" (Conductor), điều phối tài nguyên dựa trên ngữ cảnh thực tế thay vì chỉ ép xung mù quáng.

---

## 2. PHÂN TÍCH CHI TIẾT TỪNG THÀNH PHẦN PHẦN CỨM

### 2.1. HỆ THỐNG XỬ LÝ (SoC & CPU)
Đây là trái tim của hiệu năng. Mỗi nhà sản xuất có kiến trúc khác nhau.

#### A. Kiến trúc Big.LITTLE / DynamIQ
Hầu hết chip hiện đại đều dùng cấu trúc cụm (Clusters):
- **Prime/Core Siêu lớn:** Hiệu năng cực cao, tốn điện, sinh nhiệt nhiều (Ví dụ: Cortex-X4).
- **Big/Core Lớn:** Cân bằng hiệu năng/điện năng.
- **Little/Core Tiết kiệm:** Xử lý tác vụ nền, tiết kiệm pin.

#### B. Đặc thù từng nhà sản xuất (Vendor Specifics)
1.  **Qualcomm Snapdragon (Adreno GPU):**
    -   *Đặc điểm:* Driver đồ họa được cập nhật qua Google Play (quan trọng). Kiến trúc CPU thường rõ ràng.
    -   *Tối ưu:* Tập trung vào `GPUFreq`, `CPUBW` (Bandwidth), và `LLC` (Last Level Cache).
    -   *File hệ thống quan trọng:* `/sys/class/devfreq/`, `/proc/cpuinfo`.
2.  **MediaTek Dimensity (Mali/Immortalis GPU):**
    -   *Đặc điểm:* Thường nóng hơn Snapdragon khi tải cao. Cơ chế điều tần (Governor) phức tạp hơn (`mtk_thermal`).
    -   *Rủi ro:* Can thiệp sai dễ gây treo máy (hard freeze) do driver đóng kín hơn.
    -   *Tối ưu:* Cần profile riêng cho từng dòng chip (ví dụ: Dimensity 9300 khác 8300). Chú ý đến `PowerHAL` của MTK.
3.  **Google Tensor (Exynos based):**
    -   *Đặc điểm:* Ưu tiên AI/NPU, hiệu năng thuần thấp hơn, quản lý nhiệt rất gắt gao.
    -   *Thách thức:* Thermal throttling xảy ra rất sớm. Cần "lừa" cảm biến nhiệt hoặc điều chỉnh ngưỡng thermal trong kernel (cần Root/Shizuku sâu).
4.  **Samsung Exynos:**
    -   Tương tự Tensor nhưng phổ biến ở phân khúc trung. Cần chú ý đến vấn đề quá nhiệt lịch sử của dòng này.

#### C. Giải pháp kỹ thuật cho CPU
-   **CPU Governor Tuning:** Thay đổi thuật toán điều tần (ví dụ: từ `schedutil` sang `performance` khi chơi game, nhưng chỉ cho các cụm Big/Prime).
-   **Core Hotplugging (Cẩn trọng):** Khóa các core Little khi chơi game nặng để tránh việc scheduler chuyển task qua lại gây giật (stutter), nhưng chỉ áp dụng nếu pin > 20%.
-   **SchedBoost:** Tăng độ ưu tiên cho thread của game trong Linux Scheduler (`/proc/sys/kernel/sched_boost`).

---

### 2.2. ĐỒ HỌA (GPU)
GPU là thành phần quyết định FPS trong game.

#### A. Các dòng GPU phổ biến
1.  **Adreno (Qualcomm):**
    -   Mạnh nhất Android, driver ổn định.
    -   *Tối ưu:* Điều chỉnh `gpu_max_freq`, `min_bus_bandwidth`. Hỗ trợ tốt Vulkan.
2.  **Mali (ARM - dùng trong MTK, Exynos, Tensor):**
    -   Driver phụ thuộc hoàn toàn vào Vendor.
    -   *Tối ưu:* Khó can thiệp sâu hơn Adreno. Tập trung vào `dvfs_sysfs`.
3.  **PowerVR / IMG:** (Hiếm gặp, chủ yếu chip cũ) - Bỏ qua ưu tiên.

#### B. Thuật toán tối ưu GPU
-   **Dynamic Resolution Scaling (Giả lập):** Nếu phát hiện GPU quá tải (thời gian render frame > 16ms cho 60FPS), giảm nhẹ độ phân giải render nội bộ (nếu game hỗ trợ) hoặc giảm cài đặt đồ họa qua file config của game.
-   **Shader Compilation Pre-warming:** Phát hiện shader bị giật lúc đầu game và kích thích biên dịch trước (cần tích hợp sâu với driver, rất khó, có thể dùng cách load texture giả).
-   **Vulkan vs OpenGL ES:** Ưu tiên ép game chạy Vulkan nếu thiết bị hỗ trợ tốt (thường mượt hơn trên Adreno).

---

### 2.3. BỘ NHỚ & LƯU TRỮ (RAM & UFS)
Độ trễ bộ nhớ là nguyên nhân chính của "micro-stutter" (giật lag nhỏ).

#### A. RAM Management
-   **LMK (Low Memory Killer):** Android sẽ kill app khi thiếu RAM.
    -   *Giải pháp:* Điều chỉnh thông số `lmk` (cần Root) để ưu tiên giữ process của game sống lâu hơn các app khác.
    -   **ZRAM:** Một số máy dùng ZRAM (nén RAM). Nếu CPU mạnh nhưng RAM ít, việc tắt ZRAM có thể tăng tốc độ truy xuất nhưng giảm dung lượng khả dụng. Cần thuật toán cân bằng dựa trên dung lượng RAM thực tế.
-   **Page Cache:** Xóa bộ nhớ đệm trang (`drop_caches`) trước khi vào game để giải phóng RAM sạch, nhưng không nên làm liên tục vì gây tốn CPU để đọc lại từ đĩa.

#### B. UFS (Universal Flash Storage)
-   **Chuẩn UFS 3.1 / 4.0:** Tốc độ đọc/ghi cực cao.
-   **Vấn đề:** Khi ghi liên tục (load map, save game), UFS có thể bị nóng và giảm tốc độ (throttling).
-   **Tối ưu:**
    -   Sử dụng `ion_heap` để cấp phát bộ nhớ liên tục, tránh phân mảnh.
    -   Giám sát nhiệt độ UFS (nếu sensor hỗ trợ) và tạm dừng các tác vụ nền (log, analytics) khi đang load màn chơi nặng.
    -   **F2FS Optimization:** Nếu máy dùng hệ thống file F2FS, có thể tinh chỉnh `gc_urgent_threshold` để tránh việc dọn dẹp rác (garbage collection) xảy ra đúng lúc đang chơi game.

---

### 2.4. QUẢN LÝ NHIỆT (THERMAL ENGINE)
Đây là "kẻ thù" lớn nhất của hiệu năng bền vững.

#### A. Cơ chế hoạt động
Hệ thống nhiệt đọc cảm biến -> So sánh ngưỡng -> Ra lệnh cho CPU/GPU giảm xung (Throttling).

#### B. Chiến lược "Hợp tác" thay vì "Vô hiệu hóa"
-   **Sai lầm:** Vô hiệu hóa hoàn toàn thermal daemon (`thermald`) -> Máy quá nóng, hỏng phần cứng, pin chai, người dùng bỏ app.
-   **Giải pháp đúng:**
    1.  **Thermal Offset:** Dịch chuyển ngưỡng nhiệt độ báo cáo lên 2-3 độ C (ví dụ: 45°C báo thành 42°C) để hệ thống giữ xung cao lâu hơn một chút trước khi giảm.
    2.  **Predictive Throttling:** Dùng AI dự đoán nhiệt độ tăng dựa trên tải hiện tại. Chủ động giảm xung nhẹ *trước* khi đạt ngưỡng giới hạn để duy trì đường cong hiệu năng phẳng (stable FPS) thay vì dốc đứng (cao rồi tụt đột ngột).
    3.  **Skin Temperature Monitoring:** Đọc cảm biến nhiệt vỏ máy (`skin_temp`) thay vì chỉ nhiệt độ lõi (soc_temp) để phản ánh trải nghiệm cầm nắm thực tế.

---

### 2.5. MÀN HÌNH & CẢM BIẾN (DISPLAY & SENSORS)

#### A. Màn hình (Refresh Rate)
-   **Vấn đề:** Nhiều máy tự động giảm Hz khi thấy nội dung tĩnh hoặc để tiết kiệm pin.
-   **Giải pháp:**
    -   Sử dụng API `setPreferredDisplayModeId` để khóa tần số quét (60/90/120/144Hz) phù hợp với game.
    -   Phát hiện game đang chạy qua `UsageStats` và gửi tín hiệu giữ sáng, giữ Hz cao.
    -   **Touch Sampling Rate:** Một số chip cho phép tăng tần số lấy mẫu cảm ứng (240Hz -> 480Hz) để phản hồi nhanh hơn. Cần tìm file sys tương ứng (thường là `/proc/touchpanel/`).

#### B. Cảm biến gia tốc/con quay hồi chuyển (Gyroscope)
-   Game bắn súng (PUBG, CODM) phụ thuộc nhiều vào Gyro.
-   **Tối ưu:** Tăng tần số lấy mẫu (polling rate) của cảm biến khi game yêu cầu, giảm độ trễ (latency) bằng cách ưu tiên interrupt của cảm biến trong Kernel.

---

### 2.6. KẾT NỐI (NETWORK & WIFI)
-   **Wi-Fi Latency:** Chuyển sang băng tần 5GHz/6GHz tự động nếu router hỗ trợ. Tắt tính năng tiết kiệm pin Wi-Fi (`wifi_sleep_policy`).
-   **TCP Buffer Tuning:** Điều chỉnh kích thước buffer TCP (`net.ipv4.tcp_rmem`, `tcp_wmem`) để giảm ping trong game, ưu tiên gói tin UDP (thường dùng cho game) hơn TCP (tải nền).
-   **Data Roaming/Network Switching:** Ngăn chặn việc tự động chuyển từ Wi-Fi sang 4G/5G khi sóng Wi-Fi yếu (gây giật lag đột ngột), thay vào đó cảnh báo người dùng.

---

## 3. RÀO CẢN HỆ ĐIỀU HÀNH & BẢO MẬT (ANDROID 13-16+)

### 3.1. Phân quyền và Sandbox
-   **SELinux Enforcing:** Android hiện đại bắt buộc SELinux ở chế độ Enforcing. Việc chuyển sang Permissive cần Root và có thể vi phạm SafetyNet/Play Integrity.
    -   *Giải pháp:* Chỉ sử dụng các file node được phép ghi (writable nodes) mà không cần đổi SELinux. Lập danh sách trắng các file sys an toàn cho từng model máy.
-   **Scoped Storage:** Không thể truy cập file game tùy ý.
    -   *Giải pháp:* Sử dụng Shizuku để thao tác file thông qua shell command hoặc request permission đặc biệt.

### 3.2. Anti-Cheat Systems
-   Các game như Genshin Impact, PUBG có cơ chế phát hiện can thiệp hệ thống.
-   **Nguy cơ:** Bị khóa tài khoản nếu app sửa đổi bộ nhớ game hoặc inject code.
-   **Nguyên tắc vàng:** **KHÔNG** sửa đổi bộ nhớ game (Memory Editing), **KHÔNG** inject DLL/SO vào process game.
-   **Phạm vi an toàn:** Chỉ can thiệp vào **Kernel Parameters** (CPU/GPU freq, Thermal, IO Scheduler) và **System Settings**. Đây là vùng xám an toàn hơn vì nó ảnh hưởng đến toàn hệ thống chứ không riêng game.

---

## 4. KIẾN TRÚC GIẢI PHÁP ĐỀ XUẤT (THE "TITAN" ARCHITECTURE)

Để xử lý sự đa dạng phần cứng, chúng ta cần một kiến trúc hướng dữ liệu (Data-Driven).

### 4.1. Module 1: Hardware Profiler (Nhận diện)
-   **Chức năng:** Quét toàn bộ phần cứng khi khởi động lần đầu.
-   **Dữ liệu thu thập:**
    -   SoC Model, Manufacturing Process (nm).
    -   CPU Topology (Số core, loại core, freq max/min).
    -   GPU Model, Driver Version.
    -   RAM Size, Type (LPDDR4X/5), Storage Type (UFS 2.1/3.1/4.0).
    -   Thermal Sensors List (Tên, vị trí, ngưỡng hiện tại).
    -   Kernel Version, SELinux status.
-   **Output:** Tạo một "Device Fingerprint" duy nhất.

### 4.2. Module 2: Cloud Rule Engine (Bộ quy tắc đám mây)
-   Không hard-code logic trong app. Logic nằm trên Server.
-   **Cơ chế:** App gửi "Device Fingerprint" lên Server -> Server trả về "Profile JSON".
-   **Nội dung Profile:**
    ```json
    {
      "device_id": "xiaomi_13_pro",
      "soc": "snapdragon_8_gen_2",
      "rules": [
        {"target": "cpu_cluster_big", "action": "lock_freq", "value": "1800000"},
        {"target": "thermal_skin", "action": "offset", "value": "-2"},
        {"target": "io_scheduler", "action": "set", "value": "noop"},
        {"target": "gpu_power_level", "action": "minimize"}
      ],
      "safety_limits": {
        "max_temp": 45,
        "min_battery": 15
      }
    }
    ```
-   **Lợi ích:** Cập nhật profile cho máy mới ngay khi ra mắt mà không cần update app. Thu thập dữ liệu crowd-source để tinh chỉnh profile tốt hơn.

### 4.3. Module 3: Adaptive Execution Core (Thực thi thích nghi)
-   Chạy vòng lặp kiểm tra (Loop) với tần số cao (100ms - 500ms).
-   **Input:** Dữ liệu thời gian thực (Real-time metrics): FPS, Nhiệt độ, Tải CPU/GPU.
-   **Logic:**
    -   Nếu `Temp > Limit`: Giảm xung ngay lập tức (Emergency Throttle).
    -   Nếu `FPS < Target` && `Temp < Safe`: Tăng xung từ từ (Step-up).
    -   Nếu `Battery < 15%`: Chuyển sang chế độ tiết kiệm, bỏ qua tối ưu hiệu năng.
-   **Công cụ:** Sử dụng Kotlin Flow + Native Daemon (viết bằng C++) để thực thi lệnh shell nhanh nhất có thể.

### 4.4. Module 4: Safety Watchdog (Giám sát an toàn)
-   Độc lập với module thực thi.
-   Giám sát nhiệt độ pin và vỏ máy liên tục.
-   Nếu phát hiện nhiệt độ tăng đột biến bất thường (dấu hiệu lỗi logic): **Force Restore** (Khôi phục cài đặt gốc ngay lập tức) và tắt dịch vụ booster.
-   Log lại sự cố và gửi báo cáo ẩn danh về server để phân tích.

---

## 5. KẾ HOẠCH TRIỂN KHAI & RỦI RO

### 5.1. Lộ trình phát triển
1.  **Giai đoạn 1 (Nghiên cứu & Thu thập dữ liệu):**
    -   Xây dựng tool quét phần cứng chi tiết.
    -   Thu thập danh sách các file `sysfs` an toàn cho 50 dòng máy phổ biến nhất (Samsung S series, Xiaomi, OnePlus, Pixel).
    -   Thiết lập Server Rule Engine cơ bản.
2.  **Giai đoạn 2 (Xây dựng Core Engine):**
    -   Viết Native Daemon (C++) để thực thi lệnh với độ trễ thấp.
    -   Tích hợp Shizuku API ổn định.
    -   Xây dựng cơ chế Backup/Restore trạng thái gốc (cực kỳ quan trọng).
3.  **Giai đoạn 3 (Thử nghiệm Beta kín):**
    -   Phát hành cho 100 tester với nhiều loại máy khác nhau.
    -   Thu thập log nhiệt độ và hiệu năng.
    -   Tinh chỉnh các ngưỡng Thermal.
4.  **Giai đoạn 4 (Hoàn thiện & Mở rộng):**
    -   Thêm tính năng Cloud Profile.
    -   Tối ưu hóa UI/UX.

### 5.2. Đánh giá rủi ro
| Rủi ro | Mức độ | Giải pháp giảm thiểu |
| :--- | :--- | :--- |
| **Quá nhiệt/Hỏng phần cứng** | Cao | Watchdog độc lập, giới hạn cứng (Hard cap) nhiệt độ, không vô hiệu hóa hoàn toàn thermal. |
| **Vi phạm Anti-Cheat** | Trung bình | Cam kết không sửa bộ nhớ game, chỉ can thiệp hệ thống. Công khai minh bạch phương thức hoạt động. |
| **Tre máy/Khởi động lại** | Trung bình | Cơ chế khôi phục tự động khi app crash. Backup trạng thái trước khi áp dụng. |
| **Không tương thích thiết bị** | Cao | Sử dụng Cloud Rule Engine để cập nhật profile liên tục. Chế độ "Safe Default" cho máy lạ. |
| **Pin tụt nhanh** | Thấp | Cảnh báo người dùng rõ ràng. Có chế độ cân bằng (Balanced Mode). |

---

## 6. KẾT LUẬN

Để xây dựng một ứng dụng tối ưu hóa Android ở quy mô doanh nghiệp, chúng ta không thể đi theo lối mòn của các app "làm sạch RAM" hay "tăng tốc ảo" tràn lan. Chìa khóa thành công nằm ở:
1.  **Hiểu biết sâu sắc về phần cứng:** Tôn trọng giới hạn vật lý của CPU, GPU, Pin.
2.  **Dữ liệu là vua:** Sử dụng Cloud Rule Engine để thích ứng với hàng ngàn cấu hình máy khác nhau mà không cần hard-code.
3.  **An toàn là trên hết:** Cơ chế Watchdog và khôi phục trạng thái gốc phải là ưu tiên số 1.
4.  **Minh bạch:** Người dùng cần biết chính xác app đang làm gì với máy của họ.

Báo cáo này là nền tảng để bắt đầu viết mã. Bước tiếp theo là xây dựng module **Hardware Profiler** để thu thập dữ liệu thực tế từ các thiết bị mẫu.

---
*Ký tên: Chuyên gia Phát triển Android System & Performance Optimization.*
