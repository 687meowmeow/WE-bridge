package com.wepwawet.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wepwawet.data.SteamApiService
import com.wepwawet.data.WorkshopRepository
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.TagPickerSheet
import com.wepwawet.ui.components.TileSize
import com.wepwawet.ui.components.WallpaperCard
import com.wepwawet.ui.components.defaultExcludedTags
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Workshop browser screen — the default landing screen for WepWawet.
 *
 * v0.5.3 OVERHAUL — the filter UI has been further tightened:
 *
 *   - The "Mobile Compatible" toggle and the "Filter ▼" button both live
 *     in the [TagPickerSheet] / FilterBar respectively — the TopAppBar no
 *     longer hosts the Filter button.
 *   - The Filter button is now the LEFTMOST control in the [FilterBar]
 *     (the same horizontal row as the Sort and Tile size selectors), so
 *     the layout is:
 *
 *         [Filter ▼] [Sort: Most Popular (Year) ▼] [Tile: Medium ▼]
 *
 *     All three controls share a single horizontally-scrollable row
 *     right below the search bar.
 *   - The TopAppBar has been slimmed down — its windowInsets are removed
 *     and the search bar / filter row paddings are reduced, so there is
 *     much less empty space between the status bar and the wallpaper
 *     grid.
 *   - The trend-days selector has been removed entirely. Trend days are
 *     now baked into the chosen [SteamApiService.Sort] (Year=365,
 *     Month=31, Week=7, Today=1) so the user picks the period directly
 *     from the Sort dropdown.
 *   - The Sort dropdown now exposes ALL eight user-facing sort options
 *     (Top Rated, Most Popular Year/Month/Week/Today, Most Recent,
 *     Most Up Votes, Most Subscriptions). MOST_RELEVANT is search-only
 *     and remains hidden. The previous "client-side" badge on the
 *     Most Up Votes / Most Subscriptions items has been removed (the
 *     [SteamApiService.Sort] enum no longer has `isClientSide`).
 *   - The default sort is [SteamApiService.Sort.MOST_POPULAR_YEAR].
 *   - The default excluded-tags set is [com.wepwawet.ui.components.defaultExcludedTags]
 *     — only the four non-mobile resolution sections (Standard,
 *     Ultrawide, Dual, Triple) are OFF by default. Every other section
 *     (Mobile Resolutions, Age Rating, Genre, Type, Miscellaneous) is ON.
 *   - The user's `sort`, `tileSize`, `excludedTags` and `mobileCompatible`
 *     selections are persisted to the `wepwawet_filters` SharedPreferences
 *     file so they survive app restarts. Values are loaded once on screen
 *     entry via [LaunchedEffect] and saved immediately whenever they change.
 *
 * Top bar (compact, single row): Back (creator mode only) | Bluetooth |
 * Refresh | Downloads | Favorites | Settings.
 *
 * v0.7.1: The Logs icon was removed (the in-app Logs screen is still
 * reachable from other entry points, but the workshop browser's top bar
 * was getting too crowded). A Favorites (heart) icon was added in its
 * place — it navigates to the placeholder favorites route (see
 * [com.wepwawet.ui.WepWawetNavHost]'s `favorites` composable).
 *
 * Filter row (single horizontal scroll, below the search bar):
 * Filter ▼ | Sort ▼ | Tile ▼.
 *
 * State management:
 *   - Any change to sort / mobileCompatible / excludedTags / creatorSteamId
 *     rebuilds the [WorkshopRepository.Filter]. If a search is currently
 *     active (non-blank query AND not in creator mode), the search is
 *     RE-RUN with the new filter (preserves the user's query). Otherwise
 *     the browse session is rebuilt via [WorkshopRepository.startBrowse].
 *   - Typing into the search bar (debounced) calls
 *     [WorkshopRepository.startSearch] if non-blank, else
 *     [WorkshopRepository.clearSearch]. Search is disabled (and the field
 *     hidden) in creator browse mode.
 *
 * @param onItemClick invoked with a workshop item id when a card is tapped
 * @param onOpenDownloads invoked when the user taps the Downloads action icon
 * @param onOpenSettings invoked when the user taps the Settings action icon
 * @param onOpenFavorites invoked when the user taps the Favorites (heart) icon.
 *   v0.7.1: Replaces the previous `onOpenLogs` parameter (the Logs icon was
 *   removed). Defaults to a no-op so callers that haven't been updated yet
 *   don't crash.
 * @param onOpenPairing invoked when the user taps the Bluetooth (pairing) icon
 * @param onCreatorClick invoked with a creator Steam ID64 when the user taps
 *   the "by <creator>" line on a wallpaper tile. Typically wires up to
 *   `nav.navigate("creator/$steamId")` in the NavHost. Defaults to a no-op.
 * @param onBack optional back callback — when non-null, a back arrow is shown
 *   in the top bar. Used by the creator-browse route to return to global browse.
 * @param creatorSteamId optional Steam ID64 to scope the browse to a single
 *   creator's workshop page. When non-null, the search bar is hidden, the
 *   sort control is disabled (Steam forces `toprated` for creator listings),
 *   and a "Showing items by <creator>" header is shown.
 * @param creatorDisplayName optional resolved display name for the creator —
 *   shown in the header when [creatorSteamId] is non-null.
 * @param repo shared workshop repository (created in [com.wepwawet.ui.WepWawetNavHost])
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun WorkshopBrowserScreen(
    onItemClick: (String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFavorites: () -> Unit = {},
    onOpenPairing: () -> Unit,
    repo: WorkshopRepository,
    onCreatorClick: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    creatorSteamId: String? = null,
    creatorDisplayName: String? = null,
    // v0.7.21: gridState is now hoisted to the NavHost level so it survives
    // navigation to detail and back. The old rememberSaveable approach didn't
    // reliably restore scroll position within NavHost composables.
    gridState: LazyGridState = LazyGridState(),
) {
    // === Repository state ===
    val items by repo.browseItems.collectAsState()
    val searchItems by repo.searchItems.collectAsState()
    val browseState by repo.browseState.collectAsState()
    val searchState by repo.searchState.collectAsState()
    val isRefreshing by repo.isRefreshing.collectAsState()
    val creatorNames by repo.creatorNames.collectAsState()
    val scope = rememberCoroutineScope()

    val isCreatorMode = creatorSteamId != null

    // === SharedPreferences for persisting filter state across app restarts ===
    // We store `sort`, `tileSize`, `excludedTags` (as a comma-separated string)
    // and `mobileCompatible` here. Values are loaded once on screen entry via
    // LaunchedEffect(Unit) below, and saved immediately whenever any value
    // changes via per-key LaunchedEffects.
    val context = LocalContext.current
    val filterPrefs = remember {
        context.getSharedPreferences("wepwawet_filters", Context.MODE_PRIVATE)
    }
    // v0.7.1: Pagination mode toggle (read once on screen entry from the
    // `wepwawet_settings` prefs file written by SettingsScreen). When true,
    // the infinite-scroll LaunchedEffect is skipped and a manual pagination
    // bar (Prev / Page N / Next / Go-to-page) is shown below the grid.
    val paginationEnabled = remember {
        context.getSharedPreferences("wepwawet_settings", Context.MODE_PRIVATE)
            .getBoolean("pagination_enabled", false)
    }
    // Guard flag — `false` until the LaunchedEffect(Unit) finishes loading the
    // saved filter values. Prevents the per-key save effects from overwriting
    // the saved values with the defaults before the load has had a chance to
    // apply them.
    var filterLoaded by rememberSaveable { mutableStateOf(false) }

    // === User-controlled filter state (initialized to defaults; overwritten
    //     by LaunchedEffect(Unit) if a saved value exists) ===
    // v0.4.7/v0.5.9-alpha: query is now hoisted into WorkshopRepository
    // via `searchQueryText` StateFlow. The local `remember { mutableStateOf("") }`
    // was destroyed on navigation to detail/{itemId} and back, causing:
    //   1. query resets to ""
    //   2. snapshotFlow { query } re-emits the empty value
    //   3. debounce fires clearSearch() → search results wiped
    // Now: query survives navigation because WorkshopRepository is a singleton
    // held at the NavHost level. We use a local mutableState as a proxy for
    // the text field (Compose requires State<String> for OutlinedTextField
    // value), but we initialize it from the repo and write back to the repo
    // on every change.
    val repoQuery by repo.searchQueryText.collectAsState()
    // v0.7.24 (M-U8): rememberSaveable — the search text was lost on every
    // configuration change (rotation) because plain remember is discarded
    // with the Activity.
    var query by rememberSaveable { mutableStateOf(repoQuery) }
    // Sync local state when the repo's query changes (e.g., after navigation
    // back, or after clearSearch was called from elsewhere — sort/tag change
    // LaunchedEffect below calls repo.clearSearch() which clears the repo
    // value, so we need to mirror that into the local query).
    LaunchedEffect(repoQuery) {
        if (query != repoQuery) {
            query = repoQuery
        }
    }
    fun updateQuery(newValue: String) {
        query = newValue
        repo.updateSearchQueryText(newValue)
    }
    var sort by remember { mutableStateOf(SteamApiService.Sort.MOST_POPULAR_YEAR) }
    // v0.5.10-alpha: KEEP mobileCompatible=true default (matches WE PC's
    // canUploadWallpaperToMobileDevice logic — author permission flag).
    // The actual fix for "only showing 4 items" is in
    // WorkshopRepository.applyClientFilters: we REMOVED the redundant
    // client-side type filter (which was dropping Image/Playlist/Unknown
    // types that the AUTHOR had marked mobile-compatible via the
    // required_kv_tags[app_workshop_eula_version]=3 URL param). The API
    // already filters by author flag server-side — the client-side type
    // check was over-aggressive and didn't match WE PC's logic.
    var mobileCompatible by remember { mutableStateOf(true) }
    var tileSize by remember { mutableStateOf(TileSize.MEDIUM) }
    // Default excluded tags — only the four non-mobile resolution sections
    // (Standard / Ultrawide / Dual / Triple) are OFF by default; everything
    // else (Mobile Resolutions, Age Rating, Genre, Type, Miscellaneous) is ON.
    // This initial value is computed once per composition via remember { ... }.
    var excludedTags by remember {
        mutableStateOf(defaultExcludedTags())
    }
    var showTagPicker by remember { mutableStateOf(false) }

    // === Dropdown menu open/close flags ===
    var sortMenuOpen by remember { mutableStateOf(false) }
    var tileMenuOpen by remember { mutableStateOf(false) }

    // === Grid state for infinite scroll ===
    // v0.7.21: gridState is now passed in from the NavHost (hoisted) so it
    // survives navigation to detail and back. The old rememberSaveable/
    // rememberLazyGridState approaches didn't reliably restore scroll
    // position within NavHost composables — the state was lost when the
    // composable left composition during navigation.

    // === Load saved filter values on screen entry ===
    // Uses LaunchedEffect(Unit) so it runs a single time per composition.
    LaunchedEffect(Unit) {
        val savedSortName = filterPrefs.getString("sort", null)
        savedSortName?.let { name ->
            runCatching { SteamApiService.Sort.valueOf(name) }.getOrNull()?.let { loaded ->
                if (loaded != sort) {
                    sort = loaded
                    WepWawetLogger.workshop(
                        "filter_state_loaded",
                        "key" to "sort",
                        "value" to loaded.name,
                    )
                }
            }
        }

        val savedTileName = filterPrefs.getString("tileSize", null)
        savedTileName?.let { name ->
            runCatching { TileSize.valueOf(name) }.getOrNull()?.let { loaded ->
                if (loaded != tileSize) {
                    tileSize = loaded
                    WepWawetLogger.workshop(
                        "filter_state_loaded",
                        "key" to "tileSize",
                        "value" to loaded.name,
                    )
                }
            }
        }

        val savedMobile = filterPrefs.getBoolean("mobileCompatible", true)
        if (savedMobile != mobileCompatible) {
            mobileCompatible = savedMobile
            WepWawetLogger.workshop(
                "filter_state_loaded",
                "key" to "mobileCompatible",
                "value" to savedMobile,
            )
        }

        val savedExcludedStr = filterPrefs.getString("excludedTags", null)
        if (!savedExcludedStr.isNullOrBlank()) {
            val savedExcluded = savedExcludedStr
                .split(",")
                .filter { it.isNotBlank() }
            if (savedExcluded.toSet() != excludedTags.toSet()) {
                excludedTags = savedExcluded
                WepWawetLogger.workshop(
                    "filter_state_loaded",
                    "key" to "excludedTags",
                    "count" to savedExcluded.size,
                )
            }
        }

        // Mark load complete so the per-key save effects below start persisting
        // changes again. Setting this last ensures we don't save the just-loaded
        // values back to prefs (which would be a harmless no-op, but generates
        // noisy log entries).
        filterLoaded = true
    }

    // === Persist filter state immediately when any value changes ===
    // Each effect is keyed on its state plus filterLoaded, and is gated by
    // filterLoaded so it doesn't fire until after the LaunchedEffect(Unit)
    // above has finished restoring saved values.
    LaunchedEffect(sort, filterLoaded) {
        if (!filterLoaded) return@LaunchedEffect
        filterPrefs.edit().putString("sort", sort.name).apply()
        WepWawetLogger.workshop(
            "filter_state_saved",
            "key" to "sort",
            "value" to sort.name,
        )
    }
    LaunchedEffect(tileSize, filterLoaded) {
        if (!filterLoaded) return@LaunchedEffect
        filterPrefs.edit().putString("tileSize", tileSize.name).apply()
        WepWawetLogger.workshop(
            "filter_state_saved",
            "key" to "tileSize",
            "value" to tileSize.name,
        )
    }
    LaunchedEffect(mobileCompatible, filterLoaded) {
        if (!filterLoaded) return@LaunchedEffect
        filterPrefs.edit().putBoolean("mobileCompatible", mobileCompatible).apply()
        WepWawetLogger.workshop(
            "filter_state_saved",
            "key" to "mobileCompatible",
            "value" to mobileCompatible,
        )
    }
    LaunchedEffect(excludedTags, filterLoaded) {
        if (!filterLoaded) return@LaunchedEffect
        filterPrefs.edit()
            .putString("excludedTags", excludedTags.joinToString(","))
            .apply()
        WepWawetLogger.workshop(
            "filter_state_saved",
            "key" to "excludedTags",
            "count" to excludedTags.size,
        )
    }

    // === Build the current Filter snapshot from user state ===
    // NOTE: `trendDays` was removed from Filter in v0.5.2 — it's now part
    // of the chosen Sort enum (Year=365, Month=31, Week=7, Today=1).
    fun currentFilter(): WorkshopRepository.Filter = WorkshopRepository.Filter(
        query = null,
        tags = emptyList(),
        excludedTags = excludedTags,
        mobileCompatible = mobileCompatible,
        sort = if (isCreatorMode) SteamApiService.Sort.TOP_RATED else sort,
        creatorSteamId = creatorSteamId,
    )

    // === When ANY filter changes — snapshotFlow+collect (M1 fix) ===
    // v0.5.14-alpha: Use LaunchedEffect(Unit)+snapshotFlow to avoid
    // cancellation when keys change. Only calls startBrowse when the
    // filter ACTUALLY changes (dedup via lastStartBrowseFilter).
    //
    // v0.7.1: When the user changes the sort (or any other filter field)
    // while a search is active, we now RE-RUN the search with the new
    // filter instead of wiping the query and switching to browse mode.
    // The previous behavior (`updateQuery(""); repo.clearSearch();
    // repo.startBrowse(filter)`) was destructive — the user lost their
    // search results just because they wanted to change the sort.
    // Now: if we're in search mode (`query.isNotBlank()` AND not in
    // creator mode where the search bar is hidden), call
    // `repo.startSearch(query, filter)` to re-fetch page 1 with the new
    // filter applied. Otherwise, switch to browse mode as before.
    //
    // v0.7.23 BUG WB-1: lastStartBrowseFilter was `remember` (lost on
    // navigation), causing a spurious startBrowse on every return from
    // detail. Now reads from repo.lastStartBrowseFilter (singleton, hoisted
    // to WorkshopRepository so it survives navigation).
    LaunchedEffect(Unit) {
        snapshotFlow {
            if (filterLoaded) currentFilter() else null
        }
            .distinctUntilChanged()
            .filterNotNull()
            .collect { filter ->
                if (filter != repo.lastStartBrowseFilter) {
                    val currentQuery = query  // snapshot the State<String>
                    if (!isCreatorMode && currentQuery.isNotBlank()) {
                        // v0.7.1: In search mode — re-run the search with
                        // the new sort/filter (preserves the user's query).
                        WepWawetLogger.workshop("filter_changed re-run search",
                            "query" to currentQuery.take(80),
                            "sort" to filter.sort.name,
                            "trend_days" to filter.sort.trendDays,
                            "mobile_compatible" to filter.mobileCompatible,
                            "excluded_tags_count" to filter.excludedTags.size,
                            "excluded_tags" to filter.excludedTags.joinToString(","),
                            "creator_steam_id" to (creatorSteamId ?: "none"),
                        )
                        repo.startSearch(currentQuery, filter)
                    } else {
                        WepWawetLogger.workshop("filter_changed restart_browse",
                            "sort" to sort.name, "trend_days" to sort.trendDays,
                            "mobile_compatible" to mobileCompatible,
                            "excluded_tags_count" to excludedTags.size,
                            "excluded_tags" to excludedTags.joinToString(","),
                            "creator_steam_id" to (creatorSteamId ?: "none"),
                        )
                        if (currentQuery.isNotBlank()) {
                            updateQuery("")
                            repo.clearSearch()
                        }
                        repo.startBrowse(filter)
                    }
                }
            }
    }

    // === Debounced search: when the user types, after 500 ms of quiet, fire startSearch ===
    // (Disabled in creator mode — no search box shown there)
    // v0.4.7/v0.5.9-alpha: Skip the initial empty query emission if search
    // results already exist (prevents the snapshotFlow from clearing a
    // restored search on navigation back). We track this with a
    // `firstEmission` flag.
    if (!isCreatorMode) {
        LaunchedEffect(Unit) {
            var firstEmission = true
            snapshotFlow { query }
                .debounce(500)
                .distinctUntilChanged()
                .collect { q ->
                    if (firstEmission) {
                        firstEmission = false
                        // On first emission (screen just composed or recomposed):
                        //   - If q is blank AND search items exist → DON'T clear
                        //     (the user just navigated back to a previous search)
                        //   - If q is blank AND no search items → no-op
                        //   - If q has text AND it matches the repo's CURRENT query
                        //     text with results already loaded → restored query,
                        //     no need to re-trigger.
                        // v0.7.4: the old `if (q.isNotBlank()) return@collect` guard
                        // couldn't tell a RESTORED query from the user's FIRST
                        // KEYSTROKE — typing within 500ms of entering the screen
                        // was silently swallowed and the user had to type an extra
                        // character to make the search fire. Now the skip only
                        // happens when the repo really is showing THIS query.
                        if (q.isBlank() && repo.searchItems.value.isNotEmpty()) {
                            WepWawetLogger.workshop("first emission: preserving restored search",
                                "search_items_count" to repo.searchItems.value.size)
                            return@collect
                        }
                        if (q.isNotBlank() && repo.searchItems.value.isNotEmpty() &&
                            repo.searchQueryText.value == q) {
                            WepWawetLogger.workshop("first emission: restored query, no re-trigger",
                                "query" to q.take(80))
                            return@collect
                        }
                        // First emission with fresh user input (or empty query
                        // and no search items) — fall through to the normal
                        // handling below.
                    }
                    if (q.isBlank()) {
                        if (repo.searchItems.value.isNotEmpty()) {
                            WepWawetLogger.workshop("query cleared — clearSearch")
                            repo.clearSearch()
                        }
                    } else {
                        WepWawetLogger.workshop(
                            "debounced query — startSearch",
                            "query" to q.take(80),
                        )
                        repo.startSearch(q)
                    }
                }
        }
    }

    // === Active state for display (browse vs search) ===
    val inSearchMode = !isCreatorMode && (searchItems.isNotEmpty() ||
        (query.isNotBlank() && searchState !is WorkshopRepository.BrowseState.Idle))
    val displayState = if (inSearchMode) searchState else browseState
    val displayItems: List<WorkshopItemTyped> = if (inSearchMode) searchItems else items

    // v0.7.3: When manual pagination is enabled, scroll the grid back to
    // the top whenever the loaded page changes. Without this, navigating
    // to the next page (or jumping to page N via the Go-to-page field)
    // leaves the scroll position wherever it was on the previous page —
    // usually near the bottom, just above the pagination bar — so the
    // user has to manually scroll up to see the new page's first row.
    // Only fires when pagination is on (the infinite-scroll mode does
    // its own scroll management via the snapshotFlow near-bottom
    // detector and doesn't need to be reset on each page).
    //
    // Keyed on `displayState` so it re-fires whenever the state
    // transitions from Loading(N) → Loaded(N, hasMore). The
    // `lastSeenPage` guard prevents the scroll from firing on the
    // initial composition (where Loaded(1) would equal the initial
    // state and we'd be at the top anyway) and avoids redundant
    // scrolls when the Loaded state hasn't actually changed.
    //
    // v0.7.20: lastSeenPage is now rememberSaveable so it survives
    // navigation to a detail screen and back. Previously it was plain
    // `remember { mutableStateOf(0) }` which reset to 0 on every return
    // from detail — causing the LaunchedEffect to see page=N != 0 and
    // scroll the grid to the top EVEN THOUGH the user was just viewing
    // a wallpaper and expected to return to their scroll position.
    var lastSeenPage by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(displayState, paginationEnabled) {
        if (!paginationEnabled) return@LaunchedEffect
        val loadedState = displayState as? WorkshopRepository.BrowseState.Loaded
        if (loadedState != null && loadedState.page != lastSeenPage) {
            lastSeenPage = loadedState.page
            gridState.scrollToItem(0)
            WepWawetLogger.workshop("pagination_scroll_to_top",
                "page" to loadedState.page)
        }
    }

    // === Infinite scroll: snapshotFlow reads State<T> directly (C2 fix) ===
    // v0.5.14-alpha: Read State<T> delegates (items, browseState, etc.)
    // DIRECTLY inside snapshotFlow block — NOT captured local vals.
    // Captured vals don't register as State<T> reads, so snapshotFlow
    // never re-evaluates when items/state change → stuck at 1 item.
    //
    // v0.7.1: Skipped entirely when the user has enabled manual pagination
    // mode in Settings — instead of auto-loading the next page when the user
    // nears the bottom of the grid, we show a Prev/Next/Go-to-page bar below
    // the grid (see the pagination bar section below the LazyVerticalGrid).
    if (!paginationEnabled) {
        LaunchedEffect(Unit) {
            snapshotFlow {
                val curItems = items
                val curSearchItems = searchItems
                val curBrowseState = browseState
                val curSearchState = searchState
                val curQuery = query
                val curInSearch = !isCreatorMode && (curSearchItems.isNotEmpty() ||
                    (curQuery.isNotBlank() && curSearchState !is WorkshopRepository.BrowseState.Idle))
                val activeItems = if (curInSearch) curSearchItems else curItems
                val activeState = if (curInSearch) curSearchState else curBrowseState
                val total = activeItems.size
                val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val loadedState = activeState as? WorkshopRepository.BrowseState.Loaded
                loadedState != null && loadedState.hasMore &&
                    (total < 30 || (total > 0 && lastVisible >= total - 6))
            }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    WepWawetLogger.workshop("infinite_scroll triggered loadNextPage",
                        "last_visible" to (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1),
                        "total" to displayItems.size,
                        "auto_preload" to (displayItems.size < 30),
                    )
                    repo.loadNextPage()
                }
        }
    }

    // === Pull-to-refresh handler ===
    val onRefresh: () -> Unit = {
        WepWawetLogger.workshop("pull_to_refresh triggered")
        scope.launch { repo.refresh() }
    }

    // The creator display name to show in the header — prefer the resolved
    // name from the StateFlow, fall back to the passed-in display name, then
    // to the raw steam ID.
    val resolvedCreatorName: String? = if (isCreatorMode) {
        creatorNames[creatorSteamId] ?: creatorDisplayName ?: creatorSteamId
    } else null

    Scaffold(
        topBar = {
            // Compact TopAppBar — no title text, only icon actions.
            // The Filter button was moved OUT of the top bar and INTO the
            // FilterBar (the row below the search bar that also hosts the
            // Sort and Tile size selectors). What's left on the top bar:
            //   - Left (navigationIcon): Back arrow (creator mode only),
            //     Bluetooth (pairing) button.
            //   - Right (actions): Refresh, Downloads, Favorites, Settings.
            //     v0.7.1: Logs was removed (top bar was too crowded) and
            //     Favorites was added in its place.
            //
            // The TopAppBar's windowInsets are set to WindowInsets(0) so
            // the bar's icons sit right at the top edge of the bar (no
            // extra status bar inset eating into the 64dp height). The
            // default status bar padding is preserved by applying
            // Modifier.statusBarsPadding() to the TopAppBar itself, so the
            // bar is still pushed below the system status bar.
            TopAppBar(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(56.dp),
                title = { },
                navigationIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Back arrow (creator mode only) — leftmost control
                        // on the top bar when in creator mode.
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                        // Pairing (Bluetooth) button.
                        IconButton(onClick = {
                            WepWawetLogger.workshop("topbar pairing tapped")
                            onOpenPairing()
                        }) {
                            Icon(
                                Icons.Filled.Bluetooth,
                                contentDescription = "Pairing",
                            )
                        }
                    }
                },
                actions = {
                    // v0.7.1: Re-added Refresh button (user clarified they meant
                    // remove the PIN refresh button, not the browse refresh).
                    IconButton(onClick = {
                        WepWawetLogger.workshop("topbar refresh tapped")
                        onRefresh()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh",
                            modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = {
                        WepWawetLogger.workshop("topbar downloads tapped")
                        onOpenDownloads()
                    }) {
                        Icon(Icons.Filled.Download, contentDescription = "Downloads",
                            modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = {
                        WepWawetLogger.workshop("topbar favorites tapped")
                        onOpenFavorites()
                    }) {
                        Icon(Icons.Filled.Favorite, contentDescription = "Favorites",
                            modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = {
                        WepWawetLogger.workshop("topbar settings tapped")
                        onOpenSettings()
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings",
                            modifier = Modifier.size(22.dp))
                    }
                    // v0.7.1: Removed logs icon per user request.
                },
                // Strip the TopAppBar's default status bar inset — we apply
                // statusBarsPadding() ourselves above. This keeps the bar at
                // its 56dp height (down from the default 64dp + 24dp inset).
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        // No additional content window insets — the top app bar already
        // accounts for the status bar via statusBarsPadding(), and the
        // grid's bottom inset is handled by the LazyVerticalGrid's
        // contentPadding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            // === Creator header (only shown in creator mode) ===
            if (isCreatorMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Showing items by " +
                                (resolvedCreatorName ?: creatorSteamId ?: "unknown"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // === Search bar (hidden in creator mode) ===
            if (!isCreatorMode) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { updateQuery(it) },
                    placeholder = { Text("Search wallpapers…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        // v0.7.1: vertical padding removed so the search bar
                        // sits flush against the filter bar below — the user
                        // asked for the gap to be 0dp (was 2dp top + 2dp bottom
                        // of the filter row = 4dp gap).
                        .padding(horizontal = 16.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            WepWawetLogger.workshop(
                                "ime search action — startSearch",
                                "query" to query.take(80),
                            )
                            if (query.isBlank()) scope.launch { repo.clearSearch() }
                            else scope.launch { repo.startSearch(query) }
                        },
                    ),
                    singleLine = true,
                )
            }

            // === Single-row filter bar (horizontal scroll on narrow screens) ===
            // The Filter button is the LEFTMOST control here (moved out of
            // the TopAppBar in v0.5.3), followed by the Sort and Tile size
            // dropdowns. All three share the same horizontal row.
            val filterLabel = when {
                excludedTags.isEmpty() && mobileCompatible -> "Filter"
                mobileCompatible -> "Filter (${excludedTags.size})"
                else -> "Filter (${excludedTags.size}+mob)"
            }
            FilterBar(
                sort = sort,
                onSortChange = { sort = it },
                sortMenuOpen = sortMenuOpen,
                onSortMenuOpenChange = { sortMenuOpen = it },
                tileSize = tileSize,
                onTileSizeChange = { tileSize = it },
                tileMenuOpen = tileMenuOpen,
                onTileMenuOpenChange = { tileMenuOpen = it },
                sortEnabled = !isCreatorMode,
                filterLabel = filterLabel,
                onFilterClick = {
                    WepWawetLogger.workshop(
                        "filter_picker open",
                        "currently_excluded" to excludedTags.size,
                        "mobile_compatible" to mobileCompatible,
                    )
                    showTagPicker = true
                },
            )

            // === Grid (with pull-to-refresh) ===
            // v0.7.1: When pagination is enabled, use Modifier.weight(1f)
            // instead of fillMaxSize so the manual pagination bar below has
            // room to render. When pagination is off (default), keep the
            // original fillMaxSize so the grid takes the whole screen.
            Box(modifier = if (paginationEnabled) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // v0.7.4: while the saved filter is still loading from prefs
                    // (filterLoaded=false), the repository's browseItems may hold
                    // the OTHER mode's items (e.g. popping back from
                    // creator/{id} to the global workshop screen). Rendering them
                    // here caused a brief flash of the wrong wallpapers before
                    // the fresh page-1 load replaced them — show a spinner until
                    // the filter is loaded and the correct session has started.
                    if (!filterLoaded) {
                        LoadingState()
                    } else when (displayState) {
                        WorkshopRepository.BrowseState.Idle -> EmptyState(
                            text = if (isCreatorMode)
                                "Loading creator's workshop…"
                            else "Pick a filter to start browsing.",
                        )

                        is WorkshopRepository.BrowseState.Loading -> {
                            if (displayItems.isEmpty()) {
                                LoadingState()
                            } else {
                                WallpaperGrid(
                                    gridState = gridState,
                                    items = displayItems,
                                    tileSize = tileSize,
                                    onItemClick = onItemClick,
                                    onCreatorClick = onCreatorClick,
                                    creatorNames = creatorNames,
                                    footerState = displayState,
                                )
                            }
                        }

                        is WorkshopRepository.BrowseState.Error -> ErrorState(
                            message = displayState.message,
                        )

                        else -> {
                            if (displayItems.isEmpty()) {
                                EmptyState(
                                    text = if (isCreatorMode)
                                        "No wallpapers from this creator."
                                    else if (inSearchMode)
                                        "No wallpapers match your search."
                                    else "No wallpapers found.",
                                )
                            } else {
                                WallpaperGrid(
                                    gridState = gridState,
                                    items = displayItems,
                                    tileSize = tileSize,
                                    onItemClick = onItemClick,
                                    onCreatorClick = onCreatorClick,
                                    creatorNames = creatorNames,
                                    footerState = displayState,
                                )
                            }
                        }
                    }
                }
            }

            // === Pagination bar (only when pagination is enabled) ===
            // v0.7.2: Compact pagination — single row, minimal padding.
            // v0.7.1 (round 4): Page bar was STILL too tall (~56dp) because
            // the OutlinedTextField's default height is 56dp with built-in
            // 16dp top + 16dp bottom padding, and the IconButton touch
            // targets were defaulting to 48dp minimum even with
            // Modifier.size(32.dp) applied (Material 3's
            // `minimumInteractiveComponentSize` Modifier enlarges the
            // ripple area back to 48dp). The bar height was effectively
            // 56dp+ which the user described as "way too tall".
            //
            // v0.7.1 (round 5 — pagination arrows don't work): TWO bugs
            // found:
            //   A) The pagination bar read `browseState` directly instead
            //      of `displayState` (which accounts for search mode). In
            //      search mode, browseState is stale (it's whatever the
            //      last browse loaded), while searchState has the actual
            //      current page + hasMore for the search session. The
            //      arrows used stale browse state — clicking Next would
            //      call loadPage(2) on the search session, search page 2
            //      might return 0 items → searchState=Done, but browseState
            //      was still Loaded(1, hasMore=true) so the bar STILL
            //      showed "Page 1" and Next was still enabled. The page
            //      number never advanced and the user perceived the
            //      arrows as "not working".
            //      FIX: use `displayState` instead of `browseState`.
            //   B) The Box.clickable approach (round 4) replaced the
            //      IconButton to avoid the 48dp minimum touch target.
            //      However, in some Compose versions, `Modifier.clickable`
            //      on a Box WITHOUT explicit `interactionSource` and
            //      `indication = ripple()` may not propagate the click
            //      event reliably in all layouts (especially inside a
            //      Surface with shadowElevation). To be safe, revert to
            //      IconButton (which has built-in ripple + interaction
            //      source) but with `Modifier.size(36.dp)` to override the
            //      default 48dp size. IconButton's
            //      minimumInteractiveComponentSize() adds INVISIBLE
            //      padding OUTSIDE the visual bounds — it doesn't make
            //      the row taller (the padding extends the touch area
            //      beyond the visual element).
            //      FIX: use IconButton with Modifier.size(36.dp).
            //
            // Also added logging in the click handlers so we can verify
            // they fire when debugging.
            if (paginationEnabled) {
                // v0.7.1 (round 5): Use displayState (which accounts for
                // search mode) instead of browseState. This is THE fix
                // for "the arrows to go to next page dont work" — the
                // arrows were using stale browse state in search mode.
                val loadedState = displayState as? WorkshopRepository.BrowseState.Loaded
                // v0.7.4: also show the page being LOADED, not just loaded pages.
                // The old `loadedState?.page ?: 1` made the bar briefly read
                // "Page 1" whenever the user tapped Next from page 5 (during
                // Loading(6) the cast to Loaded fails and the fallback kicks in).
                val loadingState = displayState as? WorkshopRepository.BrowseState.Loading
                val currentPage = loadedState?.page ?: loadingState?.page ?: 1
                val hasMore = loadedState?.hasMore ?: false
                val isLoading = displayState is WorkshopRepository.BrowseState.Loading
                var jumpPageText by remember { mutableStateOf("") }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 4.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Previous page button — IconButton with explicit
                        // size override (Modifier.size(36.dp)) so the
                        // minimumInteractiveComponentSize padding stays
                        // outside the visual bounds and doesn't inflate
                        // the row height.
                        val prevEnabled = currentPage > 1 && !isLoading
                        IconButton(
                            onClick = {
                                WepWawetLogger.workshop(
                                    "pagination_prev_tap",
                                    "current_page" to currentPage,
                                    "enabled" to prevEnabled,
                                    "in_search_mode" to inSearchMode,
                                )
                                if (currentPage > 1) {
                                    scope.launch { repo.loadPage(currentPage - 1) }
                                }
                            },
                            enabled = prevEnabled,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous page",
                                modifier = Modifier.size(20.dp),
                                tint = if (prevEnabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                        Text(
                            "Page $currentPage",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        // Next page button
                        val nextEnabled = hasMore && !isLoading
                        IconButton(
                            onClick = {
                                WepWawetLogger.workshop(
                                    "pagination_next_tap",
                                    "current_page" to currentPage,
                                    "has_more" to hasMore,
                                    "enabled" to nextEnabled,
                                    "in_search_mode" to inSearchMode,
                                )
                                if (hasMore) {
                                    scope.launch { repo.loadPage(currentPage + 1) }
                                }
                            },
                            enabled = nextEnabled,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next page",
                                modifier = Modifier.size(20.dp),
                                tint = if (nextEnabled) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // v0.7.1 (round 4): "Go to page:" label makes the
                        // text field's purpose obvious — the user said
                        // "its not clear you should type into the thing to
                        // go to that page". Now the label sits right next
                        // to the field so it's immediately obvious.
                        Text(
                            "Go to page:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = jumpPageText,
                            onValueChange = { if (it.all { c -> c.isDigit() }) jumpPageText = it.take(6) }, // L-U12: cap at 6 digits
                            placeholder = { Text("page #", style = MaterialTheme.typography.labelSmall) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Search,
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    val page = jumpPageText.toIntOrNull()
                                    if (page != null && page > 0) {
                                        WepWawetLogger.workshop(
                                            "pagination_jump_submit",
                                            "page" to page,
                                            "in_search_mode" to inSearchMode,
                                        )
                                        scope.launch { repo.loadPage(page) }
                                        jumpPageText = ""
                                    }
                                },
                            ),
                            // v0.7.1 (round 4): Force the field to 36dp tall
                            // so the bar's total height stays at 44dp.
                            // Without this, the OutlinedTextField's default
                            // 56dp height (16dp top + 16dp bottom padding +
                            // 24dp text) makes the bar ~56dp tall — the user
                            // said "way too tall".
                            //
                            // NOTE: Material 3 OutlinedTextField in BOM
                            // 2024.10.01 does NOT have a contentPadding
                            // parameter (it was added in a later version).
                            // To work around the lack of contentPadding,
                            // we just constrain the height — the field's
                            // internal text vertical-centering is good
                            // enough at 36dp height that it doesn't look
                            // clipped. (Compose Material 3 1.3.x.)
                            modifier = Modifier
                                .width(72.dp)
                                .height(36.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                textAlign = TextAlign.Center,
                            ),
                            trailingIcon = {
                                // Send button (IconButton with size override
                                // for the smaller touch target inside the
                                // text field).
                                IconButton(
                                    onClick = {
                                        val page = jumpPageText.toIntOrNull()
                                        if (page != null && page > 0) {
                                            WepWawetLogger.workshop(
                                                "pagination_jump_send_tap",
                                                "page" to page,
                                                "in_search_mode" to inSearchMode,
                                            )
                                            scope.launch { repo.loadPage(page) }
                                            jumpPageText = ""
                                        }
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Send,
                                        contentDescription = "Go to page",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // === Tag picker bottom sheet (also hosts the Mobile Compatible toggle) ===
    TagPickerSheet(
        visible = showTagPicker,
        excludedTags = excludedTags,
        mobileCompatible = mobileCompatible,
        onApply = { newExcluded, newMobileCompat ->
            WepWawetLogger.workshop(
                "filter_apply",
                "total_excluded" to newExcluded.size,
                "excluded" to newExcluded.joinToString(","),
                "mobile_compatible" to newMobileCompat,
            )
            excludedTags = newExcluded
            mobileCompatible = newMobileCompat
            showTagPicker = false
        },
        onDismiss = { showTagPicker = false },
    )
}

// ====== Sub-composables (kept in this file for locality) ======

/**
 * Compact single-row filter bar with horizontal scroll for narrow screens.
 *
 * v0.5.3 — the Filter button (which used to live in the TopAppBar) has been
 * moved INTO this row, so the layout is:
 *
 *     [Filter ▼] [Sort: Most Popular (Year) ▼] [Tile: Medium ▼]
 *
 * All three controls share the same horizontal row, right below the search
 * bar.
 *
 * v0.5.2 — the trend-days selector and the Mobile Compatible toggle were
 * both removed from this bar (the toggle moved into the [TagPickerSheet]
 * bottom sheet; trend-days is now part of the Sort enum). Only the Sort
 * dropdown, Tile size dropdown and the Filter button remain.
 *
 * All sub-controls are wrapped in [TextButton]s with small icons + labels to
 * keep the visual density low.
 *
 * @param filterLabel text label to show on the Filter button (e.g. "Filter",
 *   "Filter (12)", "Filter (12+mob)"). The caller is responsible for
 *   computing this label from the current `excludedTags` and
 *   `mobileCompatible` state.
 * @param onFilterClick invoked when the user taps the Filter button.
 *   Typically opens the [TagPickerSheet] bottom sheet.
 */
@Composable
private fun FilterBar(
    sort: SteamApiService.Sort,
    onSortChange: (SteamApiService.Sort) -> Unit,
    sortMenuOpen: Boolean,
    onSortMenuOpenChange: (Boolean) -> Unit,
    tileSize: TileSize,
    onTileSizeChange: (TileSize) -> Unit,
    tileMenuOpen: Boolean,
    onTileMenuOpenChange: (Boolean) -> Unit,
    sortEnabled: Boolean,
    filterLabel: String,
    onFilterClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // v0.7.1: vertical padding removed so the filter bar sits flush
            // against the search bar above — the user asked for the gap to
            // be 0dp.
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // === Filter button (leftmost control on the row) ===
        // Opens the TagPickerSheet bottom sheet, which hosts the
        // excluded-tags picker and the Mobile Compatible toggle.
        Box {
            TextButton(onClick = onFilterClick) {
                Icon(
                    Icons.Filled.FilterList,
                    contentDescription = "Filter",
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    filterLabel,
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        // === Sort dropdown — shows all 8 user-facing sort options ===
        // Trend days are baked into the chosen Sort enum (Year/Month/Week/Today).
        Box {
            TextButton(
                onClick = {
                    WepWawetLogger.workshop("sort_menu open")
                    onSortMenuOpenChange(true)
                },
                enabled = sortEnabled,
            ) {
                Text(
                    "Sort: ${sort.displayName}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Sort",
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { onSortMenuOpenChange(false) },
            ) {
                // Show all sort options EXCEPT MOST_RELEVANT (search-only).
                // The order matches WE's screenshot:
                //   1. Top Rated
                //   2. Most Popular (Year) — DEFAULT
                //   3. Most Popular (Month)
                //   4. Most Popular (Week)
                //   5. Most Popular (Today)
                //   6. Most Recent
                //   7. Most Up Votes
                //   8. Most Subscriptions
                val orderedSorts = listOf(
                    SteamApiService.Sort.TOP_RATED,
                    SteamApiService.Sort.MOST_POPULAR_YEAR,
                    SteamApiService.Sort.MOST_POPULAR_MONTH,
                    SteamApiService.Sort.MOST_POPULAR_WEEK,
                    SteamApiService.Sort.MOST_POPULAR_TODAY,
                    SteamApiService.Sort.MOST_RECENT,
                    SteamApiService.Sort.MOST_UP_VOTES,
                    SteamApiService.Sort.MOST_SUBSCRIPTIONS,
                )
                orderedSorts.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(s.displayName)
                        },
                        onClick = {
                            WepWawetLogger.workshop(
                                "sort_changed",
                                "sort" to s.name,
                                "trend_days" to s.trendDays,
                            )
                            onSortChange(s)
                            onSortMenuOpenChange(false)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        // === Tile size dropdown ===
        Box {
            TextButton(onClick = {
                WepWawetLogger.workshop("tile_size_menu open")
                onTileMenuOpenChange(true)
            }) {
                Icon(
                    Icons.Filled.ViewModule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Tile: ${tileSize.displayName}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = "Tile size",
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = tileMenuOpen,
                onDismissRequest = { onTileMenuOpenChange(false) },
            ) {
                TileSize.entries.forEach { ts ->
                    DropdownMenuItem(
                        text = { Text(ts.displayName) },
                        onClick = {
                            WepWawetLogger.workshop(
                                "tile_size_changed",
                                "size" to ts.name,
                            )
                            onTileSizeChange(ts)
                            onTileMenuOpenChange(false)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The grid of [WallpaperCard]s with a footer that shows loading-more / end-of-results.
 *
 * Uses [BoxWithConstraints] to detect phone-vs-tablet width buckets and adjust
 * the column count via [TileSize.columnsFor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallpaperGrid(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    items: List<WorkshopItemTyped>,
    tileSize: TileSize,
    onItemClick: (String) -> Unit,
    onCreatorClick: (String) -> Unit,
    creatorNames: Map<String, String>,
    footerState: WorkshopRepository.BrowseState,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp
        val columnCount = tileSize.columnsFor(isCompact)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                val resolvedName = creatorNames[item.creator]
                WallpaperCard(
                    item = item,
                    tileSize = tileSize,
                    creatorName = resolvedName,
                    onClick = {
                        WepWawetLogger.workshop(
                            "wallpaper_card_tap",
                            "id" to item.id,
                            "title" to item.title.take(80),
                        )
                        onItemClick(item.id)
                    },
                    onCreatorClick = onCreatorClick,
                )
            }

            // Footer spans all columns
            item(span = { GridItemSpan(columnCount) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (footerState) {
                        is WorkshopRepository.BrowseState.Loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Loading more…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        is WorkshopRepository.BrowseState.Done,
                        is WorkshopRepository.BrowseState.Loaded -> {
                            val isEnd = when (footerState) {
                                is WorkshopRepository.BrowseState.Done -> true
                                is WorkshopRepository.BrowseState.Loaded -> !footerState.hasMore
                                else -> false
                            }
                            if (isEnd) {
                                Text(
                                    "— End of results —",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Spacer(Modifier.size(1.dp))
                            }
                        }

                        else -> {
                            Spacer(Modifier.size(1.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator()
            Text(
                "Loading workshop…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Filled.AccountBox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    "⚠️ Failed to load",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
