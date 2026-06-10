package com.titan.core

/**
 * Định nghĩa các loại lỗi cụ thể trong Titan Engine
 * Giúp xử lý lỗi an toàn và rõ ràng thay vì dùng Exception chung chung.
 */
sealed class TitanError(message: String) : Exception(message) {
    object InitializationFailed : TitanError("Khởi tạo Titan Engine thất bại. Kiểm tra quyền Shizuku/Root.")
    object HardwareNotSupported : TitanError("Phần cứng thiết bị không được hỗ trợ hoặc không thể nhận diện.")
    object SysFSAccessDenied : TitanError("Không thể truy cập node sysfs. Cần quyền cao hơn.")
    object ThermalLimitExceeded : TitanError("Nhiệt độ vượt ngưỡng an toàn. Đã kích hoạt Emergency Stop.")
    object ProfileNotFound : TitanError("Không tìm thấy cấu hình tối ưu cho game này.")
    object NativeLibraryLoadFailed : TitanError("Không thể tải thư viện native titan_core.so")
    object ProfileParseError : TitanError("Lỗi phân tích cú pháp profile game.")
    
    data class ExecutionFailed(val detail: String) : TitanError("Thực thi lệnh thất bại: $detail")
    data class InvalidParameterValue(val param: String, val value: Any) : TitanError("Giá trị không hợp lệ cho $param: $value")
    
    companion object {
        fun PROFILE_PARSE_ERROR(message: String): TitanError {
            return ProfileParseError
        }
    }
}
