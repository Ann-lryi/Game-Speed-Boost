package com.example.util;

import android.util.Log;
import java.lang.reflect.Method;

/**
 * Executes processes via Shizuku using reflection.
 *
 * Problem: Shizuku.newProcess() has private access in the 13.x API JAR,
 * blocking both Kotlin and direct Java calls at compile time.
 *
 * Solution: Java reflection with setAccessible(true).
 * Android's hidden-API restrictions (API 28+) only apply to platform classes
 * (android.*, dalvik.*, java.*, etc.) — NOT to third-party libraries such as
 * rikka.shizuku.*. So setAccessible() works here without restriction.
 *
 * The Method reference is cached after the first call to avoid repeated
 * reflection overhead on every shell command.
 */
public final class ShizukuProcessHelper {

    private static final String TAG = "ShizukuProcessHelper";

    // Cached to avoid getDeclaredMethod() overhead on every call
    private static volatile Method sNewProcessMethod = null;

    private ShizukuProcessHelper() {}

    /**
     * Creates a new privileged process via the Shizuku service.
     * Must be called only after Shizuku permission has been granted.
     *
     * @param cmd  command split into tokens, e.g. {"sh", "-c", "whoami"}
     * @param env  environment variables, or null to inherit from parent
     * @param dir  working directory path, or null for default
     * @return a Process connected to the privileged remote process
     */
    public static Process newProcess(String[] cmd, String[] env, String dir) throws Throwable {
        Method method = sNewProcessMethod;
        if (method == null) {
            synchronized (ShizukuProcessHelper.class) {
                method = sNewProcessMethod;
                if (method == null) {
                    Class<?> clazz = Class.forName("rikka.shizuku.Shizuku");
                    method = clazz.getDeclaredMethod(
                            "newProcess",
                            String[].class,
                            String[].class,
                            String.class
                    );
                    method.setAccessible(true);
                    sNewProcessMethod = method;
                    Log.d(TAG, "Shizuku.newProcess() resolved via reflection");
                }
            }
        }
        return (Process) method.invoke(null, cmd, env, dir);
    }
}
