package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.BoostLog
import com.example.data.BoosterRepository
import com.example.util.ShizukuManager
import com.example.util.ShizukuState
import com.example.util.SystemMetrics
import kotlinx.coroutines.*

class GameBoosterService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var statsJob: Job? = null

    private lateinit var windowManager: WindowManager
    private var floatingView: TextView? = null

    // FIX C-01 cascade: renamed to avoid shadowing companion property
    private var overlayVisible = false

    private var targetPackageName: String? = null
    private var targetGameName: String? = null
    private var targetCustomFps: Int = 60
    private var targetProfile: String = "balanced"
    private var targetBypassThermal: Boolean = false

    // FIX C-01 / H-03: @Volatile for cross-thread access on Dispatchers.IO
    @Volatile private var isGameActive = false
    @Volatile private var lastButlerPhase = ""
    @Volatile private var butlerStatus = "Ready"

    // FIX H-01: currentFps now comes from getGameFpsViaGfxInfo (cross-process), not Choreographer
    private var currentFps = -1

    private val backupReadAheadKbList = mutableMapOf<String, String>()

    companion object {
        const val CHANNEL_ID = "game_speed_boost_channel"
        const val NOTIFICATION_ID = 4529
        const val ACTION_START = "ACTION_START_BOOSTER_SERVICE"
        const val ACTION_STOP = "ACTION_STOP_BOOSTER_SERVICE"
        const val ACTION_TOGGLE_OVERLAY = "ACTION_TOGGLE_OVERLAY"
        const val ACTION_LAUNCH_GAME = "ACTION_LAUNCH_GAME"

        const val EXTRA_GAME_NAME = "EXTRA_GAME_NAME"
        const val EXTRA_PACKAGE_NAME = "EXTRA_PACKAGE_NAME"
        const val EXTRA_CUSTOM_FPS = "EXTRA_CUSTOM_FPS"
        const val EXTRA_PERFORMANCE_PROFILE = "EXTRA_PERFORMANCE_PROFILE"
        const val EXTRA_BYPASS_THERMAL = "EXTRA_BYPASS_THERMAL"

        @Volatile var isServiceRunning = false
        // FIX H-03: Externally readable overlay state for ViewModel.checkStates() sync
        @Volatile var isOverlayActive = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "GameBoosterService created")
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        SystemMetrics.resetCpuCache()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "Action: $action")

        when (action) {
            ACTION_START -> {
                isServiceRunning = true
                startForegroundServiceCompat()
                startStatsPollingLoop()
                applyUfsTweaks()
            }
            ACTION_LAUNCH_GAME -> {
                targetPackageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
                targetGameName    = intent?.getStringExtra(EXTRA_GAME_NAME)
                targetCustomFps   = intent?.getIntExtra(EXTRA_CUSTOM_FPS, 60) ?: 60
                targetProfile     = intent?.getStringExtra(EXTRA_PERFORMANCE_PROFILE) ?: "balanced"
                targetBypassThermal = intent?.getBooleanExtra(EXTRA_BYPASS_THERMAL, false) ?: false
                isGameActive  = true
                lastButlerPhase = ""
                butlerStatus  = "Launching: $targetGameName"

                isServiceRunning = true
                startForegroundServiceCompat()
                startStatsPollingLoop()
                applyUfsTweaks()
            }
            ACTION_STOP -> {
                shutdownService()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_OVERLAY -> toggleFloatingOverlay()
        }
        return START_STICKY
    }

    private fun shutdownService() {
        isServiceRunning = false
        isGameActive = false
        statsJob?.cancel()

        // FIX C-02: Shell cleanup off Main Thread; stop only after cleanup completes
        serviceScope.launch(Dispatchers.IO) {
            restoreUfsTweaks()
            resetSystemSettings()
            withContext(Dispatchers.Main) {
                removeFloatingView()
                stopSelf()
            }
        }
        // Cancel remaining scope after giving cleanup a window
        serviceScope.launch {
            delay(3000)
            serviceScope.cancel()
        }
    }

    private fun resetSystemSettings() {
        if (ShizukuManager.state.value != ShizukuState.AUTHORIZED) return
        try {
            ShizukuManager.executeShell("wm size reset")
            ShizukuManager.executeShell("wm density reset")
            ShizukuManager.executeShell("cmd power set-mode 0")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup reset", e)
        }
    }

    private fun startForegroundServiceCompat() {
        val notification = createStatsNotification("Game Speed Boost Active", "Monitoring system resources...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createStatsNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setColor(Color.parseColor("#00E676"))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Game Speed Boost Monitor", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Real-time system monitoring for gaming"
                enableLights(true); lightColor = Color.GREEN; setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun startStatsPollingLoop() {
        statsJob?.cancel()

        statsJob = serviceScope.launch(Dispatchers.IO) {
            val database   = AppDatabase.getDatabase(this@GameBoosterService)
            val repository = BoosterRepository(database.boosterDao())
            delay(500)

            while (isActive) {
                try {
                    val ramPercent   = SystemMetrics.getRamUsagePercent(this@GameBoosterService)
                    val batteryTemp  = SystemMetrics.getBatteryTempCelsius(this@GameBoosterService)
                    val cpuLoad      = SystemMetrics.getCpuLoadPercent()

                    val pkgName = targetPackageName
                    if (pkgName != null && isGameActive) {
                        // FIX H-01: Real cross-process FPS via dumpsys gfxinfo
                        val gameFps = ShizukuManager.getGameFpsViaGfxInfo(pkgName)
                        if (gameFps >= 0) currentFps = gameFps

                        val isAppRunning = ShizukuManager.isAppRunning(this@GameBoosterService, pkgName)
                                || ShizukuManager.getAppPid(pkgName) > 0

                        if (isAppRunning) {
                            handleGameActive(pkgName, cpuLoad, ramPercent, batteryTemp, repository)
                        } else {
                            handleGameClosed(repository)
                        }
                    }

                    updateNotification(cpuLoad, ramPercent, batteryTemp)
                    updateOverlay(cpuLoad, ramPercent)

                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in stats loop", e)
                }
                delay(2000)
            }
        }
    }

    // FIX C-01: Added `suspend` — was calling suspend fun repository.insertLog from non-suspend
    private suspend fun handleGameActive(
        packageName: String,
        cpuLoad: Int,
        ramPercent: Int,
        batteryTemp: Int,
        repository: BoosterRepository
    ) {
        val pid = ShizukuManager.getAppPid(packageName)

        when {
            batteryTemp >= 45 -> {
                if (lastButlerPhase != "thermal_emergency") {
                    lastButlerPhase = "thermal_emergency"
                    executeButlerCommand("cmd power set-mode 0", "Power saving mode")
                    if (pid > 0) executeButlerCommand("renice -n 0 -p $pid", "Reset priority")
                    repository.insertLog(BoostLog(
                        actionName = "⚠️ Thermal Emergency",
                        details = "Battery temp reached ${batteryTemp}°C. Activating emergency cooling."
                    ))
                }
                butlerStatus = "⚠️ Thermal limit: ${batteryTemp}°C"
            }
            batteryTemp >= 40 -> {
                if (lastButlerPhase != "balanced") {
                    lastButlerPhase = "balanced"
                    if (pid > 0) executeButlerCommand("renice -n -5 -p $pid", "Elevated priority")
                    repository.insertLog(BoostLog(
                        actionName = "⚖️ Balanced Mode",
                        details = "Battery temp ${batteryTemp}°C. Applied balanced profile."
                    ))
                }
                butlerStatus = "⚖️ Balanced: ${batteryTemp}°C"
            }
            else -> {
                if (lastButlerPhase != "performance") {
                    lastButlerPhase = "performance"
                    executeButlerCommand("cmd power set-mode 1", "Performance mode")
                    if (pid > 0) executeButlerCommand("renice -n -10 -p $pid", "High priority")
                    repository.insertLog(BoostLog(
                        actionName = "🚀 Performance Mode",
                        details = "Battery temp ${batteryTemp}°C. Applied performance profile."
                    ))
                }
                butlerStatus = "🚀 Performance: ${batteryTemp}°C"
            }
        }
    }

    // FIX C-01: Added `suspend`
    private suspend fun handleGameClosed(repository: BoosterRepository) {
        isGameActive    = false
        lastButlerPhase = ""
        butlerStatus    = "Game closed - resetting"

        restoreUfsTweaks()
        resetSystemSettings()

        repository.insertLog(BoostLog(
            actionName = "🧼 Game Session Ended",
            details = "Game ($targetGameName) closed. All system tweaks have been reverted to defaults."
        ))

        targetPackageName = null
        targetGameName    = null
    }

    private fun executeButlerCommand(command: String, description: String) {
        if (ShizukuManager.state.value != ShizukuState.AUTHORIZED) return
        val result = ShizukuManager.executeShell(command)
        Log.d(TAG, "Butler [$description]: success=${result.isSuccess}, output=${result.output.take(100)}")
    }

    private fun updateNotification(cpuLoad: Int, ramPercent: Int, batteryTemp: Int) {
        try {
            val title = if (isGameActive && targetGameName != null) "🎮 Monitoring: $targetGameName"
            else "🎮 Game Speed Boost Active"

            val fpsStr  = if (currentFps >= 0) "FPS: ${currentFps} | " else ""
            val tempStr = if (batteryTemp > 0) "Temp: ${batteryTemp}°C" else ""
            val cpuStr  = if (cpuLoad >= 0) "CPU: ${cpuLoad}%" else "CPU: --"
            val ramStr  = if (ramPercent >= 0) "RAM: ${ramPercent}%" else "RAM: --"
            val content = "$fpsStr$cpuStr | $ramStr | $tempStr${if (isGameActive) " | $butlerStatus" else ""}"

            val notification = createStatsNotification(title, content)
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    private fun updateOverlay(cpuLoad: Int, ramPercent: Int) {
        if (!overlayVisible || floatingView == null) return
        try {
            val stealthPrefs = getSharedPreferences("gaming_booster_tweaks_prefs", Context.MODE_PRIVATE)
            val isStealthMode = stealthPrefs.getBoolean("stealth_overlay", false)

            serviceScope.launch(Dispatchers.Main) {
                if (isGameActive && isStealthMode && targetGameName != null) {
                    floatingView?.visibility = android.view.View.GONE
                } else {
                    floatingView?.visibility = android.view.View.VISIBLE
                    val fpsDisplay = if (currentFps >= 0) "${currentFps} FPS • " else ""
                    val text = if (isGameActive && targetGameName != null) {
                        "⚡ $targetGameName • ${fpsDisplay}CPU ${if (cpuLoad >= 0) cpuLoad else "--"}% • RAM ${if (ramPercent >= 0) ramPercent else "--"}%"
                    } else {
                        "🟢 Game Speed Boost • CPU ${if (cpuLoad >= 0) cpuLoad else "--"}% • RAM ${if (ramPercent >= 0) ramPercent else "--"}%"
                    }
                    floatingView?.text = text
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating overlay", e)
        }
    }

    private fun toggleFloatingOverlay() {
        if (overlayVisible) removeFloatingView() else showFloatingView()
    }

    private fun showFloatingView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission not granted"); return
        }
        try {
            removeFloatingView()
            val density = resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val metrics = resources.displayMetrics
                x = (metrics.widthPixels - (260 * density).toInt()) / 2
                y = (32 * density).toInt()
            }

            val capsuleBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(Color.parseColor("#E60B0E14"))
                setStroke((1 * density).toInt(), Color.parseColor("#8000E676"))
            }

            val overlayView = TextView(this).apply {
                id = android.R.id.text1
                setTextColor(Color.parseColor("#00E676"))
                background = capsuleBg
                setPadding((14 * density).toInt(), (5 * density).toInt(),
                    (14 * density).toInt(), (5 * density).toInt())
                textSize = 9.5f
                typeface = android.graphics.Typeface.MONOSPACE
                text = "🟢 Game Speed Boost • Starting..."
                gravity = Gravity.CENTER
            }

            var initialX = 0; var initialY = 0
            var initialTouchX = 0f; var initialTouchY = 0f
            overlayView.setOnTouchListener { view, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY; true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try { windowManager.updateViewLayout(view, params) }
                        catch (e: Exception) { Log.e(TAG, "Error updating overlay position", e) }
                        true
                    }
                    else -> false
                }
            }

            floatingView = overlayView
            windowManager.addView(overlayView, params)
            overlayVisible   = true
            isOverlayActive  = true  // FIX H-03: sync companion state
            Log.d(TAG, "Floating overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating overlay", e)
        }
    }

    private fun removeFloatingView() {
        if (overlayVisible && floatingView != null) {
            try {
                floatingView?.setOnTouchListener(null)
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
            floatingView     = null
            overlayVisible   = false
            isOverlayActive  = false  // FIX H-03: sync companion state
        }
    }

    private fun applyUfsTweaks() {
        if (ShizukuManager.state.value != ShizukuState.AUTHORIZED) return
        val version = SystemMetrics.detectUfsVersion(this)
        val kbValue = when {
            version.startsWith("4.") -> "16384"
            version.startsWith("3.") -> "8192"
            version.startsWith("2.") -> "4096"
            else -> return
        }
        val blockDevices = listOf("sda", "sdb", "sdc", "sdd", "sde", "sdf", "dm-0")
        if (backupReadAheadKbList.isEmpty()) {
            for (dev in blockDevices) {
                val path = "/sys/block/$dev/queue/read_ahead_kb"
                try {
                    val readRes = ShizukuManager.executeShell("cat $path")
                    if (readRes.isSuccess && readRes.output.isNotBlank()) {
                        val v = readRes.output.trim()
                        if (v.all { it.isDigit() }) backupReadAheadKbList[path] = v
                    }
                } catch (_: Exception) {}
            }
        }
        for (dev in blockDevices) {
            try { ShizukuManager.executeShell("echo $kbValue > /sys/block/$dev/queue/read_ahead_kb") }
            catch (_: Exception) {}
        }
    }

    private fun restoreUfsTweaks() {
        if (ShizukuManager.state.value != ShizukuState.AUTHORIZED) return
        for ((path, oldVal) in backupReadAheadKbList) {
            try { ShizukuManager.executeShell("echo $oldVal > $path") }
            catch (e: Exception) { Log.e(TAG, "Error restoring $path", e) }
        }
        backupReadAheadKbList.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "GameBoosterService destroyed")

        isServiceRunning = false
        isGameActive     = false
        isOverlayActive  = false
        statsJob?.cancel()

        // FIX C-02: Don't block Main Thread — remove UI immediately, cleanup IO async
        removeFloatingView()
        serviceScope.cancel()

        // Detached IO coroutine — survives serviceScope cancellation
        @OptIn(DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            restoreUfsTweaks()
            resetSystemSettings()
        }
    }

    private val TAG = "GameBoosterService"
}
