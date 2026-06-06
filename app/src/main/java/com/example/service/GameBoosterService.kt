package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.BoostLog
import com.example.data.BoosterRepository
import com.example.engine.GameAdaptiveEngine
import com.example.engine.StateBackupManager
import com.example.util.DeviceProfiler
import com.example.util.ShizukuManager
import com.example.util.ShizukuState
import com.example.util.SystemMetrics
import kotlinx.coroutines.*

class GameBoosterService : Service() {

    // ── FIX auto-close: CoroutineExceptionHandler prevents crashes from
    //    killing the service; SupervisorJob isolates failures per-child ────────
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine error (service continues): ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + exceptionHandler
    )
    private var statsJob   : Job? = null
    private var engineJob  : Job? = null

    // ── DB / Repo — class-level lazy (not recreated per loop restart) ─────────
    private val database   by lazy { AppDatabase.getDatabase(this) }
    private val repository by lazy { BoosterRepository(database.boosterDao()) }

    // ── Hardware + Engine ─────────────────────────────────────────────────────
    private lateinit var deviceProfile : DeviceProfiler.DeviceProfile
    private var adaptiveEngine         : GameAdaptiveEngine? = null

    // ── Overlay ───────────────────────────────────────────────────────────────
    private lateinit var windowManager : WindowManager
    private var floatingView           : TextView? = null
    private var overlayVisible         = false

    // ── Game session state ────────────────────────────────────────────────────
    @Volatile private var targetPackageName  : String? = null
    @Volatile private var targetGameName     : String? = null
    @Volatile private var targetCustomFps    : Int     = 60
    @Volatile private var targetProfile      : String  = "balanced"
    @Volatile private var targetBypassThermal: Boolean = false
    @Volatile private var isGameActive       : Boolean = false
    @Volatile private var butlerStatus       : String  = "Sẵn sàng"
    private var currentFps                   : Int     = -1

    private val backupReadAheadKb = mutableMapOf<String, String>()

    companion object {
        const val CHANNEL_ID         = "game_speed_boost_channel"
        const val NOTIFICATION_ID    = 4529
        const val ACTION_START       = "ACTION_START_BOOSTER_SERVICE"
        const val ACTION_STOP        = "ACTION_STOP_BOOSTER_SERVICE"
        const val ACTION_TOGGLE_OVERLAY = "ACTION_TOGGLE_OVERLAY"
        const val ACTION_LAUNCH_GAME = "ACTION_LAUNCH_GAME"

        const val EXTRA_GAME_NAME           = "EXTRA_GAME_NAME"
        const val EXTRA_PACKAGE_NAME        = "EXTRA_PACKAGE_NAME"
        const val EXTRA_CUSTOM_FPS          = "EXTRA_CUSTOM_FPS"
        const val EXTRA_PERFORMANCE_PROFILE = "EXTRA_PERFORMANCE_PROFILE"
        const val EXTRA_BYPASS_THERMAL      = "EXTRA_BYPASS_THERMAL"

        @Volatile var isServiceRunning = false
        @Volatile var isOverlayActive  = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager  = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        deviceProfile  = DeviceProfiler.getProfile(this)
        adaptiveEngine = GameAdaptiveEngine(this, deviceProfile)
        SystemMetrics.resetCpuCache()
        Log.i(TAG, "Service created — SoC: ${deviceProfile.socModel}, GPU: ${deviceProfile.gpuModel}, Cores: ${deviceProfile.coreTopology.totalCores}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                isServiceRunning = true
                startForegroundCompat()
                startStatsLoop()
                applyUfsTweaks()
                notifyUser("🟢 Game Speed Boost đang chạy", "Giám sát hệ thống đã bật")
            }
            ACTION_LAUNCH_GAME -> {
                val pkg     = intent?.getStringExtra(EXTRA_PACKAGE_NAME)   ?: return START_STICKY
                val name    = intent.getStringExtra(EXTRA_GAME_NAME)       ?: pkg
                targetPackageName   = pkg
                targetGameName      = name
                targetCustomFps     = intent.getIntExtra(EXTRA_CUSTOM_FPS, 60)
                targetProfile       = intent.getStringExtra(EXTRA_PERFORMANCE_PROFILE) ?: "balanced"
                targetBypassThermal = intent.getBooleanExtra(EXTRA_BYPASS_THERMAL, false)
                isGameActive        = true
                butlerStatus        = "Đang khởi động: $name"

                if (!isServiceRunning) {
                    isServiceRunning = true
                    startForegroundCompat()
                    startStatsLoop()
                    applyUfsTweaks()
                }
                // Capture system state BEFORE first tweak (StateBackupManager)
                StateBackupManager.captureIfAbsent()
                startEngineLoop(pkg, name)
                notifyUser("🎮 Đã phát hiện: $name", "Thuật toán thích nghi đã kích hoạt")
            }
            ACTION_STOP       -> { gracefulStop(); return START_NOT_STICKY }
            ACTION_TOGGLE_OVERLAY -> toggleOverlay()
        }
        return START_STICKY
    }

    // ── Stats polling loop (basic metrics, always running) ────────────────────

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch(Dispatchers.IO) {
            delay(600)
            while (isActive) {
                try {
                    val cpu  = SystemMetrics.getCpuLoadPercent()
                    val ram  = SystemMetrics.getRamUsagePercent(this@GameBoosterService)
                    val temp = SystemMetrics.getBatteryTempCelsius(this@GameBoosterService)
                    val fps  = targetPackageName?.let {
                        ShizukuManager.getGameFpsViaGfxInfo(it).takeIf { f -> f >= 0 } ?: currentFps
                    } ?: -1
                    currentFps = fps

                    updateNotification(cpu, ram, temp, fps)
                    updateOverlay(cpu, ram, fps)
                } catch (e: CancellationException) { break }
                  catch (e: Exception) { Log.w(TAG, "Stats loop error: ${e.message}") }
                delay(2000)
            }
        }
    }

    // ── Adaptive engine loop (game-specific, per session) ────────────────────

    private fun startEngineLoop(packageName: String, gameName: String) {
        engineJob?.cancel()
        engineJob = serviceScope.launch(Dispatchers.IO) {
            val engine = adaptiveEngine ?: return@launch
            delay(1500) // give game process time to start

            var lastLoad = GameAdaptiveEngine.GameLoad.IDLE
            var gameMissingCycles = 0

            while (isActive && isGameActive) {
                try {
                    val pid = DeviceProfiler.findPidFromProcFs(packageName)
                        .takeIf { it > 0 }
                        ?: ShizukuManager.getAppPid(packageName)

                    if (pid <= 0) {
                        gameMissingCycles++
                        if (gameMissingCycles >= 5) {
                            // Game closed — restore and stop
                            handleGameEnded(packageName, gameName)
                            break
                        }
                    } else {
                        gameMissingCycles = 0
                        val state = engine.tick(packageName)
                        butlerStatus = state.statusLine

                        // Notify user on significant load transitions
                        if (state.load != lastLoad) {
                            val significant = lastLoad == GameAdaptiveEngine.GameLoad.IDLE ||
                                state.load == GameAdaptiveEngine.GameLoad.CRITICAL ||
                                (lastLoad.ordinal - state.load.ordinal).let { kotlin.math.abs(it) } >= 2
                            if (significant) {
                                notifyUser(
                                    "${state.load.emoji} ${state.load.label}",
                                    state.statusLine
                                )
                            }
                            lastLoad = state.load
                        }
                    }
                } catch (e: CancellationException) { break }
                  catch (e: Exception) { Log.w(TAG, "Engine tick error: ${e.message}") }
                delay(500)
            }
        }
    }

    private suspend fun handleGameEnded(pkg: String, name: String) {
        Log.i(TAG, "Game ended: $name")
        isGameActive = false
        butlerStatus = "Game đã đóng — đang khôi phục hệ thống"

        // Restore SYSTEM tweaks (not user prefs)
        withContext(Dispatchers.IO) {
            adaptiveEngine?.onGameEnd()
            StateBackupManager.restoreSystemTweaks()
            restoreUfsTweaks()
        }

        repository.insertLog(BoostLog(
            actionName = "🧼 Kết thúc phiên: $name",
            details    = "Tất cả tham số hệ thống đã được khôi phục về trạng thái trước khi chơi"
        ))

        notifyUser("✅ $name đã đóng", "Hệ thống khôi phục hoàn tất")

        targetPackageName = null
        targetGameName    = null
    }

    // ── Graceful stop (user-initiated only) ───────────────────────────────────

    private fun gracefulStop() {
        isServiceRunning = false
        isGameActive     = false
        isOverlayActive  = false
        engineJob?.cancel()
        statsJob?.cancel()
        removeFloatingView()

        serviceScope.launch(Dispatchers.IO) {
            try {
                adaptiveEngine?.onGameEnd()
                StateBackupManager.restoreSystemTweaks()
                restoreUfsTweaks()
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup error: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        isGameActive     = false
        isOverlayActive  = false
        engineJob?.cancel()
        statsJob?.cancel()
        removeFloatingView()
        // FIX: don't cancel serviceScope here — let cleanup coroutine finish
        // then let it naturally complete
        @OptIn(DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                adaptiveEngine?.onGameEnd()
                StateBackupManager.restoreSystemTweaks()
                restoreUfsTweaks()
            } catch (_: Exception) {}
        }
        serviceScope.cancel()
    }

    // ── UFS tweaks ────────────────────────────────────────────────────────────

    private fun applyUfsTweaks() {
        if (ShizukuManager.state.value != ShizukuState.AUTHORIZED) return
        val version = SystemMetrics.detectUfsVersion(this)
        val kbValue = when { version.startsWith("4.") -> "16384"; version.startsWith("3.") -> "8192"; version.startsWith("2.") -> "4096"; else -> return }
        val devs    = listOf("sda","sdb","sdc","sdd","sde","sdf","dm-0")
        if (backupReadAheadKb.isEmpty()) {
            devs.forEach { dev ->
                val p = "/sys/block/$dev/queue/read_ahead_kb"
                try {
                    val v = ShizukuManager.executeShell("cat $p").output.trim()
                    if (v.isNotBlank() && v.all { it.isDigit() }) backupReadAheadKb[p] = v
                } catch (_: Exception) {}
            }
        }
        devs.forEach { dev ->
            try { ShizukuManager.executeShell("echo $kbValue > /sys/block/$dev/queue/read_ahead_kb") }
            catch (_: Exception) {}
        }
    }

    private fun restoreUfsTweaks() {
        if (ShizukuManager.state.value != ShizukuState.AUTHORIZED) return
        backupReadAheadKb.forEach { (path, old) ->
            try { ShizukuManager.executeShell("echo $old > $path") } catch (_: Exception) {}
        }
        backupReadAheadKb.clear()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startForegroundCompat() {
        val n = buildNotification("Game Speed Boost", "Dịch vụ đang hoạt động")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIFICATION_ID, n)
    }

    private fun notifyUser(title: String, body: String) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(title, body))
            } catch (_: Exception) {}
        }
    }

    private fun updateNotification(cpu: Int, ram: Int, temp: Int, fps: Int) {
        val title = if (isGameActive && targetGameName != null)
            "🎮 ${targetGameName}" else "🟢 Game Speed Boost"
        val fpsStr  = if (fps >= 0) "FPS:$fps " else ""
        val cpuStr  = if (cpu >= 0) "CPU:${cpu}%" else "CPU:--"
        val ramStr  = if (ram >= 0) "RAM:${ram}%" else "RAM:--"
        val tempStr = if (temp > 0) " ${temp}°C" else ""
        val butler  = if (isGameActive) " • $butlerStatus" else ""
        serviceScope.launch(Dispatchers.Main) {
            try {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(title, "$fpsStr$cpuStr $ramStr$tempStr$butler"))
            } catch (_: Exception) {}
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setColor(Color.parseColor("#00E676")).setOngoing(true)
            .setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Game Speed Boost", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Giám sát và tối ưu hiệu năng game"
                enableLights(true); lightColor = Color.GREEN; setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun toggleOverlay() { if (overlayVisible) removeFloatingView() else showFloatingView() }

    private fun showFloatingView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) return
        try {
            removeFloatingView()
            val d = resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val m = resources.displayMetrics
                x = (m.widthPixels - (260 * d).toInt()) / 2
                y = (32 * d).toInt()
            }
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24 * d
                setColor(Color.parseColor("#E60B0E14"))
                setStroke((1 * d).toInt(), Color.parseColor("#8000E676"))
            }
            val tv = TextView(this).apply {
                setTextColor(Color.parseColor("#00E676"))
                background = bg
                setPadding((14*d).toInt(), (5*d).toInt(), (14*d).toInt(), (5*d).toInt())
                textSize = 9.5f
                typeface = android.graphics.Typeface.MONOSPACE
                text = "⚡ Game Speed Boost"
                gravity = Gravity.CENTER
            }
            var ix=0; var iy=0; var tx=0f; var ty=0f
            tv.setOnTouchListener { v, e ->
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { ix=params.x; iy=params.y; tx=e.rawX; ty=e.rawY; true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = ix + (e.rawX-tx).toInt()
                        params.y = iy + (e.rawY-ty).toInt()
                        try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
                        true
                    }
                    else -> false
                }
            }
            floatingView = tv
            windowManager.addView(tv, params)
            overlayVisible  = true
            isOverlayActive = true
        } catch (e: Exception) { Log.e(TAG, "Overlay error: ${e.message}") }
    }

    private fun removeFloatingView() {
        if (!overlayVisible || floatingView == null) return
        try {
            floatingView?.setOnTouchListener(null)
            windowManager.removeView(floatingView)
        } catch (_: Exception) {}
        floatingView    = null
        overlayVisible  = false
        isOverlayActive = false
    }

    private fun updateOverlay(cpu: Int, ram: Int, fps: Int) {
        if (!overlayVisible || floatingView == null) return
        serviceScope.launch(Dispatchers.Main) {
            val fStr = if (fps >= 0) "${fps}FPS • " else ""
            val cStr = if (cpu >= 0) "CPU${cpu}%" else "--"
            val rStr = if (ram >= 0) " RAM${ram}%" else ""
            val load = adaptiveEngine?.state?.value?.load
            val lStr = load?.emoji ?: "⚡"
            floatingView?.text = "$lStr $fStr$cStr$rStr"
        }
    }

    private val TAG = "GameBoosterService"
}
