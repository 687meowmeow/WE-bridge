package com.wepwawet.log

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * WorkManager job that flushes the WepWawetLogger ring buffer to a daily file in
 * app-internal storage: `files/wepwawet/logs/wepwawet-YYYYMMDD.log`
 *
 * Scheduled by WepWawetLogger.init via WorkManager with:
 *   - 15-minute periodic interval (the minimum allowed)
 *   - KEEP existing work policy (so re-init on app start doesn't double-schedule)
 *   - Constraints: not low-battery (so we don't burn cycles when the user needs them)
 *
 * Pruning: any file older than 14 days is deleted.
 *
 * If the ring buffer is empty, the worker still runs (cheap no-op) — this is intentional,
 * we want the worker active so the pruning logic fires regularly.
 */
class LogPersistenceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val logsDir = File(applicationContext.filesDir, "wepwawet/logs").apply { mkdirs() }

        // Take a snapshot + read the watermark ATOMICALLY.
        //
        // v0.7.24 (C-A5 BUG FIX): snapshot() and the watermark read were two
        // separate steps. WepWawetLogger.clear() resets BOTH the in-memory seq
        // counter AND the persisted last_seq to 0 — a clear() racing BETWEEN
        // the two reads made the snapshot contain pre-clear high seq numbers
        // while lastSeq was already 0, so EVERY snapshot entry passed the
        // `seq > lastSeq` filter and the entire buffer was re-written to disk.
        // The new WepWawetLogger.snapshotWithWatermark() takes both under the
        // ring-buffer monitor, so the pair is always consistent.
        val prefs = applicationContext.getSharedPreferences("wepwawet_log_persistence", android.content.Context.MODE_PRIVATE)
        val (snapshot, lastSeq) = WepWawetLogger.snapshotWithWatermark(prefs)
        val toWrite = snapshot.filter { it.seq > lastSeq }
        if (toWrite.isNotEmpty()) {
            val today = LocalDate.now(ZoneId.systemDefault())
            val dailyFile = File(logsDir, "wepwawet-${LOG_FMT.format(today)}.log")
            // v0.7.24 (C-A3 + C-A4 REWRITE): the v0.7.4 scheme flushed +
            // commit()ed the watermark PER ENTRY — up to 5000 fsyncs of the
            // log file plus 5000 synchronous SharedPreferences commits (each
            // of which fsyncs the XML) per worker run: 5-25 s of pure I/O on
            // typical storage, flirting with WorkManager's ~10-minute cap on
            // slow flash. It also still allowed duplicates (kill between
            // fw.flush() and prefs.commit() → entry on disk, watermark stale
            // → re-written next run — the exact thing the v0.7.4 comment
            // claimed was impossible).
            //
            // Correctness argument for the batched scheme: we write the whole
            // batch, flush ONCE, then commit the FINAL watermark once. The
            // only kill window that duplicates is between flush and commit —
            // bounded to one batch, and self-healing: the duplicate lines are
            // inert log text (no parsing downstream), unlike the lost-entry
            // risk of committing the watermark before the data flush. Losing
            // an entry was NOT acceptable (the v0.7.4 requirement); duplicating
            // a bounded tail is.
            java.io.FileWriter(dailyFile, true).use { fw ->
                for (e in toWrite) {
                    fw.write(e.format())
                    fw.write("\n")
                }
                fw.flush()
            }
            prefs.edit().putLong("last_seq", toWrite.last().seq).commit()
        }

        // Prune
        val cutoffMs = System.currentTimeMillis() - (PRUNE_DAYS.toLong() * 24L * 60L * 60L * 1000L)
        logsDir.listFiles { f ->
            f.isFile && f.name.matches(Regex("wepwawet-\\d{4}-\\d{2}-\\d{2}\\.log"))
        }?.forEach { f ->
            if (f.lastModified() < cutoffMs) f.delete()
        }

        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "wepwawet_log_persistence"
        const val PRUNE_DAYS = 14
        val LOG_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun schedule(context: Context) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = androidx.work.PeriodicWorkRequestBuilder<LogPersistenceWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).setConstraints(constraints).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
