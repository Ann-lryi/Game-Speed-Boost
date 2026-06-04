package com.example.util;

import rikka.shizuku.Shizuku;

/**
 * Java wrapper for Shizuku.newProcess().
 *
 * Kotlin compiler enforces @RestrictTo visibility and reports newProcess() as
 * "private" in Shizuku 13.x — this is a false positive caused by how Kotlin
 * maps Android's @RestrictTo annotation. Java does not enforce @RestrictTo at
 * compile time, so calling through this helper bypasses the Kotlin error while
 * keeping the same runtime behaviour.
 */
public final class ShizukuProcessHelper {

    private ShizukuProcessHelper() {}

    /**
     * Creates a new process via the Shizuku service (equivalent to adb shell).
     * Must only be called when Shizuku permission is already granted.
     *
     * @param cmd shell command split into tokens, e.g. {"sh", "-c", "ls -la"}
     * @param env environment variables, or null to inherit
     * @param dir working directory, or null for default
     * @return a Process whose stdin/stdout/stderr are connected to the remote process
     */
    public static Process newProcess(String[] cmd, String[] env, String dir) throws Throwable {
        return Shizuku.newProcess(cmd, env, dir);
    }
}
