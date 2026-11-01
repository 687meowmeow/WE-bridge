package com.wepwawet.pairing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wepwawet.log.WepWawetLogger
import java.io.File

/**
 * PairingService — foreground service that runs the WE pairing servers.
 *
 * Starts:
 * - WeDiscoveryBroadcaster (UDP 7884 — WallpaperEngine/Ping broadcast)
 * - WePairingServer (TCP 7889 — RSA + AES pairing handshake)
 *
 * The service runs as a foreground service with a sticky notification so
 * Android doesn't kill it when the user switches to the WE Android app.
 *
 * Usage from PairingScreen:
 *   val intent = Intent(context, PairingService::class.java)
 *   intent.putExtra("pin", pin)
 *   ContextCompat.startForegroundService(context, intent)
 *   // To stop:
 *   context.stopService(Intent(context, PairingService::class.java))
 *
 * == Static access from UI (per Task 9) ==
 *
 * The [Companion.pairingServer] field exposes the singleton [WePairingServer]
 * instance for the lifetime of the service. UI screens can call:
 *   - [isPaired] to check if a phone is currently paired
 *   - [queueWallpaper] to push a .mpkg file to the paired phone
 *   - [pairedDeviceName] to display the connected phone's name
 *
 * All three are safe to call from any thread (atomic / volatile reads).
 */
class PairingService : Service() {

    companion object {
        const val CHANNEL_ID = "wepwawet_pairing"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_PIN = "pin"

        /**
         * Notification action: the user tapped "Stop" on the foreground
         * notification. Handled in [onStartCommand] — we tear down the
         * servers and stop the service. The intent is delivered to the
         * already-running service via `PendingIntent.getService`, so it
         * doesn't count as a fresh background service start (the user's
         * tap on the notification is treated as a user-initiated event
         * by the platform, not a background start).
         */
        const val ACTION_KILL = "com.wepwawet.pairing.ACTION_KILL"

        // ====== PIN persistence (v0.7.0) ======
        // The user can now set a custom PIN in Settings. It's persisted in
        // SharedPreferences so it survives app restarts/updates.
        private const val PREFS_NAME = "wepwawet_pairing"
        private const val KEY_PIN = "pairing_pin"
        private const val KEY_PAIRED_DEVICE = "paired_device_name"
        private const val DEFAULT_PIN = "1234"

        /** Get the user's saved pairing PIN (default: "1234"). */
        fun getPairingPin(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN
        }

        /** Save a custom pairing PIN. */
        fun setPairingPin(context: Context, pin: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PIN, pin).apply()
        }

        /** Persist the paired device name so it survives app restarts. */
        fun savePairedDevice(context: Context, name: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PAIRED_DEVICE, name).apply()
        }

        /** Load the last paired device name (or null if never paired). */
        fun loadPairedDevice(context: Context): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PAIRED_DEVICE, null)
        }

        /** Clear the persisted paired device name (called on unpair/disconnect). */
        fun clearPairedDevice(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_PAIRED_DEVICE).apply()
        }

        // ====== Static UI accessors (per Task 9) ======

        /**
         * Singleton reference to the active [WePairingServer], or `null` if the
         * pairing service isn't running. Set in [onStartCommand], cleared in
         * [onDestroy]. Read by [isPaired] and [queueWallpaper].
         */
        @Volatile
        var pairingServer: WePairingServer? = null
            private set

        /**
         * The name of the currently-paired phone, or `null` if no phone is paired.
         * Updated by the [WePairingServer.onPairingSuccess] / [onPairingFailure]
         * callbacks registered in [onStartCommand].
         */
        @Volatile
        var pairedDeviceName: String? = null
            private set

        /**
         * Quick check whether a phone is currently paired and ready to receive
         * wallpaper transfers.
         *
         * Returns `true` iff [pairingServer] is non-null AND [pairedDeviceName]
         * is non-null. The underlying [WePairingServer] command loop is what
         * actually delivers queued files — this flag just tells the UI whether
         * a transfer will be picked up immediately or will sit in the queue
         * until pairing completes.
         */
        fun isPaired(): Boolean {
            // pairedDeviceName is the authoritative signal — it is set only in the
            // WePairingServer.onPairingSuccess callback (which fires after the AES
            // handshake completes) and cleared in onPairingFailure / stopServers.
            // We deliberately don't reflect into WePairingServer's private
            // currentClient field; the name flag is the source of truth.
            //
            // v0.7.0: Also check SharedPreferences for persisted pairing state —
            // the app may have been killed/restarted, and the phone hasn't
            // reconnected yet, but the UI should still show "paired".
            //
            // v0.7.4: the old first check (`pairingServer != null &&
            // pairedDeviceName != null → return true`) was dead code — the
            // return below already covers that case (pairedDeviceName != null →
            // true). Simplified to the single expression; truth table unchanged.
            return pairedDeviceName != null ||
                (appContext?.let { loadPairedDevice(it) } != null)
        }

        /** App context for static accessors (set in onStartCommand). */
        @Volatile var appContext: Context? = null  // v0.7.1: public for MainActivity to set

        /**
         * Queue a wallpaper .mpkg file for transfer to the paired phone.
         *
         * If a phone is currently paired, the file is sent during the next
         * iteration of the [WePairingServer] command loop (typically immediate).
         * If no phone is paired yet, the file sits in the queue and is sent
         * as soon as pairing completes.
         *
         * @param file the .mpkg file to send. Must be readable. Should be a stable
         *   path (not a temp file) because the queue holds a [File] reference, not
         *   the bytes — the file must still exist when the loop picks it up.
         * @param title human-readable wallpaper title, shown in the WE app's
         *   "Open wallpaper" dialog. Must be ≤ 256 chars.
         */
        fun queueWallpaper(file: File, title: String) {
            val server = pairingServer
            if (server == null) {
                WepWawetLogger.proxyWarn("queueWallpaper called but pairing server not running",
                    "file" to file.name, "title" to title)
                return
            }
            if (!file.exists() || !file.canRead()) {
                WepWawetLogger.proxyWarn("queueWallpaper file not readable",
                    "path" to file.absolutePath,
                    "exists" to file.exists(),
                    "readable" to file.canRead())
                return
            }
            WepWawetLogger.proxy("queueWallpaper",
                "file" to file.name,
                "title" to title,
                "size" to file.length(),
                "paired" to isPaired())
            server.sendWallpaper(file, title)
        }

        /**
         * Auto-open the WE Android app so the user can see the wallpaper transfer.
         * Called after queueWallpaper completes. Uses packageManager to find and
         * launch the WE Android app's main activity.
         */
        fun launchWallpaperEngineApp(context: Context) {
            // v0.7.1: The correct WE Android package name is io.wallpaperengine.weclient
            // (from Google Play Store URL). Previously used com.wallpaper.engine which
            // was wrong and never matched.
            val wePackages = context.packageManager.getLaunchIntentForPackage("io.wallpaperengine.weclient")
                ?: context.packageManager.getLaunchIntentForPackage("com.wallpaper.engine")
                ?: context.packageManager.getLaunchIntentForPackage("com.fws.wallpaperengine")
            if (wePackages != null) {
                wePackages.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(wePackages) }
                WepWawetLogger.proxy("launched Wallpaper Engine Android app")
            } else {
                WepWawetLogger.proxyWarn("Wallpaper Engine Android app not found — cannot auto-open")
            }
        }

        // ====== Service start/stop helpers (existing) ======

        fun start(context: Context, pin: String) {
            val intent = Intent(context, PairingService::class.java)
            intent.putExtra(EXTRA_PIN, pin)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PairingService::class.java))
        }
    }

    private var pairingServerLocal: WePairingServer? = null
    private var discoveryBroadcaster: WeDiscoveryBroadcaster? = null

    /**
     * v0.7.1 (round 6 — pairing persistence): tracks the PIN the currently-
     * running server was started with, so we can detect "PIN unchanged →
     * don't restart the live pairing session" in [onStartCommand].
     *
     * Set in onStartCommand after [start] succeeds; cleared in [stopServers].
     * Compared to the new PIN passed in via the start intent — if they match
     * AND the server is still running, we know the user hasn't changed the
     * PIN and we can avoid killing the live phone connection.
     */
    private var runningPin: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        WepWawetLogger.proxy("PairingService.onCreate")
    }

    /**
     * Called by the system on Android 14+ (API 34+) when the foreground service
     * has reached its time limit OR when the system has decided the service
     * should stop (e.g. on Android 16 the dataSync FGS type can be asked to
     * stop shortly after starting if the system thinks it's no longer needed).
     *
     * v0.7.20: Our `specialUse` foreground service type is NOT subject to the
     * system timeout (only `dataSync` and `mediaProcessing` have the 6-hour
     * timeout per the Android docs). So this callback should NEVER fire in
     * practice. If it does (e.g. a future Android version extends timeouts to
     * `specialUse`, or the OEM's battery optimizer triggers it), we gracefully
     * stop the servers + foreground state and let the system restart us via
     * START_STICKY (which will re-enter onStartCommand and re-promote to
     * foreground once the app is in the foreground again).
     *
     * If we don't call stopSelf() within the system's timeout window (a few
     * seconds), the system throws ForegroundServiceDidNotStopInTimeException
     * and kills the app process.
     */
    override fun onTimeout(startId: Int) {
        WepWawetLogger.proxyWarn("PairingService.onTimeout — system requested stop (specialUse should not time out)",
            "start_id" to startId,
            "action" to "graceful_stop")
        // Stop the pairing servers cleanly so pairedDeviceName is cleared
        stopServers()
        // Remove the foreground notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Stop the service — the proxy will resume on next PairingService.start()
        // call (either from MainActivity.onCreate auto-start, BootReceiver, or
        // the user returning to the Pairing screen).
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WepWawetLogger.proxy("PairingService.onStartCommand",
            "flags" to flags,
            "action" to (intent?.action ?: "null"))
        appContext = applicationContext

        // v0.7.5: Handle the "Stop" notification action — the user tapped
        // the Stop button on the foreground notification. Tear down the
        // servers, remove the notification, and stop the service. We
        // return START_NOT_STICKY so the system doesn't auto-restart the
        // service after we've been explicitly killed (BootReceiver /
        // MainActivity will start it again on next boot / app open, which
        // is the right UX — explicit kill should stick until the user
        // re-engages with the app).
        //
        // Note: this Intent is delivered via PendingIntent.getService to
        // the already-running service instance (the notification exists
        // because the service is in the foreground state). The user's
        // tap on the notification action button is treated as a
        // user-initiated event, so it is NOT subject to the Android 12+
        // background-service-start restriction.
        if (intent?.action == ACTION_KILL) {
            WepWawetLogger.proxy("PairingService: ACTION_KILL — explicit stop from notification",
                "had_server" to (pairingServerLocal != null),
                "had_broadcaster" to (discoveryBroadcaster != null),
                "was_paired" to (pairedDeviceName != null))
            stopServers()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Get PIN from intent (default "1234" matches the auto-start PIN
        // used by MainActivity.onCreate and the PairingScreen default).
        val pin = intent?.getStringExtra(EXTRA_PIN) ?: getPairingPin(this)
        WepWawetLogger.proxy("Starting pairing servers", "pin" to pin)

        // v0.7.1 (round 6 — pairing persistence): SMART RESTART.
        //
        // PROBLEM: onStartCommand was called from MainActivity.onCreate,
        // every PairingScreen appearance, and every PIN change. The old
        // implementation ALWAYS called stopServers() and rebuilt the
        // pairing server stack — killing the live TCP connection to the
        // phone, clearing pairedDeviceName, and forcing the user to
        // re-pair on every screen entry. The comment in PairingScreen
        // claiming onStartCommand was a no-op when already running was
        // FALSE.
        //
        // FIX: if the pairing server is already running AND the PIN
        // matches the running PIN, we DO NOT tear down the live phone
        // connection. We only check if the discovery broadcaster died
        // (e.g. its thread crashed silently) and restart JUST that
        // component. Otherwise we return START_STICKY immediately,
        // leaving the live pairing session intact.
        val existingServer = pairingServerLocal
        val existingBroadcaster = discoveryBroadcaster
        if (existingServer != null && existingServer.isRunning && runningPin == pin) {
            // Server is alive + PIN unchanged — don't kill the phone session.
            WepWawetLogger.proxy("PairingService: server already running with same PIN — keeping live session",
                "pin" to pin,
                "broadcaster_alive" to (existingBroadcaster?.isRunning ?: false))

            // v0.7.1 (round 6): notification text should show "Paired: <name>"
            // if already paired. This handles the case where MainActivity
            // re-issues onStartCommand after the user already paired — the
            // notification should reflect the actual paired state, not the
            // generic "proxy running" text.
            val persistedName = loadPairedDevice(applicationContext)
            val displayName = pairedDeviceName ?: persistedName
            val notifText = if (!displayName.isNullOrBlank()) {
                "Paired: $displayName"
            } else {
                "WepWawet proxy running. PIN: $pin"
            }
            updateNotification(notifText)

            // Only restart the discovery broadcaster if it died (its
            // thread crashed silently). We pass the persistent GUID so
            // the phone sees the SAME computer.
            if (existingBroadcaster == null || !existingBroadcaster.isRunning) {
                WepWawetLogger.proxy("Discovery broadcaster died — restarting it (server kept alive)")
                try {
                    val guid = PairingIdentity.getGuid(applicationContext)
                    val broadcaster = WeDiscoveryBroadcaster(this)
                    broadcaster.start(guid = guid)
                    discoveryBroadcaster = broadcaster
                } catch (e: Exception) {
                    WepWawetLogger.proxyError("Failed to restart discovery broadcaster",
                        "exception" to e.javaClass.name,
                        "message" to (e.message ?: ""))
                }
            }
            return START_STICKY
        }

        // PIN changed OR server isn't running — do the full (re)start.
        WepWawetLogger.proxy("PairingService: full (re)start — server down or PIN changed",
            "server_running" to (existingServer?.isRunning ?: false),
            "running_pin_matches" to (runningPin == pin))

        // v0.7.1 (round 6): notification text shows "Paired: <name>" if
        // we have a persisted paired-device name (so the foreground
        // notification reflects the previously-paired state during the
        // brief window before the phone reconnects).
        val persistedName = loadPairedDevice(applicationContext)
        val initialNotifText = if (!persistedName.isNullOrBlank()) {
            "Paired: $persistedName"
        } else {
            "WepWawet proxy running. PIN: $pin"
        }
        val notification = createNotification(initialNotifText)

        // v0.7.1 (round 6): wrap startForeground in try/catch — on
        // Android 12+ (API 31+) a background restart (e.g. from
        // BootReceiver if the user has just rebooted and the app isn't
        // yet in the foreground) throws
        // ForegroundServiceStartNotAllowedException. We catch it, log
        // the issue, and return START_STICKY anyway — Android will
        // retry the service start later when the user opens the app
        // (which puts us in the foreground and lets startForeground
        // succeed). The previous code would CRASH, killing the
        // foreground service permanently until the user manually
        // re-launched the app.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when
            // the service is started from the background (e.g. from
            // BootReceiver just after a reboot, before the user opens
            // the app). We log instead of crashing.
            val excName = e.javaClass.name
            WepWawetLogger.proxyError("startForeground failed — service start deferred until app foreground",
                "exception" to excName,
                "message" to (e.message ?: ""))
            return START_STICKY
        }

        // Stop any existing servers (PIN changed or first start)
        stopServers()

        // v0.7.1 (round 6): load the PERSISTENT RSA keypair so the
        // phone sees the same computer across app restarts/updates.
        val persistentKeyPair = PairingIdentity.loadOrCreateKeyPair(applicationContext)

        // Start the pairing server (TCP 7889)
        try {
            pairingServerLocal = WePairingServer().also { server ->
                server.onPairingSuccess = { deviceName ->
                    // Update the static pairedDeviceName so UI can query isPaired()
                    pairedDeviceName = deviceName
                    // v0.7.0: Persist the paired device name so it survives
                    // app restarts/updates. The UI can show "paired" even
                    // before the phone reconnects.
                    savePairedDevice(applicationContext, deviceName)
                    WepWawetLogger.proxy("Pairing SUCCESS!",
                        "device" to deviceName,
                        "queued_wallpapers" to "n/a")
                    updateNotification("Paired: $deviceName")
                }
                server.onPairingFailure = { error ->
                    pairedDeviceName = null
                    WepWawetLogger.proxyError("Pairing FAILED", "error" to error)
                }
                // v0.7.1 (round 6): clear pairedDeviceName + update the
                // notification when the phone disconnects. The persisted
                // paired-device name in SharedPreferences is KEPT (so
                // the UI can still show "previously paired with X" and
                // the phone can reconnect without re-PIN-prompting, since
                // the phone's pairing DB is keyed by the persistent GUID
                // + RSA pubkey — both stable across app restarts).
                server.onDisconnected = {
                    pairedDeviceName = null
                    val pinForNotif = runningPin ?: getPairingPin(applicationContext)
                    updateNotification("WepWawet proxy running. PIN: $pinForNotif")
                    WepWawetLogger.proxy("Pairing: phone disconnected — UI reverted to 'not paired' (persisted name kept)")
                }
                // v0.7.1 (round 6): pass the persistent keypair so the
                // phone sees the same RSA pubkey across restarts.
                server.start(pin, persistentKeyPair)
                // Expose via companion
                pairingServer = server
            }
            // v0.7.1 (round 6): record the PIN we started with so the
            // next onStartCommand can detect "PIN unchanged → don't
            // tear down the live session".
            runningPin = pin
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Failed to start pairing server",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
        }

        // Start the discovery broadcaster (UDP 7884)
        try {
            // v0.7.1 (round 6): pass the persistent GUID so the phone
            // sees the same computer across restarts.
            val guid = PairingIdentity.getGuid(applicationContext)
            discoveryBroadcaster = WeDiscoveryBroadcaster(this).also { broadcaster ->
                broadcaster.start(guid = guid)
            }
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Failed to start discovery broadcaster",
                "exception" to e.javaClass.name, "message" to (e.message ?: ""))
        }

        return START_STICKY
    }

    override fun onDestroy() {
        WepWawetLogger.proxy("PairingService.onDestroy")
        stopServers()
        super.onDestroy()
    }

    private fun stopServers() {
        try {
            pairingServerLocal?.stop()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Error stopping pairing server",
                "exception" to e.javaClass.name)
        }
        pairingServerLocal = null
        pairingServer = null
        pairedDeviceName = null
        // v0.7.1 (round 6): clear the running PIN so the next onStartCommand
        // does a full restart instead of being short-circuited by a stale
        // runningPin match. We deliberately do NOT clear the persisted
        // paired-device name in SharedPreferences here — that's preserved
        // across service restarts so the phone can reconnect without
        // re-PIN-prompting (the phone's pairing DB is keyed by the
        // persistent GUID + RSA pubkey).
        runningPin = null

        try {
            discoveryBroadcaster?.stop()
        } catch (e: Exception) {
            WepWawetLogger.proxyError("Error stopping discovery broadcaster",
                "exception" to e.javaClass.name)
        }
        discoveryBroadcaster = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "WepWawet Pairing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Pairing proxy service for Wallpaper Engine"
            // v0.7.20: Show the notification on the lock screen so the user
            // can always see/kill the proxy even when the phone is locked.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // v0.7.20: Don't allow the user to bypass via the channel
            // settings — the notification IS the foreground service
            // notification, hiding it defeats the purpose.
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        // v0.7.5: "Stop" action — lets the user kill the proxy from the
        // notification shade without having to open the app. Tapping the
        // action fires a PendingIntent that re-enters onStartCommand
        // with [ACTION_KILL], which tears down the servers and stops
        // the service.
        //
        // PendingIntent flags:
        //   FLAG_UPDATE_CURRENT — if a prior PendingIntent exists, update
        //     its extras rather than creating a new one (avoids stale
        //     intent caching).
        //   FLAG_IMMUTABLE — required on Android 12+ (API 31+) when the
        //     PendingIntent doesn't need to be mutated by the receiver.
        //     Our kill intent is fully self-contained (just an action
        //     string), so immutable is the correct and most secure choice.
        val killIntent = Intent(this, PairingService::class.java).apply {
            action = ACTION_KILL
        }
        val killPendingIntent = PendingIntent.getService(
            this,
            0,
            killIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // v0.7.20: Also wire a content-tap intent that opens MainActivity —
        // tapping the notification body (not the Stop button) brings the
        // user into the app so they can see pairing status / change settings.
        val contentIntent = Intent(this, com.wepwawet.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            1,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // v0.7.20: Notification hardening — the previous notification was
        //   missing several flags that made it feel flaky on Android 13+:
        //
        //   setOngoing(true)           — already set; prevents swipe-dismiss
        //                                on Android < 14. On Android 14+ the
        //                                user CAN swipe an ongoing FGS notif,
        //                                but the system re-posts it within a
        //                                few seconds because the service is
        //                                still running. There is no way to
        //                                make an FGS notif truly non-swipeable
        //                                on Android 14+ — the platform
        //                                enforces this. We keep setOngoing so
        //                                the system re-posts ASAP.
        //   setShowWhen(false)         — hide the "just now" timestamp so
        //                                the notification doesn't look stale.
        //   setForegroundServiceBehavior(IMMEDIATE) — on Android 12+ this
        //                                makes the notif appear the instant
        //                                startForeground is called, rather
        //                                than after a 10s grace period.
        //   setPriority(PRIORITY_LOW)  — matches the channel importance.
        //   setCategory(CATEGORY_SERVICE) — system UI groups it with other
        //                                service notifications.
        //   setVisibility(VISIBILITY_PUBLIC) — show on lock screen.
        //   setOnlyAlertOnce(true)     — don't buzz/beep on every update.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WepWawet")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop proxy",
                killPendingIntent,
            )
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
