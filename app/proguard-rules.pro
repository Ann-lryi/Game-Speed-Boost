# ProGuard rules for Titan Engine - Game Speed Boost

# Keep Room entities
-keep class com.example.data.** { *; }

# Keep Shizuku API
-keep class rikka.shizuku.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Titan Engine classes (CRITICAL for native bridge)
-keep class com.titan.** { *; }
-keep class com.titan.engine.** { *; }
-keep class com.titan.core.** { *; }
-keep class com.titan.hal.** { *; }
-keep class com.titan.engine.models.** { *; }
-keep class com.titan.engine.rules.** { *; }

# Keep JNI methods and native bindings
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep data classes used in JNI
-keepclassmembers class com.titan.core.HardwareSnapshot {
    <fields>;
    <init>(...);
}

-keepclassmembers class com.titan.engine.models.** {
    <fields>;
    <init>(...);
}

# Keep Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
