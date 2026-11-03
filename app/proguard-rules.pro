# WepWawet ProGuard rules

# Compose — recommended by AndroidX
-keep class androidx.compose.** { *; }

# Gson — data classes used for JSON serialization (CRITICAL: without these,
# R8 renames the classes and Gson can't find the no-arg constructor, causing
# "Abstract classes can't be instantiated" errors at runtime)
-keep class com.wepwawet.pairing.** { *; }
-keep class com.wepwawet.data.model.** { *; }
-keep class com.wepwawet.data.api.** { *; }
# v0.7.24 (C-D5 BUG FIX): DownloadProgress + CompletedDownload live in
# com.wepwawet.data TOP-LEVEL (DownloadManager.kt) — NOT in .model/.api, so
# R8 renamed their fields in release builds and Gson silently round-tripped
# EMPTY objects: every completed-download history entry lost its metadata on
# app restart. Keep the whole package.
-keep class com.wepwawet.data.** { *; }
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod, InnerClasses

# Gson TypeToken — anonymous subclasses (e.g. `object : TypeToken<List<T>>() {}`)
# require the generic Signature attribute to be preserved, otherwise Gson
# throws "TypeToken must be created with a type argument" at <clinit> time.
# Keep ALL TypeToken subclasses (anonymous or named) and their generic
# signatures, no matter what package they live in.
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.reflect.TypeToken$* { *; }
-keepclasseswithmembers class * {
    extends com.google.gson.reflect.TypeToken;
}
-keep,allowobfuscation class * {
    extends com.google.gson.reflect.TypeToken;
}

# Kotlinx Serialization — required for the JSON converter
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keep class kotlinx.serialization.json.** { *; }

# Retrofit — service interfaces (used by SteamApiService)
-keepattributes InnerClasses, EnclosingMethod
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# OkHttp — platform-specific (don't strip)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Tink crypto (transitively pulled in via androidx.security.crypto for
# EncryptedSharedPreferences). Tink's runtime classes use compile-time-only
# annotations from com.google.errorprone.annotations which are NOT packaged
# in the runtime APK. R8's "Missing class" report (new in AGP 8.x) treats
# references to missing classes as build-breaking errors. We use -dontwarn
# to silence them — the annotations are not needed at runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**

# Kotlin coroutines — keep internal state machine
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# LZ4 (lz4-java, org.lz4:lz4-java) — used by MpkgPacker.convertTexPngToEtc2
# for .tex compression (compression=1 format).
# CRITICAL: LZ4Factory.safeInstance() loads LZ4JavaSafeCompressor via reflection
# (Class.forName). Without these keep rules, R8 strips the Java fallback class,
# causing "ClassNotFoundException: net.jpountz.lz4.LZ4JavaSafeCompressor" when
# the native lz4 library fails to load on Android (which is always, since the
# bundled native libs are for x86_64 Linux/Mac/Windows, not Android arm64).
# Result: "Failed to pack .pkg → .mpkg" during ETC2+LZ4 .tex conversion.
# LZ4 is REQUIRED (compression=1) — falling back to compression=0 produces a
# .tex the WE Android renderer does not decode correctly (black wallpaper).
# Restored from v0.5.0-alpha after the v0.5.1 regression stripped these rules.
# See: https://github.com/lz4/lz4-java/issues/130
-keep class net.jpountz.lz4.** { *; }
-keep class net.jpountz.util.** { *; }
-keepclassmembers class net.jpountz.** { *; }
-dontwarn net.jpountz.**

# JavaSteam (SteamKit2 Java port) — keep all classes
-keep class in.dragonbra.javasteam.** { *; }
-dontwarn in.dragonbra.javasteam.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class in.dragonbra.javasteam.depotdownloader.** { *; }
-dontwarn in.dragonbra.javasteam.depotdownloader.**
