package com.wepwawet.pairing

import android.content.Context
import android.os.PowerManager
import com.wepwawet.WepWawetApplication
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton holding the state of in-flight (or last-completed) workshop
 * downloads. The state is exposed as a [StateFlow] so any Composable can
 * observe it — including after the user has navigated away from the screen
 * that originally started the download.
 *
 * The download itself is launched on [WepWawetApplication.appScope], which
 * survives Activity/Composable destruction. So the user can:
 *  - Start a download from the detail screen
 *  - Navigate back to the browse list
 *  - Come back later and the download will either still be running, or have
 *    finished and saved the .mpkg to Downloads.
 *
 * v0.7.20: CONCURRENT DOWNLOADS — multiple downloads can now run at the
 * same time. The old singleton had a single `Job?` + `State.isRunning`
 * guard that serialized all downloads (launching a second while the first
 * was running was rejected). Now each item has its own [State] in
 * [_states] and its own [Job] in [jobs], keyed by workshop item ID. The
 * detail screen observes [stateFor] for its specific item, so the
 * "Send to phone" button is enabled per-item (not globally).
 */
object WorkshopDownloadJob {

    // v0.7.4: settings keys duplicated from SettingsScreen (whose constants
    // are private). Kept in sync with SettingsScreen's PREFS_NAME /
    // KEY_PKG_CACHE_ENABLED — same pattern WepWawetLogger uses for the
    // verbose_log key.
    private const val WEPWAWET_SETTINGS_PREFS = "wepwawet_settings"
    private const val WEPWAWET_KEY_PKG_CACHE_ENABLED = "pkg_cache_enabled"

    /** Snapshot of the current (or last) download's state. */
    data class State(
        val isRunning: Boolean = false,
        /** 0..1 — chunk-level progress reported by JavaSteam. May stay at 0
         *  for small downloads where the entire payload fits in one chunk. */
        val progress: Float = 0f,
        /** Human-readable status line (e.g. "Connected to Steam — authenticating..."). */
        val status: String = "",
        /** Workshop item id this download is for. */
        val workshopId: String? = null,
        /** Workshop item title — for the UI to show. */
        val workshopTitle: String? = null,
        /** Set when the download succeeded — final user-facing message. */
        val resultMessage: String? = null,
        /** Set when the download failed — final user-facing error message. */
        val errorMessage: String? = null,
        /** Epoch ms when the download started (for elapsed-time UI). */
        val startedAt: Long = 0L,
        /** Epoch ms when the download ended (success or failure). */
        val endedAt: Long = 0L,
        /** True after at least one chunk-level progress callback has fired —
         *  used by the UI to decide between determinate vs indeterminate bar. */
        val hasChunkProgress: Boolean = false,
        /** Final saved file path (success only) — informational. */
        val savedFilePath: String? = null,
        /** Final saved file size in bytes (success only). */
        val savedFileSize: Long = 0L,
        /** v0.7.1 (round 10): When the downloaded .pkg is a video, this is
         *  set to the downloaded file path so the UI can show a dialog asking
         *  "Do you want to trim the video?" before proceeding with pack + queue.
         *  Null for non-video wallpapers (the normal pack + queue flow runs
         *  immediately). */
        val pendingVideoFile: String? = null,
        /** v0.7.1 (round 10): The workshop item associated with pendingVideoFile. */
        val pendingVideoItem: WorkshopItemTyped? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // v0.7.20: Per-item state map for concurrent downloads. Each item gets
    // its own State entry, so the detail screen can observe just its item's
    // download progress without being affected by other concurrent downloads.
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    // v0.7.20: Per-item job map. Each concurrent download has its own Job,
    // keyed by workshop item ID. Replaced the old single `private var job:
    // Job?` that serialized all downloads.
    private val jobs = ConcurrentHashMap<String, Job>()

    /** v0.7.24 (H-D6): remove a completed job's own map entry (no-op if a
     *  newer job already replaced it). Called from launchSafely's finally. */
    internal fun removeJobEntry(itemId: String, job: Job?) {
        if (job != null) jobs.remove(itemId, job)
    }

    // v0.7.20: Cached StateFlows for stateFor() — creating a new StateFlow
    // per recomposition would leak, so we cache one per item ID.
    private val stateFlows = ConcurrentHashMap<String, StateFlow<State>>()

    /**
     * v0.7.20: Returns a [StateFlow] for a specific workshop item's download
     * state. The detail screen calls this to observe per-item progress /
     * status / pending-video state. Multiple concurrent downloads each have
     * their own independent state flow.
     */
    fun stateFor(itemId: String): StateFlow<State> =
        stateFlows.computeIfAbsent(itemId) { id ->
            _states
                // v0.7.24 (H-D7 BUG FIX): was SharingStarted.Eagerly — every
                // item whose detail screen was ever opened kept a
                // permanently-active upstream coroutine on appScope forever
                // (browsing 500 wallpapers = 500 leaked coroutines + flows).
                // WhileSubscribed stops the upstream when no UI collects it
                // (with a 5s grace window across recompositions), while the
                // cached StateFlow itself still replays the last value to any
                // new collector instantly.
                .map { it[id] ?: State() }
                .stateIn(WepWawetApplication.appScope, SharingStarted.WhileSubscribed(5000), State())
        }

    /**
     * v0.7.20: Returns the pending video file path + item for a specific
     * workshop item, or null if the item has no pending video. Used by the
     * NavHost when navigating to the VideoTrimScreen (the video file path
     * can't be passed as a nav arg because paths contain slashes).
     */
    fun pendingVideoFor(itemId: String): Pair<String, WorkshopItemTyped>? {
        val s = _states.value[itemId] ?: return null
        val path = s.pendingVideoFile ?: return null
        val item = s.pendingVideoItem ?: return null
        return path to item
    }

    /**
     * v0.7.20: Update a specific item's state.
     *
     * v0.7.24 (H-D9): the legacy `_state` mirror previously wrote the
     * calling item's state on EVERY update — with concurrent downloads, two
     * items alternated overwriting it, so any remaining `state` collector
     * saw the progress flicker between items. The mirror is now only
     * maintained for single-download sessions (exactly one item with a job
     * in the map); when multiple downloads are in flight the mirror is
     * cleared to the neutral State rather than flickering. All real
     * consumers were migrated to [stateFor] in v0.7.20-v0.7.23; the mirror
     * exists only for compatibility.
     */
    private fun updateState(itemId: String, update: (State) -> State) {
        _states.update { current ->
            val existing = current[itemId] ?: State(workshopId = itemId)
            current + (itemId to update(existing))
        }
        val running = jobs.count { it.value.isActive }
        _state.value = when {
            running <= 1 -> _states.value[itemId] ?: State()
            else -> State()
        }
    }

    // === v0.5.9-alpha (R8 fix): mirror state into DownloadManager so the
    //     Downloads screen + WallpaperCard "Downloading…" overlay + "✓" badge
    //     actually populate. Previously (v0.5.0-v0.5.8) WallpaperDetailScreen
    //     used inline scope.launch coroutines and NEVER called
    //     DownloadManager.start/complete/fail — the Downloads top-bar icon
    //     navigated to an empty screen. Now that the screen delegates to
    //     launchSendToPhone/launchDebugDownload (v0.4.14 pattern, restored
    //     in R1 fix), we wire DownloadManager at these strategic points.
    //
    // === v0.5.32-alpha (audit §1 fix): EXTEND the mirror to PROGRESS +
    //     STATUS updates, not just start/complete/fail/cancel. The original
    //     R8 mirror was start/complete/fail/cancel only, with the rationale
    //     that "the granular progress bar is already shown on the detail
    //     screen". But the Downloads screen (DownloadManagerScreen) ALSO
    //     renders `download.progress` as a progress bar + percentage text,
    //     and the WallpaperCard overlay ALSO renders `activeDownload.progress`
    //     as a progress bar + status message. So with the R8-only mirror,
    //     both UI surfaces got stuck at the initial "0% / Connecting to
    //     Steam…" state for the entire download duration until the entry
    //     was removed by completeDownload()/failDownload(). The user
    //     perceived this as "the download is frozen".
    //
    //     Fix: call DownloadManager.updateProgress() from the onProgress
    //     and onStatusUpdate callbacks of WorkshopDownloader.downloadWorkshopPkg,
    //     AND from each `_state.update { ... }` call in the packing /
    //     saving / queueing phases (so the Downloads screen sees the same
    //     phase transitions as the detail screen). ===
    private fun onDownloadStarted(item: WorkshopItemTyped) {
        com.wepwawet.data.DownloadManager.startDownload(item)
    }

    /**
     * Mirror a progress + status update into [com.wepwawet.data.DownloadManager].
     *
     * Called from the onProgress / onStatusUpdate callbacks of
     * [WorkshopDownloader.downloadWorkshopPkg] AND from each phase
     * transition in [launchDebugDownload] / [launchSendToPhone] (downloading →
     * packing → saving / queueing).
     *
     * If the item is no longer in [com.wepwawet.data.DownloadManager.activeDownloads]
     * (e.g., the user pressed Cancel from the Downloads screen, or the
     * download has already completed/failed), [com.wepwawet.data.DownloadManager.updateProgress]
     * is a no-op — see its `current[itemId] ?: return@update current` guard.
     * So this is safe to call even after the active entry has been removed.
     */
    private fun onDownloadProgress(
        itemId: String,
        progress: Float,
        status: com.wepwawet.data.DownloadStatus,
        statusMessage: String,
    ) {
        com.wepwawet.data.DownloadManager.updateProgress(
            itemId = itemId,
            progress = progress,
            status = status,
            statusMessage = statusMessage,
        )
    }

    private fun onDownloadCompleted(item: WorkshopItemTyped, fileSizeBytes: Long, deviceName: String) {
        com.wepwawet.data.DownloadManager.completeDownload(item.id, fileSizeBytes, deviceName)
    }

    private fun onDownloadFailed(item: WorkshopItemTyped, errorMessage: String) {
        com.wepwawet.data.DownloadManager.failDownload(item.id, errorMessage)
    }

    private fun onDownloadCancelled(itemId: String?) {
        if (itemId != null) {
            com.wepwawet.data.DownloadManager.cancelDownload(itemId)
        }
    }

    /**
     * Start (or queue) the "Save .mpkg to Downloads (debug)" download.
     *
     * v0.7.20: Returns `false` if THIS ITEM already has a download running
     * (other items can download concurrently). The caller should show a
     * "this item is already downloading" toast or just ignore the tap.
     *
     * @param context app/context (used for cache dir, Steam credentials)
     * @param item workshop item to download
     * @return true if a new download was started
     */
    fun launchDebugDownload(
        context: Context,
        item: WorkshopItemTyped,
    ): Boolean {
        val itemId = item.id
        // v0.7.20: Per-item guard — only reject if THIS item is already
        // downloading. Other items can download concurrently.
        if (jobs[itemId]?.isActive == true) {
            WepWawetLogger.workshop("WorkshopDownloadJob: launch rejected — this item already running",
                "requested_id" to itemId)
            return false
        }

        val appContext = context.applicationContext
        val steamUser = SteamCredentialsStore.getUsername(appContext)
        val steamPass = SteamCredentialsStore.getPassword(appContext)
        if (steamUser == null || steamPass == null) {
            updateState(itemId) { it.copy(
                errorMessage = "Steam login not set. Open Settings → Steam account first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }

        val sendId = System.currentTimeMillis()
        val pkgFile = File(appContext.cacheDir, "${itemId}_${sendId}_debug.pkg")
        val downloader = WorkshopDownloader(steamUser, steamPass)

        updateState(itemId) { it.copy(
            isRunning = true,
            progress = 0f,
            status = "Downloading real .pkg via JavaSteam (debug)…",
            workshopId = itemId,
            workshopTitle = item.title,
            resultMessage = null,
            errorMessage = null,
            startedAt = System.currentTimeMillis(),
            endedAt = 0L,
            hasChunkProgress = false,
            savedFilePath = null,
            savedFileSize = 0L,
            // v0.7.4: clear any stale pending-video state (same as the send
            // path) so a leftover trim-dialog file can't resurface.
            pendingVideoFile = null,
            pendingVideoItem = null,
        ) }
        // v0.5.9-alpha (R8): mirror the start into DownloadManager so the
        // Downloads screen + WallpaperCard "Downloading…" overlay populate.
        onDownloadStarted(item)

        WepWawetLogger.workshop("WorkshopDownloadJob: launching debug download",
            "id" to itemId,
            "title" to item.title.take(80),
            "pkg_cache_path" to pkgFile.absolutePath)

        jobs[itemId] = WepWawetApplication.appScope.launchSafely(itemId) {
            var downloaderRef: WorkshopDownloader? = downloader
            try {
                WepWawetLogger.workshop("WorkshopDownloadJob: coroutine STARTED",
                    "id" to item.id,
                    "thread" to Thread.currentThread().name)
            // v0.7.4: same .pkg cache wiring as the send path (see
            // launchSendToPhone) — reuse a cached scene .pkg when the
            // "Cache .pkg files" setting is enabled.
            val pkgCacheEnabled = appContext.getSharedPreferences(
                WEPWAWET_SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(WEPWAWET_KEY_PKG_CACHE_ENABLED, false)
            val cachedPkg = File(File(appContext.cacheDir, "pkg_cache"), "${item.id}.pkg")
            val canReuseCache = pkgCacheEnabled && cachedPkg.exists() &&
                cachedPkg.length() > 0L && !detectVideoByMagicBytes(cachedPkg)
            val downloadedPkg: File? = if (canReuseCache) {
                WepWawetLogger.workshop("WorkshopDownloadJob: reusing cached .pkg (skip Steam download)",
                    "id" to item.id,
                    "path" to cachedPkg.absolutePath,
                    "size" to cachedPkg.length())
                updateState(itemId) { s -> s.copy(status = "Using cached .pkg — packing…") }
                cachedPkg
            } else {
            downloader.downloadWorkshopPkg(
                context = appContext,
                workshopId = item.id,
                outputFile = pkgFile,
                onProgress = { pct ->
                    val coerced = pct.coerceIn(0f, 1f)
                    updateState(itemId) { s -> s.copy(
                        progress = coerced,
                        hasChunkProgress = true,
                    ) }
                    // v0.5.32 (audit §1): mirror the progress + a fresh
                    // status message into DownloadManager so the Downloads
                    // screen + WallpaperCard overlay update live (previously
                    // they got stuck at the initial "0% / Connecting to
                    // Steam…" state for the entire download duration).
                    val statusMsg = if (coerced > 0f)
                        "Downloading… ${(coerced * 100).toInt()}%"
                    else "Connecting to Steam…"
                    onDownloadProgress(
                        itemId = item.id,
                        progress = coerced,
                        status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                        statusMessage = statusMsg,
                    )
                    // Log every 10% increment so we can see progress in logs
                    // (not every callback, which would be very spammy for 6MB
                    // files arriving in many small chunks).
                    val pctInt = (coerced * 100).toInt()
                    if (pctInt % 10 == 0) {
                        WepWawetLogger.workshop("WorkshopDownloadJob: progress",
                            "pct" to pctInt,
                            "raw" to pct)
                    }
                },
                onStatusUpdate = { status ->
                    updateState(itemId) { s -> s.copy(status = status) }
                    // v0.5.32 (audit §1): mirror the status string too so
                    // the Downloads screen's statusMessage text stays in
                    // sync with the detail screen.
                    onDownloadProgress(
                        itemId = item.id,
                        // v0.7.20: read per-item progress (was _state.value.progress
                        // which under concurrent downloads would read the wrong item).
                        progress = _states.value[itemId]?.progress ?: 0f,
                        status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                        statusMessage = status,
                    )
                    WepWawetLogger.workshop("WorkshopDownloadJob: status update",
                        "status" to status.take(150))
                },
            )
            }

            WepWawetLogger.workshop("WorkshopDownloadJob: downloadWorkshopPkg RETURNED",
                "id" to item.id,
                "result_null" to (downloadedPkg == null),
                "result_exists" to (downloadedPkg?.exists() ?: false),
                "result_size" to (downloadedPkg?.length() ?: -1L),
                "output_path" to (downloadedPkg?.absolutePath ?: "null"))

            if (downloadedPkg == null || !downloadedPkg.exists() || downloadedPkg.length() == 0L) {
                val err = "Failed to download .pkg via Steam (see logs)"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: pkg download failed/null",
                    "id" to item.id)
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                return@launchSafely
            }

            updateState(itemId) { s -> s.copy(status = "Packing content → .mpkg…", progress = 0.85f) }
            // v0.5.32 (audit §1): mirror the packing phase transition.
            onDownloadProgress(
                itemId = item.id,
                progress = 0.85f,
                status = com.wepwawet.data.DownloadStatus.PACKING,
                statusMessage = "Packing content → .mpkg…",
            )
            val cacheMpkg = File(appContext.cacheDir, "${item.id}_${sendId}_debug.mpkg")
            val installDir = File(appContext.cacheDir, "workshop_downloads/${item.id}")
            // === v0.4.9: Detect video via MAGIC BYTES, not file extension.
            // The downloaded file is always named *.pkg by WorkshopDownloader
            // (the outputFile parameter is ${item.id}_${sendId}_debug.pkg), so
            // checking the extension .mp4 NEVER works. Instead, check the
            // file's magic bytes:
            //   - MP4 files have "ftyp" at offset 4
            //   - PKGV (.pkg) files have "PKGV" at offset 4
            val isVideo = detectVideoByMagicBytes(downloadedPkg)
            // v0.7.4 (parity G2): image-type downloads (JPEG/PNG/WebP/BMP/GIF)
            // pack as a Scene via packSceneWallpaper instead of failing the
            // .pkg parse.
            val isImage = !isVideo && detectImageByMagicBytes(downloadedPkg)
            WepWawetLogger.workshop("WorkshopDownloadJob: packing content → .mpkg",
                "input_file" to downloadedPkg.absolutePath,
                "input_size" to downloadedPkg.length(),
                "mpkg_output" to cacheMpkg.absolutePath,
                "is_video" to isVideo,
                "is_image" to isImage)
            // Acquire a PARTIAL_WAKE_LOCK during packing to prevent CPU from
            // sleeping. ETC2 encoding is CPU-intensive and can take minutes
            // for large textures — without a wake lock, the device may throttle
            // or sleep mid-pack, drastically increasing pack time.
            // v0.7.24 (M-D15): appContext (not `context`) — the parameter can
            // be an Activity; holding its getSystemService result is fine here
            // (POWER_SERVICE returns a system singleton) but the pattern was
            // inconsistent with the other two call sites and risks holding an
            // Activity reference in a coroutine that outlives it.
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WepWawet:MpkgPacker")
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(10 * 60 * 1000L) // 10 minute timeout
            WepWawetLogger.workshop("WorkshopDownloadJob: acquired wake lock for packing",
                "timeout_ms" to (10 * 60 * 1000L))
            val mpkgSize = try {
                // v0.7.21 BUG FIX: was `runCatching { ... }.getOrElse { e -> ... }`
                // which swallowed CancellationException (runCatching catches Throwable).
                // Now uses explicit try/catch that re-throws CancellationException
                // so structured concurrency is preserved.
                try {
                    if (isVideo) {
                        WepWawetLogger.workshop("WorkshopDownloadJob: calling packVideoWallpaper",
                            "video_file" to downloadedPkg.name,
                            "video_size" to downloadedPkg.length())
                        val previewFile = File(installDir, "preview.jpg")
                            .takeIf { it.exists() }
                            ?: File(installDir, "preview.gif")
                                .takeIf { it.exists() }
                        com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                            item = item,
                            videoFile = downloadedPkg,
                            outputFile = cacheMpkg,
                            previewFile = previewFile,
                        )
                    } else if (isImage) {
                        // v0.7.4 (parity G2): image-type Workshop downloads pack
                        // as a Scene (single fullscreen quad).
                        WepWawetLogger.workshop("WorkshopDownloadJob: calling packSceneWallpaper (image item)",
                            "image_file" to downloadedPkg.name,
                            "image_size" to downloadedPkg.length())
                        com.wepwawet.pairing.MpkgPacker.packSceneWallpaper(
                            item = item,
                            imageFile = downloadedPkg,
                            outputFile = cacheMpkg,
                        )
                    } else {
                        WepWawetLogger.workshop("WorkshopDownloadJob: calling packFromPkg",
                            "pkg_file" to downloadedPkg.name,
                            "pkg_size" to downloadedPkg.length())
                        com.wepwawet.pairing.MpkgPacker.packFromPkg(
                            item = item,
                            pkgFile = downloadedPkg,
                            outputFile = cacheMpkg,
                            installDir = installDir,
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val err = "Failed to pack → .mpkg: ${e.message}"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob: pack failed",
                        "exception" to e.javaClass.name, "message" to (e.message ?: ""),
                        "is_video" to isVideo)
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    // v0.7.4: don't delete the CACHE entry on failure — only
                    // the freshly-downloaded temp file.
                    if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete() }
                    return@launchSafely
                }
            } finally {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    WepWawetLogger.workshop("WorkshopDownloadJob: released wake lock")
                }
            }

            WepWawetLogger.workshop("WorkshopDownloadJob: pack done",
                "mpkg_output_size" to mpkgSize,
                "mpkg_output_exists" to cacheMpkg.exists(),
                "is_video" to isVideo)

            if (mpkgSize == 0L || !cacheMpkg.exists()) {
                val err = "Failed to pack .pkg → .mpkg (empty output)"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: empty mpkg output",
                    "size" to mpkgSize,
                    "exists" to cacheMpkg.exists(),
                    "pkg_input_size" to downloadedPkg.length())
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete() }
                return@launchSafely
            }

            updateState(itemId) { s -> s.copy(status = "Saving to Downloads…", progress = 0.95f) }
            // v0.5.32 (audit §1): mirror the "saving to Downloads" phase
            // transition so the Downloads screen shows what's happening
            // (otherwise it would still show "Packing content → .mpkg…"
            // until the download completes).
            onDownloadProgress(
                itemId = item.id,
                progress = 0.95f,
                status = com.wepwawet.data.DownloadStatus.PACKING,
                statusMessage = "Saving to Downloads…",
            )
            WepWawetLogger.workshop("WorkshopDownloadJob: reading mpkg bytes",
                "file" to cacheMpkg.absolutePath,
                "expected_size" to mpkgSize)
            val bytes = runCatching { cacheMpkg.readBytes() }.getOrElse { e ->
                val err = "Failed to read packed .mpkg: ${e.message}"
                WepWawetLogger.workshopWarn("WorkshopDownloadJob: read failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
                if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete(); cacheMpkg.delete() }
                return@launchSafely
            }
            WepWawetLogger.workshop("WorkshopDownloadJob: read mpkg bytes done",
                "bytes_read" to bytes.size)

            val downloadsName = "${item.id}_debug.mpkg"
            WepWawetLogger.workshop("WorkshopDownloadJob: writing to Downloads",
                "filename" to downloadsName,
                "bytes" to bytes.size,
                "target_dir" to "Download/WepWawet")
            val uri = com.wepwawet.log.PublicDownloadsWriter.writeBytes(
                appContext, downloadsName, bytes)
            WepWawetLogger.workshop("WorkshopDownloadJob: PublicDownloadsWriter returned",
                "uri_null" to (uri == null),
                "uri" to (uri?.toString() ?: "null"))
            // v0.7.4: store the .pkg in the cache when the setting is enabled
            // (and this was a fresh scene download), then clean up the temp
            // copy — mirrors the send path.
            if (downloadedPkg != null && downloadedPkg !== cachedPkg) {
                if (pkgCacheEnabled && !isVideo) {
                    runCatching {
                        cachedPkg.parentFile?.mkdirs()
                        downloadedPkg.copyTo(cachedPkg, overwrite = true)
                    }
                }
                runCatching { downloadedPkg.delete() }
            }
            runCatching { cacheMpkg.delete() }
            // === Delete install directory (was deferred from WorkshopDownloader
            // so packFromPkg/packVideoWallpaper could read standalone files like
            // project.json and preview.jpg from it) ===
            runCatching { installDir.deleteRecursively() }
            WepWawetLogger.workshop("WorkshopDownloadJob: install dir cleaned up",
                "path" to installDir.absolutePath)

            if (uri != null) {
                val msg = "Saved ${formatBytes(bytes.size.toLong())} to Downloads/WepWawet/$downloadsName"
                WepWawetLogger.workshop("WorkshopDownloadJob: saved to Downloads",
                    "file" to downloadsName,
                    "size" to bytes.size,
                    "uri" to uri.toString())
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    progress = 1f,
                    status = msg,
                    resultMessage = msg,
                    endedAt = System.currentTimeMillis(),
                    savedFilePath = uri.toString(),
                    savedFileSize = bytes.size.toLong(),
                ) }
                onDownloadCompleted(item, bytes.size.toLong(), "Downloads")
            } else {
                val err = "Failed to write .mpkg to Downloads (see logs)"
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = err,
                    errorMessage = err,
                    endedAt = System.currentTimeMillis(),
                ) }
                onDownloadFailed(item, err)
            }
            } finally {
                // === CRITICAL (v0.4.7): ALWAYS call cleanup() on the WorkshopDownloader,
                // even on success — otherwise the SteamClient's TCP connection to Steam
                // stays alive, blocking subsequent download attempts (Steam rejects
                // the second login with the same loginID from the same IP → AccessDenied).
                // ===
                WepWawetLogger.workshop("WorkshopDownloadJob: cleaning up SteamClient",
                    "id" to item.id)
                runCatching { downloaderRef?.cleanup() }
                downloaderRef = null
            }
        }
        return true
    }

    /**
     * Start "Send to phone" — produces the EXACT same .mpkg as [launchDebugDownload]
     * (same installDir handling, same MpkgPacker.packFromPkg call signature) and queues
     * it for transfer to the paired phone via [PairingService.queueWallpaper].
     *
     * === CRITICAL (v0.4.7): Previously, send-to-phone used a SEPARATE code path
     * in `WallpaperDetailScreen.onSendToPhoneClicked` that did NOT pass `installDir`
     * to `MpkgPacker.packFromPkg`. The debug download DID pass installDir, so the
     * two flows produced DIFFERENT .mpkg files — debug worked, send-to-phone
     * produced a black wallpaper on the phone (missing project.json + preview.jpg
     * standalone files that the WE Android app's renderer needs).
     *
     * Now: send-to-phone uses the SAME code path as debug download (this method),
     * just with a different final step (queue for transfer vs. save to Downloads).
     * The .mpkg byte content is identical, so the rendering is identical.
     *
     * User explicitly said: "send to phone should work smoothly without compromises.
     * it should make the same thing the debug download makes and sends it perfectly."
     *
     * NOTE: We do NOT save a backup .mpkg to Downloads (the user said "saving it
     * downloads defeats the point thats what debug download works" — the debug
     * download button exists separately for that purpose).
     *
     * @param context app/context (used for cache dir, Steam credentials)
     * @param item workshop item to download + send
     * @return true if a new download was started
     */
    fun launchSendToPhone(
        context: Context,
        item: WorkshopItemTyped,
    ): Boolean {
        val itemId = item.id
        if (jobs[itemId]?.isActive == true) {
            WepWawetLogger.workshop("WorkshopDownloadJob: launch rejected — this item already running",
                "requested_id" to itemId)
            return false
        }

        val appContext = context.applicationContext
        val steamUser = SteamCredentialsStore.getUsername(appContext)
        val steamPass = SteamCredentialsStore.getPassword(appContext)
        if (steamUser == null || steamPass == null) {
            updateState(itemId) { it.copy(
                errorMessage = "Steam login not set. Open Settings → Steam account first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }
        if (!PairingService.isPaired()) {
            updateState(itemId) { it.copy(
                errorMessage = "Not paired — open the Pairing tab first.",
                endedAt = System.currentTimeMillis(),
            ) }
            return false
        }

        val sendId = System.currentTimeMillis()
        val pkgFile = File(appContext.cacheDir, "${item.id}_${sendId}_send.pkg")
        val downloader = WorkshopDownloader(steamUser, steamPass)

        updateState(itemId) { it.copy(
            isRunning = true,
            progress = 0f,
            status = "Sending to phone — downloading real .pkg via JavaSteam…",
            workshopId = item.id,
            workshopTitle = item.title,
            resultMessage = null,
            errorMessage = null,
            startedAt = System.currentTimeMillis(),
            endedAt = 0L,
            hasChunkProgress = false,
            savedFilePath = null,
            savedFileSize = 0L,
            // v0.7.4: clear any stale pending-video state left over from a
            // previous download — otherwise the trim dialog could reappear
            // for a file that no longer belongs to this session.
            pendingVideoFile = null,
            pendingVideoItem = null,
        ) }
        // v0.5.9-alpha (R8): mirror the start into DownloadManager so the
        // Downloads screen + WallpaperCard "Downloading…" overlay populate.
        onDownloadStarted(item)

        WepWawetLogger.workshop("WorkshopDownloadJob: launching send-to-phone",
            "id" to item.id,
            "title" to item.title.take(80),
            "pkg_cache_path" to pkgFile.absolutePath,
            "paired_device" to (PairingService.pairedDeviceName ?: "null"))

        jobs[itemId] = WepWawetApplication.appScope.launchSafely(itemId) {
            var downloaderRef: WorkshopDownloader? = downloader
            try {
                WepWawetLogger.workshop("WorkshopDownloadJob.send: coroutine STARTED",
                    "id" to item.id,
                    "thread" to Thread.currentThread().name)
                // v0.7.4: WIRE UP the "Cache .pkg files" setting (it was
                // previously persisted but read by NO ONE — a pure UI no-op).
                // When enabled, a previously-downloaded scene .pkg for this
                // item is reused and the fresh download is copied to the
                // stable cache path after a successful pack. Only SCENE
                // packages are cached — video files flow through the trim
                // dialog lifecycle where they get consumed/deleted.
                val pkgCacheEnabled = appContext.getSharedPreferences(
                    WEPWAWET_SETTINGS_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(WEPWAWET_KEY_PKG_CACHE_ENABLED, false)
                val cachedPkg = File(File(appContext.cacheDir, "pkg_cache"), "${item.id}.pkg")
                val canReuseCache = pkgCacheEnabled && cachedPkg.exists() &&
                    cachedPkg.length() > 0L && !detectVideoByMagicBytes(cachedPkg)
                val downloadedPkg: File? = if (canReuseCache) {
                    WepWawetLogger.workshop("WorkshopDownloadJob.send: reusing cached .pkg (skip Steam download)",
                        "id" to item.id,
                        "path" to cachedPkg.absolutePath,
                        "size" to cachedPkg.length())
                    updateState(itemId) { s -> s.copy(status = "Using cached .pkg — packing…") }
                    cachedPkg
                } else {
                    downloader.downloadWorkshopPkg(
                    context = appContext,
                    workshopId = item.id,
                    outputFile = pkgFile,
                    onProgress = { pct ->
                        val coerced = pct.coerceIn(0f, 1f)
                        updateState(itemId) { s -> s.copy(
                            progress = (coerced * 0.8f),
                            hasChunkProgress = true,
                        ) }
                        // v0.5.32 (audit §1): mirror progress + status
                        // message into DownloadManager so the Downloads
                        // screen + WallpaperCard overlay update live.
                        val mirroredProgress = coerced * 0.8f
                        val statusMsg = if (coerced > 0f)
                            "Downloading… ${(coerced * 100).toInt()}%"
                        else "Connecting to Steam…"
                        onDownloadProgress(
                            itemId = item.id,
                            progress = mirroredProgress,
                            status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                            statusMessage = statusMsg,
                        )
                        val pctInt = (coerced * 100).toInt()
                        if (pctInt % 10 == 0) {
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: progress",
                                "pct" to pctInt)
                        }
                    },
                    onStatusUpdate = { status ->
                        updateState(itemId) { s -> s.copy(status = status) }
                        // v0.5.32 (audit §1): mirror the status string too.
                        onDownloadProgress(
                            itemId = item.id,
                            // v0.7.20: read per-item progress (was _state.value.progress).
                            progress = _states.value[itemId]?.progress ?: 0f,
                            status = com.wepwawet.data.DownloadStatus.DOWNLOADING,
                            statusMessage = status,
                        )
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: status",
                            "status" to status.take(150))
                    },
                )
                }

                WepWawetLogger.workshop("WorkshopDownloadJob.send: downloadWorkshopPkg RETURNED",
                    "id" to item.id,
                    "result_null" to (downloadedPkg == null),
                    "result_size" to (downloadedPkg?.length() ?: -1L))

                if (downloadedPkg == null || !downloadedPkg.exists() || downloadedPkg.length() == 0L) {
                    val err = "Failed to download .pkg via Steam (see logs)"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: pkg download failed/null",
                        "id" to item.id)
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    return@launchSafely
                }

                // === Pack to .mpkg using SAME code path as debug download
                // (with installDir!) so the resulting .mpkg is byte-equivalent
                // to what debug download produces. The installDir contains
                // project.json + preview.jpg standalone files that the WE
                // Android app's renderer needs — without them, the wallpaper
                // renders as a black image.
                //
                // v0.4.9: Detect video via MAGIC BYTES (not extension — the
                // outputFile is always .pkg-named by WorkshopDownloader).
                //   - MP4 files have "ftyp" at offset 4
                //   - PKGV (.pkg) files have "PKGV" at offset 4
                // ===
                updateState(itemId) { s -> s.copy(
                    status = "Packing content → .mpkg (might take a while)…",
                    progress = 0.85f,
                ) }
                // v0.5.32 (audit §1): mirror the packing phase transition
                // (same as the debug download path).
                onDownloadProgress(
                    itemId = item.id,
                    progress = 0.85f,
                    status = com.wepwawet.data.DownloadStatus.PACKING,
                    statusMessage = "Packing content → .mpkg (might take a while)…",
                )
                val cacheMpkg = File(appContext.cacheDir, "${item.id}_${sendId}_send.mpkg")
                val installDir = File(appContext.cacheDir, "workshop_downloads/${item.id}")
                val isVideo = detectVideoByMagicBytes(downloadedPkg)
                // v0.7.4 (parity G2): Image-type wallpapers (JPEG/PNG/WebP/BMP/GIF
                // without animation intent from the download) arrive as raw image
                // files — they pack as a Scene via packSceneWallpaper instead of
                // failing the .pkg parse. Only checked when NOT a video.
                val isImage = !isVideo && detectImageByMagicBytes(downloadedPkg)
                WepWawetLogger.workshop("WorkshopDownloadJob.send: packing content → .mpkg",
                    "input_file" to downloadedPkg.absolutePath,
                    "input_size" to downloadedPkg.length(),
                    "mpkg_output" to cacheMpkg.absolutePath,
                    "install_dir" to installDir.absolutePath,
                    "install_dir_exists" to installDir.exists(),
                    "is_video" to isVideo,
                    "is_image" to isImage)

                // v0.7.1 (round 10): If the downloaded file is a video, pause
                // BEFORE packing and set pendingVideoFile so the UI can show
                // a dialog asking "Do you want to trim the video?". The user
                // can either:
                //   a) Tap "Trim" → navigate to VideoTrimScreen with the
                //      downloaded video file. The trim screen handles pack +
                //      queue after trimming.
                //   b) Tap "Send Full" → call continueVideoSend() which
                //      resumes the pack + queue flow below (skipping trim).
                //   c) Tap "Cancel" → call cancelVideoSend() which deletes
                //      the downloaded file and resets state.
                if (isVideo) {
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,  // Not "running" anymore — waiting for user decision
                        status = "Video downloaded — do you want to trim?",
                        progress = 0.85f,
                        pendingVideoFile = downloadedPkg.absolutePath,
                        pendingVideoItem = item,
                    ) }
                    // Mirror into DownloadManager
                    onDownloadProgress(
                        itemId = item.id,
                        progress = 0.85f,
                        status = com.wepwawet.data.DownloadStatus.PACKING,
                        statusMessage = "Video downloaded — waiting for trim decision",
                    )
                    // Return here — the UI will call continueVideoSend() or
                    // the VideoTrimScreen will handle the rest.
                    WepWawetLogger.workshop("WorkshopDownloadJob.send: video downloaded — waiting for trim decision",
                        "video_file" to downloadedPkg.absolutePath,
                        "video_size" to downloadedPkg.length())
                    return@launchSafely
                }

                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WepWawet:MpkgPacker")
                wakeLock.setReferenceCounted(false)
                wakeLock.acquire(10 * 60 * 1000L)
                WepWawetLogger.workshop("WorkshopDownloadJob.send: acquired wake lock for packing",
                    "timeout_ms" to (10 * 60 * 1000L))
                val mpkgSize = try {
                    // v0.7.21 BUG FIX: was runCatching — now explicit try/catch
                    // that re-throws CancellationException.
                    try {
                        if (isVideo) {
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: calling packVideoWallpaper",
                                "video_file" to downloadedPkg.name,
                                "video_size" to downloadedPkg.length())
                            val previewFile = File(installDir, "preview.jpg")
                                .takeIf { it.exists() }
                                ?: File(installDir, "preview.gif")
                                    .takeIf { it.exists() }
                            com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                                item = item,
                                videoFile = downloadedPkg,
                                outputFile = cacheMpkg,
                                previewFile = previewFile,
                            )
                        } else if (isImage) {
                            // v0.7.4 (parity G2): Image wallpapers pack as a
                            // minimal Scene (single fullscreen quad + the image
                            // as an RGBA8888 .tex) — the same representation the
                            // phone renders for image-type content.
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: calling packSceneWallpaper (image item)",
                                "image_file" to downloadedPkg.name,
                                "image_size" to downloadedPkg.length())
                            com.wepwawet.pairing.MpkgPacker.packSceneWallpaper(
                                item = item,
                                imageFile = downloadedPkg,
                                outputFile = cacheMpkg,
                            )
                        } else {
                            WepWawetLogger.workshop("WorkshopDownloadJob.send: calling packFromPkg",
                                "pkg_file" to downloadedPkg.name,
                                "pkg_size" to downloadedPkg.length())
                            com.wepwawet.pairing.MpkgPacker.packFromPkg(
                                item = item,
                                pkgFile = downloadedPkg,
                                outputFile = cacheMpkg,
                                installDir = installDir,
                            )
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val err = "Failed to pack → .mpkg: ${e.message}"
                        WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: pack failed",
                            "exception" to e.javaClass.name, "message" to (e.message ?: ""),
                            "is_video" to isVideo)
                        updateState(itemId) { s -> s.copy(
                            isRunning = false,
                            status = err,
                            errorMessage = err,
                            endedAt = System.currentTimeMillis(),
                        ) }
                        onDownloadFailed(item, err)
                        // v0.7.4: don't delete the CACHE entry on failure.
                        if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete() }
                        return@launchSafely
                    }
                } finally {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: released wake lock")
                    }
                }

                WepWawetLogger.workshop("WorkshopDownloadJob.send: pack done",
                    "mpkg_output_size" to mpkgSize,
                    "mpkg_output_exists" to cacheMpkg.exists(),
                    "is_video" to isVideo)

                if (mpkgSize == 0L || !cacheMpkg.exists()) {
                    val err = "Failed to pack .pkg → .mpkg (empty output)"
                    WepWawetLogger.workshopWarn("WorkshopDownloadJob.send: empty mpkg output",
                        "size" to mpkgSize, "exists" to cacheMpkg.exists())
                    updateState(itemId) { s -> s.copy(
                        isRunning = false,
                        status = err,
                        errorMessage = err,
                        endedAt = System.currentTimeMillis(),
                    ) }
                    onDownloadFailed(item, err)
                    if (downloadedPkg !== cachedPkg) runCatching { downloadedPkg.delete() }
                    return@launchSafely
                }

                // === Queue for transfer to phone ===
                updateState(itemId) { s -> s.copy(
                    status = "Queuing for transfer to phone…",
                    progress = 0.95f,
                ) }
                // v0.5.32 (audit §1): mirror the "queueing for transfer"
                // phase transition. Uses the QUEUING status (distinct from
                // PACKING) so the Downloads screen can show a different
                // status string while the file waits for the phone to
                // pick it up via the WePairingServer heartbeat-driven
                // transfer loop.
                onDownloadProgress(
                    itemId = item.id,
                    progress = 0.95f,
                    status = com.wepwawet.data.DownloadStatus.QUEUING,
                    statusMessage = "Queuing for transfer to phone…",
                )
                val deviceName = PairingService.pairedDeviceName ?: "phone"
                WepWawetLogger.workshop("WorkshopDownloadJob.send: queueing for transfer",
                    "file" to cacheMpkg.name,
                    "size" to cacheMpkg.length(),
                    "device" to deviceName)
                PairingService.queueWallpaper(cacheMpkg, item.title)

                // v0.7.0: Auto-open the Wallpaper Engine Android app so the user
                // can see the wallpaper transfer happen. The WE app will display
                // the "Receiving wallpaper..." progress dialog.
                PairingService.launchWallpaperEngineApp(appContext)

                // === v0.7.4: Delete the install directory (mirrors the debug
                // download path, which already did this). It contains the raw
                // depot files (project.json, preview.jpg, models, scripts…)
                // that are no longer needed once the .mpkg is built — leaving
                // them behind leaked megabytes per send-to-phone in
                // cacheDir/workshop_downloads/<id>/ until Android's cache
                // cleaner ran. (WorkshopDownloader also wipes it BEFORE the
                // next download of the same id, but that doesn't help users
                // who send several different wallpapers.) ===
                runCatching { installDir.deleteRecursively() }
                WepWawetLogger.workshop("WorkshopDownloadJob.send: install dir cleaned up",
                    "path" to installDir.absolutePath)

                // === IMPORTANT: do NOT delete cacheMpkg here! ===
                // The PairingService.queueWallpaper only holds a File reference,
                // not the bytes — the file must still exist when the WePairingServer
                // command loop picks it up on the next heartbeat (~3s) and
                // transfers it via AES-encrypted chunks. Deleting it here would
                // cause "file not readable" in queueWallpaper → silent transfer
                // failure → black wallpaper on phone.
                //
                // The cache file will be cleaned up:
                //   - When the next send-to-phone for the SAME item starts
                //     (cacheDir reuses the path prefix, so the old file is
                //     overwritten naturally on the next pack)
                //   - When Android's cache cleaner runs (cacheDir is auto-cleaned
                //     under memory pressure)
                //   - Manually by the user via "Clear cache" in Android Settings

                // v0.7.4: store the .pkg in the stable cache when the
                // "Cache .pkg files" setting is enabled (scene wallpapers
                // only — videos are consumed by the trim flow), then clean up
                // the timestamped temp copy as before.
                if (downloadedPkg !== cachedPkg) {
                    if (pkgCacheEnabled && !isVideo) {
                        runCatching {
                            cachedPkg.parentFile?.mkdirs()
                            downloadedPkg.copyTo(cachedPkg, overwrite = true)
                        }
                        WepWawetLogger.workshop("WorkshopDownloadJob.send: .pkg stored in cache for faster re-packing",
                            "path" to cachedPkg.absolutePath,
                            "size" to cachedPkg.length())
                    }
                    runCatching { downloadedPkg.delete() }
                }

                val msg = "Queued ${formatBytes(cacheMpkg.length())} for transfer to $deviceName"
                WepWawetLogger.workshop("WorkshopDownloadJob.send: QUEUED",
                    "file" to cacheMpkg.name,
                    "size" to cacheMpkg.length(),
                    "device" to deviceName)
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    progress = 1f,
                    status = msg,
                    resultMessage = msg,
                    endedAt = System.currentTimeMillis(),
                    savedFilePath = cacheMpkg.absolutePath,
                    savedFileSize = cacheMpkg.length(),
                ) }
                onDownloadCompleted(item, cacheMpkg.length(), deviceName)
            } finally {
                // === CRITICAL (v0.4.7): ALWAYS call cleanup() on the WorkshopDownloader,
                // even on success — otherwise the SteamClient's TCP connection to Steam
                // stays alive, blocking subsequent download attempts (Steam rejects
                // the second login with the same loginID from the same IP → AccessDenied).
                // ===
                WepWawetLogger.workshop("WorkshopDownloadJob.send: cleaning up SteamClient",
                    "id" to item.id)
                runCatching { downloaderRef?.cleanup() }
                downloaderRef = null
            }
        }
        return true
    }

    /**
     * v0.7.1 (round 10): Continue the video send flow WITHOUT trimming.
     * Called when the user taps "Send Full Video" in the trim dialog.
     * Packs the downloaded video into a .mpkg and queues it for transfer
     * — exactly the same code as the normal send-to-phone flow for video.
     */
    fun continueVideoSend(context: Context, itemId: String) {
        val pendingFile = _states.value[itemId]?.pendingVideoFile
        val pendingItem = _states.value[itemId]?.pendingVideoItem
        if (pendingFile == null || pendingItem == null) {
            WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoSend: no pending video",
                "id" to itemId,
                "pending_file" to (pendingFile ?: "null"))
            return
        }
        val appContext = context.applicationContext
        val videoFile = File(pendingFile)
        if (!videoFile.exists()) {
            WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoSend: video file gone",
                "path" to pendingFile)
            updateState(itemId) { it.copy(
                pendingVideoFile = null,
                pendingVideoItem = null,
                errorMessage = "Video file not found",
            ) }
            return
        }
        // Clear the pending state — we're resuming
        updateState(itemId) { it.copy(
            pendingVideoFile = null,
            pendingVideoItem = null,
            isRunning = true,
            status = "Packing video → .mpkg…",
        ) }
        val sendId = System.currentTimeMillis()
        val cacheMpkg = File(appContext.cacheDir, "${pendingItem.id}_${sendId}_send.mpkg")
        val installDir = File(appContext.cacheDir, "workshop_downloads/${pendingItem.id}")
        jobs[itemId] = WepWawetApplication.appScope.launchSafely(itemId) {
            try {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WepWawet:MpkgPacker")
                wakeLock.setReferenceCounted(false)
                wakeLock.acquire(10 * 60 * 1000L)
                val mpkgSize = try {
                    val previewFile = File(installDir, "preview.jpg")
                        .takeIf { it.exists() }
                        ?: File(installDir, "preview.gif")
                            .takeIf { it.exists() }
                    com.wepwawet.pairing.MpkgPacker.packVideoWallpaper(
                        item = pendingItem,
                        videoFile = videoFile,
                        outputFile = cacheMpkg,
                        previewFile = previewFile,
                    )
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
                // Queue for transfer
                updateState(itemId) { s -> s.copy(
                    status = "Queuing for transfer…",
                    progress = 0.95f,
                ) }
                onDownloadProgress(
                    itemId = pendingItem.id,
                    progress = 0.95f,
                    status = com.wepwawet.data.DownloadStatus.QUEUING,
                    statusMessage = "Queuing for transfer to phone…",
                )
                val deviceName = PairingService.pairedDeviceName ?: "phone"
                PairingService.queueWallpaper(cacheMpkg, pendingItem.title)
                PairingService.launchWallpaperEngineApp(appContext)
                // v0.7.23 BUG WD-2: only delete the source video if the
                // pairing server is running (queueWallpaper silently returns
                // if pairingServer is null). Prevents silent data loss.
                if (PairingService.pairingServer != null) {
                    runCatching { videoFile.delete() }
                }
                // v0.7.4: clean up the install dir here too (the video path in
                // launchSendToPhone returns early before the normal cleanup —
                // this is the continuation of that flow, so it owns the cleanup).
                runCatching { installDir.deleteRecursively() }
                WepWawetLogger.workshop("WorkshopDownloadJob.continueVideoSend: install dir cleaned up",
                    "path" to installDir.absolutePath)
                val msg = "Queued ${formatBytes(cacheMpkg.length())} for transfer to $deviceName"
                WepWawetLogger.workshop("WorkshopDownloadJob.continueVideoSend: QUEUED",
                    "file" to cacheMpkg.name, "size" to cacheMpkg.length())
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    progress = 1f,
                    status = msg,
                    resultMessage = msg,
                    endedAt = System.currentTimeMillis(),
                    savedFilePath = cacheMpkg.absolutePath,
                    savedFileSize = cacheMpkg.length(),
                ) }
                onDownloadCompleted(pendingItem, cacheMpkg.length(), deviceName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v0.7.21 BUG FIX: re-throw CancellationException — was swallowed
                // by catch (e: Exception) which broke structured concurrency.
                throw e
            } catch (e: Exception) {
                WepWawetLogger.workshopWarn("WorkshopDownloadJob.continueVideoSend: failed",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                updateState(itemId) { s -> s.copy(
                    isRunning = false,
                    status = "Failed: ${e.message}",
                    errorMessage = "Failed: ${e.message}",
                    endedAt = System.currentTimeMillis(),
                ) }
            }
        }
    }

    /**
     * v0.7.1 (round 10): Cancel a pending video send (user tapped the X
     * in the trim dialog corner). Deletes the downloaded video file and
     * resets state.
     *
     * v0.7.3 — Bug fix: previously, cancelling the trim dialog left the
     * download status stuck on the stale "Video downloaded — waiting for
     * trim decision" message that [launchSendToPhone] sets via
     * `onDownloadProgress(..., statusMessage = "Video downloaded — waiting
     * for trim decision")` when it detects the file is a video. The
     * DownloadManager entry was never cleared because cancelVideoSend did
     * NOT call `onDownloadCancelled` (the mirror that removes the entry
     * from DownloadManager.activeDownloads). The local WorkshopDownloadJob
     * status was updated to "Cancelled" but the DownloadManager screen
     * still showed the waiting-for-trim-decision status indefinitely.
     *
     * Fix: call `onDownloadCancelled(workshopId)` so the DownloadManager
     * entry is removed too, reset progress to 0 (it was sitting at 0.85f
     * from the trim-wait state — leaving it at 0.85f made the Downloads
     * screen show a frozen 85% bar), and clear any stale result/error
     * messages so a fresh send-to-phone starts clean.
     */
    fun cancelVideoSend(itemId: String) {
        val pendingFile = _states.value[itemId]?.pendingVideoFile
        WepWawetLogger.workshop("WorkshopDownloadJob.cancelVideoSend",
            "id" to itemId,
            "pending_file" to (pendingFile ?: "null"))
        if (pendingFile != null) {
            runCatching { File(pendingFile).delete() }
        }
        // v0.7.3: Mirror the cancel into DownloadManager so the Downloads
        // screen's entry for this item is removed — otherwise it stays
        // in activeDownloads with the stale "waiting for trim decision"
        // statusMessage and 85% progress until the next event clears it.
        onDownloadCancelled(itemId)
        updateState(itemId) { it.copy(
            isRunning = false,
            pendingVideoFile = null,
            pendingVideoItem = null,
            progress = 0f,  // Reset — was sitting at 0.85f from the trim-wait state
            status = "Cancelled",
            // v0.7.3: Clear stale result/error messages so the next
            // send-to-phone for this item starts fresh and doesn't
            // show a stale "Sent ✓" or error pill from a prior run.
            resultMessage = null,
            errorMessage = null,
            endedAt = System.currentTimeMillis(),
        ) }
    }

    /**
     * v0.7.4: Called by VideoTrimScreen after it has successfully queued a
     * video (trimmed or full). Clears the pending-video state WITHOUT deleting
     * the file (the trim screen owns file cleanup) so the "Video Downloaded"
     * dialog doesn't reappear after navigating back, and a second "Trim Video"
     * tap can't navigate to a file that was already consumed.
     */
    fun onVideoConsumedByTrimScreen(itemId: String) {
        val s = _states.value[itemId]
        if (s?.pendingVideoFile != null || s?.pendingVideoItem != null) {
            updateState(itemId) { it.copy(
                pendingVideoFile = null,
                pendingVideoItem = null,
            ) }
            WepWawetLogger.workshop("WorkshopDownloadJob.onVideoConsumedByTrimScreen: pending video state cleared")
        }
    }

    fun cancel() {
        val allIds = jobs.keys.toList()
        WepWawetLogger.workshop("WorkshopDownloadJob: cancelling all",
            "count" to allIds.size)
        allIds.forEach { id -> cancel(id) }
    }

    fun cancel(itemId: String) {
        WepWawetLogger.workshop("WorkshopDownloadJob: cancelling",
            "id" to itemId)
        jobs[itemId]?.cancel()
        jobs.remove(itemId)
        onDownloadCancelled(itemId)
        // v0.7.23 BUG WD-1: Clear pending-video state + delete the file so
        // the trim dialog doesn't reappear after a cancel from the Downloads
        // screen.
        val pendingFile = _states.value[itemId]?.pendingVideoFile
        if (pendingFile != null) {
            runCatching { File(pendingFile).delete() }
        }
        updateState(itemId) { it.copy(
            isRunning = false,
            status = "Cancelled",
            errorMessage = "Cancelled",
            progress = 0f,
            endedAt = System.currentTimeMillis(),
            // v0.7.23 BUG WD-1: clear pending-video state.
            pendingVideoFile = null,
            pendingVideoItem = null,
        ) }
    }

    /** Mark the current download as failed with the given message. Called by
     *  [launchSafely] if the coroutine throws — keeps the UI from hanging on
     *  "Running…" forever after a crash. */
    fun markCrashed(itemId: String, message: String) {
        WepWawetLogger.workshopWarn("WorkshopDownloadJob: coroutine crashed",
            "msg" to message)
        updateState(itemId) { s -> s.copy(
            isRunning = false,
            status = message,
            errorMessage = message,
            endedAt = System.currentTimeMillis(),
        ) }
        // v0.5.9-alpha (R8): mirror the crash into DownloadManager so the
        // Downloads screen shows the failure instead of a stale "Downloading…".
        com.wepwawet.data.DownloadManager.failDownload(itemId, "Coroutine crashed: $message")
    }

    /** Reset to a clean idle state (called by UI when user dismisses the result). */
    fun reset() {
        _state.value = State()
    }

    /**
     * v0.7.20: Reset a specific item's download state — removes its entry
     * from the per-item [_states] map and drops its cached StateFlow so a
     * future call to [stateFor] for this item starts fresh. Called by the
     * UI when the user dismisses a single item's result panel.
     */
    fun reset(itemId: String) {
        _states.update { it - itemId }
        stateFlows.remove(itemId)
    }

    // v0.7.24 (M-D2/L-D1): Locale.US + shared with WorkshopDownloader's
    // identical (removed-duplicate) copy.
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

/**
 * Detect whether a downloaded file is a video (MP4) or a scene (.pkg) by
 * checking its magic bytes.
 *
 * v0.4.9: Previously we checked the file EXTENSION (.mp4 vs .pkg), but
 * WorkshopDownloader always names the output file `*.pkg` regardless of
 * content type (the outputFile parameter is `${item.id}_${sendId}_send.pkg`).
 * So the extension check ALWAYS returned false for videos, causing
 * packFromPkg to be called on a .mp4 file, which failed with
 * "PkgReader implausible magic_len magic_len=469762048".
 *
 * Now we check the actual file content:
 *   - MP4/M4V/MOV files have "ftyp" box at offset 4 → bytes[4..7] = "ftyp"
 *   - PKGV (.pkg) files have "PKGV" at offset 4 → bytes[4..7] = "PKGV"
 *
 * @return true if the file is a video, false if it's a scene .pkg
 */
/**
 * v0.7.4 (parity G2): Detect whether the downloaded Workshop file is a VIDEO
 * by sniffing its container magic — NOT just "ftyp at offset 4" anymore.
 *
 * The previous MP4-only check mis-routed every WebM/MKV/AVI/WMV/GIF video
 * wallpaper into the .pkg path, where PkgReader failed with "no entries"
 * (WorkshopDownloader names every download *.pkg regardless of content, so
 * extensions are meaningless). A video wallpaper in ANY container the WE PC
 * pipeline knows how to deliver (MP4 family, WebM/MKV, AVI, WMV/ASF, GIF,
 * FLV, MPEG-TS) now routes to [MpkgPacker.packVideoWallpaper], which runs
 * the matching on-device transcoder before packing.
 *
 * Still images (JPEG/PNG/WebP/BMP) are NOT videos — see
 * [detectImageByMagicBytes] for the Image-wallpaper path.
 */
private fun detectVideoByMagicBytes(file: File): Boolean {
    if (!file.exists() || file.length() < 12) return false
    val kind = VideoContainerSniffer.sniff(file)
    val isVideo = VideoContainerSniffer.isVideoKind(kind)
    WepWawetLogger.workshop("detectVideoByMagicBytes",
        "file" to file.name,
        "sniffed" to VideoContainerSniffer.label(kind),
        "is_video" to isVideo)
    return isVideo
}

/**
 * v0.7.4 (parity G2): Detect whether the downloaded Workshop file is a still
 * IMAGE (JPEG/PNG/WebP/BMP — including GIF, which BitmapFactory decodes to
 * its first frame for packing as a Scene wallpaper via
 * [MpkgPacker.packSceneWallpaper]).
 *
 * Image-type Workshop wallpapers download as project.json + the raw image
 * file (no .pkg at all). Before this fix they fell into the .pkg path and
 * failed with "no entries in .pkg" — now they pack as a Scene whose single
 * quad is the image, which is what the WE Android renderer displays.
 */
private fun detectImageByMagicBytes(file: File): Boolean {
    if (!file.exists() || file.length() < 12) return false
    val kind = VideoContainerSniffer.sniff(file)
    val isImage = VideoContainerSniffer.isImageKind(kind)
    WepWawetLogger.workshop("detectImageByMagicBytes",
        "file" to file.name,
        "sniffed" to VideoContainerSniffer.label(kind),
        "is_image" to isImage)
    return isImage
}

/**
 * Launches a coroutine on this scope but captures any uncaught exception into
 * the [WorkshopDownloadJob] state (so a crash doesn't leave the UI showing
 * "Running…" forever).
 */
private fun CoroutineScope.launchSafely(itemId: String, block: suspend CoroutineScope.() -> Unit): Job =
    this.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coop-cancelled — WorkshopDownloadJob.cancel already updated state
            throw e
        } catch (e: Throwable) {
            WorkshopDownloadJob.markCrashed(itemId, "Crashed: ${e.message ?: e.javaClass.name}")
        } finally {
            // v0.7.24 (H-D6): remove the entry when the job completes (success,
            // failure, or crash). Dead Job references previously lingered in
            // the map forever (cancel() removed its own entry, but normal
            // completion never did) — harmless for isActive checks, but an
            // unbounded leak of Job objects across a long session.
            // remove(key, value) semantics: if a NEW download for the same
            // item already replaced our entry, leave ITS entry alone.
            WorkshopDownloadJob.removeJobEntry(itemId, coroutineContext[kotlinx.coroutines.Job])
        }
    }
