package com.example.engine

import android.content.Context
import android.util.Log
import com.example.util.ShizukuManager
import com.example.util.ShizukuState

/**
 * Manages two distinct categories of state:
 *
 * A) SYSTEM TWEAKS (restored on game session end / app exit):
 *    Power mode, I/O scheduler params, UFS read-ahead
 *    → backup before touch, restore automatically
 *
 * B) USER PREFERENCES (persisted, NEVER auto-reset):
 *    Animation scale, overlay prefs, refresh rate choice
 *    → saved to SharedPrefs, reloaded when app reopens
 *
 * Rule: "Khi user thoát app → trả về mặc định cho SYSTEM tweaks,
 *        nhưng giữ nguyên PREFERENCES của họ."
 */
object StateBackupManager {

    private const val TAG  = "StateBackupManager"
    private const val PREF = "gsb_user_preferences"

    // ── System state snapshot ─────────────────────────────────────────────────

    data class SystemSnapshot(
        val powerMode           : String = "0",
        val dirtyExpireCentisecs: String = "3000",
        val animatorDuration    : String = "1.0",
        val animatorTransition  : String = "1.0",
        val animatorWindow      : String = "1.0",
        val capturedAt          : Long   = System.currentTimeMillis()
    )

    @Volatile private var snapshot: SystemSnapshot? = null
    private val snapshotLock = Any()

    /** Call ONCE when the first game session starts. Idempotent. */
    fun captureIfAbsent() {
        synchronized(snapshotLock) {
            if (snapshot != null) return
            if (ShizukuManager.state.value != ShizukuState.AUTHORIZED) {
                snapshot = SystemSnapshot()
                return
            }
            try {
                fun read(cmd: String) = ShizukuManager.executeShell(cmd).output.trim()
                    .takeIf { it.isNotBlank() && it != "null" } ?: "unknown"

                snapshot = SystemSnapshot(
                    powerMode            = read("settings get global low_power"),
                    dirtyExpireCentisecs = readSysctl("vm.dirty_expire_centisecs") ?: "3000",
                    animatorDuration     = read("settings get global animator_duration_scale"),
                    animatorTransition   = read("settings get global transition_animation_scale"),
                    animatorWindow       = read("settings get global window_animation_scale")
                )
                Log.i(TAG, "System snapshot captured: $snapshot")
            } catch (e: Exception) {
                Log.e(TAG, "captureIfAbsent error: ${e.message}")
                snapshot = SystemSnapshot()
            }
        }
    }

    /**
     * Restore system-level tweaks to pre-session state.
     * Called on game session end AND app destroy.
     * USER PREFERENCES are NOT touched here.
     */
    fun restoreSystemTweaks() {
        val snap = synchronized(snapshotLock) { snapshot } ?: return
        if (ShizukuManager.state.value != ShizukuState.AUTHORIZED) {
            snapshot = null
            return
        }
        try {
            // Power mode
            val pm = snap.powerMode.toIntOrNull() ?: 0
            ShizukuManager.executeShell("cmd power set-mode ${pm.coerceIn(0, 1)}")

            // I/O / VM
            val dc = snap.dirtyExpireCentisecs.toIntOrNull() ?: 3000
            ShizukuManager.executeShell("sysctl -w vm.dirty_expire_centisecs=$dc")

            // Animator scale — only restore if we actually changed it
            if (wasAnimatorTweaked()) {
                ShizukuManager.executeShell("settings put global animator_duration_scale ${snap.animatorDuration}")
                ShizukuManager.executeShell("settings put global transition_animation_scale ${snap.animatorTransition}")
                ShizukuManager.executeShell("settings put global window_animation_scale ${snap.animatorWindow}")
            }

            Log.i(TAG, "System tweaks restored to snapshot")
        } catch (e: Exception) {
            Log.e(TAG, "restoreSystemTweaks error: ${e.message}")
        } finally {
            synchronized(snapshotLock) { snapshot = null }
        }
    }

    /** Clear snapshot without restoring (e.g. when service was killed before a game started) */
    fun clearSnapshot() {
        synchronized(snapshotLock) { snapshot = null }
    }

    // ── User preferences (persisted) ──────────────────────────────────────────

    fun savePreference(context: Context, key: String, value: Any) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        with(prefs.edit()) {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Int     -> putInt(key, value)
                is Float   -> putFloat(key, value)
                is Long    -> putLong(key, value)
                is String  -> putString(key, value)
                else       -> putString(key, value.toString())
            }
            apply()
        }
    }

    fun getPreference(context: Context, key: String, default: String = ""): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(key, default) ?: default

    fun getPreferenceBool(context: Context, key: String, default: Boolean = false): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(key, default)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun readSysctl(key: String): String? {
        return try {
            ShizukuManager.executeShell("sysctl -n $key").output.trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    @Volatile private var animatorTweaked = false
    fun markAnimatorTweaked() { animatorTweaked = true }
    private fun wasAnimatorTweaked(): Boolean = animatorTweaked.also { animatorTweaked = false }
}
