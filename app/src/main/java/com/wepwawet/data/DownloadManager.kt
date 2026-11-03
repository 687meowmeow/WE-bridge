package com.wepwawet.data

import android.content.Context
import com.google.gson.Gson
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent download manager that tracks active and completed wallpaper downloads.
 *
 * v0.5.0: Replaces the ephemeral per-screen download state in WallpaperDetailScreen
 * with a central, app-wide download tracker. The UI can observe [activeDownloads]
 * and [completedDownloads] to show a download manager screen.
 *
 * v0.6.1: Completed downloads are now persisted to SharedPreferences. They survive
 * app restarts. Active downloads are still in-memory only (they can't survive a
 * restart since the download process itself is killed).
 */
object DownloadManager {

    private const val PREF_NAME = "wepwawet_downloads"
    private const val KEY_COMPLETED = "completed_downloads_json"
    private var appContext: Context? = null
    private val gson = Gson()

    // ====== Active downloads (in-progress) ======

    private val _activeDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadProgress>> = _activeDownloads.asStateFlow()

    /**
     * v0.7.24 (H-U2): cached per-item derived flows. WallpaperCard previously
     * collected the WHOLE activeDownloads map + completedDownloads list — one
     * progress tick from ANY download recomposed EVERY card in the grid (a
     * 30-card grid = 30 recompositions, 10+ times per second). These flows
     * only emit when THIS item's entry changes.
     */
    private val activeItemFlows = java.util.concurrent.ConcurrentHashMap<String, StateFlow<DownloadProgress?>>()
    private val completedItemFlows = java.util.concurrent.ConcurrentHashMap<String, StateFlow<Boolean>>()

    fun activeDownloadFor(itemId: String): StateFlow<DownloadProgress?> =
        activeItemFlows.computeIfAbsent(itemId) { id ->
            // NOTE: extension-function syntax (flow.map { }), not top-level —
            // Kotlin cannot call extensions as map(flow) {}.
            activeDownloads.map { it[id] }
                .stateIn(
                    com.wepwawet.WepWawetApplication.appScope,
                    kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                    null)
        }

    fun isCompletedFor(itemId: String): StateFlow<Boolean> =
        completedItemFlows.computeIfAbsent(itemId) { id ->
            completedDownloads.map { list -> list.any { it.itemId == id } }
                .stateIn(
                    com.wepwawet.WepWawetApplication.appScope,
                    kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                    false)
        }

    // ====== Completed downloads (history) ======

    private val _completedDownloads = MutableStateFlow<List<CompletedDownload>>(emptyList())
    val completedDownloads: StateFlow<List<CompletedDownload>> = _completedDownloads.asStateFlow()

    /** Lock for concurrent access to completed downloads list. */
    private val completedLock = Any()

    /**
     * Initialize from SharedPreferences. Call once at app startup.
     * Loads previously-completed downloads from disk.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        loadCompletedFromDisk()
    }

    private fun loadCompletedFromDisk() {
        try {
            val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return
            val json = prefs.getString(KEY_COMPLETED, null) ?: return
            // v0.7.4: parse via Array<CompletedDownload>::class.java instead of an
            // anonymous `object : TypeToken<List<CompletedDownload>>() {}` — the
            // same R8/Signature-stripping hazard SteamApiService documents at its
            // own TypeToken removal note. The Array form needs no reflection on
            // generic signatures at all, so minified release builds can't hit
            // "TypeToken must be created with a type argument" here.
            val loaded: List<CompletedDownload> =
                gson.fromJson(json, Array<CompletedDownload>::class.java)?.toList() ?: emptyList()
            _completedDownloads.value = loaded
            WepWawetLogger.workshop("DownloadManager: loaded completed downloads from disk",
                "count" to loaded.size)
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn("DownloadManager: failed to load completed downloads from disk",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
        }
    }

    private fun saveCompletedToDisk() {
        try {
            val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return
            val json = gson.toJson(_completedDownloads.value)
            prefs.edit().putString(KEY_COMPLETED, json).apply()
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn("DownloadManager: failed to save completed downloads to disk",
                "exception" to e.javaClass.name)
        }
    }

    /**
     * Start tracking a new download.
     * Called when the user taps "Send to phone" on the detail screen.
     */
    fun startDownload(item: WorkshopItemTyped) {
        val progress = DownloadProgress(
            itemId = item.id,
            title = item.title,
            previewUrl = item.previewUrl,
            creator = item.creator,
            type = item.type,
            status = DownloadStatus.CONNECTING,
            progress = 0f,
            statusMessage = "Connecting to Steam...",
        )
        _activeDownloads.update { it + (item.id to progress) }
        WepWawetLogger.workshop("DownloadManager: started download",
            "item_id" to item.id, "title" to item.title)
    }

    /**
     * Update the progress of an active download.
     */
    fun updateProgress(
        itemId: String,
        progress: Float,
        status: DownloadStatus,
        statusMessage: String,
    ) {
        _activeDownloads.update { current ->
            val existing = current[itemId] ?: return@update current
            current + (itemId to existing.copy(
                progress = progress,
                status = status,
                statusMessage = statusMessage,
            ))
        }
    }

    /**
     * Mark a download as completed and move it to the completed list.
     *
     * v0.7.4: atomically capture + remove from _activeDownloads in ONE update
     * call, mirroring failDownload's v0.5.32 (audit M3) fix. The old code read
     * the active entry OUTSIDE any lock and removed it in a SECOND update after
     * updating the completed list — a concurrent failDownload(itemId) between
     * the read and the removal produced a "Completed" entry (with "Unknown"
     * title metadata) that CLOBBERED the just-written FAILED entry, and vice
     * versa. When the item wasn't in active at all we still record a completed
     * entry with default metadata (previous behavior).
     */
    fun completeDownload(itemId: String, fileSizeBytes: Long, deviceName: String) {
        var captured: DownloadProgress? = null
        _activeDownloads.update { current ->
            val existing = current[itemId]
            if (existing == null) {
                current
            } else {
                captured = existing
                current - itemId
            }
        }
        val active = captured
        synchronized(completedLock) {
            val completed = CompletedDownload(
                itemId = itemId,
                title = active?.title ?: "Unknown",
                previewUrl = active?.previewUrl,
                creator = active?.creator ?: "",
                type = active?.type ?: com.wepwawet.data.model.WallpaperType.UNKNOWN,
                completedAt = System.currentTimeMillis(),
                fileSizeBytes = fileSizeBytes,
                deviceName = deviceName,
                status = DownloadStatus.COMPLETED,
            )
            // Remove from completed if same item already exists (re-download)
            _completedDownloads.update { existing ->
                (listOf(completed) + existing.filter { it.itemId != itemId })
            }
            saveCompletedToDisk()
        }
        WepWawetLogger.workshop("DownloadManager: completed download",
            "item_id" to itemId, "size" to fileSizeBytes, "device" to deviceName)
    }

    /**
     * Mark a download as failed.
     */
    fun failDownload(itemId: String, errorMessage: String) {
        // v0.5.32 (audit M3): Atomically remove from active AND capture
        // the existing entry's metadata in ONE update call. Previously
        // the code did two separate _activeDownloads.update calls:
        //   1. Set status=FAILED (but keep in active)
        //   2. Read _activeDownloads.value[itemId]
        //   3. Add to completed under completedLock
        //   4. Remove from active
        // Between step 1 and step 4, a concurrent updateProgress() call
        // could see the entry still in active, hit `existing ?: return`,
        // and overwrite FAILED with new progress — the user would see
        // the download flip from "Failed" back to "Downloading…" in the
        // UI. (Once §1's progress-mirroring fix is applied and
        // updateProgress is actually called, this race becomes reachable.)
        //
        // The fix: skip the "set FAILED in active" intermediate step
        // entirely (the FAILED status is captured in the completed
        // entry's `status` field, not in active). Atomically remove
        // from active and capture the entry's metadata (title,
        // previewUrl, creator, type) in one update. After this point,
        // any concurrent updateProgress sees itemId is missing from
        // active and returns early — no race.
        var captured: DownloadProgress? = null
        _activeDownloads.update { current ->
            val existing = current[itemId]
            if (existing == null) {
                // Not in active — leave captured as null, no change.
                current
            } else {
                // Capture metadata for the completed list, then remove.
                captured = existing
                current - itemId
            }
        }

        val snapshot = captured ?: run {
            // itemId wasn't in active — nothing to fail. Log and return.
            WepWawetLogger.workshopWarn("DownloadManager: failDownload no-op — itemId not in active",
                "item_id" to itemId, "error" to errorMessage)
            return
        }

        synchronized(completedLock) {
            val completed = CompletedDownload(
                itemId = itemId,
                title = snapshot.title,
                previewUrl = snapshot.previewUrl,
                creator = snapshot.creator,
                type = snapshot.type,
                completedAt = System.currentTimeMillis(),
                fileSizeBytes = 0,
                deviceName = "",
                status = DownloadStatus.FAILED,
                errorMessage = errorMessage,
            )
            _completedDownloads.update { existing ->
                (listOf(completed) + existing.filter { it.itemId != itemId })
            }
            saveCompletedToDisk()
        }
        WepWawetLogger.workshopWarn("DownloadManager: failed download",
            "item_id" to itemId, "error" to errorMessage)
    }

    /**
     * Cancel an active download.
     */
    fun cancelDownload(itemId: String) {
        _activeDownloads.update { it - itemId }
        WepWawetLogger.workshop("DownloadManager: cancelled download",
            "item_id" to itemId)
    }

    /**
     * Remove a completed download from history.
     */
    fun removeCompleted(itemId: String) {
        synchronized(completedLock) {
            _completedDownloads.update { it.filter { c -> c.itemId != itemId } }
            saveCompletedToDisk()
        }
    }

    /**
     * Clear all completed downloads from history.
     */
    fun clearCompleted() {
        synchronized(completedLock) {
            _completedDownloads.value = emptyList()
            saveCompletedToDisk()
        }
    }

    /**
     * Check if an item is currently being downloaded.
     */
    fun isActive(itemId: String): Boolean = itemId in _activeDownloads.value

    /**
     * Check if an item has been downloaded before.
     */
    fun isCompleted(itemId: String): Boolean =
        _completedDownloads.value.any { it.itemId == itemId }
}

// ====== Data classes ======

data class DownloadProgress(
    val itemId: String,
    val title: String,
    val previewUrl: String?,
    val creator: String,
    val type: com.wepwawet.data.model.WallpaperType,
    val status: DownloadStatus,
    val progress: Float,          // 0.0 .. 1.0
    val statusMessage: String,
)

data class CompletedDownload(
    val itemId: String,
    val title: String,
    val previewUrl: String?,
    val creator: String,
    val type: com.wepwawet.data.model.WallpaperType,
    val completedAt: Long,        // epoch millis
    val fileSizeBytes: Long,
    val deviceName: String,
    val status: DownloadStatus,
    val errorMessage: String? = null,
)

enum class DownloadStatus {
    CONNECTING,
    DOWNLOADING,
    PACKING,
    QUEUING,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;
}
