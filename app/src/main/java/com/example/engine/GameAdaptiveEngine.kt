package com.example.engine

import android.content.Context
import android.util.Log
import com.example.util.DeviceProfiler
import com.example.util.ShizukuManager
import com.example.util.ShizukuState
import com.example.util.SystemMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList

/**
 * Two-tier adaptive performance engine.
 *
 * TIER 1 (no permissions) — reads /proc, kills background apps, wake lock
 * TIER 2 (Shizuku)       — gfxinfo parsing, governor, renice, CPU affinity
 *
 * NVIDIA-inspired: thermal headroom management, preemptive boost
 * Apple-inspired:  heterogeneous core awareness, QoS differentiation
 * Hysteresis:      instant upgrade, 4-cycle downgrade delay
 */
class GameAdaptiveEngine(
    private val context: Context,
    private val deviceProfile: DeviceProfiler.DeviceProfile
) {
    private val TAG = "GameAdaptiveEngine"

    enum class GameLoad(val label: String, val emoji: String) {
        IDLE    ("Menu / Idle",    "😴"),
        LIGHT   ("Scene nhẹ",     "✅"),
        MEDIUM  ("Scene bình thường", "⚡"),
        HEAVY   ("Combat nặng",   "🔥"),
        CRITICAL("Nguy cơ lag!",  "🚨")
    }

    data class EngineState(
        val load         : GameLoad = GameLoad.IDLE,
        val gameCpuPct   : Int      = -1,
        val gpuBusyPct   : Int      = -1,
        val jankPercent  : Float    = 0f,
        val p99FrameMs   : Int      = 0,
        val thermalCap   : GameLoad? = null,
        val isPredicting : Boolean  = false,
        val statusLine   : String   = "Chờ game khởi động..."
    )

    private val _state = MutableStateFlow(EngineState())
    val state = _state.asStateFlow()

    private var currentLoad      = GameLoad.IDLE
    private var downgradeCounter = 0
    private val loadHistory      = LinkedList<Float>()
    private var appliedTier      = GameLoad.IDLE
    private var lastPid          = -1

    // ── Main tick (called every 500ms from service) ───────────────────────────

    suspend fun tick(packageName: String): EngineState {
        val pid = resolvePid(packageName)
        val gameCpu  = DeviceProfiler.getProcessCpuPercent(pid)
        val sysCpu   = SystemMetrics.getCpuLoadPercent()
        val gpuBusy  = DeviceProfiler.getGpuBusyPercent()
        val gfx      = if (ShizukuManager.state.value == ShizukuState.AUTHORIZED)
            parseGfxInfo(packageName) else GfxMetrics()
        val battTemp = SystemMetrics.getBatteryTempCelsius(context)

        // Thermal cap (NVIDIA Boost Temp Limit inspired)
        val thermalCap = when {
            battTemp >= 45 -> GameLoad.IDLE
            battTemp >= 42 -> GameLoad.MEDIUM
            battTemp >= 38 -> GameLoad.HEAVY
            else           -> null
        }

        val rawScore = calcScore(gameCpu, sysCpu, gpuBusy, gfx)

        // Predictive boost: load rising for 3+ cycles → boost preemptively
        loadHistory.addLast(rawScore)
        if (loadHistory.size > 4) loadHistory.removeFirst()
        val rising = loadHistory.size >= 3 &&
            loadHistory.last() > loadHistory.first() + 8f &&
            loadHistory.zipWithNext().all { (a, b) -> b >= a - 5f }
        val effective = if (rising) rawScore * 1.2f else rawScore

        val raw     = classify(effective)
        val capped  = if (thermalCap != null && raw.ordinal > thermalCap.ordinal) thermalCap else raw
        val stable  = hysteresis(capped)

        if (stable != appliedTier || pid != lastPid) {
            applyOptimizations(stable, pid, packageName, battTemp)
            appliedTier = stable
            lastPid     = pid
        }

        return EngineState(
            load         = stable,
            gameCpuPct   = gameCpu,
            gpuBusyPct   = gpuBusy,
            jankPercent  = gfx.jankPercent,
            p99FrameMs   = gfx.p99Ms,
            thermalCap   = thermalCap,
            isPredicting = rising,
            statusLine   = buildStatus(stable, gameCpu, gpuBusy, gfx, battTemp, rising)
        ).also { _state.value = it }
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private fun calcScore(gameCpu: Int, sysCpu: Int, gpuBusy: Int, gfx: GfxMetrics): Float {
        return if (gfx.valid) {
            gfx.jankPercent * 0.5f +
            gameCpu.coerceAtLeast(0).toFloat() * 0.25f +
            (gfx.p99Ms / 16.67f * 10f).coerceAtMost(30f) * 0.25f
        } else {
            val cpu = if (gameCpu >= 0) gameCpu.toFloat() else sysCpu.coerceAtLeast(0).toFloat()
            val gpu = if (gpuBusy >= 0) gpuBusy.toFloat() * 0.25f else 0f
            cpu * 0.75f + gpu
        }
    }

    private fun classify(score: Float) = when {
        score < 12 -> GameLoad.IDLE
        score < 28 -> GameLoad.LIGHT
        score < 50 -> GameLoad.MEDIUM
        score < 70 -> GameLoad.HEAVY
        else       -> GameLoad.CRITICAL
    }

    private fun hysteresis(new: GameLoad): GameLoad {
        return when {
            new.ordinal > currentLoad.ordinal -> {
                downgradeCounter = 0; currentLoad = new; new
            }
            new.ordinal < currentLoad.ordinal -> {
                if (++downgradeCounter >= 4) {
                    downgradeCounter = 0; currentLoad = new; new
                } else currentLoad
            }
            else -> { downgradeCounter = 0; new }
        }
    }

    // ── Optimization tiers ────────────────────────────────────────────────────

    private fun applyOptimizations(load: GameLoad, pid: Int, pkg: String, temp: Int) {
        tier1NoPermission(load, pkg)
        if (ShizukuManager.state.value == ShizukuState.AUTHORIZED) tier2Shizuku(load, pid)
        Log.d(TAG, "Applied $load (pid=$pid, temp=${temp}°C)")
    }

    /** Works WITHOUT any special permissions. */
    private fun tier1NoPermission(load: GameLoad, targetPkg: String) {
        if (load.ordinal < GameLoad.MEDIUM.ordinal) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
            val killCount = when (load) {
                GameLoad.MEDIUM   -> 3
                GameLoad.HEAVY    -> 8
                GameLoad.CRITICAL -> 15
                else              -> 0
            }
            am.runningAppProcesses
                ?.filter { p ->
                    p.processName != targetPkg &&
                    p.processName != context.packageName &&
                    p.importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
                }
                ?.take(killCount)
                ?.forEach { am.killBackgroundProcesses(it.processName) }
        } catch (_: Exception) {}
    }

    /**
     * ADB-level optimizations via Shizuku.
     * ARM big.LITTLE aware — uses device topology detected by DeviceProfiler.
     */
    private fun tier2Shizuku(load: GameLoad, pid: Int) {
        val t = deviceProfile.coreTopology
        when (load) {
            GameLoad.IDLE -> {
                run("cmd power set-mode 0")
                if (pid > 0) { run("renice -n 0 -p $pid"); run("taskset -p ${t.allCoreMask} $pid") }
            }
            GameLoad.LIGHT -> {
                run("cmd power set-mode 1")
                if (pid > 0) run("renice -n -5 -p $pid")
            }
            GameLoad.MEDIUM -> {
                run("cmd power set-mode 1")
                if (pid > 0) {
                    run("renice -n -8 -p $pid")
                    val mask = if (t.midCores.isNotEmpty()) {
                        var m = 0; (t.bigCores + t.midCores).forEach { m = m or (1 shl it) }
                        m.toString(16)
                    } else t.bigCoreMask
                    run("taskset -p $mask $pid")
                }
            }
            GameLoad.HEAVY -> {
                run("cmd power set-mode 1")
                if (pid > 0) { run("renice -n -12 -p $pid"); run("taskset -p ${t.bigCoreMask} $pid") }
                run("pm trim-caches 512000000")
            }
            GameLoad.CRITICAL -> {
                run("cmd power set-mode 1")
                if (pid > 0) { run("renice -n -15 -p $pid"); run("taskset -p ${t.bigCoreMask} $pid") }
                run("pm trim-caches 1024000000")
                run("sysctl -w vm.dirty_expire_centisecs=500")
            }
        }
    }

    private fun run(cmd: String) { ShizukuManager.executeShell(cmd) }

    fun onGameEnd() {
        if (ShizukuManager.state.value == ShizukuState.AUTHORIZED) {
            run("cmd power set-mode 0")
            run("sysctl -w vm.dirty_expire_centisecs=3000")
        }
        reset()
    }

    private fun reset() {
        appliedTier = GameLoad.IDLE; lastPid = -1
        currentLoad = GameLoad.IDLE; downgradeCounter = 0; loadHistory.clear()
        _state.value = EngineState()
    }

    // ── PID resolution ────────────────────────────────────────────────────────

    private fun resolvePid(pkg: String): Int {
        // Try Shizuku pidof (fast, reliable)
        if (ShizukuManager.state.value == ShizukuState.AUTHORIZED) {
            val pid = ShizukuManager.getAppPid(pkg)
            if (pid > 0) return pid
        }
        // Fallback: scan /proc (no permissions needed)
        return DeviceProfiler.findPidFromProcFs(pkg)
    }

    // ── gfxinfo parsing ───────────────────────────────────────────────────────

    data class GfxMetrics(
        val valid: Boolean = false,
        val jankPercent: Float = 0f,
        val p99Ms: Int = 0,
        val totalFrames: Long = 0L
    )

    private fun parseGfxInfo(pkg: String): GfxMetrics {
        return try {
            val out = ShizukuManager.executeShell("dumpsys gfxinfo $pkg").output
            if (out.isBlank()) return GfxMetrics()
            val total = out.lineSequence()
                .firstOrNull { it.contains("Total frames rendered:") }
                ?.substringAfter(":")?.trim()?.toLongOrNull() ?: 0L
            val jank = out.lineSequence()
                .firstOrNull { it.contains("Janky frames:") }
                ?.substringAfter("Janky frames:")?.trim()
                ?.split(" ")?.firstOrNull()?.toLongOrNull() ?: 0L
            val jankPct = if (total > 0) (jank.toFloat() / total) * 100f else 0f
            val hist = out.lineSequence().firstOrNull { it.startsWith("HISTOGRAM:") }
            val p99  = parseP99(hist, total)
            GfxMetrics(total > 0, jankPct, p99, total)
        } catch (_: Exception) { GfxMetrics() }
    }

    private fun parseP99(histLine: String?, total: Long): Int {
        if (histLine == null || total == 0L) return 0
        return try {
            val buckets = Regex("(\\d+)ms=(\\d+)").findAll(histLine)
                .map { it.groupValues[1].toInt() to it.groupValues[2].toLong() }
                .sortedBy { it.first }
                .toList()
            val target = (total * 0.99).toLong()
            var cum = 0L
            for ((ms, cnt) in buckets) { cum += cnt; if (cum >= target) return ms }
            buckets.lastOrNull()?.first ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun buildStatus(
        load: GameLoad, cpu: Int, gpu: Int, gfx: GfxMetrics, temp: Int, pred: Boolean
    ): String {
        val parts = mutableListOf(load.emoji, load.label)
        if (cpu >= 0) parts += "CPU:${cpu}%"
        if (gpu >= 0) parts += "GPU:${gpu}%"
        if (gfx.valid && gfx.jankPercent > 0) parts += "Jank:${"%.1f".format(gfx.jankPercent)}%"
        if (temp > 0) parts += "${temp}°C"
        if (pred) parts += "⚡Pred"
        return parts.joinToString(" ")
    }
}
