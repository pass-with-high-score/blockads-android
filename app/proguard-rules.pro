# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line number info for debugging crashes
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlin.reflect.jvm.internal.** { *; }

# Koin
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# Keep data classes used by Room
-keep class app.pwhs.blockads.data.** { *; }

# Keep VPN service
-keep class app.pwhs.blockads.service.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

-assumenosideeffects class io.github.oshai.kotlinlogging.KLogger {
    public *** debug(...);
    public *** info(...);
    public *** trace(...);
}

-assumenosideeffects class org.slf4j.Logger {
    public *** debug(...);
    public *** info(...);
    public *** trace(...);
}

