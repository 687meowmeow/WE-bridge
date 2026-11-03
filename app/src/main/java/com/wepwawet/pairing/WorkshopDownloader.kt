package com.wepwawet.pairing

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.Closeable
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import `in`.dragonbra.javasteam.depotdownloader.IDownloadListener
import `in`.dragonbra.javasteam.depotdownloader.data.DownloadItem
import `in`.dragonbra.javasteam.depotdownloader.data.PubFileItem
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.handlers.steamworkshop.SteamWorkshop
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager

/**
 * Bridge that forwards JavaSteam library log messages into our [WepWawetLogger]
 * so they show up in the in-app Logs screen + log dumps.
 *
 * Without this, the DepotDownloader's internal debug logs (RPC requests,
 * manifest fetches, chunk downloads, errors) are invisible — making it
 * impossible to diagnose why a download hangs at "Download started".
 *
 * Installed once globally (since JavaSteam's LogManager is a singleton).
 *
 * CRITICAL: this bridge also watches for known library bugs and aborts
 * the download immediately when one is detected. The biggest known bug
 * is the "unsupported file type" / "Skipping file" path — when the
 * library can't handle a workshop item's fileType, it logs the error
 * and SKIPS the file but never completes the `completionFuture`, so
 * `awaitCompletion` hangs forever waiting for a completion that never
 * comes. The heartbeat's stuck-detection would abort after 60s, but
 * that's a long wait for the user — instead, we detect the library's
 * error log and set downloadError immediately so the awaitCompletion
 * wrapper breaks out within ~1 second.
 */
private object JavaSteamLogBridge : LogListener {
    private val installed = AtomicBoolean(false)
    /**
     * v0.7.24 (C-D1 BUG FIX): the bridge held ONE callback — a single global.
     * v0.7.20 added CONCURRENT downloads (each item gets its own
     * WorkshopDownloader), so download B's registration overwrote download
     * A's: a library error for A then set B's downloadError while A hung for
     * the full 60s stuck-detection timeout. Callbacks are now keyed by owner
     * (one per in-flight downloader) and library errors are dispatched to the
     * owner whose workshopId matches the "Published file <id>" in the message;
     * when no id can be parsed, every registered owner is notified (each
     * downloader's callback only acts if downloadError is still null).
     */
    private val abortCallbacks = java.util.concurrent.ConcurrentHashMap<WorkshopDownloader, (String) -> Unit>()

    /** The workshopId currently being downloaded by each owner (for dispatch). */
    private val ownerWorkshopIds = java.util.concurrent.ConcurrentHashMap<WorkshopDownloader, String>()

    fun setAbortCallback(owner: WorkshopDownloader, workshopId: String, callback: ((String) -> Unit)?) {
        if (callback != null) {
            abortCallbacks[owner] = callback
            ownerWorkshopIds[owner] = workshopId
        } else {
            abortCallbacks.remove(owner)
            ownerWorkshopIds.remove(owner)
        }
    }

    fun clearAbortCallback(owner: WorkshopDownloader) {
        abortCallbacks.remove(owner)
        ownerWorkshopIds.remove(owner)
    }

    /** v0.7.24 (C-D1): dispatch a library error to the matching owner(s). */
    private fun dispatchAbort(reason: String, rawMessage: String) {
        val fileId = Regex("""Published file (\d+)""").find(rawMessage)?.groupValues?.get(1)
        if (fileId != null) {
            var dispatched = false
            for ((owner, callback) in abortCallbacks) {
                if (ownerWorkshopIds[owner] == fileId) {
                    callback(reason)
                    dispatched = true
                }
            }
            if (dispatched) return
        }
        // No id in the message (or no match): notify every owner — each
        // callback no-ops unless its downloadError is still null.
        abortCallbacks.values.forEach { it(reason) }
    }

    fun installIfNeeded() {
        if (installed.compareAndSet(false, true)) {
            LogManager.addListener(this)
            WepWawetLogger.proxy("JavaSteamLogBridge installed — forwarding library logs to WepWawetLogger")
        }
    }
    override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
        // JavaSteam uses onLog for INFO-level messages
        val msg = message ?: ""
        WepWawetLogger.proxy("JavaSteam: ${clazz.simpleName}",
            "msg" to msg.take(200),
            "level" to "INFO",
            "throwable" to (throwable?.javaClass?.name))
        // Detect known INFO-level messages that indicate progress
        // (none currently — INFO messages are mostly about connection state)
    }
    override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
        val msg = message ?: ""
        WepWawetLogger.proxyError("JavaSteam ERROR: ${clazz.simpleName}",
            "msg" to msg.take(200),
            "exception" to (throwable?.javaClass?.name ?: "null"))
        // === Detect known library bugs that cause silent download hangs ===
        // BUG 1: "Published file X has unsupported file type Y. Skipping file"
        //   - Library logs this when fileType isn't in SupportedWorkshopFileTypes
        //   - Steam returns fileType=0 (k_EWorkshopFileTypeFirst) for some items
        //   - Library SKIPS the file but never completes completionFuture
        //   - awaitCompletion hangs forever waiting
        // Fix: set downloadError immediately so the awaitCompletion wrapper
        //      breaks out and surfaces the error to the user.
        if ((msg.contains("unsupported file type") || msg.contains("Skipping file")) &&
            clazz.simpleName == "DepotDownloader") {
            val reason = if (msg.contains("unsupported file type")) {
                "Steam returned an unsupported file type for this workshop item. " +
                "This is a known JavaSteam library bug — the library skips the file " +
                "but never completes the awaitCompletion future, causing an infinite hang. " +
                "Library log: $msg"
            } else {
                "JavaSteam library skipped a file during download (likely unsupported " +
                "file type). This is a known library bug — see $msg"
            }
            WepWawetLogger.proxyError("JavaSteamLogBridge: detected library skip — aborting download",
                "reason" to reason.take(300))
            dispatchAbort(reason, msg)
        }
        // BUG 2: "Unable to locate manifest ID for published file X"
        //   - Library logs this when the workshop item's fileUrl is empty AND
        //     hcontentFile is 0 (the item has no download source)
        //   - Same root cause — the library never completes completionFuture
        if (msg.contains("Unable to locate manifest ID") &&
            clazz.simpleName == "DepotDownloader") {
            val reason = "JavaSteam library can't find a download source for this workshop " +
                "item (no fileUrl AND no hcontentFile). This is a known library bug — " +
                "the library logs the error but never completes awaitCompletion. " +
                "Library log: $msg"
            WepWawetLogger.proxyError("JavaSteamLogBridge: detected no-download-source — aborting download",
                "reason" to reason.take(300))
            dispatchAbort(reason, msg)
        }
    }
}

class WorkshopDownloader(
    private val username: String,
    private val password: String,
) {
    // v0.7.4: the `guardCode` constructor parameter was removed. It was never
    // supplied by ANY caller with a real value — SteamCredentialsStore.
    // saveGuardCode() had zero call sites, so the code was always null and
    // GuardAuthenticator.getDeviceCode() could only fail. Steam auth uses the
    // device-confirmation flow ("is this you?" prompt on the Steam mobile
    // app) via acceptDeviceConfirmation().
    companion object {
        private const val TAG = "WorkshopDownloader"
        private const val APP_ID_WE = 431960
        private const val PREF_NAME = "wepwawet_steam_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCOUNT_NAME = "account_name"

        // v0.5.32 (audit M7): Previously loginID was hardcoded to 149 at
        // both refresh-token login (line ~1091) and modern-auth login
        // (line ~1149). Both WorkshopBrowseSession (singleton downloader
        // for browse queries) and WorkshopDownloadJob (per-download
        // downloader) used the same loginID — so when the user started
        // a download while BrowseSession was connected, the new logOn
        // collided with the existing session and Steam kicked the older
        // one with EResult.LogonSessionReplaced.
        //
        // Fix: each WorkshopDownloader instance gets a distinct random
        // loginID in [1, Int.MAX_VALUE]. Steam's docs say loginID is
        // a client-side identifier (any positive int is fine — the
        // JavaSteam sample _023_downloadapp uses 149 as an arbitrary
        // example, not as a required value). Random per-instance
        // values eliminate the collision.
        //
        // LOGIN_ID_RNG lives in the companion object (a single shared
        // Random is fine — Random is thread-safe). steamLoginID MUST
        // be an instance field (declared AFTER the companion object
        // below) so each WorkshopDownloader gets a different loginID —
        // putting it inside the companion would make it shared (defeating
        // the fix). Putting it between the companion's `}` and testLogin
        // (as the initial M7 fix did) was a brace-balancing bug that
        // closed the class prematurely — see the corrected placement
        // after testLogin.
        private val LOGIN_ID_RNG = java.util.Random()

        /**
         * Test Steam login — connects, authenticates, saves refresh token.
         * Returns a result message (success or error).
         */
        suspend fun testLogin(context: Context): String = withContext(Dispatchers.IO) {
            val username = SteamCredentialsStore.getUsername(context)
                ?: return@withContext "No Steam username saved"
            val password = SteamCredentialsStore.getPassword(context)
                ?: return@withContext "No Steam password saved"
            WepWawetLogger.proxy("$TAG.testLogin START", "username" to username)

            val downloader = WorkshopDownloader(username, password)
            downloader.statusCallback = { msg ->
                WepWawetLogger.proxy("$TAG.testLogin: $msg")
            }
            // Try to connect and authenticate
            val connected = downloader.connectAndAuthenticate(context)
            val result = if (connected) {
                WepWawetLogger.proxy("$TAG.testLogin SUCCESS")
                "✓ Login successful! Refresh token saved for future logins."
            } else {
                val err = downloader.downloadError?.message ?: "Unknown error"
                WepWawetLogger.proxyError("$TAG.testLogin FAILED", "error" to err)
                "✗ Login failed: $err"
            }
            downloader.cleanup()
            result
        }
    }

    /**
     * Per-instance Steam loginID — distinct across WorkshopDownloader instances.
     *
     * v0.5.32 (audit M7): see the comment above [LOGIN_ID_RNG] for context.
     * Each WorkshopDownloader instance gets its own loginID so that two
     * concurrent logins (e.g., WorkshopBrowseSession's singleton + a
     * WorkshopDownloadJob's per-download downloader) don't collide and
     * trigger EResult.LogonSessionReplaced.
     *
     * Placed AFTER the companion object (instance field, not shared static).
     */
    private val steamLoginID: Int = LOGIN_ID_RNG.nextInt(1, Int.MAX_VALUE)

    private var steamClient: SteamClient? = null
    private var callbackManager: CallbackManager? = null
    private var steamUser: SteamUser? = null
    // v0.5.32 (audit H2): keep the previous httpClient reference so we
    // can shutdown its dispatcher's executor on reconnect. Previously
    // this was a local variable in connectAndAuthenticate — each reconnect
    // leaked an entire unbounded cached OkHttp thread pool (one thread
    // per HTTP request that ever ran on the old client). After ~5-10
    // reconnects the app accumulated a dozen orphaned pools.
    @Volatile private var httpClient: OkHttpClient? = null
    private var licenseList: List<License> = emptyList()
    @Volatile private var isRunning = false
    @Volatile private var downloadError: Exception? = null
    @Volatile private var authenticated = false
    @Volatile private var cmRetries = 0
    @Volatile private var authInProgress = false
    @Volatile private var isRetrying = false  // true during TryAnotherCM retry
    @Volatile private var usedRefreshToken = false  // true if we tried refresh token login
    @Volatile private var hasValidToken = false  // true after auth session succeeds (token saved)
    @Volatile private var appContext: Context? = null  // for clearing stale token on rate limit
    @Volatile private var statusCallback: ((String) -> Unit)? = null
    @Volatile private var isDownloadInProgress = false  // true during DepotDownloader.awaitCompletion — used by onDisconnected to decide whether to auto-reconnect
    private val subscriptions = mutableListOf<Closeable>()

    /** Tracks the callback dispatch thread so we can interrupt+join it before
     *  spawning a new one on reconnect. Without this, the old thread (still
     *  inside runWaitCallbacks(1000L)) races with the new thread — callbacks
     *  may be dispatched to neither, one, or both threads. */
    @Volatile private var callbackThread: Thread? = null

    /**
     * v0.7.4: the modern-auth polling thread (onConnected spawns it to block on
     * authSessionFuture.get() / pollingWaitForResult().get()). Kept as a field
     * so cleanup() can interrupt it — previously it was fire-and-forget and
     * could outlive the session, eventually calling steamUser!!.logOn() on an
     * already-disconnected client whose callbacks nobody dispatches.
     */
    @Volatile private var authPollingThread: Thread? = null

    private fun updateStatus(msg: String) {
        WepWawetLogger.proxy("$TAG: $msg")
        statusCallback?.invoke(msg)
    }

    fun isAuthenticated(): Boolean = authenticated

    /**
     * Check if the underlying SteamClient is actually connected (not just that
     * auth was previously successful). Used by [com.wepwawet.pairing.WorkshopBrowseSession]
     * to verify the session is alive before reusing it — catches the case where
     * the WebSocket watchdog disconnected after 30s of inactivity but
     * `authenticated` wasn't reset yet (e.g., between queries with no failure).
     */
    fun isSteamConnected(): Boolean = steamClient?.isConnected ?: false

    /**
     * Public accessor for the underlying JavaSteam [SteamWorkshop] handler.
     *
     * Returns `null` if the [SteamClient] is not connected/authenticated.
     *
     * Used by [com.wepwawet.pairing.WorkshopBrowseSession] to issue workshop
     * browse queries via the ISteamPublishedFile.QueryFiles service RPC —
     * which bypasses the IPublishedFileService HTTP API's [1,7] days limit
     * for RankedByTrend. This achieves absolute parity with WE PC's
     * `steam_api64.dll`-based workshop browse queries (which use the same
     * underlying Steam CM protocol that JavaSteam speaks).
     *
     * Usage:
     * ```kotlin
     * val workshop = downloader.getSteamWorkshopHandler() ?: return
     * val details = QueryFilesDetails(appId = 431960, queryType = EPublishedFileQueryType.k_PublishedFileQueryType_RankedByTrend, days = 365u)
     * val response = workshop.queryFiles(details).await()
     * val body = response.body.build()
     * val total = body.total
     * val items = body.publishedfiledetailsList
     * ```
     */
    fun getSteamWorkshopHandler(): SteamWorkshop? =
        steamClient?.getHandler(SteamWorkshop::class.java)

    private inner class GuardAuthenticator : IAuthenticator {
        override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            // v0.7.4: Steam Guard CODE entry is not supported (and never was —
            // the old guardCode parameter was always null because nothing ever
            // saved a code). Steam's device-confirmation flow (the "is this
            // you?" prompt on the Steam mobile app) is the supported 2FA path —
            // see acceptDeviceConfirmation below.
            WepWawetLogger.proxy("$TAG: Steam Guard 2FA code requested — not supported (device confirmation only)",
                "previous_incorrect" to previousCodeWasIncorrect)
            val future = CompletableFuture<String>()
            future.completeExceptionally(
                AuthenticationException("Steam Guard code entry is not supported — approve the login on your Steam mobile app instead."))
            return future
        }

        override fun getEmailCode(email: String?, previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
            WepWawetLogger.proxy("$TAG: Steam email code requested", "email" to (email ?: ""))
            val future = CompletableFuture<String>()
            future.completeExceptionally(AuthenticationException("Email code auth not supported."))
            return future
        }

        override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
            WepWawetLogger.proxy("$TAG: device confirmation — auto-accepting (user approves on Steam mobile app)")
            updateStatus("Check your Steam mobile app — tap 'Approve' to authorize WepWawet (one-time only)")
            return CompletableFuture.completedFuture(true)
        }
    }

    suspend fun downloadWorkshopPkg(
        context: Context,
        workshopId: String,
        outputFile: File,
        onProgress: (Float) -> Unit,
        onStatusUpdate: (String) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        this@WorkshopDownloader.statusCallback = onStatusUpdate
        WepWawetLogger.proxy("$TAG START", "workshop_id" to workshopId, "username" to username)
        try {
            updateStatus("Connecting to Steam...")
            if (!connectAndAuthenticate(context)) {
                val err = downloadError?.message ?: "Unknown error"
                onStatusUpdate("Steam login failed: $err")
                return@withContext null
            }

            onStatusUpdate("Fetching Steam licenses...")
            if (!waitForLicenses(30000)) {
                onStatusUpdate("Timed out waiting for licenses")
                return@withContext null
            }
            WepWawetLogger.proxy("$TAG: got ${licenseList.size} licenses")

            onStatusUpdate("Downloading workshop item via Steam protocol...")
            val installDir = File(context.cacheDir, "workshop_downloads/$workshopId")
            // v0.7.4: wipe any leftovers from a PREVIOUS download of the same
            // workshop id before (re)creating the dir. mkdirs() alone is a no-op
            // when the dir already exists, so files removed in a newer workshop
            // version (or stale partial downloads from a cancelled run) would
            // linger and get packed alongside the fresh content by
            // MpkgPacker.packFromPkg — producing a corrupted .mpkg.
            if (installDir.exists()) {
                runCatching { installDir.deleteRecursively() }
                WepWawetLogger.proxy("$TAG: cleared stale install dir before download",
                    "path" to installDir.absolutePath)
            }
            val mkdirsOk = installDir.mkdirs()
            WepWawetLogger.proxy("$TAG: install dir prepared",
                "path" to installDir.absolutePath,
                "exists" to installDir.exists(),
                "mkdirs_returned" to mkdirsOk,
                "is_directory" to installDir.isDirectory,
                "can_write" to installDir.canWrite(),
                "cache_dir" to context.cacheDir.absolutePath,
                "parent_exists" to (installDir.parentFile?.exists() ?: false))

            WepWawetLogger.proxy("$TAG: constructing DepotDownloader",
                "steam_client_connected" to (steamClient?.isConnected ?: false),
                "licenses_count" to licenseList.size,
                "install_dir" to installDir.absolutePath)
            // === CRITICAL FIX: pass debug=true to DepotDownloader ===
            // With debug=false, the library's `logger` field is NULL, so ALL
            // internal debug logs are suppressed (we'd never see "DepotDownloader
            // launched with X for account", "requestUGCDetails result: X", etc.).
            // With debug=true, the logger is initialized and our JavaSteamLogBridge
            // forwards those logs to WepWawetLogger — letting us see EXACTLY
            // where the download hangs (e.g., "RPC request sent" but no response,
            // OR "EResult.X while retrieving file details").
            // Other params: install dir is set per-item via PubFileItem constructor;
            // maxDownloads=8, maxDecompress=4, maxFileWrites=8; androidEmulation=false
            // (false is correct — on Android, IS_OS_LINUX returns true before
            // IS_OS_ANDROID, so getSteamOS returns "linux" anyway, and Wallpaper
            // Engine's workshop files don't use OS-specific depots — they're .pkg
            // files downloaded via downloadWebFile after getPublishedFileDetails
            // returns the fileUrl).
            val downloader = DepotDownloader(steamClient!!, licenseList, true, false, 8, 4, 8, false, null)
            WepWawetLogger.proxy("$TAG: DepotDownloader constructed",
                "downloader_class" to downloader.javaClass.name)

            // Heartbeat state — updated by the listener, read by the heartbeat thread.
            // Use Atomic* so cross-thread visibility is guaranteed (Kotlin local vars
            // captured by closures are boxed in Ref objects, but writes from one
            // thread aren't guaranteed visible to another thread without a
            // happens-before edge — Atomic* gives us that).
            val lastStatus = java.util.concurrent.atomic.AtomicReference("(no status yet)")
            val lastProgressPct = java.util.concurrent.atomic.AtomicInteger(-1)
            val lastChunkBytes = java.util.concurrent.atomic.AtomicLong(0L)
            val filesCompleted = java.util.concurrent.atomic.AtomicInteger(0)
            val depotsCompleted = java.util.concurrent.atomic.AtomicInteger(0)
            val chunksCompleted = java.util.concurrent.atomic.AtomicInteger(0)
            val lastActivityMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val downloadStartMs = System.currentTimeMillis()

            val listener = object : IDownloadListener {
                override fun onItemAdded(item: DownloadItem) {
                    lastActivityMs.set(System.currentTimeMillis())
                    WepWawetLogger.proxy("$TAG: item added",
                        "app_id" to item.appId,
                        "install_dir" to item.installDirectory)
                }
                override fun onDownloadStarted(item: DownloadItem) {
                    lastActivityMs.set(System.currentTimeMillis())
                    lastStatus.set("Download started")
                    WepWawetLogger.proxy("$TAG: download started",
                        "app_id" to item.appId)
                    onStatusUpdate("Download started — fetching chunks from Steam CDN...")
                }
                override fun onDownloadCompleted(item: DownloadItem) {
                    lastActivityMs.set(System.currentTimeMillis())
                    WepWawetLogger.proxy("$TAG: download completed",
                        "app_id" to item.appId,
                        "elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    onProgress(1f)
                    onStatusUpdate("Download completed — finalizing...")
                }
                override fun onDownloadFailed(item: DownloadItem, error: Throwable) {
                    lastActivityMs.set(System.currentTimeMillis())
                    WepWawetLogger.proxyError("$TAG: download failed",
                        "app_id" to item.appId,
                        "exception" to error.javaClass.name,
                        "message" to (error.message ?: ""))
                    downloadError = java.lang.Exception(error.message ?: "download failed", error)
                }
                override fun onStatusUpdate(message: String) {
                    lastActivityMs.set(System.currentTimeMillis())
                    lastStatus.set(message)
                    // IMPORTANT: was proxyDebug (filtered out by min level = INFO).
                    // Now logged at INFO so the user can see what's happening.
                    WepWawetLogger.proxy("$TAG: depot status",
                        "msg" to message.take(200))
                    onStatusUpdate(message)
                }
                override fun onFileCompleted(depotId: Int, fileName: String, depotPercentComplete: Float) {
                    lastActivityMs.set(System.currentTimeMillis())
                    val pct = (depotPercentComplete * 100).toInt()
                    val done = filesCompleted.incrementAndGet()
                    WepWawetLogger.proxy("$TAG: file complete",
                        "depot" to depotId,
                        "file" to fileName.take(120),
                        "depot_pct" to pct,
                        "files_done" to done,
                        "elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    lastProgressPct.set(pct)
                    onProgress(depotPercentComplete)
                    onStatusUpdate("Downloading… $pct% (file #$done: ${fileName.take(40)})")
                }
                override fun onChunkCompleted(depotId: Int, depotPercentComplete: Float, compressedBytes: Long, uncompressedBytes: Long) {
                    lastActivityMs.set(System.currentTimeMillis())
                    val done = chunksCompleted.incrementAndGet()
                    val pct = (depotPercentComplete * 100).toInt()
                    lastProgressPct.set(pct)
                    // === v0.4.8 FIX: Use set() instead of addAndGet().
                    // The `compressedBytes` parameter in onChunkCompleted is the
                    // CUMULATIVE compressed bytes downloaded so far for the depot
                    // (NOT just this chunk's bytes). Using addAndGet() caused the
                    // displayed "X GB so far" to grow geometrically:
                    //   chunk 1: 1 MB added → total = 1 MB
                    //   chunk 2: 2 MB added → total = 3 MB
                    //   chunk 3: 3 MB added → total = 6 MB
                    //   chunk N: total = N*(N+1)/2 MB
                    // For a 700 MB video with 700 chunks, the displayed total
                    // became ~245 GB. Using set() makes it show the actual
                    // cumulative bytes (700 MB). ===
                    lastChunkBytes.set(compressedBytes)
                    // Log every chunk — this is the only true progress signal.
                    WepWawetLogger.proxy("$TAG: chunk complete",
                        "depot" to depotId,
                        "chunk#" to done,
                        "depot_pct" to pct,
                        "compressed" to compressedBytes,
                        "uncompressed" to uncompressedBytes,
                        "elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    onProgress(depotPercentComplete)
                    onStatusUpdate("Downloading… $pct% ($done chunks, ${formatBytes(lastChunkBytes.get())} so far)")
                }
                override fun onDepotCompleted(depotId: Int, compressedBytes: Long, uncompressedBytes: Long) {
                    lastActivityMs.set(System.currentTimeMillis())
                    val done = depotsCompleted.incrementAndGet()
                    // === v0.4.8 FIX: Don't addAndGet() here either — compressedBytes
                    // is the depot's final total, which is approximately the same
                    // as the last chunk's cumulative value (just a few KB more for
                    // the manifest). Adding it again would double-count.
                    // Use set() to ensure the final value reflects the depot's
                    // official total. ===
                    lastChunkBytes.set(compressedBytes)
                    WepWawetLogger.proxy("$TAG: depot done",
                        "depot" to depotId,
                        "compressed" to compressedBytes,
                        "uncompressed" to uncompressedBytes,
                        "depots_done" to done,
                        "elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    onStatusUpdate("Depot $depotId done: ${formatBytes(uncompressedBytes)} (depot #$done)")
                }
            }

            downloader.addListener(listener)
            WepWawetLogger.proxy("$TAG: listener registered",
                "listener_class" to listener.javaClass.name,
                "downloader_class" to downloader.javaClass.name)

            val pubFileId = workshopId.toLongOrNull() ?: run {
                WepWawetLogger.proxyError("$TAG: invalid workshop id (not a long)",
                    "input" to workshopId)
                return@withContext null
            }
            // === CRITICAL: pass installDirectory to PubFileItem ===
            // The 2-arg constructor PubFileItem(appId, pubFileId) leaves installDirectory=null,
            // which causes the DepotDownloader to fall back to "depots/" relative to the
            // JVM's current working directory — NOT our prepared installDir. So the file
            // watcher scans our installDir and sees 0 files, even though the DepotDownloader
            // might be writing to a totally different location. The 4-arg constructor
            // PubFileItem(appId, pubFile, installToGameNameDirectory=false, installDirectory=...)
            // lets us tell the DepotDownloader EXACTLY where to write.
            val pubItem = PubFileItem(APP_ID_WE, pubFileId, false, installDir.absolutePath)

            WepWawetLogger.proxy("$TAG: adding PubFileItem",
                "app_id" to APP_ID_WE,
                "pub_file_id" to pubFileId,
                "install_dir" to installDir.absolutePath,
                "pub_item_install_dir" to pubItem.installDirectory,
                "install_dir_exists" to installDir.exists(),
                "install_dir_writable" to installDir.canWrite(),
                "licenses_count" to licenseList.size,
                "steam_client_connected" to (steamClient?.isConnected ?: false),
                "steam_client_class" to (steamClient?.javaClass?.name ?: "null"))
            downloader.add(pubItem)
            WepWawetLogger.proxy("$TAG: PubFileItem added — calling finishAdding")
            downloader.finishAdding()
            WepWawetLogger.proxy("$TAG: items queued, finishAdding done")

            onStatusUpdate("Downloading... this may take a few minutes for large wallpapers")

            // === Heartbeat thread: log download state every 3s (was 5s) so the
            //     user can see whether the download is actually progressing or stuck. ===
            val heartbeatThread = Thread({
                var tick = 0
                while (true) {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { return@Thread }
                    tick++
                    try {
                        val now = System.currentTimeMillis()
                        val elapsedSec = (now - downloadStartMs) / 1000
                        val silentSec = (now - lastActivityMs.get()) / 1000
                        val status = lastStatus.get() ?: "(no status yet)"
                        // Check steam client state for diagnostics
                        val steamConnected = steamClient?.isConnected ?: false
                        // Scan install dir for current file count and total bytes
                        var fileCount = 0
                        var totalBytes = 0L
                        var largestFile = ""
                        var largestSize = 0L
                        try {
                            installDir.walkTopDown().forEach { f ->
                                if (f.isFile) {
                                    fileCount++
                                    totalBytes += f.length()
                                    if (f.length() > largestSize) {
                                        largestSize = f.length()
                                        largestFile = f.name
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                        WepWawetLogger.proxy("$TAG: heartbeat #$tick",
                            "elapsed_s" to elapsedSec,
                            "silent_s" to silentSec,
                            "last_status" to status.take(80),
                            "last_pct" to lastProgressPct.get(),
                            "files_done" to filesCompleted.get(),
                            "chunks_done" to chunksCompleted.get(),
                            "depots_done" to depotsCompleted.get(),
                            "chunk_bytes" to lastChunkBytes.get(),
                            "steam_connected" to steamConnected,
                            "dir_file_count" to fileCount,
                            "dir_total_bytes" to totalBytes,
                            "dir_largest_file" to largestFile.take(60),
                            "dir_largest_size" to largestSize)
                        // Update UI status with elapsed time so the user sees movement
                        // even when no chunk callback has fired yet.
                        if (silentSec > 5) {
                            onStatusUpdate("$status (${elapsedSec}s elapsed, ${silentSec}s since last activity, $fileCount files in cache, ${formatBytes(totalBytes)} total)")
                        } else {
                            onStatusUpdate("$status (${elapsedSec}s elapsed, $fileCount files in cache, ${formatBytes(totalBytes)} total)")
                        }
                        // === Stuck-detection: if we've been silent for 60s+ AND
                        // no files have been written to installDir, the download is
                        // almost certainly dead (Steam disconnected, DepotDownloader
                        // is hung on a dead connection, etc.). Set downloadError so
                        // the awaitCompletion wrapper breaks out and the user gets
                        // a clear "stuck" message instead of waiting the full 10min. ===
                        if (silentSec >= 60 && fileCount == 0 && downloadError == null) {
                            WepWawetLogger.proxyError("$TAG: stuck-detection — aborting download",
                                "silent_s" to silentSec,
                                "dir_file_count" to fileCount,
                                "steam_connected" to steamConnected,
                                "last_status" to status.take(80))
                            downloadError = java.lang.Exception(
                                "Download stuck — no activity for ${silentSec}s and no files written. " +
                                "Steam may have disconnected mid-download (last status: $status, " +
                                "steam_connected=$steamConnected). " +
                                "Try again — the next attempt should be cleaner now that we don't auto-reconnect during downloads.")
                            // Also try to nudge the DepotDownloader's awaitCompletion
                            // by interrupting its thread (best-effort; if the library
                            // doesn't respond to interrupts, the 10min timeout will
                            // still fire).
                        }
                    } catch (he: Throwable) {
                        // Never let the heartbeat thread die silently — that
                        // would hide the fact that the download is stuck.
                        WepWawetLogger.proxyError("$TAG: heartbeat exception",
                            "exception" to he.javaClass.name,
                            "message" to (he.message ?: ""))
                    }
                }
            }, "JavaSteam-Heartbeat").apply {
                isDaemon = true
                start()
            }
            WepWawetLogger.proxy("$TAG: heartbeat thread started",
                "interval_ms" to 3000)

            // === File-watcher thread: scans installDir every 2s for NEW files
            //     and logs them. This catches cases where the depotdownloader
            //     writes files but doesn't fire onFileCompleted (e.g. for very
            //     small workshop items that arrive in a single chunk). ===
            val seenFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val fileWatcherThread = Thread({
                var tick = 0
                while (true) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    try {
                        tick++
                        val currentFiles = mutableListOf<File>()
                        try {
                            installDir.walkTopDown().forEach { f ->
                                if (f.isFile) currentFiles.add(f)
                            }
                        } catch (_: Exception) {}
                        for (f in currentFiles) {
                            val key = f.absolutePath
                            if (seenFiles.add(key)) {
                                WepWawetLogger.proxy("$TAG: file-watcher NEW file",
                                    "tick" to tick,
                                    "file" to f.name.take(120),
                                    "size" to f.length(),
                                    "path" to f.absolutePath.take(200),
                                    "parent" to f.parentFile?.name)
                            } else {
                                // Existing file — log if it grew significantly (>=10%)
                                // Useful to see chunks being appended
                            }
                        }
                    } catch (fe: Throwable) {
                        WepWawetLogger.proxyError("$TAG: file-watcher exception",
                            "exception" to fe.javaClass.name,
                            "message" to (fe.message ?: ""))
                    }
                }
            }, "JavaSteam-FileWatcher").apply {
                isDaemon = true
                start()
            }
            WepWawetLogger.proxy("$TAG: file-watcher thread started",
                "interval_ms" to 2000,
                "install_dir" to installDir.absolutePath)

            // === Mark download as in-progress so onDisconnected doesn't auto-reconnect ===
            // (which would interfere with the DepotDownloader's state — see comment in
            // onDisconnected). Cleared in the finally block after awaitCompletion returns.
            isDownloadInProgress = true

            // === Register the abort callback so the JavaSteamLogBridge can abort
            // this download when it detects a known library bug (e.g., "unsupported
            // file type. Skipping file" — the library logs the error but never
            // completes the completionFuture, causing awaitCompletion to hang
            // forever). Without this, the user waits 60s for the stuck-detection
            // to fire — with it, the download aborts within ~1 second of the
            // library's error log, with a clear "known library bug" message.
            // Cleared in the finally block below. ===
            // v0.7.4: registered via the owner-checked setter so a previous
            // download's cleanup can't race-clear THIS download's callback.
            JavaSteamLogBridge.setAbortCallback(this, workshopId) { reason ->
                if (downloadError == null) {
                    WepWawetLogger.proxyError("$TAG: abort triggered by JavaSteamLogBridge",
                        "reason" to reason.take(300))
                    downloadError = java.lang.Exception(reason)
                    updateStatus("Download aborted: known library bug detected — see logs")
                }
            }
            WepWawetLogger.proxy("$TAG: registered abort callback with JavaSteamLogBridge")

            // === Run awaitCompletion with a 10-minute hard timeout. ===
            // Previously this could hang forever if Steam stopped sending
            // chunks but didn't disconnect — the user would see "Download
            // started..." for hours with no further updates.
            val completionTimeoutMs = 10L * 60 * 1000  // 10 minutes
            val completionStart = System.currentTimeMillis()
            // v0.7.24 (H-D2 BUG FIX): a `var` captured by the inner Thread
            // lambda is boxed in a Ref object WITHOUT volatile semantics —
            // the waiting thread could observe a stale null forever. Atomic
            // reference gives a safe happens-before edge.
            val completionException = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
            WepWawetLogger.proxy("$TAG: calling awaitCompletion (with ${completionTimeoutMs / 1000}s timeout)",
                "thread" to Thread.currentThread().name,
                "downloader_class" to downloader.javaClass.name)
            try {
                val completionJob = Thread {
                    WepWawetLogger.proxy("$TAG: awaitCompletion thread ENTERED",
                        "thread" to Thread.currentThread().name)
                    try {
                        runBlocking { downloader.awaitCompletion() }
                        WepWawetLogger.proxy("$TAG: awaitCompletion thread RETURNED NORMALLY",
                            "thread" to Thread.currentThread().name)
                    } catch (e: Exception) {
                        WepWawetLogger.proxyError("$TAG: awaitCompletion thread THREW",
                            "thread" to Thread.currentThread().name,
                            "exception" to e.javaClass.name,
                            "message" to (e.message ?: ""))
                        completionException.set(e)
                    }
                }.apply { isDaemon = true; name = "JavaSteam-DepotCompletion"; start() }

                // Wait for either completion or timeout
                // v0.7.4: track WHY we left the wait loop. If downloadError was
                // set by the abort callback / disconnect watchdog / stuck
                // detector, that precise root-cause message MUST survive — the
                // old code fell through into the `completionJob.isAlive`
                // "timeout" branch and REPLACED it with a misleading
                // "Steam CDN may be slow or stuck" message, sending the user
                // into pointless retries.
                var waitInterrupted = false
                while (completionJob.isAlive &&
                       System.currentTimeMillis() - completionStart < completionTimeoutMs) {
                    if (downloadError != null) {
                        WepWawetLogger.proxyWarn("$TAG: download error detected — breaking wait",
                            "error" to (downloadError?.message ?: ""))
                        break
                    }
                    // v0.7.24 (C-D3 BUG FIX): was `Thread.sleep(500)` — NOT a
                    // suspension point, so cancelling the parent coroutine
                    // (user taps Cancel / navigates away) never took effect:
                    // the loop kept polling for up to the full 10-minute
                    // timeout while the Steam client kept streaming chunks.
                    // delay(500) suspends and throws CancellationException the
                    // moment the job is cancelled — we break out, interrupt
                    // the completion thread, and the finally block cleans up
                    // the Steam connection within ~0.5s of the tap.
                    try {
                        kotlinx.coroutines.delay(500)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        waitInterrupted = true
                        break
                    }
                }
                when {
                    // Real error already set (abort callback / watchdog / stuck
                    // detection / listener failure) — keep it, just nudge the
                    // completion thread so it doesn't linger.
                    downloadError != null -> {
                        WepWawetLogger.proxyWarn("$TAG: aborting wait — real download error present",
                            "error" to (downloadError?.message ?: ""))
                        completionJob.interrupt()
                    }
                    // Our wait was interrupted (cancellation) — don't report a
                    // bogus timeout, and restore the interrupt status for the
                    // caller's blocking-cancellation handling.
                    waitInterrupted -> {
                        // v0.7.24 (C-D3): no Thread.currentThread().interrupt() —
                        // with delay()-based cancellation there is no interrupt to
                        // restore, and setting the flag on a pooled Dispatchers.IO
                        // thread would spuriously interrupt the NEXT coroutine
                        // that reuses it.
                        completionJob.interrupt()
                        downloadError = java.lang.Exception("Download wait interrupted — cancelled")
                    }
                    completionJob.isAlive -> {
                        val elapsedSec = (System.currentTimeMillis() - completionStart) / 1000
                        val statusAtTimeout = lastStatus.get() ?: "(no status yet)"
                        WepWawetLogger.proxyError("$TAG: awaitCompletion TIMED OUT",
                            "timeout_s" to (completionTimeoutMs / 1000),
                            "elapsed_s" to elapsedSec,
                            "last_status" to statusAtTimeout,
                            "files_done" to filesCompleted.get(),
                            "chunks_done" to chunksCompleted.get(),
                            "depots_done" to depotsCompleted.get())
                        // Interrupt the completion thread (best-effort)
                        completionJob.interrupt()
                        downloadError = java.lang.Exception("Download timed out after ${elapsedSec}s — Steam CDN may be slow or stuck. Last status: $statusAtTimeout")
                    }
                    completionException.get() != null -> {
                        val ce = completionException.get()
                        WepWawetLogger.proxyWarn("$TAG: awaitCompletion threw",
                            "exception" to (ce?.javaClass?.name ?: ""),
                            "message" to (ce?.message ?: ""))
                        if (downloadError == null && ce != null) {
                            downloadError = ce
                        }
                    }
                    else -> {
                        WepWawetLogger.proxy("$TAG: awaitCompletion returned normally",
                            "elapsed_ms" to (System.currentTimeMillis() - completionStart),
                            "files_done" to filesCompleted.get(),
                            "chunks_done" to chunksCompleted.get(),
                            "depots_done" to depotsCompleted.get())
                    }
                }
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: awaitCompletion wrapper exception",
                    "exception" to e.javaClass.name, "message" to (e.message ?: ""))
                if (downloadError == null) {
                    downloadError = e
                }
            }

            // Stop the heartbeat + file-watcher threads
            heartbeatThread.interrupt()
            fileWatcherThread.interrupt()
            WepWawetLogger.proxy("$TAG: heartbeat + file-watcher threads interrupted")

            // Final scan: list every file in installDir so we know what's there
            // before we close the downloader. This is critical for debugging
            // cases where the .pkg lookup fails — we'll have a snapshot of
            // exactly what files exist on disk at this point.
            try {
                val allFiles = installDir.walkTopDown().toList()
                val fileSummary = allFiles.map { f ->
                    val type = if (f.isDirectory) "[D]" else "[F ${f.length()}B]"
                    "$type ${f.absolutePath.replace(installDir.absolutePath, "<root>")}"
                }
                WepWawetLogger.proxy("$TAG: FINAL install dir snapshot",
                    "dir" to installDir.absolutePath,
                    "total_entries" to allFiles.size,
                    "listing" to fileSummary.joinToString("\n  ").take(2000))
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: failed to list install dir for final snapshot",
                    "exception" to e.javaClass.name)
            }

            downloader.removeListener(listener)
            WepWawetLogger.proxy("$TAG: listener removed")
            try { downloader.close() } catch (_: Exception) {}
            // === Clear the download-in-progress flag — onDisconnected can
            // auto-reconnect again now that the DepotDownloader is done. ===
            isDownloadInProgress = false
            // === Clear the abort callback so the bridge doesn't keep a stale
            // reference to this WorkshopDownloader instance (would cause
            // memory leak + incorrect aborts on the next download).
            // v0.7.4: owner-checked — only clears if WE still own it (a newer
            // download may have already registered its own callback). ===
            JavaSteamLogBridge.clearAbortCallback(this)
            WepWawetLogger.proxy("$TAG: cleared abort callback from JavaSteamLogBridge")
            WepWawetLogger.proxy("$TAG: downloader closed",
                "files_done" to filesCompleted.get(),
                "chunks_done" to chunksCompleted.get(),
                "depots_done" to depotsCompleted.get(),
                "chunk_bytes" to lastChunkBytes.get(),
                "total_elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))

            if (downloadError != null) {
                onStatusUpdate("Download failed: ${downloadError!!.message}")
                return@withContext null
            }

            onStatusUpdate("Locating downloaded content...")
            WepWawetLogger.proxy("$TAG: scanning install dir for .pkg or .mp4",
                "dir" to installDir.absolutePath)
            // === v0.4.8: First try .pkg (Scene wallpapers), then .mp4 (Video wallpapers). ===
            // Video wallpapers downloaded from Steam don't have a .pkg file — the
            // downloaded content is just the .mp4 file directly. Previously this
            // caused video downloads to "fail" even though the .mp4 was right
            // there in the install dir.
            val pkgFile = findPkgFile(installDir)
            if (pkgFile != null) {
                WepWawetLogger.proxy("$TAG: .pkg found",
                    "file" to pkgFile.absolutePath,
                    "size" to pkgFile.length(),
                    "files_done" to filesCompleted.get(),
                    "chunks_done" to chunksCompleted.get(),
                    "depots_done" to depotsCompleted.get())
                pkgFile.copyTo(outputFile, overwrite = true)
                WepWawetLogger.proxy("$TAG COMPLETE (Scene .pkg)",
                    "output" to outputFile.name,
                    "size" to outputFile.length(),
                    "total_elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                onStatusUpdate("Downloaded ${formatBytes(outputFile.length())} .pkg (Scene wallpaper)")
                outputFile
            } else {
                // No .pkg found — try .mp4 (video wallpaper)
                WepWawetLogger.proxy("$TAG: no .pkg found, checking for video file",
                    "dir" to installDir.absolutePath)
                val videoFile = findVideoFile(installDir)
                if (videoFile != null) {
                    val isVideo = isVideoWallpaper(installDir)
                    WepWawetLogger.proxy("$TAG: video file found",
                        "file" to videoFile.absolutePath,
                        "size" to videoFile.length(),
                        "is_video_wallpaper" to isVideo,
                        "files_done" to filesCompleted.get(),
                        "chunks_done" to chunksCompleted.get(),
                        "depots_done" to depotsCompleted.get())
                    videoFile.copyTo(outputFile, overwrite = true)
                    WepWawetLogger.proxy("$TAG COMPLETE (Video .mp4)",
                        "output" to outputFile.name,
                        "size" to outputFile.length(),
                        "is_video" to isVideo,
                        "total_elapsed_ms" to (System.currentTimeMillis() - downloadStartMs))
                    onStatusUpdate("Downloaded ${formatBytes(outputFile.length())} .mp4 (Video wallpaper)")
                    outputFile
                } else {
                    // Neither .pkg nor media found — list directory contents so we can debug
                    val listing = installDir.walkTopDown().toList().take(20).joinToString("\n  ") { f ->
                        "${if (f.isDirectory) "[DIR] " else ""}${f.name} (${f.length()} B)"
                    }
                    WepWawetLogger.proxyWarn("$TAG: no .pkg or media file found",
                        "dir" to installDir.absolutePath,
                        "dir_listing" to listing)
                    onStatusUpdate("No .pkg or media file found after download (see logs for directory listing)")
                    return@withContext null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v0.7.24 (C-D4 BUG FIX): CancellationException extends
            // IllegalStateException extends Exception — the old
            // `catch (e: Exception)` swallowed the cancellation and converted
            // it to a normal `null` return, after which the caller kept doing
            // real work (packing, queueing) against a cancelled scope.
            // Re-throw so structured concurrency completes; the finally block
            // below still disconnects Steam.
            WepWawetLogger.proxyWarn("$TAG: download cancelled",
                "exception" to e.javaClass.name)
            throw e
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: exception", "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            onStatusUpdate("Error: ${e.message}")
            null
        } finally {
            cleanup()
        }
    }

    /**
     * Connect to Steam and authenticate. Can be called standalone for testLogin.
     */
    fun connectAndAuthenticate(context: Context): Boolean {
        // === Install the JavaSteam library log bridge ONCE globally so we can
        // see what the library is doing internally (RPC requests, callback
        // dispatch, errors). Without this, the DepotDownloader's logs are
        // invisible — making it impossible to diagnose why a download hangs. ===
        JavaSteamLogBridge.installIfNeeded()

        // === v0.5.32 (audit H2): Clean up the OLD SteamClient + OkHttp
        // thread pool BEFORE creating new ones. ===
        // Previously this function unconditionally created a brand new
        // SteamClient (line 891) and a brand new okhttp3.Dispatcher with
        // a fresh cached thread pool (line 880), without ever disconnecting
        // the previous SteamClient or shutting down the previous OkHttp
        // pool. The orphaned SteamClient may still hold a half-open TCP
        // socket to a Steam CM, and the auto-reconnect thread spawned in
        // onDisconnected keeps poking it. Each reconnect leaked an entire
        // unbounded cached thread pool. After ~5-10 reconnects the app
        // accumulated a dozen orphaned Steam TCP connections and stale
        // OkHttp pools — Steam rate-limits by IP, so the user got
        // RateLimitExceeded from the wrong (orphaned) session, blocking
        // the new session.
        runCatching { steamClient?.disconnect() }
        runCatching {
            (httpClient?.dispatcher?.executorService as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
        }
        // v0.7.24 (C-D2 BUG FIX): the disconnect above aborts the OLD
        // session's authPollingThread's in-flight pollingWaitForResult()
        // future — but the thread itself was only ever interrupted in
        // cleanup(), NEVER on reconnect. The orphaned thread then received
        // the disconnect exception, saw isRetrying == false, and wrote
        // `downloadError = e` AFTER this function had just cleared it —
        // clobbering the NEW session's state (connectAndAuthenticate then
        // failed spuriously). Interrupt + JOIN the old thread here so it is
        // fully dead before the state reset below.
        authPollingThread?.let { oldThread ->
            try { oldThread.interrupt() } catch (_: Exception) {}
            try { oldThread.join(2000) } catch (_: Exception) {}
        }
        authPollingThread = null

        // === Reset auth state from any previous session ===
        // CRITICAL: If this WorkshopDownloader instance is being REUSED (e.g.,
        // WorkshopBrowseSession reuses the same downloader across browse queries),
        // the `authenticated` flag might still be true from a previous session
        // that has since disconnected. Without this reset, the polling loop below
        // returns true IMMEDIATELY (because `authenticated` is stale-true), before
        // the new SteamClient has even connected. This causes every subsequent
        // RPC call (e.g., ISteamPublishedFile.QueryFiles) to fail with
        // AsyncJobFailedException because the connection isn't actually ready.
        //
        // Also reset downloadError so a previous error doesn't short-circuit the
        // new attempt, and hasValidToken so the refresh-token logic runs fresh.
        authenticated = false
        downloadError = null
        hasValidToken = false

        // === Clean up old subscriptions before re-subscribing ===
        // Each call to connectAndAuthenticate() adds 6+ subscriptions to the
        // `subscriptions` list. Without clearing old ones first, the list grows
        // unbounded on each reconnect — a memory leak. The old subscriptions
        // also reference the old (now-orphaned) callbackManager, so they'd
        // dispatch to stale handlers.
        subscriptions.forEach { try { it.close() } catch (_: Exception) {} }
        subscriptions.clear()

        // === Stop old callback thread before starting a new one ===
        // Setting isRunning = false causes the old callback thread to exit its
        // polling loop (it checks isRunning at the top of each iteration).
        // We'll set it back to true below before spawning the new thread.
        isRunning = false
        // Interrupt the old thread so it exits immediately even if it's inside
        // runWaitCallbacks(1000L) (which blocks for up to 1 second). Then join
        // for up to 2 seconds to ensure it's fully dead before we spawn a new
        // one — this prevents the race where both old and new threads call
        // runWaitCallbacks on the same callbackManager concurrently.
        callbackThread?.let { oldThread ->
            try {
                oldThread.interrupt()
                oldThread.join(2000)
            } catch (_: Exception) {}
        }
        callbackThread = null

        val dispatcher = okhttp3.Dispatcher(Executors.newCachedThreadPool { r ->
            Thread(r, "JavaSteam-Http").apply { isDaemon = true }
        })
        // v0.5.32 (audit H2): store in field so the next connectAndAuthenticate
        // call can shutdown the dispatcher's executor — see the cleanup at
        // the top of this function.
        httpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        // v0.7.4: capture the APPLICATION context — never the caller's
        // (possibly Activity) context. WorkshopBrowseSession keeps its
        // downloader alive for the whole app process, and this context is
        // stored both in the `appContext` field and inside the ConnectedCallback
        // subscription lambda — capturing an Activity here would leak that
        // Activity for the lifetime of the singleton. getSharedPreferences and
        // every other use here behave identically on an application context.
        val appCtx = context.applicationContext
        appContext = appCtx
        steamClient = SteamClient(SteamConfiguration.create { it.withHttpClient(httpClient!!) })
        callbackManager = CallbackManager(steamClient!!)
        steamUser = steamClient!!.getHandler(SteamUser::class.java)

        subscriptions.add(callbackManager!!.subscribe(ConnectedCallback::class.java) { onConnected(it, appCtx) })
        subscriptions.add(callbackManager!!.subscribe(DisconnectedCallback::class.java) { onDisconnected(it) })
        subscriptions.add(callbackManager!!.subscribe(LoggedOnCallback::class.java) { onLoggedOn(it) })
        subscriptions.add(callbackManager!!.subscribe(LoggedOffCallback::class.java) { onLoggedOff(it) })
        subscriptions.add(callbackManager!!.subscribe(LicenseListCallback::class.java) { onLicenseList(it) })
        // === Debug: subscribe to the base CallbackMsg class to log EVERY callback
        // that's dispatched by the callback manager. This catches ServiceMethodResponse
        // (the RPC response from steamPublishedFile.getDetails), ServiceMethodNotification,
        // and any other callbacks we don't explicitly subscribe to. ===
        try {
            subscriptions.add(callbackManager!!.subscribe(
                `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg::class.java
            ) { msg ->
                WepWawetLogger.proxy("$TAG: callback dispatched",
                    "class" to msg.javaClass.simpleName,
                    "job_id" to (msg.jobID?.toString() ?: "null"))
            })
            WepWawetLogger.proxy("$TAG: subscribed to CallbackMsg (base) for verbose dispatch logging")
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn("$TAG: failed to subscribe to CallbackMsg base class",
                "exception" to e.javaClass.name)
        }

        isRunning = true
        cmRetries = 0
        WepWawetLogger.proxy("$TAG: connecting to Steam...")
        steamClient!!.connect()

        // === Callback manager thread ===
        // Runs runWaitCallbacks(1000) in a loop while isRunning is true.
        // The JavaSteam library's CoroutineScope (used by DepotDownloader) suspends
        // on AsyncJobSingle.await() — this only resumes when the corresponding
        // callback is dispatched through the callback manager. If this thread dies
        // (e.g., due to an unhandled exception), every async RPC call hangs forever.
        //
        // Previously, the catch(_:Exception){break} silently killed the thread —
        // leaving no log evidence that the thread had died. Now we log every
        // exception and continue the loop (don't break) so a transient failure
        // doesn't permanently disable RPC dispatch.
        //
        // We also call runWaitAllCallbacks(0) AFTER runWaitCallbacks(1000L) to
        // drain any additional pending callbacks in the queue (in case there's
        // a burst of multiple callbacks).
        Thread({
            var tick = 0
            var lastException: String? = null
            while (isRunning) {
                try {
                    callbackManager!!.runWaitCallbacks(1000L)
                    // Drain any additional pending callbacks immediately
                    callbackManager!!.runWaitAllCallbacks(0)
                } catch (e: InterruptedException) {
                    WepWawetLogger.proxy("$TAG: callback thread interrupted, exiting")
                    return@Thread
                } catch (e: Exception) {
                    // Log the exception ONCE per unique message to avoid spam
                    val excName = e.javaClass.name
                    if (excName != lastException) {
                        lastException = excName
                        WepWawetLogger.proxyError("$TAG: callback thread exception (continuing)",
                            "exception" to excName,
                            "message" to (e.message ?: "").take(200))
                    }
                }
                // Heartbeat every 10 iterations (~10s) so we can verify
                // the thread is alive in the logs
                tick++
                if (tick % 10 == 0) {
                    WepWawetLogger.proxy("$TAG: callback thread heartbeat",
                        "tick" to tick,
                        "is_running" to isRunning,
                        "authenticated" to authenticated,
                        "steam_connected" to (steamClient?.isConnected ?: false))
                }
            }
            WepWawetLogger.proxy("$TAG: callback thread EXITED (isRunning=false)")
        }).apply { name = "JavaSteam-Callback"; isDaemon = true }.also {
            callbackThread = it
        }.start()

        val timeout = 120000L
        val start = System.currentTimeMillis()
        while (isRunning && System.currentTimeMillis() - start < timeout) {
            if (authenticated) return true
            if (downloadError != null) return false
            try { Thread.sleep(500) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    /**
     * Clean up — disconnect from Steam, close subscriptions.
     */
    fun cleanup() {
        try { steamUser?.logOff() } catch (_: Exception) {}
        // v0.7.24 (M-D8): logOff() is asynchronous — disconnecting in the
        // same microsecond could cut the logoff message mid-flight, leaving
        // a ghost session on Steam's side (the CM keeps the session alive
        // until its own timeout, contributing to RateLimitExceeded). A short
        // grace delay lets the message hit the wire.
        try { Thread.sleep(150) } catch (_: InterruptedException) {}
        try { steamClient?.disconnect() } catch (_: Exception) {}
        // v0.7.4: interrupt the modern-auth polling thread — it may still be
        // blocked in pollingWaitForResult().get() waiting for the user to
        // approve the login on their phone. Without this it lingered as a
        // fire-and-forget thread that later called steamUser!!.logOn() on an
        // already-disconnected client.
        try { authPollingThread?.interrupt() } catch (_: Exception) {}
        authPollingThread = null
        // v0.5.32 (audit H2): also shutdown the OkHttp dispatcher's
        // executor so the cached thread pool doesn't leak daemon threads
        // (one per HTTP request that ever ran on this client). Without
        // this, even after cleanup() the OkHttp threads stay alive as
        // daemons consuming file descriptors + memory.
        try {
            (httpClient?.dispatcher?.executorService as? java.util.concurrent.ExecutorService)
                ?.shutdownNow()
        } catch (_: Exception) {}
        httpClient = null
        subscriptions.forEach { try { it.close() } catch (_: Exception) {} }
        subscriptions.clear()
        isRunning = false
        // v0.7.4: interrupt + join the callback dispatch thread (mirrors what
        // connectAndAuthenticate does before spawning a new one). Without the
        // interrupt the thread could linger up to 1s inside
        // runWaitCallbacks(1000L) after cleanup() returned — and dispatch
        // callbacks into the now-closed subscriptions.
        callbackThread?.let { oldThread ->
            try {
                oldThread.interrupt()
                oldThread.join(2000)
            } catch (_: Exception) {}
        }
        callbackThread = null
    }

    private fun onConnected(callback: ConnectedCallback, context: Context) {
        WepWawetLogger.proxy("$TAG: connected — authenticating as $username")
        updateStatus("Connected to Steam — authenticating...")

        // Don't start a new auth session if one is already in progress (e.g. during TryAnotherCM retry)
        if (authInProgress) {
            WepWawetLogger.proxyWarn("$TAG: auth already in progress, skipping")
            return
        }
        authInProgress = true
        isRetrying = false  // clear retry flag on new connection

        // Step 1: Try saved refresh token first (no 2FA needed)
        val prefs = SteamCredentialsStore.tokenPrefs(context)
        val savedToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        val savedAccountName = prefs.getString(KEY_ACCOUNT_NAME, null)
        WepWawetLogger.proxy("$TAG: checking for saved refresh token",
            "has_token" to !savedToken.isNullOrBlank(),
            "has_account_name" to !savedAccountName.isNullOrBlank(),
            "token_length" to (savedToken?.length ?: 0),
            "account_name" to (savedAccountName ?: "null"))
        if (!savedToken.isNullOrBlank() && !savedAccountName.isNullOrBlank()) {
            WepWawetLogger.proxy("$TAG: trying saved refresh token",
                "account_name" to savedAccountName,
                "user_entered" to username)
            try {
                val details = LogOnDetails()
                details.username = savedAccountName  // Use Steam's account name (case-correct)
                details.accessToken = savedToken
                // === CRITICAL (v0.4.7): Set shouldRememberPassword = true when
                // using refresh token login. The JavaSteam sample code (and
                // LogOnDetails.kt docstring) says:
                //   "Set this to true when [AuthSessionDetails.persistentSession]
                //    is set to true."
                // Without this flag, Steam treats the refresh token as
                // EPHEMERAL — it gets invalidated after one use, causing the
                // next login attempt to fail with AccessDenied. This was the
                // ROOT CAUSE of the user's "steam login only lasts one wallpaper
                // then access denies me" bug.
                // Verified against JavaSteam sample _023_downloadapp:
                //   details.setShouldRememberPassword(true);
                //   details.setLoginID(149);
                // https://github.com/687meowmeow/JavaSteam samples
                details.shouldRememberPassword = true
                // v0.5.32 (audit M7): use a per-instance random loginID instead
                // of hardcoded 149. WorkshopBrowseSession's singleton downloader
                // and WorkshopDownloadJob's per-download downloader both use the
                // same Steam credentials — using the same loginID = 149 caused
                // Steam to kick the older session with EResult.LogonSessionReplaced
                // when a download was started while a browse session was active.
                details.loginID = steamLoginID
                usedRefreshToken = true
                steamUser!!.logOn(details)
                return  // Wait for onLoggedOn callback — if it fails, we'll fall back
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: refresh token logOn call failed",
                    "exception" to e.javaClass.name)
                usedRefreshToken = false
            }
        }

        // Step 2: Use modern auth flow (supports 2FA)
        try {
            WepWawetLogger.proxy("$TAG: starting modern auth session")
            val authDetails = AuthSessionDetails()
            authDetails.username = username
            authDetails.password = password
            authDetails.deviceFriendlyName = "WepWawet"
            authDetails.persistentSession = true
            authDetails.authenticator = GuardAuthenticator()

            val authSessionFuture = steamClient!!.authentication.beginAuthSessionViaCredentials(authDetails)

            // v0.7.4: store the thread so cleanup() can interrupt it (it used
            // to be fire-and-forget and could outlive the session), and bound
            // both .get() calls with timeouts so a silent Steam never leaves it
            // blocked forever.
            authPollingThread = Thread {
                try {
                    WepWawetLogger.proxy("$TAG: waiting for auth session...")
                    updateStatus("Waiting for Steam auth session...")
                    val authSession = authSessionFuture.get(60, TimeUnit.SECONDS)

                    WepWawetLogger.proxy("$TAG: polling auth session (may request 2FA)...")
                    updateStatus("Authenticating with Steam (approve on your Steam mobile app)...")
                    // 5 minutes for the user to tap "Approve" on their phone —
                    // Steam Guard confirmations expire after a few minutes anyway.
                    val result = authSession.pollingWaitForResult().get(5, TimeUnit.MINUTES)

                    WepWawetLogger.proxy("$TAG: auth SUCCESS",
                        "account" to result.accountName,
                        "has_refresh_token" to !result.refreshToken.isNullOrBlank())

                    // Save refresh token + account name for future logins (survives app updates)
                    if (!result.refreshToken.isNullOrBlank()) {
                        SteamCredentialsStore.tokenPrefs(context)
                            .edit()
                            .putString(KEY_REFRESH_TOKEN, result.refreshToken)
                            .putString(KEY_ACCOUNT_NAME, result.accountName)
                            .apply()
                        hasValidToken = true
                        WepWawetLogger.proxy("$TAG: refresh token + account name saved ✓ (survives app updates)",
                            "account_name" to result.accountName)
                    }

                    val details = LogOnDetails()
                    details.username = result.accountName
                    details.accessToken = result.refreshToken
                    // === CRITICAL (v0.4.7): Same as refresh-token login — set
                    // shouldRememberPassword = true when persistentSession was
                    // requested. Without this, Steam may invalidate the refresh
                    // token after one use → next attempt = AccessDenied. ===
                    details.shouldRememberPassword = true
                    // v0.5.32 (audit M7): use per-instance steamLoginID here too
                    // (see the comment in connectAndAuthenticate above) — using
                    // hardcoded 149 here collides with the BrowseSession's loginID
                    // if a download is started while a browse session is connected.
                    details.loginID = steamLoginID
                    steamUser!!.logOn(details)
                } catch (e: Exception) {
                    // Don't fail if we're retrying (TryAnotherCM causes CancellationException)
                    if (isRetrying) {
                        WepWawetLogger.proxyWarn("$TAG: auth polling cancelled during retry (expected)",
                            "exception" to e.javaClass.name)
                    } else {
                        WepWawetLogger.proxyError("$TAG: auth polling failed",
                            "exception" to e.javaClass.name,
                            "message" to (e.message ?: ""))
                        downloadError = e
                    }
                    authInProgress = false
                }
            }.apply { isDaemon = true; name = "JavaSteam-AuthPoll" }.also { it.start() }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("$TAG: failed to start auth session",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
            downloadError = e
        }
    }

    private fun onLoggedOn(callback: LoggedOnCallback) {
        if (callback.result != EResult.OK) {
            WepWawetLogger.proxyError("$TAG: logon failed", "result" to callback.result,
                "used_refresh_token" to usedRefreshToken,
                "has_valid_token" to hasValidToken)
            if (callback.result == EResult.TryAnotherCM && cmRetries < 5) {
                cmRetries++
                isRetrying = true
                authInProgress = false
                WepWawetLogger.proxy("$TAG: TryAnotherCM — retry #$cmRetries with different CM server...")
                updateStatus("Switching to different Steam server (retry $cmRetries)...")
                // v0.7.4: spawn a NEW thread for the retry — calling connect()
                // directly on the callback-dispatch thread risks re-entering
                // JavaSteam's connection state machine and deadlocking its
                // internal locks (the same reasoning as the onDisconnected
                // reconnect path below), and Thread.sleep(3000) here would
                // block ALL callback dispatch for 3 seconds.
                Thread {
                    try { Thread.sleep(3000); steamClient?.connect() } catch (_: InterruptedException) {}
                    catch (_: Exception) {}
                }.apply { isDaemon = true; name = "JavaSteam-CmRetry"; start() }
                return
            }
            // If refresh token failed for any token-related reason (Steam backend
            // changes mean refresh tokens for SteamClient platform are now
            // authenticated — so tokens that previously worked can suddenly start
            // returning AccessDenied, Expired, Revoked, etc.),
            //
            // CRITICAL FIX (v0.4.7): Previously, the code tried to reconnect
            // the same SteamClient for a fresh push-notification login. But
            // the reconnect's WebSocket connected without firing onConnected
            // (the Steam protocol handshake failed silently), causing the app
            // to hang indefinitely. The user had to force-restart the app.
            //
            // Now: we DON'T try to reconnect. We clean up immediately, clear
            // the stale token, and return a user-friendly error. The user can
            // immediately retry by tapping the download button again — each
            // retry creates a FRESH WorkshopDownloader with a FRESH SteamClient,
            // which goes through the modern auth flow (Steam Guard push
            // notification) cleanly since the refresh token was cleared.
            val isTokenRelatedFailure = callback.result == EResult.InvalidPassword ||
                callback.result == EResult.AccessDenied ||
                callback.result == EResult.Expired ||
                callback.result == EResult.Revoked ||
                callback.result == EResult.LogonSessionReplaced ||
                callback.result == EResult.AlreadyLoggedInElsewhere ||
                callback.result == EResult.PasswordRequiredToKickSession
            if (usedRefreshToken && isTokenRelatedFailure) {
                WepWawetLogger.proxy("$TAG: refresh token rejected (result=${callback.result}) — cleaning up for user retry",
                    "result" to callback.result)
                usedRefreshToken = false
                authInProgress = false
                // Clear the stale refresh token so the next attempt uses modern auth
                appContext?.let { SteamCredentialsStore.tokenPrefs(it) }
                    ?.edit()?.remove(KEY_REFRESH_TOKEN)?.remove(KEY_ACCOUNT_NAME)?.apply()
                WepWawetLogger.proxy("$TAG: stale refresh token cleared (was rejected with ${callback.result})")
                // Set a user-friendly error and let the caller handle it.
                // The user can immediately retry — each retry creates a fresh
                // WorkshopDownloader with a fresh SteamClient.
                val retryMsg = "Steam session expired (${callback.result}). Tap download again to re-authenticate via Steam Guard."
                downloadError = java.lang.Exception(retryMsg)
                updateStatus(retryMsg)
                isRunning = false
                return
            }
            // Clear stale token on RateLimitExceeded
            if (callback.result == EResult.RateLimitExceeded) {
                appContext?.let { SteamCredentialsStore.tokenPrefs(it) }
                    ?.edit()?.remove(KEY_REFRESH_TOKEN)?.remove(KEY_ACCOUNT_NAME)?.apply()
            }
            // Provide user-friendly error messages
            val friendlyError = when (callback.result) {
                EResult.InvalidPassword -> "Invalid password (or 2FA code needed/expired)"
                EResult.AccountLogonDenied -> "Steam Guard email code required"
                EResult.TwoFactorCodeMismatch -> "Steam Guard 2FA code is wrong or expired"
                EResult.AccountDisabled -> "Steam account is disabled"
                EResult.RateLimitExceeded -> "Steam rate limit exceeded — wait 30 minutes and try again"
                else -> "Steam logon failed: ${callback.result}"
            }
            downloadError = java.lang.Exception(friendlyError)
            updateStatus("Login failed: $friendlyError")
            isRunning = false
            return
        }
        authenticated = true
        authInProgress = false
        // v0.7.4: reset the CM retry budget on every successful login. It was
        // only reset in connectAndAuthenticate, so a session that used 4-5
        // retries to connect could exhaust the budget for LATER transient
        // TryAnotherCM responses in the same session, turning a recoverable
        // CM hiccup into a hard failure.
        cmRetries = 0
        val wasUsingRefreshToken = usedRefreshToken
        usedRefreshToken = false
        // Log detailed info about the login success
        WepWawetLogger.proxy("$TAG: logged on to Steam ✓",
            "used_refresh_token" to wasUsingRefreshToken,
            "has_valid_token" to hasValidToken,
            "result" to callback.result)
        // === Token rotation note (v0.4.7): ===
        // We previously tried to detect a rotated refresh token by reflecting
        // on LoggedOnCallback fields ("refreshToken", "accessToken",
        // "newRefreshToken"). That was USELESS — we verified by reading the
        // actual JavaSteam source (LoggedOnCallback.kt at
        // github.com/687meowmeow/JavaSteam) that the class has NO refresh
        // token field. Steam's token rotation, if any, is invisible to us
        // through LoggedOnCallback.
        //
        // The CORRECT way to keep the refresh token valid across multiple
        // logins is to set `details.shouldRememberPassword = true` on the
        // LogOnDetails — this tells Steam "I want this refresh token to be
        // persistent, don't invalidate it after one use." We do that now in
        // onConnected (both for the refresh-token path AND the modern-auth
        // path). Without that flag, Steam treats the token as ephemeral and
        // invalidates it after one login → next attempt = AccessDenied.
        //
        // With shouldRememberPassword=true, Steam should keep the refresh
        // token valid for ~30 days (its standard refresh token lifetime).
        // No rotation detection needed.
        hasValidToken = true  // Mark that we have a valid authenticated session
        if (wasUsingRefreshToken) {
            WepWawetLogger.proxy("$TAG: refresh token login SUCCEEDED (token kept valid by shouldRememberPassword=true)")
        }
        updateStatus("Logged in to Steam ✓ — fetching licenses...")
    }

    private fun onLoggedOff(callback: LoggedOffCallback) {
        WepWawetLogger.proxy("$TAG: logged off",
            "result" to callback.result,
            "authenticated" to authenticated,
            "download_in_progress" to isDownloadInProgress)
        // v0.7.4: clear `authenticated` too. It used to stay true, so the
        // onDisconnected that fires right after took the auto-reconnect
        // branch and spawned a reconnect thread — but isRunning=false kills
        // the callback-dispatch thread, so the reconnect's onConnected was
        // never delivered: the session sat half-alive, the SteamClient +
        // OkHttp pool leaked, and WorkshopBrowseSession kept seeing
        // isAuthenticated()==true for a dead session.
        authenticated = false
        isRunning = false
    }

    private fun onDisconnected(callback: DisconnectedCallback) {
        // === CRITICAL: Reset `authenticated` flag on disconnect ===
        // Without this, the `authenticated` flag stays true after a disconnect
        // (e.g., WebSocket watchdog timeout after 30s of inactivity). Then the
        // next `connectAndAuthenticate()` call's polling loop returns true
        // immediately (based on stale auth state), before the new SteamClient
        // has connected — causing every RPC call to fail with
        // AsyncJobFailedException. WorkshopBrowseSession.ensureConnected() also
        // relies on this to invalidate its fast-path return.
        authenticated = false

        WepWawetLogger.proxy("$TAG: disconnected",
            "user_initiated" to callback.isUserInitiated,
            "authenticated" to authenticated,
            "download_in_progress" to isDownloadInProgress)
        // (v0.7.4: the old isReconnectingForFallback branch here was removed —
        //  the flag was never set to true anywhere, so the branch could never
        //  execute. Behavior is unchanged.)
        // === CRITICAL: do NOT auto-reconnect while a download is in progress. ===
        // The DepotDownloader holds a reference to steamClient and is making API
        // calls through it. If we reconnect (creating a new internal session),
        // the DepotDownloader's state becomes inconsistent with the new connection
        // — its network calls hang forever waiting for chunks that will never
        // arrive.
        //
        // Also: if the disconnect is NOT user-initiated during a download
        // (e.g., the WebSocket "Watchdog: No response for 30 seconds"
        // disconnect), the connection is already dead — we can't recover
        // mid-download. Set downloadError immediately so the user sees a
        // clear "Steam disconnected during download" message instead of
        // waiting the full 60s stuck-detection window.
        if (isDownloadInProgress) {
            WepWawetLogger.proxyError("$TAG: disconnect DURING download (likely WebSocket watchdog) — aborting immediately",
                "user_initiated" to callback.isUserInitiated)
            if (downloadError == null) {
                // v0.5.32 (audit C3 fix): Wrap the if-expression in parens
                // so the trailing retry suffix concatenates correctly. Without
                // the parens, Kotlin parses `String + if (cond) A else B + C`
                // as `String + (if (cond) A else (B + C))` — so the suffix `C`
                // ends up inside the `else` branch and is only included when
                // the watchdog text is NOT (i.e., user-initiated disconnects).
                // For the common watchdog case the retry hint was missing.
                downloadError = java.lang.Exception(
                    "Steam connection dropped during download" +
                    (if (!callback.isUserInitiated) " (WebSocket watchdog — Steam was unresponsive for 30s)" else "") +
                    ". The DepotDownloader can't recover mid-download — please retry.")
            }
            updateStatus("Steam connection dropped during download — aborting (please retry)")
            return
        }
        if (!callback.isUserInitiated && authenticated) {
            // Spawn a NEW thread for the reconnect attempt — calling connect()
            // from the callback thread (which dispatched onDisconnected) risks
            // re-entering the connection state machine and deadlocking JavaSteam's
            // internal locks. The callback thread must be free to drain any
            // remaining callbacks from the old connection.
            Thread {
                try { Thread.sleep(2000); steamClient?.connect() } catch (_: InterruptedException) {}
                catch (_: Exception) {}
            }.apply { isDaemon = true; name = "JavaSteam-Reconnect"; start() }
        } else if (!callback.isUserInitiated && !authenticated) {
            // Reconnect during initial connection attempt — same: spawn a new thread.
            Thread {
                try { Thread.sleep(2000); steamClient?.connect() } catch (_: InterruptedException) {}
                catch (_: Exception) {}
            }.apply { isDaemon = true; name = "JavaSteam-Reconnect"; start() }
        } else {
            isRunning = false
        }
    }

    private fun onLicenseList(callback: LicenseListCallback) {
        if (callback.result != EResult.OK) return
        licenseList = callback.licenseList
        WepWawetLogger.proxy("$TAG: got licenses", "count" to licenseList.size)
    }

    private fun waitForLicenses(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (licenseList.isNotEmpty()) return true
            if (downloadError != null) return false
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return licenseList.isNotEmpty()
    }

    private fun findPkgFile(searchDir: File): File? {
        if (!searchDir.exists()) return null
        searchDir.walkTopDown().forEach { file ->
            if (file.isFile && file.length() > 1024) {
                try {
                    // v0.7.4: .use{} — if readFully throws (file truncated between
                    // the length() check and the read, IO error), close() was
                    // previously skipped and the FD leaked silently.
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        val header = ByteArray(12)
                        raf.readFully(header)
                        if (header[0] == 0x08.toByte() && header[4] == 'P'.code.toByte() &&
                            header[5] == 'K'.code.toByte() && header[6] == 'G'.code.toByte() &&
                            header[7] == 'V'.code.toByte()) return file
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * === v0.4.8: Find a video file (.mp4) in the install dir. ===
     *
     * Video wallpapers downloaded from Steam don't have a .pkg file — the
     * downloaded content is just the .mp4 file directly (plus project.json
     * and preview.gif). The previous code only looked for .pkg files, so
     * video wallpapers would fail with "No .pkg file found after download"
     * even though the .mp4 was right there in the install dir.
     *
     * This helper finds the largest .mp4 file in the install dir (the
     * largest file is almost certainly the video, not a preview).
     *
     * Returns the .mp4 File, or null if no .mp4 found.
     */
    private fun findVideoFile(searchDir: File): File? {
        // === v0.7.4 (parity G2): ALL video containers + still images. ===
        // The v0.4.8 version only looked for .mp4/.m4v/.mov/.webm AND verified
        // the MP4 "ftyp" magic — which REJECTED WebM (EBML magic) and never
        // looked for AVI/WMV/MKV/FLV/TS/GIF video wallpapers or JPEG/PNG/WebP/
        // BMP image wallpapers at all. Now every file > 1 MB is sniffed with
        // [VideoContainerSniffer] — no extension trust, no per-format magic
        // re-implementation. The largest video-kind or image-kind file wins
        // (the largest file is the content, not a preview).
        if (!searchDir.exists()) return null
        var largestMedia: File? = null
        var largestSize = 0L
        searchDir.walkTopDown().forEach { file ->
            if (file.isFile && file.length() > 1024 * 1024) {  // > 1MB to skip tiny files
                val kind = VideoContainerSniffer.sniff(file)
                val isMedia = VideoContainerSniffer.isVideoKind(kind) ||
                    VideoContainerSniffer.isImageKind(kind)
                if (isMedia && file.length() > largestSize) {
                    largestSize = file.length()
                    largestMedia = file
                }
            }
        }
        if (largestMedia != null) {
            WepWawetLogger.proxy("$TAG.findVideoFile: media file found",
                "file" to largestMedia!!.name,
                "size" to largestMedia!!.length(),
                "container" to VideoContainerSniffer.label(
                    VideoContainerSniffer.sniff(largestMedia!!)))
        }
        return largestMedia
    }

    /**
     * === v0.4.8: Detect if a project.json indicates a Video wallpaper. ===
     * Reads project.json from the install dir and checks the "type" field.
     * Returns true if type=="video", false otherwise (or if project.json
     * can't be read).
     */
    private fun isVideoWallpaper(installDir: File): Boolean {
        val projectJson = java.io.File(installDir, "project.json")
        if (!projectJson.exists()) return false
        return try {
            val content = projectJson.readText()
            // Simple regex check — avoid pulling in a JSON parser here
            // Match: "type" : "video" (with optional whitespace variations)
            val pattern = Regex("""["']type["']\s*:\s*["']video["']""", RegexOption.IGNORE_CASE)
            pattern.containsMatchIn(content)
        } catch (_: Exception) {
            false
        }
    }

    // v0.7.24 (M-D2): Locale.US — String.format with the default locale
    // renders Arabic-Indic digits (٣٫٥) in ar/fa locales, breaking the log
    // parsers AND the status strings shown to the user.
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
