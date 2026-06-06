package com.example.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.BoostLog
import com.example.data.BoosterRepository
import com.example.data.UserGame
import com.example.engine.GameAdaptiveEngine
import com.example.service.GameBoosterService
import com.example.util.ShizukuManager
import com.example.util.ShizukuState
import com.example.util.SystemMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SystemTelemetry(
    val cpuLoad: Int = -1,
    val ramUsage: Int = -1,
    val batteryTemp: Int = -1,
    val resolvedRamGb: String = "-- / -- GB"
)

data class InstalledAppInfo(
    val appName: String,
    val packageName: String
)

data class BoostResult(
    val killedApps: List<String>,
    val freedCacheDescription: String,
    val ramBeforeMb: Long,
    val ramAfterMb: Long
)

class BoosterViewModel(
    application: Application,
    private val repository: BoosterRepository
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext

    private val _telemetry = MutableStateFlow(SystemTelemetry())
    val telemetry = _telemetry.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps = _installedApps.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning = _isServiceRunning.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive = _isOverlayActive.asStateFlow()

    private val _shizukuState = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val shizukuState = _shizukuState.asStateFlow()

    private val _ufsVersion = MutableStateFlow("")
    val ufsVersionState = _ufsVersion.asStateFlow()

    // Engine state (null when no game is active)
    val engineState: kotlinx.coroutines.flow.StateFlow<GameAdaptiveEngine.EngineState?>?
        get() = null  // Read from service indirectly via notification/overlay

    // FIX H-04: SharedFlow to surface silent errors back to UI
    private val _addGameError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val addGameError: SharedFlow<String> = _addGameError.asSharedFlow()

    private val exemptedPrefs = context.getSharedPreferences(
        "gaming_booster_exempted_apps_prefs",
        Context.MODE_PRIVATE
    )
    private val _exemptedApps = MutableStateFlow<Set<String>>(emptySet())
    val exemptedApps = _exemptedApps.asStateFlow()

    val addedGames: StateFlow<List<UserGame>> = repository.allGames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentLogs: StateFlow<List<BoostLog>> = repository.recentLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isBoosting = MutableStateFlow(false)
    val isBoosting = _isBoosting.asStateFlow()

    val consoleLogs = ShizukuManager.consoleLogs

    init {
        viewModelScope.launch {
            ShizukuManager.state.collect { state ->
                _shizukuState.value = state
            }
        }
        checkStates()
        startTelemetryLoop()
        loadExemptedApps()
        loadInstalledApps()
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                // queryIntentActivities(MAIN/LAUNCHER) is what Android launchers use.
                // It is far more reliable than getInstalledPackages on Android 14-16
                // because it resolves through the intent system which respects QUERY_ALL_PACKAGES
                // permission correctly, unlike getLaunchIntentForPackage which can return null.
                try {
                    val pm     = context.packageManager
                    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    }
                    val activities = if (android.os.Build.VERSION.SDK_INT >= 33) {
                        pm.queryIntentActivities(
                            intent,
                            android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        pm.queryIntentActivities(intent, 0)
                    }
                    Log.d(TAG, "queryIntentActivities returned \${activities.size} launcher apps")
                    activities.mapNotNull { info ->
                        val ai  = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                        val pkg = ai.packageName
                        if (pkg == context.packageName) return@mapNotNull null
                        val name = try { pm.getApplicationLabel(ai).toString().trim() }
                                   catch (_: Exception) { return@mapNotNull null }
                        if (name.isEmpty()) null else InstalledAppInfo(name, pkg)
                    }.distinctBy { it.packageName }
                     .sortedWith(compareBy { it.appName.lowercase() })
                } catch (e: Exception) {
                    Log.e(TAG, "queryIntentActivities failed: \${e.message}")
                    emptyList()
                }
            }
        }
    }

        /** Maps a package to InstalledAppInfo, returns null if the package should be excluded. */
    private fun buildAppInfo(
        pm: android.content.pm.PackageManager,
        pkg: String,
        appInfo: android.content.pm.ApplicationInfo
    ): InstalledAppInfo? {
        // Skip this app itself
        if (pkg == context.packageName) return null

        // Skip pure pre-installed system apps (Settings, Phone, etc.)
        // Keep updated system apps (Google Play, YouTube, etc.) and user-installed apps
        val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem && !isUpdatedSystem) return null

        val name = try {
            pm.getApplicationLabel(appInfo).toString().trim()
        } catch (e: Exception) { return null }

        return if (name.isNotEmpty()) InstalledAppInfo(name, pkg) else null
    }

    private fun loadExemptedApps() {
        val saved = exemptedPrefs.getStringSet("exempted_packages", null) ?: emptySet()
        _exemptedApps.value = saved
    }

    fun toggleExemptedApp(packageName: String) {
        val current = _exemptedApps.value.toMutableSet()
        val added = if (current.contains(packageName)) {
            current.remove(packageName); false
        } else {
            current.add(packageName); true
        }
        exemptedPrefs.edit().putStringSet("exempted_packages", current).apply()
        _exemptedApps.value = current

        viewModelScope.launch {
            repository.insertLog(
                BoostLog(
                    actionName = if (added) "🛡️ Added exemption" else "🛡️ Removed exemption",
                    details = "$packageName will ${if (added) "be protected" else "no longer be protected"} during cleanup"
                )
            )
        }
    }

    fun checkStates() {
        _isServiceRunning.value = GameBoosterService.isServiceRunning
        // FIX H-03: Sync overlay state from service companion (not guessed from local toggle)
        _isOverlayActive.value = GameBoosterService.isOverlayActive
        ShizukuManager.updateShizukuState(context)
        _shizukuState.value = ShizukuManager.state.value

        viewModelScope.launch {
            _ufsVersion.value = withContext(Dispatchers.IO) {
                val ver = SystemMetrics.detectUfsVersion(context)
                when {
                    ver.startsWith("4.") -> "UFS 4.x (Ultra Fast)"
                    ver.startsWith("3.") -> "UFS 3.x (Fast)"
                    ver.startsWith("2.") -> "UFS 2.x (Standard)"
                    ver.isNotEmpty() -> "UFS $ver"
                    else -> "Unable to detect"
                }
            }
        }
    }

    private fun startTelemetryLoop() {
        viewModelScope.launch(Dispatchers.Default) {
            delay(500) // CPU cache warmup
            while (true) {
                try {
                    val ram = SystemMetrics.getRamUsagePercent(context)
                    val temp = SystemMetrics.getBatteryTempCelsius(context)
                    val cpu = SystemMetrics.getCpuLoadPercent()
                    val resolveText = SystemMetrics.getResolvedRamGbText(context)
                    _telemetry.value = SystemTelemetry(
                        cpuLoad = cpu, ramUsage = ram,
                        batteryTemp = temp, resolvedRamGb = resolveText
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Telemetry error", e)
                }
                delay(2500)
            }
        }
    }

    fun toggleBoosterService() {
        val nextState = !_isServiceRunning.value
        val intent = Intent(context, GameBoosterService::class.java).apply {
            action = if (nextState) GameBoosterService.ACTION_START else GameBoosterService.ACTION_STOP
        }
        try {
            if (nextState) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                viewModelScope.launch {
                    repository.insertLog(BoostLog(
                        actionName = "Start Monitor Service",
                        details = "Game optimization service started with real-time monitoring"
                    ))
                }
            } else {
                context.stopService(intent)
                _isOverlayActive.value = false
                viewModelScope.launch {
                    repository.insertLog(BoostLog(
                        actionName = "Stop Monitor Service",
                        details = "Game optimization service stopped. All tweaks reverted."
                    ))
                }
            }
            _isServiceRunning.value = nextState
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling service", e)
        }
    }

    fun toggleFloatingMeter() {
        if (!_isServiceRunning.value) toggleBoosterService()
        val intent = Intent(context, GameBoosterService::class.java).apply {
            action = GameBoosterService.ACTION_TOGGLE_OVERLAY
        }
        context.startService(intent)
        _isOverlayActive.value = !_isOverlayActive.value
    }

    fun requestShizukuAuthorization() {
        if (ShizukuManager.isShizukuAvailable()) {
            ShizukuManager.requestPermission(1001)
        } else {
            ShizukuManager.updateShizukuState(context)
            val currentState = ShizukuManager.state.value
            _shizukuState.value = currentState
            viewModelScope.launch {
                val detailMsg = when (currentState) {
                    ShizukuState.NOT_INSTALLED -> "Shizuku app not installed. Please install it from GitHub or Play Store."
                    ShizukuState.NOT_RUNNING -> "Shizuku service not running. Please start Shizuku via ADB or Wireless Debugging."
                    ShizukuState.UNAUTHORIZED -> "Shizuku permission not granted. Please allow this app in Shizuku."
                    else -> "Cannot connect to Shizuku. Please ensure it's running."
                }
                repository.insertLog(BoostLog(
                    actionName = "❌ Shizuku Connection Failed",
                    details = detailMsg
                ))
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, detailMsg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun quickBoost() {
        viewModelScope.launch {
            if (_isBoosting.value) return@launch
            _isBoosting.value = true

            val ramBeforeMb = SystemMetrics.getAvailableRamMb(context)
            val exempted = _exemptedApps.value

            repository.insertLog(BoostLog(
                actionName = "🔍 System Scan Started",
                details = "Analyzing cache usage and background processes..."
            ))

            val cacheResult = withContext(Dispatchers.IO) {
                ShizukuManager.executeShell("pm trim-caches 4096G")
            }
            delay(400)

            val killedApps = mutableListOf<String>()
            val pm = context.packageManager
            val killResults = withContext(Dispatchers.IO) {
                val runningApps = try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.runningAppProcesses?.map { it.processName } ?: emptyList()
                } catch (e: Exception) { emptyList() }

                val targets = _installedApps.value.filter { app ->
                    app.packageName != context.packageName &&
                            !exempted.contains(app.packageName) &&
                            runningApps.contains(app.packageName)
                }.filter { app ->
                    try {
                        val appInfo = pm.getApplicationInfo(app.packageName, 0)
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                                (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT) == 0
                    } catch (e: Exception) { false }
                }.take(15)

                for (app in targets) {
                    try {
                        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                        am.killBackgroundProcesses(app.packageName)
                        if (ShizukuManager.state.value == ShizukuState.AUTHORIZED) {
                            ShizukuManager.executeShell("am force-stop ${app.packageName}")
                        }
                        killedApps.add(app.appName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to kill ${app.packageName}", e)
                    }
                }
                killedApps
            }

            delay(500)
            val ramAfterMb = SystemMetrics.getAvailableRamMb(context)
            val freedMb = if (ramAfterMb > 0 && ramBeforeMb > 0) (ramAfterMb - ramBeforeMb).coerceAtLeast(0) else 0

            val detailsText = buildString {
                if (cacheResult.isSuccess) append("Cache trim executed. ")
                if (killResults.isNotEmpty()) {
                    append("Stopped ${killResults.size} background apps: ${killResults.take(5).joinToString(", ")}")
                    if (killResults.size > 5) append(" and ${killResults.size - 5} more")
                    append(". ")
                } else {
                    append("No safe-to-kill background apps found. ")
                }
                if (freedMb > 0) append("Available RAM increased by ~${freedMb}MB.")
                else append("System reallocated memory during cleanup (normal behavior).")
            }

            repository.insertLog(BoostLog(
                actionName = if (killResults.isNotEmpty()) "🚀 Cleanup Complete" else "🧹 Cache Trimmed",
                details = detailsText,
                clearedMemoryMb = freedMb.toInt()
            ))

            _isBoosting.value = false
            checkStates()
        }
    }

    /**
     * FIX C-03: executeShell is blocking I/O — must run on Dispatchers.IO, not Main.
     */
    fun executeShizukuTweak(tweakName: String, command: String, onResult: ((Boolean, String) -> Unit)? = null) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { ShizukuManager.executeShell(command) }
            repository.insertLog(BoostLog(
                actionName = tweakName,
                details = if (result.isSuccess) "Success (${result.executionTimeMs}ms): ${result.output.take(200)}"
                else "Failed: ${result.output.take(200)}"
            ))
            onResult?.invoke(result.isSuccess, result.output)
        }
    }

    /**
     * FIX H-04: Emit error to SharedFlow so UI can show Toast instead of silently failing.
     * FIX M-01: Added bypassThermal parameter so UI can actually configure it.
     */
    fun addGameToSpace(
        name: String,
        packageName: String,
        profile: String,
        fps: Int,
        bypassThermal: Boolean = false
    ) {
        viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) {
                try { context.packageManager.getPackageInfo(packageName, 0); true }
                catch (e: Exception) { false }
            }

            if (!exists) {
                _addGameError.tryEmit("⚠️ Package '$packageName' không được cài đặt trên thiết bị này")
                repository.insertLog(BoostLog(
                    actionName = "⚠️ Game Not Found",
                    details = "Package $packageName is not installed on this device"
                ))
                return@launch
            }

            repository.insertGame(UserGame(
                gameName = name,
                packageName = packageName,
                performanceProfile = profile,
                customFps = fps,
                bypassThermal = bypassThermal
            ))
            repository.insertLog(BoostLog(
                actionName = "Game Registered",
                details = "$name ($packageName) added with profile: $profile, FPS: $fps, bypassThermal: $bypassThermal"
            ))
        }
    }

    fun removeGame(game: UserGame) {
        viewModelScope.launch {
            repository.deleteGame(game)
            repository.insertLog(BoostLog(
                actionName = "Game Removed",
                details = "${game.gameName} removed from game list"
            ))
        }
    }

    fun clearLogViewer() {
        viewModelScope.launch { repository.clearLogHistory() }
    }

    fun clearTerminalConsole() {
        ShizukuManager.clearConsole()
    }

    companion object {
        private const val TAG = "BoosterViewModel"
    }
}

class BoosterViewModelFactory(
    private val application: Application,
    private val repository: BoosterRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BoosterViewModel::class.java)) {
            return BoosterViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
