package com.wepwawet

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.wepwawet.log.CrashHandler
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * WepWawet Application entry point.
 *
 * Responsibilities (each step is independently try-caught so one failure does
 * not block the rest — this is critical because if any of these throw, the
 * app would never finish starting and the user would see "crashes on boot
 * without logs being made"):
 *
 *  1. Install the global CrashHandler FIRST so we capture any subsequent crash
 *  2. Write a "boot breadcrumb" to Downloads/WepWawet/boot.txt so the user
 *     can verify the app actually started onCreate — if even this fails, we
 *     know the crash is in the Application.attachBaseContext or class loading
 *     (which we can't catch here).
 *  3. Initialize the WepWawetLogger ring buffer + WorkManager job
 *  4. Create the foreground service notification channels
 *  5. Log a startup-complete event with build info
 *
 * Every step writes to logcat (Log.i / Log.e) as a fallback if file IO fails,
 * so even if our logger can't initialize we still have a breadcrumb in logcat
 * that the user can capture via `adb logcat` or bugreport.
 */
class WepWawetApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    /**
     * v0.5.0: Custom Coil ImageLoader with GIF playback + disk cache.
     *
     * - GIF decoder: enables animated GIF preview thumbnails (Steam preview URLs
     *   are often .gif files — without this, only the first frame is shown).
     * - Disk cache: 100 MB persistent cache in app cache dir → faster re-loads.
     * - Memory cache: 25% of available app memory → fast scrolling.
     * - Crossfade: 200ms fade-in animation for smoother image loading.
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                // GIF decoder for Android 9.0+ (ImageDecoder-based, API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                }
                // GIF decoder for all versions (movie-based fallback)
                add(GifDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024) // 100 MB
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% of available memory
                    .build()
            }
            .crossfade(true)
            .crossfade(200) // 200ms fade-in
            .build()
    }

    override fun onCreate() {
        // v0.5.32 (audit M4): Initialize appScope FIRST, before any
        // other initialization step. Previously appScope was initialized
        // at line 159 (the very LAST step of onCreate) — if any earlier
        // step (CrashHandler.install, writeBootBreadcrumb, WepWawetLogger.init,
        // createNotificationChannels) ever touched appScope (directly or
        // transitively), the app would crash with
        // UninitializedPropertyAccessException *before* the user-visible
        // UI initialized.
        //
        // In practice nothing hits appScope during onCreate today, so this
        // was latent — but it's fragile. A future change (e.g., a
        // CrashHandler that wants to log via a coroutine) would crash on
        // boot with no obvious cause. Moving appScope init to the very
        // first line of onCreate eliminates the risk.
        // v0.7.24 (H-U8 BUG FIX): super.onCreate() must run FIRST — it
        // initializes ContentProviders (including WorkManager's default
        // initializer); any provider's onCreate that touched
        // WepWawetApplication.instance hit an
        // UninitializedPropertyAccessException because instance was assigned
        // only after the CrashHandler block. Field inits that must precede
        // super.onCreate() (nothing does, per audit) stay after it.
        super.onCreate()

        appScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                // v0.7.24 (H-U7): uncaught exceptions in appScope children
                // (e.g. a WorkshopDownloadJob packing NPE) propagate to the
                // thread's UncaughtExceptionHandler and CRASH the app from
                // the background. A logged handler keeps the process alive.
                CoroutineExceptionHandler { _, t ->
                    Log.e(TAG, "Uncaught exception in appScope", t)
                    runCatching {
                        com.wepwawet.log.WepWawetLogger.appError(
                            "Uncaught exception in appScope",
                            exception = t)
                    }
                }
        )

        // Set singleton instance (after super.onCreate — see H-U8).
        instance = this

        // Step 1: Install crash handler FIRST so we never miss a crash from the rest of onCreate.
        // This must succeed even if the rest of onCreate fails — wrap in try-catch and log
        // via logcat (the only always-available sink at this point).
        runCatching {
            CrashHandler.install(this)
        }.onFailure { t ->
            Log.e(TAG, "CrashHandler.install failed", t)
        }

        // v0.6.1: Load persisted download history from SharedPreferences
        // v0.7.24 (C-A1 BUG FIX): wrapped — the class KDoc promises every step
        // is independently try-caught, but this call was bare: one throw and
        // the logger init / notification channels / breadcrumb never ran,
        // booting the app half-initialized.
        runCatching {
            com.wepwawet.data.DownloadManager.initialize(this)
        }.onFailure { t ->
            Log.e(TAG, "DownloadManager.initialize failed", t)
        }

        // v0.7.4: load the user's page-size setting into SteamApiService so the
        // "Page size" slider in Settings actually affects queries (it was
        // previously persisted but never read — the API always used 30).
        runCatching {
            com.wepwawet.data.SteamApiService.initPageSize(this)
        }.onFailure { t ->
            Log.e(TAG, "SteamApiService.initPageSize failed", t)
        }

        // Step 2: Write a boot breadcrumb to public Downloads/WepWawet/boot.txt
        // so the user can verify the app actually reached onCreate — if even
        // this file is missing, the crash is happening BEFORE Application.onCreate
        // (which means class-loading failure, manifest merge issue, or native
        // init crash — none of which the Java CrashHandler can capture).
        //
        // v0.7.24 (C-U2 BUG FIX): writeBootBreadcrumb does a MediaStore
        // query + insert + openOutputStream + write — IPC to MediaProvider
        // that can block for hundreds of ms under load, all on the MAIN
        // thread inside onCreate (ANR on slow devices). Hop to the IO
        // dispatcher (appScope was created above); the write is best-effort
        // and its ordering vs. the later appendToBootBreadcrumb calls is
        // preserved by writing the "onCreate complete" line through the
        // same scope below.
        appScope.launch {
            runCatching {
                writeBootBreadcrumb()
            }.onFailure { t ->
                Log.e(TAG, "writeBootBreadcrumb failed", t)
            }
        }

        // Step 3: Initialize the logger. If this fails, we can't use WepWawetLogger
        // for the rest of onCreate — fall back to logcat.
        var loggerOk = false
        runCatching {
            WepWawetLogger.init(this)
            loggerOk = true
        }.onFailure { t ->
            Log.e(TAG, "WepWawetLogger.init failed", t)
        }

        // Step 4: Create notification channels for foreground services.
        // Required for Android 8.0+ — without a channel, foreground service
        // notifications silently fail to display, and the system kills the
        // service after 5 seconds for not showing a notification.
        runCatching {
            createNotificationChannels()
        }.onFailure { t ->
            if (loggerOk) WepWawetLogger.appError("createNotificationChannels failed",
                exception = t)
            else Log.e(TAG, "createNotificationChannels failed", t)
        }

        // Step 5: Log a startup-complete event with build info.
        if (loggerOk) {
            runCatching {
                WepWawetLogger.appInfo("WepWawetApplication.onCreate complete",
                    "app_version" to BuildConfig.VERSION_NAME,
                    "git_sha" to BuildConfig.GIT_SHA,
                    "build_time" to BuildConfig.BUILD_TIME,
                    "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "android_version" to Build.VERSION.SDK_INT.toString(),
                    "abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                )
            }
        } else {
            // Logger failed — write directly to logcat as a last resort
            Log.i(TAG, "onCreate complete (logger failed — logcat only): " +
                "version=${BuildConfig.VERSION_NAME} sha=${BuildConfig.GIT_SHA}")
        }

        // Final breadcrumb: confirm we got to the end of onCreate
        // (v0.7.24, C-U2: same IO scope as the initial breadcrumb write.)
        appScope.launch {
            runCatching {
                appendToBootBreadcrumb("onCreate complete\n")
            }
        }
        // NOTE: appScope was initialized at the TOP of onCreate (audit M4).
        // The old init at this point has been removed.
    }

    /**
     * Write a boot breadcrumb to Downloads/WepWawet/boot.txt. This proves the
     * app actually reached Application.onCreate() — if this file is missing,
     * the crash is happening BEFORE the Application class even runs (class
     * loading failure, manifest merge issue, or native init crash).
     */
    private fun writeBootBreadcrumb() {
        val ts = System.currentTimeMillis()
        val sb = StringBuilder(2048)
        sb.append("=== WepWawet boot ===\n")
        sb.append("Time: ").append(java.time.Instant.ofEpochMilli(ts)).append("\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ")
            .append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append("\n")
        runCatching {
            sb.append("App version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            sb.append("Git sha: ").append(BuildConfig.GIT_SHA).append("\n")
            sb.append("Build time: ").append(BuildConfig.BUILD_TIME).append("\n")
        }
        sb.append("--- onCreate starting ---\n")

        val content = sb.toString()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — MediaStore.Downloads collection (no permission)
            runCatching {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "boot.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/WepWawet")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Try to find an existing boot.txt and overwrite; if not found, insert
                val existing = findBootFile(collection)
                val uri: Uri = if (existing != null) {
                    // Overwrite by writing a fresh row with the same display name
                    // (we delete + re-insert to avoid IS_PENDING update issues on
                    // legacy Android 10 implementations)
                    runCatching { resolver.delete(existing, null, null) }
                    resolver.insert(collection, values) ?: return@runCatching
                } else {
                    resolver.insert(collection, values) ?: return@runCatching
                }
                resolver.openOutputStream(uri, "w")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        } else {
            // Android 9 and below — legacy File API to public Downloads
            @Suppress("DEPRECATION")
            runCatching {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloads, "WepWawet").apply { mkdirs() }
                File(dir, "boot.txt").writeText(content)
            }
        }
    }

    private fun appendToBootBreadcrumb(extra: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val existing = findBootFile(collection) ?: return@runCatching
                resolver.openOutputStream(existing, "wa")?.use { out ->
                    out.write(extra.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloads, "WepWawet/boot.txt")
                if (file.exists()) {
                    file.appendText(extra)
                }
            }
        }
    }

    private fun findBootFile(collection: Uri): Uri? {
        val resolver = contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        // v0.5.32 (audit M5): filter by RELATIVE_PATH too — otherwise
        // this query matches any file named "boot.txt" anywhere in the
        // user's Downloads collection (including unrelated apps' files).
        // If another app also writes a boot.txt to Downloads, we'd
        // return that app's URI instead of WepWawet's, then
        // appendToBootBreadcrumb would write WepWawet's "onCreate
        // complete" line into the OTHER app's file, and the user's
        // Downloads/WepWawet/boot.txt would stop getting updates.
        // NOTE: Android's MediaStore trims leading/trailing slashes from
        // RELATIVE_PATH on both write and store (per Android docs:
        // https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#RELATIVE_PATH
        // — "trims leading/trailing slashes before being stored"). The
        // write-side code at line 207 uses "Download/WepWawet" (no trailing
        // slash), and the stored value is also normalized to that form.
        // The query selection must therefore also use "Download/WepWawet"
        // (no trailing slash) — using "Download/WepWawet/" would cause the
        // SQL comparison `RELATIVE_PATH = 'Download/WepWawet/'` to fail to
        // match the stored "Download/WepWawet" value, and we'd fall through
        // to the createNewFile path on every onCreate call.
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf("boot.txt", "Download/WepWawet")
        resolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // v0.7.4: create the channel ID the PairingService actually uses
        // ("wepwawet_pairing" — see PairingService.CHANNEL_ID). This used to
        // create "wepwawet.proxy", a DIFFERENT id that no notification was ever
        // posted to — a dead channel alongside the real one the service creates
        // itself. Creating the same id here is idempotent (first creation wins)
        // and just means the channel exists even before the service first runs.
        nm.createNotificationChannel(
            NotificationChannel(
                "wepwawet_pairing",
                getString(R.string.notif_proxy_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_proxy_channel_desc)
                setShowBadge(false)
            }
        )
    }

    /**
     * WorkManager Configuration — uses our explicit min-shred-logging initializer
     * (we don't want WorkManager's default to spam logcat).
     */
    // v0.7.24 (M-U19): cached — the getter built a new Configuration on
    // every access (WorkManager reads it multiple times at init).
    override val workManagerConfiguration: Configuration by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
    }

    companion object {
        private const val TAG = "WepWawet-App"
        // (v0.7.4: the unused CHANNEL_PROXY = "wepwawet.proxy" constant was
        //  removed — the app now creates the "wepwawet_pairing" channel id that
        //  PairingService actually posts to. See createNotificationChannels.)

        /**
         * Singleton Application instance. Set in onCreate(). Used by
         * MpkgPacker.texCacheLookup/texCacheStore to access cacheDir
         * without passing Context through the call chain.
         */
        // v0.7.24 (M-U18): @Volatile — readers on other threads (native
        // upcall, binder thread) must observe the write from onCreate.
        @Volatile lateinit var instance: WepWawetApplication
            private set

        /**
         * App-scoped CoroutineScope. Initialized in onCreate. Use for
         * background work that must outlive any Activity/Composable
         * (e.g. WorkshopDownloadJob launches downloads here so the user
         * can navigate away without cancelling the download).
         */
        lateinit var appScope: CoroutineScope
            private set
    }
}
