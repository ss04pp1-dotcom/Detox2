# v2.5 r9: keep the ENTIRE app namespace — enforcement is reflection-
# sensitive (WidgetUpdater instantiates providers reflectively; guards and
# engines are cross-referenced from manifest services) and obfuscation
# gains nothing on a closed-source enforcement app.
-keep class com.maxleveldetox.** { *; }

# MAXLEVEL DETOX — release hardening

# Keep the enforcement engine intact. Obfuscation is welcome; breakage of the
# security boundary is not.
-keep class com.maxleveldetox.enforcement.** { *; }
-keep class com.maxleveldetox.accessibility.** { *; }
-keep class com.maxleveldetox.storage.** { *; }
-keep class com.maxleveldetox.bridge.** { *; }

# Accessibility service + receivers are referenced from the manifest.
-keep class com.maxleveldetox.DetoxAccessibilityService { *; }
-keep class com.maxleveldetox.recovery.** { *; }
-keep class com.maxleveldetox.alarm.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# DataStore
-dontwarn com.google.protobuf.**

# Strip debug logging from release builds (TRD §89/§140).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
