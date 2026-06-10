package com.titan.core.model

/**
 * Định nghĩa các loại lỗi trong Titan Engine để xử lý an toàn.
 */
sealed class TitanError(message: String) {
    class HardwareNotFound(val component: String) : TitanError("Hardware component not found: $component")
    class PermissionDenied(val action: String) : TitanError("Permission denied for action: $action")
    class ThermalThresholdExceeded(val temp: Float) : TitanError("Thermal threshold exceeded: $temp°C")
    class ExecutionFailed(val command: String, val reason: String) : TitanError("Execution failed: $command ($reason)")
    class ProfileLoadError(val gameId: String) : TitanError("Failed to load profile for game: $gameId")
    
    override fun toString(): String = message
}
