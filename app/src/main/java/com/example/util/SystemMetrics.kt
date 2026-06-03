package com.example.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

object SystemMetrics {
    private const val TAG = "SystemMetrics"

    // Cache for CPU calculation to avoid re-reading immediately
    private var lastCpuTotal: Long = 0
    private var lastCpuIdle: Long = 0
    private var lastCpuCheckTime: Long = 0

    fun getRamUsagePercent(context: Context): Int {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val totalMemory = memoryInfo.totalMem.toDouble()
            val availableMemory = memoryInfo.availMem.toDouble()
            val usedMemory = totalMemory - availableMemory

            ((usedMemory / totalMemory) * 100).roundToInt().coerceIn(0, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read RAM usage", e)
            -1 // Return -1 to indicate error, not fake value
        }
    }

    fun getAvailableRamMb(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.availMem / (1024L * 1024L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read available RAM", e)
            -1L
        }
    }

    fun getTotalRamMb(context: Context): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem / (1024L * 1024L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read total RAM", e)
            -1L
        }
    }

    fun getBatteryTempCelsius(context: Context): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            (temp / 10).coerceIn(0, 100) // Real range: 0-100°C
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read battery temp", e)
            -1
        }
    }

    /**
     * Read CPU usage from /proc/stat using cached values for accurate delta calculation.
     * Returns -1 if unable to read (not a fake random value).
     */
    fun getCpuLoadPercent(): Int {
        return try {
            RandomAccessFile("/proc/stat", "r").use { reader ->
                val load1 = reader.readLine() ?: return -1
                val toks = load1.trim().split(Regex("\\s+"))
                if (toks.size < 8 || !toks[0].startsWith("cpu")) return -1

                // Parse CPU times: user, nice, system, idle, iowait, irq, softirq, steal
                val user = toks[1].toLongOrNull() ?: 0L
                val nice = toks[2].toLongOrNull() ?: 0L
                val system = toks[3].toLongOrNull() ?: 0L
                val idle = toks[4].toLongOrNull() ?: 0L
                val iowait = toks[5].toLongOrNull() ?: 0L
                val irq = toks[6].toLongOrNull() ?: 0L
                val softirq = toks[7].toLongOrNull() ?: 0L
                val steal = if (toks.size > 8) toks[8].toLongOrNull() ?: 0L else 0L

                val total = user + nice + system + idle + iowait + irq + softirq + steal
                val totalIdle = idle + iowait

                val currentTime = SystemClock.elapsedRealtime()
                val elapsed = currentTime - lastCpuCheckTime

                // Need at least 100ms between readings for accurate measurement
                if (lastCpuTotal > 0 && lastCpuIdle > 0 && elapsed >= 100) {
                    val totalDelta = total - lastCpuTotal
                    val idleDelta = totalIdle - lastCpuIdle

                    if (totalDelta > 0) {
                        val usage = (((totalDelta - idleDelta).toDouble() / totalDelta) * 100).roundToInt()
                        // Update cache
                        lastCpuTotal = total
                        lastCpuIdle = totalIdle
                        lastCpuCheckTime = currentTime
                        return usage.coerceIn(0, 100)
                    }
                }

                // First reading or too soon - update cache and return previous if available
                lastCpuTotal = total
                lastCpuIdle = totalIdle
                lastCpuCheckTime = currentTime

                // If we have cached result from telemetry loop, return last known good value
                // Otherwise return -1 to indicate need for second reading
                -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read CPU from /proc/stat", e)
            -1
        }
    }

    /**
     * Reset CPU cache when service restarts
     */
    fun resetCpuCache() {
        lastCpuTotal = 0
        lastCpuIdle = 0
        lastCpuCheckTime = 0
    }

    /**
     * Detect UFS version by reading actual hardware info.
     * Returns empty string if unable to detect (not a guess).
     */
    fun detectUfsVersion(context: Context): String {
        // Method 1: Read UFS controller model directly
        val ufsPaths = listOf(
            "/sys/devices/platform/soc/1d84000.ufshc/model",
            "/sys/devices/platform/11270000.ufshc/model",
            "/sys/devices/platform/soc/1d84000.ufs/model",
            "/sys/class/scsi_disk/0:0:0:0/device/model",
            "/sys/class/scsi_disk/0:0:0:1/device/model",
            "/sys/block/sda/device/model",
            "/sys/block/sdb/device/model"
        )

        for (path in ufsPaths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val model = file.readText().trim().uppercase()
                    if (model.isNotBlank()) {
                        return when {
                            model.contains("UFS4") || model.contains("KLUE") ||
                            model.contains("HN8T") || model.contains("TY9") -> "4.x"
                            model.contains("UFS3") || model.contains("KLUD") ||
                            model.contains("KLUC") || model.contains("H9HQ") -> "3.x"
                            model.contains("UFS2") || model.contains("KLUB") ||
                            model.contains("KLUA") -> "2.x"
                            else -> "" // Unknown model, don't guess
                        }
                    }
                }
            } catch (e: Exception) {
                // Continue to next path
            }
        }

        // Method 2: Check build props for known UFS 4.x SoCs
        try {
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL?.lowercase() ?: ""
            } else {
                Build.BOARD?.lowercase() ?: ""
            }
            val hardware = Build.HARDWARE?.lowercase() ?: ""

            val ufs4Socs = listOf(
                // Snapdragon 8 Gen 2/3/Elite
                "sm8550", "sm8650", "sm8750",
                // Dimensity 9200/9300/9400
                "mt6985", "mt6989", "mt6991"
            )

            for (soc in ufs4Socs) {
                if (socModel.contains(soc) || hardware.contains(soc)) {
                    return "4.x (inferred from SoC)"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking SoC model", e)
        }

        return "" // Return empty if truly unknown - don't guess from RAM
    }

    fun getResolvedRamGbText(context: Context): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val totalGb = (memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0))
            val availGb = (memoryInfo.availMem / (1024.0 * 1024.0 * 1024.0))
            val usedGb = totalGb - availGb
            "${String.format("%.1f", usedGb)} / ${String.format("%.1f", totalGb)} GB"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to format RAM text", e)
            "-- / -- GB"
        }
    }

    /**
     * Measure actual FPS using Choreographer frame callbacks.
     * Call startFpsTracking() before game, then getCurrentFps() during gameplay.
     */
    class FpsTracker {
        private var frameCount = 0
        private var lastFpsTime = 0L
        private var currentFps = 0
        private var frameCallback: Choreographer.FrameCallback? = null

        fun startTracking() {
            frameCount = 0
            lastFpsTime = SystemClock.elapsedRealtime()
            currentFps = 0

            frameCallback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    frameCount++
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - lastFpsTime
                    if (elapsed >= 1000) {
                        currentFps = (frameCount * 1000 / elapsed.toInt()).coerceAtLeast(0)
                        frameCount = 0
                        lastFpsTime = now
                    }
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(frameCallback!!)
        }

        fun stopTracking() {
            frameCallback?.let {
                Choreographer.getInstance().removeFrameCallback(it)
            }
            frameCallback = null
        }

        fun getCurrentFps(): Int = currentFps
    }
}
