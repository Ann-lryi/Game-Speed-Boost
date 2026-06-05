package com.example.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
    const val SHIZUKU_PACKAGE_NAME = "moe.shizuku.privileged.api"
    private const val SHELL_TIMEOUT_SECONDS = 15L
    private const val MAX_CONSOLE_LOGS = 100

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state = _state.asStateFlow()

    private val _consoleLogs = MutableStateFlow<List<ConsoleCommand>>(emptyList())
    val consoleLogs = _consoleLogs.asStateFlow()

    // FIX L-04: Proper lock object — AtomicInteger was misleading (never incremented)
    private val logLock = Any()

    // FIX H-01: Per-package frame count cache for delta FPS calculation
    private val frameCountCache = mutableMapOf<String, Pair<Long, Long>>() // pkg -> (count, timeMs)

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

        val isInstalled = isPackageInstalled(context, SHIZUKU_PACKAGE_NAME)
        _state.value = if (!isInstalled) ShizukuState.NOT_INSTALLED else ShizukuState.NOT_RUNNING
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
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
        try {
            @Suppress("DEPRECATION")
            val apps = context.packageManager.getInstalledApplications(0)
            for (app in apps) { if (app.packageName == packageName) return true }
        } catch (e: Exception) {
            Log.d(TAG, "getInstalledApplications failed: ${e.message}")
        }
        try {
            if (context.packageManager.getLaunchIntentForPackage(packageName) != null) return true
        } catch (e: Exception) {
            Log.d(TAG, "getLaunchIntentForPackage failed: ${e.message}")
        }
        Log.i(TAG, "Package $packageName not found by any detection method")
        return false
    }

    fun isShizukuAvailable(): Boolean {
        return try { Shizuku.pingBinder() } catch (e: Throwable) { false }
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
     * Execute shell command. Must be called from a background thread (Dispatchers.IO).
     * Returns ConsoleCommand with actual result.
     */
    fun executeShell(command: String): ConsoleCommand {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "Executing: $command")

        var success = false
        var resultOutput = ""

        try {
            when (_state.value) {
                ShizukuState.AUTHORIZED -> {
                    val result = executeViaShizuku(command)
                    success = result.first
                    resultOutput = result.second
                }
                else -> {
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

        // FIX L-04: Use proper lock object (not AtomicInteger)
        synchronized(logLock) {
            val currentList = _consoleLogs.value.toMutableList()
            currentList.add(0, cmdObject)
            while (currentList.size > MAX_CONSOLE_LOGS) {
                currentList.removeAt(currentList.lastIndex)
            }
            _consoleLogs.value = currentList
        }

        return cmdObject
    }

    /**
     * FIX C-04: Read stdout and stderr CONCURRENTLY on separate threads to prevent
     * deadlock when either buffer fills while the other is being drained.
     */
    private fun executeViaShizuku(command: String): Pair<Boolean, String> {
        return try {
            // Use Java wrapper — Kotlin compiler incorrectly restricts Shizuku.newProcess()
        // due to @RestrictTo annotation mapping; Java enforces no such restriction at compile time.
        val process = ShizukuProcessHelper.newProcess(arrayOf("sh", "-c", command), null, null)
            val outputBuilder = StringBuilder()
            val errorBuilder  = StringBuilder()

            // Drain stdout and stderr in parallel — classic deadlock fix
            val stdoutThread = thread(start = true, isDaemon = true) {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            outputBuilder.append(line).append("\n")
                        }
                    }
                } catch (_: Exception) { /* stream closed */ }
            }

            val stderrThread = thread(start = true, isDaemon = true) {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            errorBuilder.append("[ERR] ").append(line).append("\n")
                        }
                    }
                } catch (_: Exception) { /* stream closed */ }
            }

            // Use a dedicated wait-thread so we can interrupt it reliably.
            // ShizukuRemoteProcess.waitFor(timeout, unit) may not behave correctly
            // for remote IPC processes — wrapping in thread+join is safer.
            val waitThread = thread(start = true, isDaemon = true) {
                try { process.waitFor() } catch (_: InterruptedException) {}
            }
            waitThread.join(SHELL_TIMEOUT_SECONDS * 1000)

            val timedOut = waitThread.isAlive
            if (timedOut) {
                waitThread.interrupt()
                process.destroyForcibly()
                stdoutThread.interrupt()
                stderrThread.interrupt()
                return Pair(false, "[ERROR] Command timed out after ${SHELL_TIMEOUT_SECONDS}s")
            }

            // Drain remaining buffered output
            stdoutThread.join(1000)
            stderrThread.join(1000)

            // ShizukuRemoteProcess.exitValue() may throw IllegalThreadStateException
            // ("process hasn't exited") even after waitFor() completes — treat as 0
            val exitCode = try {
                process.exitValue()
            } catch (_: IllegalStateException) { 0 }
              catch (_: IllegalThreadStateException) { 0 }

            val combined = (outputBuilder.toString() + errorBuilder.toString()).trim()

            if (combined.isEmpty()) Pair(exitCode == 0, "Exit code: $exitCode")
            else Pair(exitCode == 0, combined)

        } catch (e: Exception) {
            Log.e(TAG, "Shizuku execution failed", e)
            Pair(false, "[ERROR] ${e.localizedMessage ?: "Shizuku execution failed"}")
        }
    }

    fun clearConsole() {
        synchronized(logLock) {
            _consoleLogs.value = emptyList()
        }
    }

    fun getAppPid(packageName: String): Int {
        if (_state.value != ShizukuState.AUTHORIZED) return -1
        val result = executeShell("pidof $packageName")
        if (result.isSuccess && result.output.isNotBlank()) {
            val pid = result.output.trim().split(" ").firstOrNull()?.toIntOrNull()
            if (pid != null && pid > 0) return pid
        }
        val result2 = executeShell("pgrep -f \"$packageName\"")
        if (result2.isSuccess && result2.output.isNotBlank()) {
            val pid = result2.output.trim().split("\n").firstOrNull()?.toIntOrNull()
            if (pid != null && pid > 0) return pid
        }
        return -1
    }

    fun isAppRunning(context: Context, packageName: String): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.any { it.processName == packageName } == true
        } catch (e: Exception) { false }
    }

    /**
     * FIX H-01: Measure GAME's FPS cross-process via dumpsys gfxinfo.
     * Choreographer-based FPS only works in the current process — this is the correct approach.
     * Returns delta FPS between calls, or -1 if unavailable/too soon.
     * Call from Dispatchers.IO only.
     */
    fun getGameFpsViaGfxInfo(packageName: String): Int {
        if (_state.value != ShizukuState.AUTHORIZED) return -1
        return try {
            val now = SystemClock.elapsedRealtime()
            val previous = frameCountCache[packageName]

            // Don't poll more often than once per second
            if (previous != null && (now - previous.second) < 1000L) return -1

            val result = executeShell("dumpsys gfxinfo $packageName")
            if (!result.isSuccess || result.output.isBlank()) return -1

            val line = result.output.lineSequence()
                .firstOrNull { it.contains("Total frames rendered:") }
            val currentCount = line
                ?.substringAfter("Total frames rendered:")
                ?.trim()
                ?.toLongOrNull() ?: return -1

            val fps = if (previous != null && previous.second > 0) {
                val delta   = currentCount - previous.first
                val elapsed = now - previous.second
                if (delta >= 0 && elapsed > 0) (delta * 1000L / elapsed).toInt().coerceIn(0, 300)
                else -1
            } else -1

            frameCountCache[packageName] = Pair(currentCount, now)
            fps
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get game FPS via gfxinfo", e)
            -1
        }
    }
}
