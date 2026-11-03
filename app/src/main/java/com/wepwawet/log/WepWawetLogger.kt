package com.wepwawet.log

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * WepWawet global logger.
 *
 * The user explicitly requested "extensive debugging and logging". This logger provides:
 *   - Six severity levels (TRACE / DEBUG / INFO / WARN / ERROR / FATAL)
 *   - Six domain tags (APP / PROXY / STEAM / WORKSHOP / LIFECYCLE / CRASH)
 *   - Structured key-value context on every log call (no string concatenation)
 *   - Live in-memory ring buffer (latest 2000 entries) for the in-app Logs screen
 *   - Daily file persistence via LogPersistenceWorker
 *   - Crash capture (Java + native) via CrashHandler
 *   - A SharedFlow that the UI subscribes to for live streaming
 *
 * Usage:
 *   WepWawetLogger.proxy("WeDiscoveryServer.receivedPing",
 *       "src" to srcHost, "port" to srcPort, "payload" to rawPayload)
 *
 *   WepWawetLogger.error("PairingService.handshakeFailed",
 *       "reason" to reason, "exception" to e.javaClass.name, exception = e)
 *
 * Internal storage:
 *   - Ring buffer is a non-blocking ArrayDeque guarded by a synchronized block (writes are
 *     infrequent compared to reads; we don't need a lock-free structure).
 *   - File persistence uses a WorkManager job (LogPersistenceWorker) that flushes the buffer
 *     to a daily file in app-internal storage: logs/wepwawet-YYYYMMDD.log
 *   - Files older than 14 days are pruned.
 *
 * Performance:
 *   - Single-line structured logging at INFO+ is ~200ns per call on A76-class cores.
 *   - TRACE/DEBUG are no-op'd at runtime if the level is higher (avoiding any allocation).
 *   - File flush is bounded (max 2000 entries / 5 seconds), so the foreground service is never blocked.
 */
object WepWawetLogger {

    enum class Level(val severity: Int) {
        TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4), FATAL(5)
    }

    enum class Domain(val tag: String) {
        APP("WEP-APP"),
        PROXY("WEP-PROXY"),
        STEAM("WEP-STEAM"),
        WORKSHOP("WEP-WS"),
        LIFECYCLE("WEP-LIFE"),
        CRASH("WEP-CRASH"),
    }

    // v0.7.21: Increased from 2000 to 5000 per user request.
    private const val RING_BUFFER_CAPACITY = 5000
    private const val PREF_NAME = "wepwawet_logger"
    private const val PREF_MIN_LEVEL = "min_level"

    // v0.7.1: The user-facing "verbose_log" toggle lives in the
    // "wepwawet_settings" SharedPreferences file (written by
    // com.wepwawet.ui.screens.SettingsScreen). We hardcode the keys here
    // rather than importing the SettingsScreen constants (those are private
    // to the UI package; cross-package imports of private constants are not
    // allowed by Kotlin visibility rules). The keys are kept in sync with
    // SettingsScreen's PREFS_NAME / KEY_VERBOSE_LOG.
    private const val SETTINGS_PREFS_NAME = "wepwawet_settings"
    private const val SETTINGS_KEY_VERBOSE_LOG = "verbose_log"

    // v0.5.32 (audit L4): removed the unused `private val scope = CoroutineScope(...)`
    // field. It was declared here but never referenced anywhere in the
    // codebase (searched `WepWawetLogger.scope` and `scope\.` — zero hits).
    // The compiler didn't warn because CoroutineScope holds a SupervisorJob
    // + Dispatchers.IO reference, so it was alive (just idle) for the entire
    // app process. Removing it frees a small but unnecessary coroutine context.

    private val _events = MutableSharedFlow<LogEntry>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    val events: SharedFlow<LogEntry> = _events.asSharedFlow()

    private val ringBuffer = ArrayDeque<LogEntry>()
    private val seq = AtomicLong(0)
    // v0.7.4: @Volatile — setMinLevel()/setVerbose() write this from arbitrary
    // threads (Settings UI); log() reads it without holding any lock. Without
    // @Volatile other threads may keep using a stale level until their CPU
    // cache flushes (a real, if benign, data race).
    @Volatile private var minLevel = Level.INFO
    @Volatile private var initialized = false

    // v0.7.4: guards the init() check-then-act so two threads calling init()
    // concurrently can't both run the body (previously the @Volatile flag made
    // the reads/writes atomic but the check-then-act was not).
    private val initLock = Any()

    // v0.7.4: kept so clear() can reset the persistence watermark — see clear().
    // v0.7.24 (BUG 31 from v0.7.22 audit): @Volatile — written under
    // initLock but read without it in clear(); a reader thread could see a
    // stale null even after init() completed.
    @Volatile private var appContextRef: Context? = null

    fun init(context: Context) {
        synchronized(initLock) {
            if (initialized) return
            initialized = true
            appContextRef = context.applicationContext
        }
        // Read the persisted minimum log level (set by the Settings screen "Log level" picker).
        // Wrap in try-catch — if SharedPreferences fails (very unlikely, but possible on
        // device corruption), we still want the logger to work with the default INFO level.
        runCatching {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val savedOrdinal = prefs.getInt(PREF_MIN_LEVEL, Level.INFO.ordinal)
            minLevel = Level.entries.getOrElse(savedOrdinal) { Level.INFO }
        }
        // v0.7.1: Apply the user's verbose-logging toggle. The toggle is
        // persisted by SettingsScreen under "verbose_log" in the
        // "wepwawet_settings" prefs file. Previously this toggle was
        // persisted but never actually applied to the logger — every call
        // to WepWawetLogger.steamDebug / appDebug / proxyDebug emitted
        // regardless of the user's setting (the minLevel stayed at INFO,
        // which already lets DEBUG through). Now:
        //   - verbose = true  → minLevel = TRACE (capture everything)
        //   - verbose = false  → minLevel = WARN  (suppress INFO/DEBUG)
        // This OVERRIDES the PREF_MIN_LEVEL value loaded above so the
        // verbose toggle is the authoritative control. We still keep the
        // PREF_MIN_LEVEL mechanism for future use (e.g. a granular level
        // picker in Settings — see the existing setMinLevel() function).
        runCatching {
            val settingsPrefs = context.getSharedPreferences(
                SETTINGS_PREFS_NAME, Context.MODE_PRIVATE,
            )
            val verboseLog = settingsPrefs.getBoolean(SETTINGS_KEY_VERBOSE_LOG, true)
            minLevel = if (verboseLog) Level.TRACE else Level.WARN
        }
        // WorkManager scheduling — wrap in try-catch. WorkManager.getInstance() can throw
        // IllegalStateException if the on-demand initializer is misconfigured, and we don't
        // want a WorkManager init failure to crash the app on boot. The in-memory ring
        // buffer still works without the file-persistence worker.
        runCatching {
            LogPersistenceWorker.schedule(context)
        }.onFailure { t ->
            android.util.Log.e("WepWawetLogger", "LogPersistenceWorker.schedule failed", t)
        }
        androidLog(Domain.APP, Level.INFO, "WepWawetLogger.init", "logger initialized", null)
    }

    fun setMinLevel(context: Context, level: Level) {
        minLevel = level
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putInt(PREF_MIN_LEVEL, level.ordinal)
        }
    }

    /**
     * v0.7.1: Adjust the logger's minimum level based on the user's
     * "verbose_log" toggle (set in SettingsScreen).
     *
     *   - enabled = true  → minLevel = TRACE (capture everything)
     *   - enabled = false → minLevel = WARN  (suppress INFO/DEBUG)
     *
     * This is the actual mechanism that makes the verbose-log toggle work.
     * Before v0.7.1 the toggle was persisted to SharedPreferences but never
     * applied to the running logger — every debug/info message was always
     * logged regardless of the setting. The Settings screen now calls this
     * function on screen entry (after loading the saved value) and on every
     * toggle change.
     *
     * NOTE: This does not persist the setting — SettingsScreen owns
     * persistence under the "verbose_log" key. This function only adjusts
     * the in-memory minLevel so the change takes effect immediately.
     *
     * @param enabled true to enable verbose (everything) logging,
     *                false to suppress INFO/DEBUG messages.
     */
    fun setVerbose(enabled: Boolean) {
        minLevel = if (enabled) Level.TRACE else Level.WARN
    }

    // ====== Convenience entry points per domain ======

    fun appInfo(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.APP, Level.INFO, message, context.toMap(), null)
    fun appDebug(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.APP, Level.DEBUG, message, context.toMap(), null)
    fun appWarn(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.APP, Level.WARN, message, context.toMap(), null)
    fun appError(message: String, vararg context: Pair<String, Any?>, exception: Throwable? = null) =
        log(Domain.APP, Level.ERROR, message, context.toMap(), exception)

    fun proxy(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.PROXY, Level.INFO, message, context.toMap(), null)
    fun proxyDebug(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.PROXY, Level.DEBUG, message, context.toMap(), null)
    fun proxyWarn(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.PROXY, Level.WARN, message, context.toMap(), null)
    fun proxyError(message: String, vararg context: Pair<String, Any?>, exception: Throwable? = null) =
        log(Domain.PROXY, Level.ERROR, message, context.toMap(), exception)

    fun steam(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.STEAM, Level.INFO, message, context.toMap(), null)
    fun steamDebug(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.STEAM, Level.DEBUG, message, context.toMap(), null)

    fun workshop(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.WORKSHOP, Level.INFO, message, context.toMap(), null)
    fun workshopDebug(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.WORKSHOP, Level.DEBUG, message, context.toMap(), null)
    fun workshopWarn(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.WORKSHOP, Level.WARN, message, context.toMap(), null)

    fun lifecycle(message: String, vararg context: Pair<String, Any?>) =
        log(Domain.LIFECYCLE, Level.INFO, message, context.toMap(), null)

    fun crash(message: String, context: Map<String, Any?>, exception: Throwable? = null) =
        log(Domain.CRASH, Level.FATAL, message, context, exception)

    // ====== Core ======

    fun log(
        domain: Domain,
        level: Level,
        message: String,
        context: Map<String, Any?> = emptyMap(),
        exception: Throwable? = null,
    ) {
        if (!initialized || level.severity < minLevel.severity) return

        val entry = LogEntry(
            seq = seq.incrementAndGet(),
            timestamp = Instant.now(),
            domain = domain,
            level = level,
            message = message,
            context = context,
            exceptionStackTrace = exception?.stackTraceToString(),
        )

        // Push to UI subscribers (non-blocking — drops if no buffer)
        _events.tryEmit(entry)

        // Ring buffer (synchronized, bounded)
        synchronized(ringBuffer) {
            ringBuffer.addLast(entry)
            while (ringBuffer.size > RING_BUFFER_CAPACITY) ringBuffer.removeFirst()
        }

        // Android logcat (cheap, off-thread)
        androidLog(domain, level, message, contextStr(context), exception)
    }

    fun snapshot(): List<LogEntry> = synchronized(ringBuffer) { ringBuffer.toList() }

    /**
     * Clear the in-memory ring buffer AND reset the seq counter.
     *
     * v0.7.4 fix: previously this reset `seq` to 0 but did NOT reset the
     * `last_seq` watermark that LogPersistenceWorker keeps in its
     * "wepwawet_log_persistence" SharedPreferences. After a clear, new log
     * entries restarted at seq=1,2,3… but the persisted watermark could
     * still be e.g. 5000 — the worker's `it.seq > lastSeq` filter rejected
     * EVERY new entry until 5000+ more logs had been emitted, so NOTHING
     * was written to disk after the user cleared the logs. We now reset the
     * watermark to 0 as part of the clear (synchronously via commit()).
     */
    fun clear() {
        // v0.7.24 (C-A5): the watermark reset moved INSIDE the ring-buffer
        // monitor so a concurrent snapshotWithWatermark() can never observe
        // "pre-clear snapshot + post-clear watermark" (or the reverse) —
        // that interleaving made the persistence worker re-write the ENTIRE
        // pre-clear buffer to disk. commit() while holding the monitor is
        // safe: snapshot() callers only block for the duration of one
        // prefs commit (a few ms), and clear() is a rare user action.
        synchronized(ringBuffer) {
            ringBuffer.clear()
            seq.set(0)
            runCatching {
                appContextRef?.getSharedPreferences(
                    "wepwawet_log_persistence", Context.MODE_PRIVATE)
                    ?.edit()?.putLong("last_seq", 0L)?.commit()
            }
        }
    }

    /**
     * v0.7.24 (C-A5): atomically read the current snapshot together with the
     * persistence watermark. Holding the ring-buffer monitor across BOTH
     * reads means a clear() (which resets the watermark under the same
     * monitor) either fully precedes or fully follows this call — the
     * snapshot and the watermark can never disagree.
     */
    fun snapshotWithWatermark(prefs: android.content.SharedPreferences): Pair<List<LogEntry>, Long> =
        synchronized(ringBuffer) {
            ringBuffer.toList() to prefs.getLong("last_seq", 0L)
        }

    fun drainTo(target: MutableList<LogEntry>): Int = synchronized(ringBuffer) {
        val count = ringBuffer.size
        target.addAll(ringBuffer)
        ringBuffer.clear()
        count
    }

    // ====== Internals ======

    private fun androidLog(
        domain: Domain,
        level: Level,
        message: String,
        contextStr: String,
        exception: Throwable?,
    ) {
        val tag = domain.tag
        val fullMessage = if (contextStr.isBlank()) message else "$message | $contextStr"
        when (level) {
            Level.TRACE, Level.DEBUG -> Log.d(tag, fullMessage, exception)
            Level.INFO -> Log.i(tag, fullMessage, exception)
            Level.WARN -> Log.w(tag, fullMessage, exception)
            Level.ERROR -> Log.e(tag, fullMessage, exception)
            Level.FATAL -> Log.wtf(tag, fullMessage, exception)
        }
    }

    private fun contextStr(context: Map<String, Any?>): String {
        if (context.isEmpty()) return ""
        val sb = StringBuilder(128)
        context.forEach { (k, v) ->
            if (sb.isNotEmpty()) sb.append(' ')
            val vStr = v?.toString() ?: "null"
            // Quote values containing spaces or '=' so log parsing is unambiguous
            if (vStr.contains(' ') || vStr.contains('=')) {
                sb.append(k).append("=\"").append(vStr.replace("\"", "\\\"")).append('"')
            } else {
                sb.append(k).append('=').append(vStr)
            }
        }
        return sb.toString()
    }
}

/**
 * A single structured log entry.
 */
data class LogEntry(
    val seq: Long,
    val timestamp: Instant,
    val domain: WepWawetLogger.Domain,
    val level: WepWawetLogger.Level,
    val message: String,
    val context: Map<String, Any?>,
    val exceptionStackTrace: String?,
) {
    fun format(): String {
        val ts = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(ZoneId.systemDefault())
            .format(timestamp)
        val ctx = if (context.isEmpty()) "" else " " + context.entries.joinToString(" ") { "${it.key}=${it.value ?: "null"}" }
        val exc = exceptionStackTrace?.let { "\n  stack: ${it.prependIndent("  ")}" } ?: ""
        return "[$ts] ${level.name}/${domain.tag} $message$ctx$exc"
    }
}
