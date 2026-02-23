# ProGuard rules for Termux AI
# ---------------------------------------------------------
# FIXED: Removed blanket "-keep class com.termux.ai.** { *; }" etc.
# that prevented ALL obfuscation. Now only keeps what's necessary.

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable

# =========================================================
# App-specific keeps (only classes accessed via reflection)
# =========================================================

# Keep the Application class (referenced by name in manifest)
-keep public class com.termux.plus.TermuxPlusApplication

# Keep Activities, Services, Receivers, Providers (manifest references)
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep Fragment constructors (instantiated by framework)
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    public <init>(...);
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom view constructors (inflated from XML)
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# =========================================================
# OkHttp
# =========================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
# Only keep the public API, not internals
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Request$Builder { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.RequestBody { *; }
-keep class okhttp3.MediaType { *; }
-keep class okhttp3.Call { *; }
-keep class okhttp3.Callback { *; }
-keep interface okhttp3.** { *; }

# =========================================================
# Gson (needs reflection for serialization)
# =========================================================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Keep any data classes that Gson deserializes
-keepclassmembers class com.termux.ai.** {
    <fields>;
}

# =========================================================
# Room database (uses reflection for DAOs)
# =========================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# =========================================================
# Material Design
# =========================================================
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# =========================================================
# AndroidX (keep public API only)
# =========================================================
-dontwarn androidx.**

# =========================================================
# Strip logging in release
# =========================================================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# Keep warn and error logs for production diagnostics
# -assumenosideeffects class android.util.Log {
#     public static int w(...);
#     public static int e(...);
# }
