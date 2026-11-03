package com.wepwawet.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wepwawet.BuildConfig
import com.wepwawet.data.SteamApiService
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.SteamCredentialsStore
import com.wepwawet.pairing.WorkshopBrowseSession
import com.wepwawet.pairing.WorkshopDownloader
import com.wepwawet.ui.components.SlimTopBar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SharedPreferences file used by the Settings screen for persisting user
 * preferences that don't belong to any particular subsystem.
 *
 * Currently hosts:
 *   - The verbose-logging toggle ([KEY_VERBOSE_LOG])
 *   - The Steam Web API key ([KEY_STEAM_API_KEY]) — required for the
 *     IPublishedFileService/QueryFiles server-side sort options (Most Up
 *     Votes, Most Subscriptions) used by [com.wepwawet.data.SteamApiService].
 *     WepWawetNavHost loads this key into the shared [SteamApiService]
 *     instance at startup so server-side sorting is available as soon as
 *     the user re-enters the workshop browser.
 *
 * Subsystem-specific state (Steam credentials, refresh tokens, etc.) lives
 * in its own dedicated SharedPreferences files — see [SteamCredentialsStore]
 * and [WorkshopDownloader] for those.
 *
 * v0.5.2: the Pairing section has been removed entirely from this screen
 * (a Bluetooth button was added to the WorkshopBrowserScreen's top bar
 * instead, since the pairing proxy always auto-starts in
 * [com.wepwawet.MainActivity] onCreate — there's no longer any user-facing
 * decision to make here).
 */
private const val PREFS_NAME = "wepwawet_settings"
private const val KEY_VERBOSE_LOG = "verbose_log"

// v0.7.4 (parity G5/G9): wallpaper packing settings — texture shrink factor
// (1/2/4) and video alignment position (0–100, 0 = centered).
private const val KEY_TEXTURE_SHRINK = "texture_shrink"
private const val KEY_VIDEO_ALIGNMENT = "video_alignment"
private const val KEY_STEAM_API_KEY = "steam_api_key"
private const val KEY_PAGINATION_ENABLED = "pagination_enabled"
private const val KEY_PAGE_SIZE = "page_size"
private const val KEY_SAVED_CREATORS = "saved_creators"
// v0.7.1: .pkg caching toggle — when enabled, downloaded .pkg files are
// kept in the app cache dir for faster re-packing to .mpkg (the next
// send-to-phone / debug download can skip the Steam CDN fetch and reuse
// the cached .pkg). Off by default — .pkg files can be large (10-100MB)
// so leaving this off avoids silently filling the cache.
private const val KEY_PKG_CACHE_ENABLED = "pkg_cache_enabled"

/**
 * The WepWawet Settings screen.
 *
 * Renders five cards inside a scrollable [LazyColumn]:
 *
 *  1. **Steam account** — username/password fields, Save / Test Login / Clear
 *  2. **Steam Web API** — API key field + Save
 *  3. **Others** — verbose logging, .pkg caching, and browse mode (pagination)
 *  4. **Cache** — current cache size + "Clear Cache" button
 *  5. **About** — app name, version, git SHA, build time
 *
 * **Logs button:** when the verbose-logging switch is ON, a Logs icon button
 * appears in the top-right corner of the TopAppBar and opens [LogsScreen] via
 * [onOpenLogs]. When verbose logging is OFF the button is hidden.
 *
 * The verbose-log toggle is persisted immediately to [PREFS_NAME]
 * SharedPreferences and applied to the running [WepWawetLogger] via
 * [WepWawetLogger.setVerbose], so it takes effect without an app restart.
 * Its initial value is read synchronously from SharedPreferences so the Logs
 * button doesn't flash in/out on screen entry.
 *
 * @param onBack     Called when the user taps the back arrow in the TopAppBar.
 * @param onOpenLogs Called when the user taps the Logs button (top-right,
 *                   visible only while verbose logging is enabled).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // ---- Persisted toggle state (verbose log) ----
    var verboseLog by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_VERBOSE_LOG, true)
        )
    }

    // v0.7.1: .pkg caching toggle (off by default). When enabled, the
    // WorkshopDownloader keeps the raw .pkg files it fetches via JavaSteam
    // in the app cache dir so subsequent send-to-phone / debug-download
    // calls can skip the Steam CDN fetch and reuse the cached .pkg.
    var pkgCacheEnabled by remember { mutableStateOf(false) }

    // ---- Pagination settings (v0.7.0) ----
    var paginationEnabled by remember { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(30) }

    // ---- Wallpaper packing settings (v0.7.4 — parity G5/G9) ----
    // Texture shrink: 1 = full resolution (default, previous behavior),
    // 2 = half, 4 = quarter — the PC's "texturereduction" / -shrink lever.
    // Video alignment position: 0 = centered (default, previous behavior),
    // 0–100 slider — written to project.json for video wallpapers exactly
    // like the PC transcoder writes the device/export config value.
    var textureShrink by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_TEXTURE_SHRINK, 1)
        )
    }
    var videoAlignment by remember {
        mutableStateOf(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_VIDEO_ALIGNMENT, 0)
        )
    }

    // ---- Steam Web API key state ----
    var steamApiKey by remember { mutableStateOf("") }
    var steamApiKeySaved by remember { mutableStateOf(false) }

    // Load saved preference values once on screen entry. We use Unit as the
    // key so this runs a single time per composition (not on every recompose).
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        verboseLog = prefs.getBoolean(KEY_VERBOSE_LOG, true)
        pkgCacheEnabled = prefs.getBoolean(KEY_PKG_CACHE_ENABLED, false)
        paginationEnabled = prefs.getBoolean(KEY_PAGINATION_ENABLED, false)
        pageSize = prefs.getInt(KEY_PAGE_SIZE, 30)
        textureShrink = prefs.getInt(KEY_TEXTURE_SHRINK, 1)
        videoAlignment = prefs.getInt(KEY_VIDEO_ALIGNMENT, 0)
        val savedKey = prefs.getString(KEY_STEAM_API_KEY, "") ?: ""
        steamApiKey = savedKey
        steamApiKeySaved = savedKey.isNotBlank()
        // v0.7.1: Apply the verbose-logging toggle to the running logger
        // on screen entry. Before this, the toggle was persisted but never
        // actually applied — every debug/info message was always logged
        // regardless of the user's setting. WepWawetLogger.setVerbose()
        // adjusts the in-memory minLevel so the change takes effect
        // immediately (no app restart needed).
        WepWawetLogger.setVerbose(verboseLog)
        // v0.7.4: Apply the packing settings to the MpkgPacker holders —
        // same immediate-effect pattern as setVerbose above (no restart
        // needed for the next pack).
        com.wepwawet.pairing.MpkgPacker.textureShrinkFactor = textureShrink
        com.wepwawet.pairing.MpkgPacker.videoAlignmentPosition = videoAlignment
    }

    // ---- Steam credentials state ----
    var steamUsername by remember {
        mutableStateOf(SteamCredentialsStore.getUsername(context) ?: "")
    }
    var steamPassword by remember { mutableStateOf("") }
    var testLoginResult by remember { mutableStateOf<String?>(null) }
    var testLoginInProgress by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Settings",
                onBack = onBack,
                // Logs button (top-right) — always shown (v0.7.1).
                actions = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Filled.BugReport, contentDescription = "Logs")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ===== 1. Steam Account =====
            item {
                SteamAccountCard(
                    savedUsername = SteamCredentialsStore.getUsername(context),
                    hasCredentials = SteamCredentialsStore.hasCredentials(context),
                    username = steamUsername,
                    password = steamPassword,
                    onUsernameChange = { steamUsername = it },
                    onPasswordChange = { steamPassword = it },
                    onSave = {
                        if (steamUsername.isBlank() || steamPassword.isBlank()) return@SteamAccountCard
                        SteamCredentialsStore.saveCredentials(
                            context, steamUsername, steamPassword,
                        )
                        WepWawetLogger.appInfo(
                            "settings_steam_credentials_saved",
                            "username" to steamUsername,
                        )
                        // Clear the in-memory password field for safety; the
                        // username is left populated since it's already shown.
                        steamPassword = ""
                    },
                    onTestLogin = {
                        if (!SteamCredentialsStore.hasCredentials(context)) {
                            testLoginResult = "Save credentials first"
                            return@SteamAccountCard
                        }
                        testLoginInProgress = true
                        testLoginResult = null
                        WepWawetLogger.appInfo("settings_test_login_clicked")
                        coroutineScope.launch {
                            val result = WorkshopDownloader.testLogin(context)
                            testLoginInProgress = false
                            testLoginResult = result
                        }
                    },
                    onClear = {
                        SteamCredentialsStore.clearCredentials(context)
                        // v0.5.32 (audit L5): also disconnect the browse
                        // session singleton so its cached WorkshopDownloader
                        // (which holds an open TCP connection to a Steam CM
                        // and the user's now-invalidated refresh token) is
                        // cleaned up. Without this, the singleton downloader
                        // stays alive for the rest of the app process,
                        // holding a stale Steam connection + stale auth
                        // state. The next browse query would either fail
                        // (token invalid) or worse, dispatch a late
                        // onLoggedOn callback that mutates `authenticated`
                        // mid-query. The disconnect() docstring already
                        // specified this intent ("Used when the user logs
                        // out of Steam in Settings") — this wires it up.
                        WorkshopBrowseSession.disconnect()
                        WepWawetLogger.appInfo("settings_steam_credentials_cleared")
                        steamUsername = ""
                        steamPassword = ""
                        testLoginResult = null
                    },
                    testLoginInProgress = testLoginInProgress,
                    testLoginResult = testLoginResult,
                )
            }

            // ===== 2. Steam Web API =====
            item {
                SteamWebApiCard(
                    apiKey = steamApiKey,
                    apiKeySaved = steamApiKeySaved,
                    onApiKeyChange = { steamApiKey = it },
                    onSave = {
                        val trimmed = steamApiKey.trim()
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(KEY_STEAM_API_KEY, trimmed)
                            .apply()
                        steamApiKey = trimmed
                        steamApiKeySaved = trimmed.isNotBlank()
                        WepWawetLogger.appInfo(
                            "settings_steam_api_key_saved",
                            "length" to trimmed.length,
                            "is_set" to trimmed.isNotBlank(),
                        )
                    },
                )
            }

            // ===== 3. Others =====
            // Verbose logging, .pkg caching and browse mode (pagination) all
            // live here now (the old Display and Browse Mode cards were merged).
            item {
                OthersCard(
                    verboseLog = verboseLog,
                    onVerboseLogChange = { enabled ->
                        verboseLog = enabled
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_VERBOSE_LOG, enabled)
                            .apply()
                        WepWawetLogger.setVerbose(enabled)
                        WepWawetLogger.appInfo(
                            "settings_verbose_log_changed",
                            "enabled" to enabled,
                        )
                    },
                    pkgCacheEnabled = pkgCacheEnabled,
                    onPkgCacheChange = { enabled ->
                        pkgCacheEnabled = enabled
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(KEY_PKG_CACHE_ENABLED, enabled)
                            .apply()
                        WepWawetLogger.appInfo(
                            "settings_pkg_cache_changed",
                            "enabled" to enabled,
                        )
                    },
                    paginationEnabled = paginationEnabled,
                    pageSize = pageSize,
                    onPaginationChange = { enabled ->
                        paginationEnabled = enabled
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(KEY_PAGINATION_ENABLED, enabled).apply()
                    },
                    onPageSizeChange = { size ->
                        pageSize = size
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_PAGE_SIZE, size).apply()
                        // v0.7.4: apply the new page size to the RUNNING SteamApiService
                        // so subsequent browse/search queries actually use it. Before
                        // this, the slider's value was persisted but never read by
                        // anything — every query hardcoded 30.
                        SteamApiService.setPageSize(size)
                        WepWawetLogger.appInfo(
                            "settings_page_size_changed",
                            "page_size" to size,
                        )
                    },
                )
            }

            // ===== 4. Wallpaper packing (v0.7.4 — parity G5/G9) =====
            item {
                WallpaperPackingCard(
                    textureShrink = textureShrink,
                    onTextureShrinkChange = { factor ->
                        textureShrink = factor
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_TEXTURE_SHRINK, factor).apply()
                        com.wepwawet.pairing.MpkgPacker.textureShrinkFactor = factor
                        WepWawetLogger.appInfo(
                            "settings_texture_shrink_changed",
                            "factor" to factor,
                        )
                    },
                    videoAlignment = videoAlignment,
                    onVideoAlignmentChange = { value ->
                        videoAlignment = value
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(KEY_VIDEO_ALIGNMENT, value).apply()
                        com.wepwawet.pairing.MpkgPacker.videoAlignmentPosition = value
                        WepWawetLogger.appInfo(
                            "settings_video_alignment_changed",
                            "value" to value,
                        )
                    },
                )
            }

            // ===== 5. Cache =====
            item {
                CacheCard(context = context)
            }

            // ===== 6. About =====
            item {
                AboutCard()
            }
        }
    }
}

/**
 * Section 1 — Steam Account.
 *
 * Lets the user enter, save, test and clear Steam credentials used by
 * JavaSteam for direct workshop downloads (no PC required). Shows the
 * currently saved username, if any.
 *
 * The card deliberately contains only minimal helper text — the
 * previous "first login" push-notification explanation has been removed.
 *
 * @param savedUsername       The username persisted by [SteamCredentialsStore], or null.
 * @param hasCredentials      Whether [SteamCredentialsStore] currently has both
 *                            username and password saved.
 * @param username            The text currently in the username field.
 * @param password            The text currently in the password field.
 * @param onUsernameChange    Called when the username field changes.
 * @param onPasswordChange    Called when the password field changes.
 * @param onSave              Called when the user taps "Save Steam credentials".
 * @param onTestLogin         Called when the user taps "Test Steam Login".
 * @param onClear             Called when the user taps "Clear saved credentials".
 * @param testLoginInProgress Whether a test-login request is currently running.
 * @param testLoginResult     The most recent test-login result string, if any.
 */
@Composable
private fun SteamAccountCard(
    savedUsername: String?,
    hasCredentials: Boolean,
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestLogin: () -> Unit,
    onClear: () -> Unit,
    testLoginInProgress: Boolean,
    testLoginResult: String?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Steam account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text(
                "Enter your Steam credentials to download wallpapers.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            // Show saved status (only username — never display the password)
            Text(
                if (hasCredentials) {
                    "Credentials saved for: ${savedUsername ?: "unknown"}"
                } else {
                    "No Steam credentials saved"
                },
                color = if (hasCredentials)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text("Steam username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Steam password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onSave,
                enabled = username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save Steam credentials")
            }
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = onTestLogin,
                enabled = !testLoginInProgress && hasCredentials,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testLoginInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Testing login...")
                } else {
                    Text("Test Steam Login")
                }
            }

            testLoginResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    result,
                    color = if (result.contains("✓"))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (hasCredentials) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear saved credentials")
                }
            }
        }
    }
}

/**
 * Section 2 — Steam Web API.
 *
 * Lets the user enter and save a Steam Web API key. The key is required for
 * the IPublishedFileService/QueryFiles server-side sort options (Most Up
 * Votes, Most Subscriptions) that [com.wepwawet.data.SteamApiService] uses
 * when the user picks those sorts in the workshop browser.
 *
 * The key is persisted to [PREFS_NAME] SharedPreferences under the
 * [KEY_STEAM_API_KEY] entry. [com.wepwawet.ui.WepWawetNavHost] loads it
 * into the shared [com.wepwawet.data.SteamApiService] instance at app
 * startup, so the user does not need to restart the app after entering
 * the key — exiting and re-entering the workshop browser is sufficient.
 *
 * The field uses [PasswordVisualTransformation] so the key is masked while
 * typing (Steam Web API keys are 32-char hex strings and look like a
 * password).
 *
 * @param apiKey        The text currently in the API key field.
 * @param apiKeySaved   Whether a non-blank API key is currently persisted.
 *                      Used to show a "key is set" status line.
 * @param onApiKeyChange Called when the text in the API key field changes.
 * @param onSave         Called when the user taps "Save". The caller is
 *                       responsible for persisting the new value to
 *                       SharedPreferences and updating `apiKeySaved`.
 */
@Composable
private fun SteamWebApiCard(
    apiKey: String,
    apiKeySaved: Boolean,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Steam Web API", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // Helper text — explains WHY the key is needed and WHERE to
            // obtain one. Mentioned specifically because Most Up Votes and
            // Most Subscriptions are otherwise silently unavailable.
            Text(
                "Required for server-side sorting (Most Up Votes, Most " +
                    "Subscriptions). Get your key at " +
                    "steamcommunity.com/dev/apikey",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            // Show saved status so the user knows whether a key is currently
            // active in the running [SteamApiService] instance.
            Text(
                if (apiKeySaved) "API key is set"
                else "No API key saved — server-side sorts will fall back to HTML scraping",
                color = if (apiKeySaved)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("Steam Web API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onSave,
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

/**
 * Section 3 — Others.
 *
 * Merges what used to be the Display and Browse Mode cards:
 *   - Verbose logging (capture detailed protocol messages for debugging).
 *     While enabled, a Logs button is shown in the Settings top bar.
 *   - .pkg caching (keep downloaded .pkg files in the cache dir for faster
 *     re-packing on the next send-to-phone / debug download). Off by
 *     default — .pkg files can be large (10-100MB each).
 *   - Browse mode: infinite scroll (default) vs pagination, with a page-size
 *     slider shown only while pagination is on.
 */
@Composable
private fun OthersCard(
    verboseLog: Boolean,
    onVerboseLogChange: (Boolean) -> Unit,
    pkgCacheEnabled: Boolean,
    onPkgCacheChange: (Boolean) -> Unit,
    paginationEnabled: Boolean,
    pageSize: Int,
    onPaginationChange: (Boolean) -> Unit,
    onPageSizeChange: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Others", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            // ---- Verbose logging ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Verbose logging", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Capture detailed protocol messages for debugging.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = verboseLog, onCheckedChange = onVerboseLogChange)
            }

            // ---- .pkg caching ----
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cache .pkg files", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Save downloaded .pkg files to cache for faster re-packing. " +
                            "Off by default.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = pkgCacheEnabled, onCheckedChange = onPkgCacheChange)
            }

            // ---- Pagination ----
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use pagination", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Infinite scroll by default. Enable to load pages manually.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = paginationEnabled, onCheckedChange = onPaginationChange)
            }
            if (paginationEnabled) {
                Spacer(Modifier.height(12.dp))
                Text("Page size: $pageSize")
                Slider(
                    value = pageSize.toFloat(),
                    onValueChange = { onPageSizeChange(it.toInt()) },
                    valueRange = 10f..100f,
                    steps = 17, // 10, 15, 20, ..., 100 (19 stops → 17 intermediate steps)
                )
            }
        }
    }
}

/**
 * Section 4½ — Wallpaper packing (v0.7.4 — parity G5/G9).
 *
 * Two levers that mirror the WE PC transcoder's per-device export options:
 *
 *  - Texture resolution (G5, the PC's "texturereduction" / -shrink):
 *    Full (default) / Half / Quarter. Applied to every texture that goes
 *    through the decode → ETC2 re-encode pipeline when packing a scene —
 *    smaller .mpkg files and lighter GPU load on the phone at the cost of
 *    sharpness. Already-ETC2 textures pass through unchanged (no decoder).
 *
 *  - Video alignment position (G9): 0–100, default 0 = centered. Written
 *    into project.json for video wallpapers exactly like the PC transcoder
 *    writes the device/export configuration value (as the field
 *    "alignmentposition": "{\"value\": N}").
 */
@Composable
private fun WallpaperPackingCard(
    textureShrink: Int,
    onTextureShrinkChange: (Int) -> Unit,
    videoAlignment: Int,
    onVideoAlignmentChange: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Wallpaper packing", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Text("Texture resolution", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Shrinks scene textures before packing (the PC's texturereduction). " +
                    "Full keeps the source resolution.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1 to "Full", 2 to "Half", 4 to "Quarter").forEach { (factor, label) ->
                    OutlinedButton(
                        onClick = { onTextureShrinkChange(factor) },
                        enabled = textureShrink != factor,
                    ) {
                        Text(if (textureShrink == factor) "$label ✓" else label)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Video alignment position", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Written to video wallpapers' project.json like the PC exporter " +
                    "(${videoAlignment} — 0 is centered).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("Alignment: $videoAlignment")
            Slider(
                value = videoAlignment.toFloat(),
                onValueChange = { onVideoAlignmentChange(it.toInt()) },
                valueRange = 0f..100f,
            )
        }
    }
}

/**
 * Section 5 — About.
 *
 * Minimal info card showing the app name, version, git SHA and build time.
 * The previous debug strings ("mpkg: PKGM0020 (VFS verified)" and
 * "Status: PKGM0020") have been removed.
 */
@Composable
private fun AboutCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("WepWawet — Wallpaper Engine companion")
            Spacer(Modifier.height(4.dp))
            Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            Spacer(Modifier.height(4.dp))
            Text(
                "Git: ${BuildConfig.GIT_SHA}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "Built: ${BuildConfig.BUILD_TIME}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Section 4 — Cache.
 *
 * Shows the current cache size and provides a "Clear cache" button that
 * deletes all files in the app's cache directory (downloaded .pkg files,
 * .mpkg files, texture conversion cache, etc.).
 */
@Composable
private fun CacheCard(context: Context) {
    var cacheSize by remember { mutableStateOf(0L) }
    var cleared by remember { mutableStateOf(false) }
    // v0.7.24 (C-U3): the clear operation now runs on Dispatchers.IO — the
    // walk + deletes of hundreds of .pkg files blocked the main thread for
    // seconds. Show a spinner while it runs and disable the button.
    var clearInProgress by remember { mutableStateOf(false) }
    val clearScope = rememberCoroutineScope()

    // Compute cache size
    // v0.7.24 (C-U4 BUG FIX): walkTopDown() ran on the LaunchedEffect's MAIN
    // dispatcher — on a cache with hundreds of .pkg files (10-100MB each) the
    // directory walk blocked composition for a visible jank burst. IO now.
    LaunchedEffect(cleared) {
        cacheSize = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                var size = 0L
                context.cacheDir.walkTopDown().forEach { f ->
                    if (f.isFile) size += f.length()
                }
                size
            }
        } catch (_: Exception) { 0L }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Cache", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Cache size: ${formatCacheSize(cacheSize)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // v0.7.20: .mpkg files are NOT cached (they are packed on-demand
                // and queued for transfer, then deleted). Only .pkg files and the
                // texture conversion cache are cleared here.
                "Clears downloaded .pkg files and texture conversion cache.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = !clearInProgress,
                onClick = {
                    if (clearInProgress) return@Button
                    WepWawetLogger.appInfo("settings_clear_cache_clicked")
                    clearInProgress = true
                    clearScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        // v0.7.21 BUG FIX: Skip files that are actively being
                        // written by in-flight downloads. Deleting these causes
                        // silent download failures (FileNotFoundException when
                        // the download/pack tries to read the deleted file).
                        // Skip patterns: *_send.pkg, *_debug.pkg, *_send.mpkg,
                        // *_debug.mpkg, *_trimmed.mp4, and the entire
                        // workshop_downloads/ directory.
                        context.cacheDir.walkTopDown().forEach { f ->
                            if (!f.isFile) return@forEach
                            val name = f.name
                            val path = f.absolutePath
                            if (name.endsWith("_send.pkg") ||
                                name.endsWith("_debug.pkg") ||
                                name.endsWith("_send.mpkg") ||
                                name.endsWith("_debug.mpkg") ||
                                name.endsWith("_trimmed.mp4") ||
                                path.contains("workshop_downloads/")
                            ) {
                                return@forEach
                            }
                            f.delete()
                        }
                        context.externalCacheDir?.walkTopDown()?.forEach { f ->
                            if (!f.isFile) return@forEach
                            val name = f.name
                            val path = f.absolutePath
                            if (name.endsWith("_send.pkg") ||
                                name.endsWith("_debug.pkg") ||
                                name.endsWith("_send.mpkg") ||
                                name.endsWith("_debug.mpkg") ||
                                name.endsWith("_trimmed.mp4") ||
                                path.contains("workshop_downloads/")
                            ) {
                                return@forEach
                            }
                            f.delete()
                        }
                        cleared = !cleared
                        WepWawetLogger.appInfo("settings_clear_cache_done")
                    } catch (e: Exception) {
                        WepWawetLogger.appInfo("settings_clear_cache_failed",
                            "exception" to e.javaClass.name)
                    } finally {
                        clearInProgress = false
                    }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (clearInProgress) "Clearing…" else "Clear Cache")
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> "%.2f GB".format(gb)
        mb >= 1 -> "%.1f MB".format(mb)
        kb >= 1 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
