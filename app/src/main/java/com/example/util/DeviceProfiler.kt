package com.example.util

import android.content.Context
import android.os.Build
import android.util.Log
import android.os.SystemClock
import java.io.File

// Reads hardware topology WITHOUT any special permissions.
// Sources: /proc/cpuinfo, /sys/devices/system/cpu/cpuN/cpufreq/
//          /sys/class/kgsl/kgsl-3d0/ (Adreno GPU), /proc/meminfo
// All reads work without root or Shizuku.
object DeviceProfiler {

    private const val TAG = "DeviceProfiler"

    enum class GpuFamily { ADRENO, MALI, UNKNOWN }
    enum class CpuFamily { SNAPDRAGON, DIMENSITY, EXYNOS, KIRIN, UNKNOWN }

    data class CoreTopology(
        val totalCores: Int,
        val bigCores: List<Int>,    // Highest max freq cores (performance)
        val midCores: List<Int>,    // Mid freq (only on tri-cluster SOCs)
        val littleCores: List<Int>, // Lowest max freq cores (efficiency)
        val bigCoreMask: String,    // Hex bitmask for taskset e.g. "f0"
        val allCoreMask: String     // All cores mask
    )

    data class DeviceProfile(
        val socModel: String,
        val cpuFamily: CpuFamily,
        val gpuFamily: GpuFamily,
        val gpuModel: String,
        val coreTopology: CoreTopology,
        val totalRamMb: Long,
        val isHighEndDevice: Boolean  // influences default algorithm aggressiveness
    )

    @Volatile private var cachedProfile: DeviceProfile? = null

    fun getProfile(context: Context): DeviceProfile {
        cachedProfile?.let { return it }
        val profile = buildProfile(context)
        cachedProfile = profile
        Log.i(TAG, "Device profile: $profile")
        return profile
    }

    private fun buildProfile(context: Context): DeviceProfile {
        val cpuInfo   = readCpuInfo()
        val socModel  = detectSocModel(cpuInfo)
        val cpuFamily = detectCpuFamily(cpuInfo, socModel)
        val (gpuFamily, gpuModel) = detectGpu()
        val topology  = buildCoreTopology()
        val totalRam  = getTotalRamMb(context)
        val highEnd   = totalRam >= 6144 || socModel.contains("888") || socModel.contains("8 Gen") || socModel.contains("Gen 2") || socModel.contains("Gen 3") || socModel.contains("A17") || socModel.contains("M2") || socModel.contains("M3")

        return DeviceProfile(
            socModel       = socModel,
            cpuFamily      = cpuFamily,
            gpuFamily      = gpuFamily,
            gpuModel       = gpuModel,
            coreTopology   = topology,
            totalRamMb     = totalRam,
            isHighEndDevice = highEnd
        )
    }

    // ── CPU Topology ──────────────────────────────────────────────────────────

    fun buildCoreTopology(): CoreTopology {
        val presentCores = readPresentCores()
        val maxFreqs = presentCores.map { cpu ->
            cpu to readCpuMaxFreq(cpu)
        }

        if (maxFreqs.isEmpty()) {
            return CoreTopology(0, emptyList(), emptyList(), emptyList(), "ff", "ff")
        }

        val sortedFreqs = maxFreqs.sortedByDescending { it.second }
        val maxFreq     = sortedFreqs.first().second
        val minFreq     = sortedFreqs.last().second
        val midThresh   = minFreq + ((maxFreq - minFreq) * 0.5).toInt()
        val bigThresh   = minFreq + ((maxFreq - minFreq) * 0.8).toInt()

        val big    = sortedFreqs.filter { it.second >= bigThresh }.map { it.first }
        val little = sortedFreqs.filter { it.second <= midThresh }.map { it.first }
        val mid    = sortedFreqs.filter { it.second > midThresh && it.second < bigThresh }.map { it.first }

        fun coresToMask(cores: List<Int>): String {
            var mask = 0
            cores.forEach { mask = mask or (1 shl it) }
            return mask.toString(16)
        }

        val allMask = coresToMask(presentCores)
        val bigMask = if (big.isNotEmpty()) coresToMask(big) else allMask

        return CoreTopology(
            totalCores  = presentCores.size,
            bigCores    = big,
            midCores    = mid,
            littleCores = little,
            bigCoreMask = bigMask,
            allCoreMask = allMask
        )
    }

    private fun readPresentCores(): List<Int> {
        return try {
            val present = File("/sys/devices/system/cpu/present").readText().trim()
            // Format: "0-7" or "0,1,2,3,4,5,6,7"
            when {
                present.contains("-") -> {
                    val (s, e) = present.split("-")
                    (s.toInt()..e.toInt()).toList()
                }
                present.contains(",") -> present.split(",").map { it.trim().toInt() }
                else -> listOf(present.toInt())
            }
        } catch (e: Exception) {
            // Fallback: assume 8 cores
            Log.w(TAG, "Cannot read cpu/present: ${e.message}")
            (0..7).toList()
        }
    }

    private fun readCpuMaxFreq(cpu: Int): Int {
        val paths = listOf(
            "/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq",
            "/sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_max_freq"
        )
        for (path in paths) {
            try {
                val v = File(path).readText().trim().toIntOrNull()
                if (v != null && v > 0) return v
            } catch (_: Exception) {}
        }
        return 1000000 // 1GHz default
    }

    // ── CPU/SoC Detection ─────────────────────────────────────────────────────

    private fun readCpuInfo(): String {
        return try { File("/proc/cpuinfo").readText() } catch (e: Exception) { "" }
    }

    private fun detectSocModel(cpuInfo: String): String {
        // Try Android property first
        try {
            val soc = listOf("ro.board.platform", "ro.hardware", "ro.product.board")
                .firstNotNullOfOrNull { getSystemProperty(it)?.takeIf { s -> s.isNotBlank() } }
            if (!soc.isNullOrBlank()) return soc
        } catch (_: Exception) {}

        // Parse /proc/cpuinfo
        val hardware = Regex("Hardware\\s*:\\s*(.+)", RegexOption.IGNORE_CASE)
            .find(cpuInfo)?.groupValues?.get(1)?.trim()
        if (!hardware.isNullOrBlank()) return hardware

        return Build.HARDWARE.takeIf { it.isNotBlank() } ?: "Unknown"
    }

    private fun detectCpuFamily(cpuInfo: String, soc: String): CpuFamily {
        val combined = "$cpuInfo $soc".lowercase()
        return when {
            combined.contains("qcom") || combined.contains("snapdragon") || combined.contains("sm8") || combined.contains("msm") -> CpuFamily.SNAPDRAGON
            combined.contains("dimensity") || combined.contains("mt6") || combined.contains("helio") -> CpuFamily.DIMENSITY
            combined.contains("exynos") || combined.contains("samsung") -> CpuFamily.EXYNOS
            combined.contains("kirin") || combined.contains("hi36") -> CpuFamily.KIRIN
            else -> CpuFamily.UNKNOWN
        }
    }

    // ── GPU Detection ─────────────────────────────────────────────────────────

    private fun detectGpu(): Pair<GpuFamily, String> {
        // Adreno (Qualcomm)
        val adrenoPaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/class/kgsl/kgsl-3d0/devfreq/governor"
        )
        for (p in adrenoPaths) {
            try {
                val v = File(p).readText().trim()
                if (v.isNotBlank()) return Pair(GpuFamily.ADRENO, v)
            } catch (_: Exception) {}
        }

        // Mali (MediaTek/Exynos)
        val maliPaths = listOf(
            "/sys/class/misc/mali0/device/gpuinfo",
            "/sys/module/mali/version"
        )
        for (p in maliPaths) {
            try {
                val v = File(p).readText().trim()
                if (v.isNotBlank()) return Pair(GpuFamily.MALI, v)
            } catch (_: Exception) {}
        }

        return Pair(GpuFamily.UNKNOWN, "Unknown GPU")
    }

    // ── GPU Busy % (no permissions needed on most devices) ────────────────────

    fun getGpuBusyPercent(): Int {
        val paths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",  // Adreno
            "/sys/class/kgsl/kgsl-3d0/devfreq/trans_stat",   // Adreno alt
            "/sys/kernel/gpu/gpu_busy"                         // Generic
        )
        for (path in paths) {
            try {
                val raw = File(path).readText().trim()
                val pct = Regex("(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
                if (pct != null) return pct.coerceIn(0, 100)
            } catch (_: Exception) {}
        }
        return -1
    }

    // ── Per-process CPU % via /proc/<pid>/stat (no permission) ───────────────

    private data class ProcStat(val utime: Long, val stime: Long, val wallClock: Long)
    private val procStatCache = mutableMapOf<Int, ProcStat>()

    fun getProcessCpuPercent(pid: Int): Int {
        if (pid <= 0) return -1
        return try {
            val parts = File("/proc/$pid/stat").readText().trim().split(" ")
            val utime = parts[13].toLong()
            val stime = parts[14].toLong()
            val now   = SystemClock.elapsedRealtime()

            val prev = procStatCache[pid]
            procStatCache[pid] = ProcStat(utime, stime, now)

            if (prev == null || now == prev.wallClock) return -1
            val cpuTicks = (utime + stime) - (prev.utime + prev.stime)
            val elapsedMs = now - prev.wallClock
            if (elapsedMs <= 0) return -1

            // USER_HZ = 100, convert ticks to ms: cpuTicks * 10ms per tick
            val cpuMs = cpuTicks * 10L
            ((cpuMs * 100L) / elapsedMs).toInt().coerceIn(0, 100)
        } catch (e: Exception) { -1 }
    }

    // ── Find PID via /proc filesystem (no permissions) ───────────────────────

    fun findPidFromProcFs(packageName: String): Int {
        return try {
            val procDir = File("/proc")
            procDir.listFiles()
                ?.asSequence()
                ?.filter { f -> f.isDirectory && f.name.all { it.isDigit() } }
                ?.firstOrNull { pidDir ->
                    try {
                        val cmdline = File(pidDir, "cmdline").readText()
                            .replace('\u0000', ' ').trim()
                        cmdline.startsWith(packageName)
                    } catch (_: Exception) { false }
                }
                ?.name?.toIntOrNull() ?: -1
        } catch (e: Exception) { -1 }
    }

    // ── Memory ────────────────────────────────────────────────────────────────

    private fun getTotalRamMb(context: Context): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem / (1024 * 1024)
        } catch (e: Exception) { 4096L }
    }

    // ── System Properties ─────────────────────────────────────────────────────

    private fun getSystemProperty(key: String): String? {
        return try {
            val c = Class.forName("android.os.SystemProperties")
            c.getMethod("get", String::class.java).invoke(null, key) as? String
        } catch (_: Exception) { null }
    }

    // ── Thermal Zones ─────────────────────────────────────────────────────────

    fun readThermalZonesCelsius(): Map<String, Float> {
        val result = mutableMapOf<String, Float>()
        try {
            File("/sys/class/thermal").listFiles()?.forEach { zone ->
                if (!zone.name.startsWith("thermal_zone")) return@forEach
                try {
                    val type  = File(zone, "type").readText().trim()
                    val temp  = File(zone, "temp").readText().trim().toFloatOrNull() ?: return@forEach
                    result[type] = temp / 1000f
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return result
    }
}
