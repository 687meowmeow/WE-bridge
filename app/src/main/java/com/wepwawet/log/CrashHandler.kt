package com.wepwawet.log

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import kotlin.system.exitProcess

/**
 * Global crash handler — captures Java uncaught exceptions and writes a detailed
 * crash report to **user-visible** storage so the user can attach it to bug reports.
 *
 * === WHERE LOGS ARE WRITTEN ===
 *
 * The user explicitly requested crash logs in their Downloads folder. We write to:
 *
 *   Primary (user-visible):   Downloads/WepWawet/crash-<timestamp>.txt
 *   Secondary (app-internal): files/wepwawet/crashes/crash-<timestamp>.txt
 *
 * Primary is written via:
 *   - Android 10+ (API 29+): MediaStore.Downloads collection — no permission needed
 *     for files our own app creates.
 *   - Android 9 and below: legacy File API to
 *     Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)/WepWawet/
 *     (requires WRITE_EXTERNAL_STORAGE, declared in manifest with maxSdkVersion=28).
 *
 * Secondary is the app's private files dir which is always writable without
 * permission; it's a backup in case MediaStore fails (e.g. user ejected our
 * Downloads access, MediaStore provider crashed, etc.).
 *
 * === WHAT WE CAPTURE ===
 *
 *   - Thread name + id
 *   - Stack trace (Java)
 *   - WepWawetLogger ring buffer snapshot (last 2000 entries)
 *   - Process state: thread list via /proc/self/task
 *   - Build info (device, ABI, Android version, app version + git sha)
 *   - /proc/self/maps (first 50 lines) for native address resolution
 *
 * === ROBUSTNESS ===
 *
 * Every step is wrapped in its own try-catch — a single I/O failure must never
 * prevent the next step from running. The crash handler itself must NEVER throw
 * (otherwise we'd recurse into ourselves); if anything fails we silently fall
 * through to the previous handler.
 */
class CrashHandler private constructor(
    private val appContext: Context,
    private val previousHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        val ts = System.currentTimeMillis()
        val report = buildReport(t, e, ts)

        // Best-effort write to internal storage (always works, no permission)
        runCatching { writeInternal(report, ts) }

        // Best-effort write to public Downloads/WepWawet/ (user-visible)
        runCatching { writePublicDownloads(appContext, report, ts) }

        // Also log via logcat so adb logcat captures it even if file IO fails
        runCatching {
            Log.e(TAG, "=== WepWawet crash ===\n${report}", e)
        }

        // Chain to the previous handler — let the default Android handler show
        // the crash dialog + kill the process. If previousHandler is null (no
        // default handler registered), force-kill so we don't zombie.
        if (previousHandler != null) {
            try {
                previousHandler.uncaughtException(t, e)
            } catch (handlerEx: Throwable) {
                // v0.7.4: runCatching — if this Log.e itself throws (possible on
                // OOM / native-crash paths), the exception would escape
                // uncaughtException() and cause the JVM to re-invoke THIS SAME
                // handler on the same thread → infinite recursion. The Log.e
                // calls at the top of this function are already wrapped; these
                // two were missed.
                runCatching { Log.e(TAG, "Default crash handler threw — force-killing", handlerEx) }
            }
            // If we get here, the handler didn't kill us — force-kill.
            exitProcess(1)
        } else {
            runCatching { Log.e(TAG, "No previous crash handler — force-killing") }
            exitProcess(1)
        }
    }

    // ====== Report builder ======

    private fun buildReport(t: Thread, e: Throwable, ts: Long): String {
        val sb = StringBuilder(8192)
        sb.append("=== WepWawet crash report ===\n")
        sb.append("Time: ").append(java.time.Instant.ofEpochMilli(ts)).append("\n")
        sb.append("Thread: ").append(t.name).append(" (id=").append(t.id).append(")\n")
        sb.append("Process: ").append(Process.myPid()).append("\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ")
            .append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(",")).append("\n")

        // BuildConfig is generated; wrap in try-catch in case the generated
        // class isn't available (R8 stripping / build variant mismatch)
        runCatching {
            sb.append("App version: ").append(com.wepwawet.BuildConfig.VERSION_NAME)
                .append(" (").append(com.wepwawet.BuildConfig.VERSION_CODE).append(")\n")
            sb.append("Git sha: ").append(com.wepwawet.BuildConfig.GIT_SHA).append("\n")
            sb.append("Build time: ").append(com.wepwawet.BuildConfig.BUILD_TIME).append("\n")
        }

        sb.append("\n--- Exception ---\n")
        sb.append(e.stackTraceToString()).append("\n")

        sb.append("\n--- Recent log entries (last 2000) ---\n")
        // v0.7.4: WepWawetLogger.snapshot() is synchronized(ringBuffer). If
        // another thread currently holds that monitor and is hung (or is itself
        // mid-crash), building the report inline would block the crash handler
        // forever and the crash report would never be written. We compute the
        // snapshot on a helper thread and wait at most 2 seconds for it — if
        // the logger is deadlocked we still write the rest of the report.
        //
        // v0.7.21 BUG FIX: the helper thread was writing to the SAME StringBuilder
        // (`sb`) as the main crash-handler thread. If the 2-second join timed out,
        // the helper thread was STILL running and mutating `sb` concurrently with
        // the main thread — StringBuilder is not thread-safe, causing corruption
        // or ArrayIndexOutOfBoundsException. Fix: the helper thread writes to its
        // OWN StringBuilder, and the main thread appends the result after join.
        runCatching {
            // v0.7.24 (C-A2 BUG FIX): the v0.7.21 attempt gave the helper its
            // own StringBuilder but still READ it after a join(2000) timeout
            // while the helper could be mid-append — StringBuilder reads
            // during a concurrent resize are NOT safe (stale array reference /
            // ArrayIndexOutOfBoundsException), acknowledged in the old comment
            // and shipped anyway. CountDownLatch + finished-flag: the helper's
            // output is only appended when the helper COMPLETED within the
            // budget; on timeout we write a marker and move on.
            val latch = java.util.concurrent.CountDownLatch(1)
            // @Volatile is not applicable to locals — the CountDownLatch's
            // happens-before edge (await returns only after countDown, which
            // follows the write) provides the needed visibility.
            var helperOutput: String? = null
            kotlin.concurrent.thread(start = true, isDaemon = true) {
                runCatching {
                    val helperSb = StringBuilder()
                    WepWawetLogger.snapshot().forEach { entry ->
                        helperSb.append(entry.format()).append("\n")
                    }
                    helperOutput = helperSb.toString()
                }
                latch.countDown()
            }
            val finished = try {
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (finished) {
                sb.append(helperOutput ?: "")
            } else {
                sb.append("(log snapshot timed out after 2s — ring buffer possibly locked)\n")
            }
        }

        sb.append("\n--- Thread dump (via /proc/self/task) ---\n")
        runCatching { dumpTopThreads(sb) }

        sb.append("\n--- /proc/self/maps (first 50 lines) ---\n")
        runCatching {
            val maps = java.io.File("/proc/self/maps").readLines()
            for (i in maps.indices) {
                if (i >= 50) break
                sb.append(maps[i]).append("\n")
            }
            if (maps.size > 50) sb.append("... (").append(maps.size - 50).append(" more lines)\n")
        }

        return sb.toString()
    }

    // ====== Write to internal storage (always works) ======

    private fun writeInternal(report: String, ts: Long) {
        val crashDir = java.io.File(appContext.filesDir, "wepwawet/crashes").apply { mkdirs() }
        val crashFile = java.io.File(crashDir, "crash-$ts.txt")
        crashFile.writeText(report)
        // Also write a "latest" symlink-ish file so the user can always find the most recent
        runCatching {
            java.io.File(crashDir, "crash-latest.txt").writeText(report)
        }
    }

    // ====== Write to public Downloads/WepWawet/ (user-visible) ======

    private fun writePublicDownloads(context: Context, report: String, ts: Long) {
        val fileName = "crash-$ts.txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ — use MediaStore.Downloads collection (no permission needed)
            writeViaMediaStore(context, fileName, report)
        } else {
            // Android 9 and below — legacy File API to public Downloads dir
            writeLegacyDownloads(fileName, report)
        }

        // Also write a "latest" file in Downloads/WepWawet/ so user can find it quickly
        val latestName = "crash-latest.txt"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // v0.7.24 (H-A4 BUG FIX): the previous crash-latest.txt row
                // was never deleted, so MediaStore auto-renamed every new
                // write to "crash-latest (1).txt", "(2).txt", … — the user
                // saw a growing pile of "latest" files while the actual
                // crash-latest.txt stayed frozen at the first crash. Delete
                // the existing row (only OURS — we own it) before inserting.
                val resolver = context.contentResolver
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(latestName, "Download/WepWawet/"),
                )
                writeViaMediaStore(context, latestName, report)
            } else {
                writeLegacyDownloads(latestName, report)
            }
        }
    }

    /**
     * MediaStore insertion on Android 10+. Files land in:
     *   /storage/emulated/0/Download/WepWawet/crash-<ts>.txt
     *
     * We use RELATIVE_PATH to direct the file into the WepWawet/ subdirectory
     * of the public Downloads collection. No permission needed because:
     *   - We're creating a NEW file (not modifying someone else's)
     *   - The app that creates a MediaStore entry owns it and can rewrite it
     */
    private fun writeViaMediaStore(context: Context, fileName: String, report: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // RELATIVE_PATH requires Android 10+; on Android 10 it's "Download/WepWawet/..."
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/WepWawet")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri: Uri = resolver.insert(collection, values)
            ?: throw java.io.IOException("MediaStore.insert returned null for $fileName")

        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                out.write(report.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw java.io.IOException("openOutputStream returned null for $uri")

            // Mark as no longer pending so the file becomes visible to the user
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            // If write fails, delete the partial entry so we don't leave an empty file
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * Legacy write for Android 9 and below — directly to the public Downloads dir.
     * Requires WRITE_EXTERNAL_STORAGE permission (declared in manifest with maxSdkVersion=28).
     */
    @Suppress("DEPRECATION")
    private fun writeLegacyDownloads(fileName: String, report: String) {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = java.io.File(downloads, "WepWawet").apply { mkdirs() }
        val file = java.io.File(dir, fileName)
        file.writeText(report)
    }

    private fun dumpTopThreads(sb: StringBuilder) {
        // Note: java.lang.management is NOT available on Android — use /proc/self/task
        // to enumerate threads.
        val taskDir = java.io.File("/proc/self/task")
        val tids = taskDir.listFiles()?.toList()
            ?.sortedByDescending { it.name.toLongOrNull() ?: 0 }
            ?: emptyList()
        sb.append("(found ${tids.size} threads via /proc/self/task)\n")
        for (tid in tids.take(20)) {
            val commFile = java.io.File("/proc/self/task/${tid.name}/comm")
            val comm = if (commFile.exists()) commFile.readText().trim() else "?"
            sb.append("  tid=").append(tid.name).append(" name=").append(comm).append("\n")
        }
    }

    companion object {
        private const val TAG = "WepWawet-Crash"

        fun install(context: Context) {
            try {
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                if (previous is CrashHandler) return  // already installed
                Thread.setDefaultUncaughtExceptionHandler(
                    CrashHandler(context.applicationContext, previous)
                )
                Log.i(TAG, "CrashHandler installed")
            } catch (t: Throwable) {
                // If install itself fails, log to logcat and continue — we don't want
                // to bring down the app while trying to set up crash capture.
                Log.e(TAG, "CrashHandler.install failed", t)
            }
        }
    }
}
