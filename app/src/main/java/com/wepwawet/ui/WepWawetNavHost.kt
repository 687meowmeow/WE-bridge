package com.wepwawet.ui

import android.content.Context
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.screens.DownloadManagerScreen
import com.wepwawet.ui.screens.FavoritesScreen
import com.wepwawet.ui.screens.LogsScreen
import com.wepwawet.ui.screens.PairingScreen
import com.wepwawet.ui.screens.SettingsScreen
import com.wepwawet.ui.screens.VideoTrimScreen
import com.wepwawet.ui.screens.WallpaperDetailScreen
import com.wepwawet.ui.screens.WorkshopBrowserScreen

/**
 * WepWawet navigation host. Eight destinations:
 *
 *   - workshop             (default landing — WorkshopBrowserScreen, global browse)
 *   - creator/{steamId}    (WorkshopBrowserScreen scoped to a single creator)
 *   - downloads            (DownloadManagerScreen showing active + completed downloads)
 *   - detail/{itemId}     (WallpaperDetailScreen — workshop item detail)
 *   - pairing             (PairingScreen)
 *   - settings            (SettingsScreen)
 *   - logs                (LogsScreen)
 *   - favorites           (v0.7.1: placeholder screen — full saved-creators grid TBD)
 *
 * A single [WorkshopRepository] is created at the NavHost level (via [remember])
 * so that the WorkshopBrowserScreen and WallpaperDetailScreen share the same
 * in-memory cache, browse session, and search session. If they each created
 * their own instance, navigation between them would lose all loaded items +
 * force a fresh fetch.
 *
 * The `creator/{steamId}` route opens a new [WorkshopBrowserScreen] with
 * `creatorSteamId` set — the repo's `creatorSteamId` filter scopes the
 * fetch to that creator's /profiles/<steamId>/myworkshop/ page. A back arrow
 * is shown so the user can return to the global browse.
 *
 * v0.7.1 — the [com.wepwawet.ui.screens.WorkshopBrowserScreen] top bar
 * now has a Favorites (heart) icon. That icon's `onOpenFavorites` callback
 * is wired here to navigate to the new `favorites` route. The placeholder
 * screen for now shows "Favorites coming soon" — the full implementation
 * (a grid of saved creators + their wallpaper counts) is tracked as a
 * follow-up task.
 *
 * v0.5.3 — the [SteamApiService] instance is now created separately (rather
 * than anonymously inside the [WorkshopRepository] constructor) so that the
 * saved Steam Web API key can be loaded from SharedPreferences (written by
 * the Settings screen under the `steam_api_key` key in the
 * `wepwawet_settings` prefs file) and set on `steamApi.apiKey` via a
 * [LaunchedEffect]. This makes the server-side sort options (Most Up Votes,
 * Most Subscriptions) available as soon as the user re-enters the workshop
 * browser, without requiring an app restart.
 *
 * v0.5.2 — the "Open Pairing Screen" button was removed from the Settings
 * screen entirely. Instead, a Bluetooth (pairing) icon was added to the
 * WorkshopBrowserScreen's top bar — both the global and creator routes wire
 * up `onOpenPairing` here to navigate to the `pairing` destination.
 *
 * The previous `steam_login` route and its wrapping `SteamLoginScreen`
 * composable have been removed — Steam credentials are now managed directly
 * on the Settings screen (see [SettingsScreen] / [com.wepwawet.pairing.SteamCredentialsStore]).
 */
@Composable
fun WepWawetNavHost() {
    val nav = rememberNavController()
    val context = LocalContext.current

    // Shared [SteamApiService] + [WorkshopRepository] instances — see class doc.
    //
    // v0.5.3 — we create the [SteamApiService] separately so we can load the
    // Steam Web API key from SharedPreferences (stored by the Settings screen
    // under the `wepwawet_settings` file via the `steam_api_key` key) and set
    // it on the service BEFORE the repository starts issuing browse requests.
    // The key is required for the IPublishedFileService/QueryFiles server-side
    // sort options (Most Up Votes, Most Subscriptions); without it, those sorts
    // fall back to HTML scraping (which doesn't support them).
    val steamApi = remember { SteamApiService(appContext = context.applicationContext) }
    val repo = remember { WorkshopRepository(steamApi) }

    // v0.7.21: Hoist the LazyGridState for the workshop + creator screens to
    // the NavHost level so scroll position survives navigation to detail and
    // back. The old rememberLazyGridState() (which uses rememberSaveable
    // internally) didn't reliably restore scroll position within NavHost
    // composables — the state was lost when the composable left composition
    // during navigation. By hoisting to the NavHost level (which never leaves
    // composition), the gridState object persists across the entire app
    // session.
    // v0.7.24 (H-U3): rememberSaveable + LazyGridState.Saver — plain remember
    // lost the scroll position on every configuration change (rotation,
    // dark-mode toggle, locale change) because the whole Activity including
    // the NavHost is recreated.
    val workshopGridState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = LazyGridState.Saver) { LazyGridState() }
    val creatorGridState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = LazyGridState.Saver) { LazyGridState() }

    // Reload the Steam Web API key every time the user returns to the NavHost
    // (e.g. after saving a new key in Settings). Uses a lifecycle observer so
    // the key is always current.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                val prefs = context.getSharedPreferences("wepwawet_settings", Context.MODE_PRIVATE)
                val savedKey = prefs.getString("steam_api_key", null)
                steamApi.apiKey = savedKey
                WepWawetLogger.steam(
                    "nav_host_steam_api_key_loaded",
                    "is_set" to (savedKey != null),
                    "length" to (savedKey?.length ?: 0),
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    NavHost(navController = nav, startDestination = "workshop") {

        composable("workshop") {
            WorkshopBrowserScreen(
                onItemClick = { id -> nav.navigate("detail/$id") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenFavorites = { nav.navigate("favorites") },
                onOpenPairing = { nav.navigate("pairing") },
                onCreatorClick = { steamId -> nav.navigate("creator/$steamId") },
                repo = repo,
                gridState = workshopGridState,
            )
        }

        composable(
            route = "creator/{steamId}",
            arguments = listOf(navArgument("steamId") { type = NavType.StringType }),
        ) { entry ->
            val steamId = entry.arguments?.getString("steamId").orEmpty()
            // v0.7.24 (H-U4): logged from a LaunchedEffect — logging
            // directly in the composable body fired on every recomposition.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                WepWawetLogger.lifecycle("NavHost navigate to creator",
                    "steam_id" to steamId)
            }
            WorkshopBrowserScreen(
                onItemClick = { id -> nav.navigate("detail/$id") },
                onOpenDownloads = { nav.navigate("downloads") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenFavorites = { nav.navigate("favorites") },
                onOpenPairing = { nav.navigate("pairing") },
                onCreatorClick = { /* no nested creator navigation */ },
                onBack = { nav.popBackStack() },
                creatorSteamId = steamId,
                repo = repo,
                gridState = creatorGridState,
            )
        }

        composable("downloads") {
            // v0.7.24 (H-U4): logged from a LaunchedEffect — logging
            // directly in the composable body fired on every recomposition.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                WepWawetLogger.lifecycle("NavHost navigate to downloads")
            }
            DownloadManagerScreen(
                onBack = { nav.popBackStack() },
                onItemClick = { itemId -> nav.navigate("detail/$itemId") },
            )
        }

        composable(
            route = "detail/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            // v0.7.24 (H-U4): logged from a LaunchedEffect — logging
            // directly in the composable body fired on every recomposition.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                WepWawetLogger.lifecycle("NavHost navigate to detail",
                    "item_id" to itemId)
            }
            WallpaperDetailScreen(
                itemId = itemId,
                onBack = { nav.popBackStack() },
                repo = repo,
                onCreatorClick = { steamId -> nav.navigate("creator/$steamId") },
                onVideoTrim = { _, title, id ->
                    val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
                    nav.navigate("videoTrim/$id/$encodedTitle")
                },
            )
        }

        composable("pairing") {
            PairingScreen(onBack = { nav.popBackStack() })
        }

        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                // Logs button in Settings' top-right corner (visible only
                // while verbose logging is enabled).
                onOpenLogs = { nav.navigate("logs") },
            )
        }

        composable("logs") {
            LogsScreen(onBack = { nav.popBackStack() })
        }

        // v0.7.1: Favorites route — full implementation.
        // The WorkshopBrowserScreen top bar's heart icon navigates here.
        // The screen renders a list of saved-creator Steam IDs, each with
        // a 40dp circular profile picture (loaded via Coil from the
        // avatar URL cached as a side-effect of getPlayerSummaries), the
        // creator's display name, the raw Steam ID below it, and a heart
        // IconButton to unfavorite. A LaunchedEffect bulk-resolves names
        // + caches avatar URLs the moment the screen composes; rows fall
        // back to showing the raw ID while loading.
        //
        // Saved-creator Steam IDs are persisted in the `wepwawet_settings`
        // SharedPreferences file under the `saved_creators` StringSet (see
        // WallpaperDetailScreen's favorite-heart toggle).
        composable("favorites") {
            // v0.7.24 (H-U4): logged from a LaunchedEffect — logging
            // directly in the composable body fired on every recomposition.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                WepWawetLogger.lifecycle("NavHost navigate to favorites")
            }
            FavoritesScreen(
                onBack = { nav.popBackStack() },
                onCreatorClick = { steamId -> nav.navigate("creator/$steamId") },
                steamApi = steamApi,
            )
        }

        // Video trim route — only passes itemId + title (URL-encoded).
        // The video file path is read from WorkshopDownloadJob.pendingVideoFor(itemId)
        // (not passed as a nav arg, because file paths contain slashes that
        // break Jetpack Navigation's path segment parsing).
        composable(
            route = "videoTrim/{itemId}/{itemTitle}",
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("itemTitle") { type = NavType.StringType },
            ),
        ) { entry ->
            val trimItemId = entry.arguments?.getString("itemId").orEmpty()
            // v0.7.24 (H-U5 BUG FIX): URLDecoder.decode THROWS
            // IllegalArgumentException on a malformed % sequence — called
            // during composition, it crashed the app straight out of the
            // NavHost. Fall back to the raw string on failure.
            val trimItemTitle = runCatching {
                java.net.URLDecoder.decode(
                    entry.arguments?.getString("itemTitle").orEmpty(), "UTF-8")
            }.getOrElse { entry.arguments?.getString("itemTitle").orEmpty() }
            // v0.7.20: Read the video file path from the per-item state (was
            // WorkshopDownloadJob.state.value.pendingVideoFile which under
            // concurrent downloads could read the wrong item's pending video).
            val trimVideoPath = com.wepwawet.pairing.WorkshopDownloadJob
                .pendingVideoFor(trimItemId)?.first
            // v0.7.24 (H-U4): logged from a LaunchedEffect (see above).
            androidx.compose.runtime.LaunchedEffect(trimItemId) {
                WepWawetLogger.lifecycle("NavHost navigate to videoTrim",
                    "item_id" to trimItemId,
                    "video_path" to (trimVideoPath ?: "null"),
                    "item_title" to trimItemTitle)
            }
            // v0.7.21 BUG FIX: If pendingVideoFor returns null (e.g. the
            // pending state was cleared by a concurrent cancelVideoSend, or
            // the user deep-linked to this route without a pending video),
            // pop back immediately instead of showing a broken trim screen
            // with File("") that ExoPlayer can't load.
            //
            // v0.7.23 BUG NAV-1: popBackStack was called during composition
            // which can cause a blank frame or IllegalStateException. Now
            // wrapped in LaunchedEffect so it's deferred to after composition.
            if (trimVideoPath == null) {
                WepWawetLogger.lifecycle("NavHost videoTrim: no pending video — popping back")
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    nav.popBackStack()
                }
                return@composable
            }
            VideoTrimScreen(
                videoFile = java.io.File(trimVideoPath),
                itemTitle = trimItemTitle,
                itemId = trimItemId,
                onBack = { nav.popBackStack() },
                onSendComplete = { nav.popBackStack() },
            )
        }
    }
}
