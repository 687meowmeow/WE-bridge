package com.wepwawet

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.PairingService
import com.wepwawet.ui.WepWawetNavHost
import com.wepwawet.ui.theme.WepWawetTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Log to logcat FIRST — even if WepWawetLogger.init failed in Application.onCreate,
        // we still want a breadcrumb in logcat.
        Log.i(TAG, "MainActivity.onCreate intent=${intent?.dataString ?: "null"}")

        // Best-effort log to WepWawetLogger if it initialized. If it didn't, this
        // call no-ops (initialized=false guard inside the logger).
        runCatching {
            WepWawetLogger.lifecycle("MainActivity.onCreate",
                "intent" to (intent?.dataString ?: "null"))
        }

        // Auto-start the pairing proxy on app launch.
        // Wrapped in try-catch that catches Throwable (not just Exception) so
        // class-loading Errors like NoClassDefFoundError don't bring down the
        // app before we get a chance to render the UI.
        runCatching {
            // v0.7.1: Set appContext FIRST so isPaired() can check SharedPreferences
            // even before onStartCommand runs (startForegroundService is async).
            PairingService.appContext = applicationContext
            // v0.7.0: Use the user's saved PIN (from Settings) instead of hardcoded "1234"
            PairingService.start(this, PairingService.getPairingPin(this))
            // Log pairing state for debugging
            val wasPaired = PairingService.loadPairedDevice(this)
            WepWawetLogger.lifecycle("Auto-started pairing proxy",
                "was_previously_paired" to (wasPaired != null),
                "paired_device" to (wasPaired ?: "null"),
                "isPaired_now" to PairingService.isPaired())
        }.onFailure { e ->
            // Log to BOTH WepWawetLogger and logcat — if the logger failed to init,
            // we still get the failure in logcat
            runCatching {
                WepWawetLogger.lifecycle("Failed to auto-start proxy: ${e.message}")
            }
            Log.e(TAG, "PairingService.start failed", e)
        }

        // Render the UI. Wrapped in try-catch so if Compose crashes during
        // first composition, we still surface the error via logcat and the
        // CrashHandler instead of dying silently.
        runCatching {
            enableEdgeToEdge()
            setContent {
                WepWawetTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        // v0.7.5: Ask for POST_NOTIFICATIONS on Android 13+
                        // BEFORE the rest of the UI mounts — this is the
                        // permission that lets the foreground-service
                        // notification (with the Stop action button) be
                        // visible to the user. Without it, the proxy runs
                        // but Android silently suppresses the notification
                        // and the user has no way to know it's running or
                        // to kill it from the shade.
                        NotificationPermissionRequester()

                        WepWawetNavHost()
                    }
                }
            }
        }.onFailure { e ->
            runCatching {
                WepWawetLogger.appError("setContent failed in MainActivity.onCreate",
                    exception = e)
            }
            Log.e(TAG, "setContent failed", e)
            // Don't rethrow — let the activity continue in a broken state so the
            // user at least sees a black screen instead of an immediate crash
            // dialog. The CrashHandler has already captured the exception via
            // the runCatching block above (it logs via WepWawetLogger.crash
            // through appError, which goes into the ring buffer + logcat).
        }
    }

    override fun onResume() {
        super.onResume()
        runCatching { WepWawetLogger.lifecycle("MainActivity.onResume") }
        // v0.7.24 (H-A6): the background-timeout control moved to
        // ProcessLifecycleOwner inside PairingService — Activity-level
        // onPause/onResume fired on every in-app navigation (e.g. to
        // SteamLoginActivity), wrongly arming the 30-minute proxy kill timer.
    }

    override fun onPause() {
        super.onPause()
        runCatching { WepWawetLogger.lifecycle("MainActivity.onPause") }
        // See onResume — timeout control is process-lifecycle-driven now.
    }

    override fun onDestroy() {
        runCatching { WepWawetLogger.lifecycle("MainActivity.onDestroy") }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WepWawet-Main"
    }
}

/**
 * v0.7.5: Asks the user to grant POST_NOTIFICATIONS on Android 13+
 * (API 33+). On earlier Android versions this is a no-op — the
 * permission is implicit at install time.
 *
 * Why we ask on first launch: the pairing proxy runs as a foreground
 * service with a sticky notification (so Android doesn't kill it
 * mid-transfer). On Android 13+, foreground-service notifications
 * are suppressed unless the user grants POST_NOTIFICATIONS — without
 * that grant, the proxy runs invisibly and the user has no way to
 * see it's running or to kill it via the Stop button on the
 * notification.
 *
 * The foreground service itself starts regardless of the permission
 * result (POST_NOTIFICATIONS only controls notification visibility,
 * not whether the FGS is allowed to run). So we don't gate the
 * PairingService.start() call in onCreate on this permission — we
 * just request the permission so the notification shows up.
 *
 * Idempotency: Android only shows the system permission dialog the
 * FIRST time the user is asked (per-app, per-process). Subsequent
 * launches silently return the previously-granted/denied state. If
 * the user denied the permission, we don't keep nagging them — they
 * can re-grant it from the system Settings → Notifications screen.
 *
 * This composable is intentionally minimal: it has no UI of its own
 * and just performs the side effect. The actual app UI is rendered
 * by WepWawetNavHost (the next composable in the tree).
 */
@Composable
private fun NotificationPermissionRequester() {
    // Android 13 (TIRAMISU, API 33) introduced POST_NOTIFICATIONS as a
    // runtime permission. On older Android, notifications are allowed
    // by default — no permission request needed.
    //
    // Compose rules: rememberLauncherForActivityResult must be called
    // unconditionally from the composable body (NOT inside a conditional
    // or after an early return), so the ActivityResultRegistry can
    // restore the launcher across configuration changes. The SDK check
    // that decides whether to actually fire the launcher lives inside
    // the LaunchedEffect below.

    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        WepWawetLogger.lifecycle(
            "POST_NOTIFICATIONS permission result",
            "granted" to granted,
        )
        if (!granted) {
            // The user denied. We don't re-prompt — they can re-grant
            // from system Settings → Notifications. The proxy will
            // still run, just without a visible notification.
            WepWawetLogger.proxyWarn(
                "POST_NOTIFICATIONS denied — foreground notification will be suppressed",
            )
        }
    }

    // Trigger the permission request once per process lifetime. Using
    // Unit as the key ensures this runs the first time the composable
    // enters composition (and not again on recomposition).
    LaunchedEffect(Unit) {
        // Skip on Android < 13 — POST_NOTIFICATIONS is implicit there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            WepWawetLogger.lifecycle(
                "POST_NOTIFICATIONS not required",
                "sdk" to Build.VERSION.SDK_INT,
            )
            return@LaunchedEffect
        }

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        WepWawetLogger.lifecycle(
            "POST_NOTIFICATIONS current state",
            "granted" to granted,
        )
        if (!granted) {
            WepWawetLogger.lifecycle("Requesting POST_NOTIFICATIONS permission")
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
