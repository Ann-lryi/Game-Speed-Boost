package com.titan.engine.models

import org.json.JSONObject
import org.json.JSONArray

/**
 * GAME PROFILE MODEL
 * 
 * Định nghĩa cấu hình tối ưu cho từng trò chơi và từng loại phần cứng.
 * Dữ liệu được lưu trữ dưới dạng JSON để dễ dàng cập nhật từ Cloud.
 * 
 * CẤU TRÚC VECTOR: Mỗi profile chứa các vector ngưỡng cho CPU, GPU, RAM, Thermal.
 */
data class GameProfile(
    val gameName: String,
    val packageName: String,
    val targetFps: Int,
    val maxTemperatureThreshold: Float,
    
    // Vector cấu hình CPU
    val cpuConfig: CPUConfig,
    
    // Vector cấu hình GPU
    val gpuConfig: GPUConfig,
    
    // Vector cấu hình RAM
    val ramConfig: RAMConfig,
    
    // Cấu hình đặc thù cho từng dòng chip
    val chipSpecifics: Map<ChipVendor, ChipSpecificConfig> = emptyMap(),
    
    // Metadata
    val version: String = "1.0.0",
    val author: String = "Titan Community",
    val rating: Float = 0.0f
) {

    companion object {
        fun fromJson(json: JSONObject): GameProfile {
            return GameProfile(
                gameName = json.getString("gameName"),
                packageName = json.getString("packageName"),
                targetFps = json.getInt("targetFps"),
                maxTemperatureThreshold = json.getDouble("maxTemperatureThreshold").toFloat(),
                
                cpuConfig = CPUConfig.fromJson(json.getJSONObject("cpuConfig")),
                gpuConfig = GPUConfig.fromJson(json.getJSONObject("gpuConfig")),
                ramConfig = RAMConfig.fromJson(json.getJSONObject("ramConfig")),
                
                chipSpecifics = if (json.has("chipSpecifics")) {
                    parseChipSpecifics(json.getJSONObject("chipSpecifics"))
                } else {
                    emptyMap()
                },
                
                version = json.optString("version", "1.0.0"),
                author = json.optString("author", "Titan Community"),
                rating = json.optDouble("rating", 0.0).toFloat()
            )
        }

        private fun parseChipSpecifics(json: JSONObject): Map<ChipVendor, ChipSpecificConfig> {
            val map = mutableMapOf<ChipVendor, ChipSpecificConfig>()
            
            json.keys().forEach { key ->
                val vendor = ChipVendor.valueOf(key.uppercase())
                val config = ChipSpecificConfig.fromJson(json.getJSONObject(key))
                map[vendor] = config
            }
            
            return map
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("gameName", gameName)
            put("packageName", packageName)
            put("targetFps", targetFps)
            put("maxTemperatureThreshold", maxTemperatureThreshold)
            put("cpuConfig", cpuConfig.toJson())
            put("gpuConfig", gpuConfig.toJson())
            put("ramConfig", ramConfig.toJson())
            
            if (chipSpecifics.isNotEmpty()) {
                val chipJson = JSONObject()
                chipSpecifics.forEach { (vendor, config) ->
                    chipJson.put(vendor.name.lowercase(), config.toJson())
                }
                put("chipSpecifics", chipJson)
            }
            
            put("version", version)
            put("author", author)
            put("rating", rating)
        }
    }
}

// --- CPU CONFIGURATION VECTOR ---
data class CPUConfig(
    val minFrequency: Int,      // MHz
    val maxFrequency: Int,      // MHz
    val governor: String,       // performance, schedutil, interactive
    val upThreshold: Int,       // % CPU load để tăng xung
    val downThreshold: Int,     // % CPU load để giảm xung
    val coreMask: String,       // Bitmask cho việc ghim luồng (ví dụ: "11110000" cho 4 nhân hiệu năng)
    val boostOnTouch: Boolean   // Tăng xung khi chạm màn hình
) {
    companion object {
        fun fromJson(json: JSONObject): CPUConfig {
            return CPUConfig(
                minFrequency = json.getInt("minFrequency"),
                maxFrequency = json.getInt("maxFrequency"),
                governor = json.getString("governor"),
                upThreshold = json.getInt("upThreshold"),
                downThreshold = json.getInt("downThreshold"),
                coreMask = json.optString("coreMask", "11111111"),
                boostOnTouch = json.optBoolean("boostOnTouch", true)
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("minFrequency", minFrequency)
            put("maxFrequency", maxFrequency)
            put("governor", governor)
            put("upThreshold", upThreshold)
            put("downThreshold", downThreshold)
            put("coreMask", coreMask)
            put("boostOnTouch", boostOnTouch)
        }
    }
}

// --- GPU CONFIGURATION VECTOR ---
data class GPUConfig(
    val minFrequency: Int,      // MHz
    val maxFrequency: Int,      // MHz
    val powerLevel: Int,        // 0 (max perf) - 7 (min perf) cho Adreno
    val enableFastRamp: Boolean // Tăng xung nhanh khi cần
) {
    companion object {
        fun fromJson(json: JSONObject): GPUConfig {
            return GPUConfig(
                minFrequency = json.getInt("minFrequency"),
                maxFrequency = json.getInt("maxFrequency"),
                powerLevel = json.optInt("powerLevel", 0),
                enableFastRamp = json.optBoolean("enableFastRamp", true)
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("minFrequency", minFrequency)
            put("maxFrequency", maxFrequency)
            put("powerLevel", powerLevel)
            put("enableFastRamp", enableFastRamp)
        }
    }
}

// --- RAM CONFIGURATION VECTOR ---
data class RAMConfig(
    val lmkAdjustment: Int,     // Điều chỉnh Low Memory Killer
    val zramEnabled: Boolean,
    val zramSizeMB: Int,
    val swappiness: Int,        // 0-100: Mức độ ưu tiên swap
    val keepGameInMemory: Boolean // Ngăn system kill game
) {
    companion object {
        fun fromJson(json: JSONObject): RAMConfig {
            return RAMConfig(
                lmkAdjustment = json.getInt("lmkAdjustment"),
                zramEnabled = json.optBoolean("zramEnabled", true),
                zramSizeMB = json.optInt("zramSizeMB", 2048),
                swappiness = json.optInt("swappiness", 60),
                keepGameInMemory = json.optBoolean("keepGameInMemory", true)
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("lmkAdjustment", lmkAdjustment)
            put("zramEnabled", zramEnabled)
            put("zramSizeMB", zramSizeMB)
            put("swappiness", swappiness)
            put("keepGameInMemory", keepGameInMemory)
        }
    }
}

// --- CHIP SPECIFIC CONFIGURATION ---
enum class ChipVendor {
    QUALCOMM,   // Snapdragon
    MEDIATEK,   // Dimensity, Helio
    SAMSUNG,    // Exynos
    GOOGLE,     // Tensor
    HUAWEI,     // Kirin
    UNKNOWN
}

data class ChipSpecificConfig(
    val vendor: ChipVendor,
    val specificNodes: Map<String, String>, // Ánh xạ node sysfs đặc thù
    val thermalProfiles: List<ThermalStep>, // Các bước điều chỉnh nhiệt
    val gpuDriverVersion: String? = null    // Phiên bản driver tối ưu
) {
    companion object {
        fun fromJson(json: JSONObject): ChipSpecificConfig {
            val vendor = ChipVendor.valueOf(json.getString("vendor").uppercase())
            
            val nodes = mutableMapOf<String, String>()
            if (json.has("specificNodes")) {
                val nodesJson = json.getJSONObject("specificNodes")
                nodesJson.keys().forEach { key ->
                    nodes[key] = nodesJson.getString(key)
                }
            }
            
            val thermalSteps = mutableListOf<ThermalStep>()
            if (json.has("thermalProfiles")) {
                val array = json.getJSONArray("thermalProfiles")
                for (i in 0 until array.length()) {
                    thermalSteps.add(ThermalStep.fromJson(array.getJSONObject(i)))
                }
            }
            
            return ChipSpecificConfig(
                vendor = vendor,
                specificNodes = nodes,
                thermalProfiles = thermalSteps,
                gpuDriverVersion = json.optString("gpuDriverVersion", null)
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("vendor", vendor.name)
            
            val nodesJson = JSONObject()
            specificNodes.forEach { (key, value) ->
                nodesJson.put(key, value)
            }
            put("specificNodes", nodesJson)
            
            val thermalArray = JSONArray()
            thermalProfiles.forEach { step ->
                thermalArray.put(step.toJson())
            }
            put("thermalProfiles", thermalArray)
            
            gpuDriverVersion?.let { put("gpuDriverVersion", it) }
        }
    }
}

data class ThermalStep(
    val temperatureThreshold: Float,
    val cpuScale: Float,      // Hệ số nhân xung CPU (0.5 = 50%)
    val gpuScale: Float,      // Hệ số nhân xung GPU
    val action: String        // "THROTTLE", "MAINTAIN", "BOOST"
) {
    companion object {
        fun fromJson(json: JSONObject): ThermalStep {
            return ThermalStep(
                temperatureThreshold = json.getDouble("temperatureThreshold").toFloat(),
                cpuScale = json.getDouble("cpuScale").toFloat(),
                gpuScale = json.getDouble("gpuScale").toFloat(),
                action = json.getString("action")
            )
        }
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("temperatureThreshold", temperatureThreshold)
            put("cpuScale", cpuScale)
            put("gpuScale", gpuScale)
            put("action", action)
        }
    }
}
