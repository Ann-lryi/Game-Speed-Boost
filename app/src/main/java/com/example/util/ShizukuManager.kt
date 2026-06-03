package com.example.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    UNAUTHORIZED,
    AUTHORIZED,
    ADB_FALLBACK
}

data class ConsoleCommand(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val command: String,
    val output: String,
    val isSuccess: Boolean,
    val executionTimeMs: Long = 0
)

object ShizukuManager {
    private const val TAG = "ShizukuManager"
    const val SHIZUKU_PACKAGE_NAME = "rikka.shizuku"
    private const val SHELL_TIMEOUT_SECONDS = 15L
    private const val MAX_CONSOLE_LOGS = 100

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state = _state.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<ConsoleCommand>>(emptyList())
    val consoleLogs = _consoleLogs.asStateFlow()

    // Thread-safe log counter
    private val logCounter = AtomicInteger(0)

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky {
                updateStateFromBinder()
            }
            Shizuku.addBinderDeadListener {
                _state.value = ShizukuState.NOT_RUNNING
            }
            Shizuku.addRequestPermissionResultListener { _, grantResult ->
                _state.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    ShizukuState.AUTHORIZED
                } else {
                    ShizukuState.UNAUTHORIZED
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Shizuku observers", e)
        }
    }

    private fun updateStateFromBinder() {
        try {
            if (Shizuku.pingBinder()) {
                _state.value = if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    ShizukuState.AUTHORIZED
                } else {
                    ShizukuState.UNAUTHORIZED
                }
            } else {
                _state.value = ShizukuState.NOT_RUNNING
            }
        } catch (e: Throwable) {
            _state.value = ShizukuState.NOT_RUNNING
        }
    }

    fun updateShizukuState(context: Context) {
        // First check if binder is alive
        try {
            if (Shizuku.pingBinder()) {
                _state.value = if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    ShizukuState.AUTHORIZED
                } else {
                    ShizukuState.UNAUTHORIZED
                }
                return
            }
        } catch (e: Throwable) {
            Log.d(TAG, "pingBinder failed", e)
        }

        // Binder not alive - check if Shizuku app is installed
        val isInstalled = isPackageInstalled(context, SHIZUKU_PACKAGE_NAME)
        _state.value = if (!isInstalled) {
            ShizukuState.NOT_INSTALLED
        } else {
            ShizukuState.NOT_RUNNING
        }
    }

    /**
     * Check if a package is installed using multiple methods.
     * Returns FALSE if all methods fail - no defaulting to true.
     */
    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        // Method 1: Standard getPackageInfo
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            return true
        } catch (e: Exception) {
            Log.d(TAG, "getPackageInfo failed for $packageName: ${e.message}")
        }

        // Method 2: Query installed applications
        try {
            @Suppress("DEPRECATION")
            val apps = context.packageManager.getInstalledApplications(0)
            for (app in apps) {
                if (app.packageName == packageName) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "getInstalledApplications failed: ${e.message}")
        }

        // Method 3: Query launcher intent
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "getLaunchIntentForPackage failed: ${e.message}")
        }

        // Method 4: Try Shizuku permission intent
        try {
            val intent = android.content.Intent("rikka.shizuku.intent.action.REQUEST_PERMISSION")
            @Suppress("DEPRECATION")
            val list = context.packageManager.queryIntentActivities(intent, 0)
            if (list.isNotEmpty()) {
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "queryIntentActivities failed: ${e.message}")
        }

        // All methods failed - package is NOT installed
        Log.i(TAG, "Package $packageName not found by any detection method")
        return false
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(requestCode)
            } else {
                _state.value = ShizukuState.NOT_RUNNING
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Request Shizuku permission failed", e)
        }
    }

    /**
     * Execute shell command with timeout protection.
     * Returns ConsoleCommand with actual result, no fake data.
     */
    fun executeShell(command: String): ConsoleCommand {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Executing: $command")

        var success = false
        var resultOutput = ""

        try {
            when (_state.value) {
                ShizukuState.AUTHORIZED -> {
                    // Execute via Shizuku with proper process API
                    val result = executeViaShizuku(command)
                    success = result.first
                    resultOutput = result.second
                }
                else -> {
                    // No Shizuku access - cannot execute shell commands
                    resultOutput = "[ERROR] Shizuku not authorized. State: ${_state.value.name}"
                    success = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed executing: $command", e)
            success = false
            resultOutput = "[ERROR] ${e.javaClass.simpleName}: ${e.localizedMessage ?: "Unknown error"}"
        }

        val executionTime = System.currentTimeMillis() - startTime

        val cmdObject = ConsoleCommand(
            command = command,
            output = resultOutput,
            isSuccess = success,
            executionTimeMs = executionTime
        )

        // Thread-safe log addition using copy-on-write pattern
        synchronized(logCounter) {
            val currentList = _consoleLogs.value.toMutableList()
            currentList.add(0, cmdObject)
            while (currentList.size > MAX_CONSOLE_LOGS) {
                currentList.removeAt(currentList.lastIndex)
            }
            _consoleLogs.value = currentList
        }

        return cmdObject
    }

    private fun executeViaShizuku(command: String): Pair<Boolean, String> {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val outputBuilder = StringBuilder()

            // Read stdout
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    outputBuilder.append(line).append("\n")
                }
            }

            // Read stderr
            BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    outputBuilder.append("[ERR] ").append(line).append("\n")
                }
            }

            val finished = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return Pair(false, "[ERROR] Command timed out after ${SHELL_TIMEOUT_SECONDS}s")
            }

            val exitCode = process.exitValue()
            val output = outputBuilder.toString().trim()

            if (output.isEmpty()) {
                Pair(exitCode == 0, "Exit code: $exitCode")
            } else {
                Pair(exitCode == 0, output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku execution failed", e)
            Pair(false, "[ERROR] ${e.localizedMessage ?: "Shizuku execution failed"}")
        }
    }

    fun clearConsole() {
        synchronized(logCounter) {
            _consoleLogs.value = emptyList()
        }
    }

    /**
     * Check if a specific app process is currently running.
     * Returns the actual PID if found, -1 otherwise.
     */
    fun getAppPid(packageName: String): Int {
        if (_state.value != ShizukuState.AUTHORIZED) return -1

        val result = executeShell("pidof $packageName")
        if (result.isSuccess && result.output.isNotBlank()) {
            val pid = result.output.trim().split(" ").firstOrNull()?.toIntOrNull()
            if (pid != null && pid > 0) return pid
        }

        // Fallback: try pgrep
        val result2 = executeShell("pgrep -f \"$packageName\"")
        if (result2.isSuccess && result2.output.isNotBlank()) {
            val pid = result2.output.trim().split("\n").firstOrNull()?.toIntOrNull()
            if (pid != null && pid > 0) return pid
        }

        return -1
    }

    /**
     * Check if app is running using ActivityManager (no root required)
     */
    fun isAppRunning(context: Context, packageName: String): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningApps = activityManager.runningAppProcesses
            runningApps?.any { it.processName == packageName } == true
        } catch (e: Exception) {
            false
        }
    }
}
