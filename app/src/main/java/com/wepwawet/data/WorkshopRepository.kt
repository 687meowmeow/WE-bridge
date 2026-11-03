package com.wepwawet.data

import com.wepwawet.WepWawetApplication
import com.wepwawet.data.model.WallpaperType
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.data.model.WorkshopTags
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for workshop data in the UI.
 *
 * Responsibilities:
 *   - Paginated browse with sort options (TRENDING / TOP_RATED / MOST_RECENT
 *     + client-side MOST_SUBSCRIBED / MOST_FAVORITED / MOST_VIEWED / ALL_TIME)
 *   - Browse a specific creator's workshop via [browseCreatorWorkshop]
 *   - Search by free-text query
 *   - Filter by required tags (AND logic) and excluded tags (NOT logic)
 *   - Mobile-compatible filtering (auto-excludes Web/Application/Image/Playlist/Unknown)
 *   - Get details by ID (cache-first)
 *   - Resolve creator Steam ID64 → display name (with in-memory cache)
 *   - In-memory cache of the current browse session AND a per-id detail cache
 *   - Logs all operations via [WepWawetLogger] (WORKSHOP domain)
 *
 * Steam doesn't expose cursor-based pagination; we use page numbers (1, 2, …).
 * When the user changes the filter (sort / tags / query / creator), we reset
 * the session and start from page 1.
 */
class WorkshopRepository constructor(
    private val steamApi: SteamApiService,
) {

    // ====== Browse state (single session — replaced when user changes filter) ======

    private val _browseItems = MutableStateFlow<List<WorkshopItemTyped>>(emptyList())
    val browseItems: StateFlow<List<WorkshopItemTyped>> = _browseItems.asStateFlow()

    private val _browseState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var currentFilter: Filter? = null
    private var currentPage: Int = 0
    @Volatile private var hasMore: Boolean = true

    /**
     * v0.7.23: The last filter that was passed to [startBrowse]. Used by
     * WorkshopBrowserScreen to detect whether a filter change should trigger
     * a new browse session. Hoisted here (singleton) so it survives navigation
     * to detail and back — the old `remember` in WorkshopBrowserScreen was
     * lost on navigation, causing a spurious startBrowse on every return.
     */
    @Volatile
    var lastStartBrowseFilter: Filter? = null
        private set

    // v0.5.12-alpha: track consecutive empty pages (pages where the API returned
    // items but client-side applyClientFilters filtered all of them out). After
    // MAX_CONSECUTIVE_EMPTY_PAGES consecutive empties, give up and set hasMore=false
    // so the UI shows "no more results" instead of "loading more" forever.
    // This prevents the infinite auto-preload loop when the user's filter is
    // too restrictive (e.g., mobileCompatible=true + 18 default-excluded
    // resolution tags + age-rating exclusion → 0 items per page forever).
    private var consecutiveEmptyPages: Int = 0
    private val MAX_CONSECUTIVE_EMPTY_PAGES: Int = 20

    // v0.7.1: Separate counter for SEARCH sessions — same purpose as
    // [consecutiveEmptyPages] but for the search pipeline. KEPT SEPARATE
    // (not shared with the browse counter) so a long browse session that
    // ran the browse counter up to 19/20 doesn't immediately trigger
    // hasMore=false on the very first transient Steam miss when the user
    // switches to search. Reset in startSearch() / clearSearch() /
    // refresh()-search-branch / loadPage()-search-branch — every entry
    // point that resets currentSearchQuery/currentSearchPage.
    private var consecutiveEmptySearchPages: Int = 0

    /** Mutex guards concurrent browse/search operations against each other. */
    private val browseLock = Mutex()
    // (v0.7.4: the `loadJob` field was removed — it was declared for an "M2 fix"
    //  but NEVER assigned anywhere, so `loadJob?.cancel()` in startBrowse was
    //  always a no-op and only created the false impression that in-flight
    //  loads were being cancelled. startBrowse serializes on browseLock
    //  exactly as before.)

    // ====== Search state (separate from browse so user can switch back) ======

    private val _searchItems = MutableStateFlow<List<WorkshopItemTyped>>(emptyList())
    val searchItems: StateFlow<List<WorkshopItemTyped>> = _searchItems.asStateFlow()

    private val _searchState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val searchState: StateFlow<BrowseState> = _searchState.asStateFlow()

    /**
     * === v0.4.7/v0.5.9-alpha: Hoisted query text (was a local `remember` in the
     * WorkshopBrowserScreen composable, which was destroyed on navigation
     * → recomposition → empty query → snapshotFlow fires clearSearch →
     * search results wiped). Hoisting here means the query text survives
     * navigation back from the detail screen.
     *
     * REGRESSION HISTORY:
     *   - v0.4.14 had this hoisted state.
     *   - v0.5.0 (regression #1) deleted it — query became local `remember`.
     *   - v0.5.1+ (regression #2) replaced it with persistent SharedPreferences
     *     for sort/tag/tile but kept query as local `remember`. The result:
     *     navigating to detail/{itemId} → back wipes query AND search results.
     *   - v0.5.9-alpha (this commit): RESTORED the v0.4.14 hoisted pattern.
     * ===
     */
    private val _searchQueryText = MutableStateFlow("")
    val searchQueryText: StateFlow<String> = _searchQueryText.asStateFlow()

    /**
     * Update the search query text (does NOT trigger a search — the UI's
     * snapshotFlow debounce handles that). This is purely for state
     * preservation across navigation.
     */
    fun updateSearchQueryText(query: String) {
        _searchQueryText.value = query
    }

    // v0.7.4: @Volatile — this field is read OUTSIDE browseLock by the routing
    // branches in loadNextPage()/loadPage() (before acquiring the mutex) and
    // written under the lock by startSearch/clearSearch. Without @Volatile a
    // reader thread may observe a stale value and route to the wrong branch.
    @Volatile private var currentSearchQuery: String? = null
    private var currentSearchPage: Int = 0
    @Volatile private var searchHasMore: Boolean = true

    // ====== In-memory detail cache (id → typed item) ======

    /** v0.7.24 (H-D8): detail-cache LRU bound (≈ 40 browse pages of items). */
    private val DETAIL_CACHE_MAX_ENTRIES = 1200

    // v0.7.24 (H-D8): was an unbounded ConcurrentHashMap — every browse page
    // cached ALL its items and every getById miss added another; a long
    // session browsing thousands of wallpapers grew it without limit.
    // Bounded access-ordered LRU (synchronized — accessOrder mutation is not
    // thread-safe even through the Map interface).
    private val detailCache: MutableMap<String, WorkshopItemTyped> =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, WorkshopItemTyped>(
                64, 0.75f, /* accessOrder = */ true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WorkshopItemTyped>): Boolean {
                    return size > DETAIL_CACHE_MAX_ENTRIES
                }
            })

    // ====== Creator display names (steamId64 → displayName) ======

    /**
     * Resolved creator display names, exposed to the UI so wallpaper tiles can
     * show "by Display Name" instead of the raw 17-digit Steam ID64.
     *
     * Populated by [resolveCreatorsForItems] as items are loaded — see the
     * helper for the lookup logic. The SteamApiService itself also caches
     * resolved names (so repeat lookups are no-ops); this StateFlow just
     * mirrors that cache for UI consumption.
     */
    private val _creatorNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val creatorNames: StateFlow<Map<String, String>> = _creatorNames.asStateFlow()

    /** Track which steam IDs we've already resolved (avoids re-dispatching). */
    private val resolvingIds = mutableSetOf<String>()

    // ====== Subscriptions (local mirror of what the user has subscribed to) ======

    private val _subscribedIds = MutableStateFlow<Set<String>>(emptySet())
    val subscribedIds: StateFlow<Set<String>> = _subscribedIds.asStateFlow()

    // ====== Browse ======

    /**
     * Reset and start a new browse session with a different filter.
     * Called when the user changes sort, tag, query, or creator.
     */
    suspend fun startBrowse(filter: Filter) {
        browseLock.withLock {
            currentFilter = filter
            // v0.7.23: Track the last filter so WorkshopBrowserScreen can
            // detect whether a filter change should trigger a new browse.
            lastStartBrowseFilter = filter
            currentPage = 0
            hasMore = true
            consecutiveEmptyPages = 0  // v0.5.12-alpha: reset on new browse session
            _browseItems.value = emptyList()
            _browseState.value = BrowseState.Idle
            steamApi.resetQueryFilesCursor()
            loadNextPageLocked()
        }
    }

    /**
     * Load the next page of the current browse session (or search session if active).
     * Safe to call from UI — does nothing if a load is already in progress or there
     * is no more data.
     */
    suspend fun loadNextPage() {
        // If we're in search mode, delegate to search loadNextPage
        if (currentSearchQuery != null) {
            loadNextSearchPage()
            return
        }
        browseLock.withLock {
            loadNextPageLocked()
        }
    }

    /**
     * Load a specific page number (for pagination mode).
     * Clears existing items and loads the requested page.
     *
     * v0.7.1: Used by the WorkshopBrowserScreen's manual pagination bar
     * (Prev / Page N / Next / Go-to-page) when the user has enabled the
     * `pagination_enabled` setting. Behaves like [startBrowse] but jumps
     * straight to the requested page number instead of always starting at
     * page 1. The next [loadNextPageLocked] call will increment
     * `currentPage` from `pageNum - 1` to `pageNum` and load it.
     *
     * Note: search mode and browse mode share the same `browseLock` (the
     * search methods just acquire the same mutex internally), so we use
     * `browseLock.withLock` here for both branches.
     */
    suspend fun loadPage(pageNum: Int) {
        // v0.7.4: validate the page number — a pageNum < 1 would set
        // currentPage negative and make Steam reject the request with a
        // confusing error. Clamp to page 1 instead.
        val safePageNum = pageNum.coerceAtLeast(1)
        if (currentSearchQuery != null) {
            // Search mode pagination
            browseLock.withLock {
                currentSearchPage = safePageNum - 1
                _searchItems.value = emptyList()
                // v0.7.1: reset the search empty-page counter when the
                // user manually jumps to a different page — otherwise a
                // previous page that came back empty (after client filters)
                // would count toward the cap and might immediately stop
                // the new page from auto-loading the next one if it also
                // comes back empty.
                consecutiveEmptySearchPages = 0
                // v0.7.4 fix: reset searchHasMore too — mirroring the browse
                // branch below. If the PREVIOUS search had reached end of
                // results (searchHasMore=false), loadNextSearchPageLocked()
                // early-returned and the user got a silently empty screen
                // with no loading state and no way to recover except by
                // running a brand-new search.
                searchHasMore = true
                loadNextSearchPageLocked()
            }
            return
        }
        browseLock.withLock {
            currentPage = safePageNum - 1
            _browseItems.value = emptyList()
            consecutiveEmptyPages = 0
            hasMore = true
            loadNextPageLocked()
        }
    }

    private suspend fun loadNextPageLocked() {
        val filter = currentFilter ?: run {
            WepWawetLogger.workshopWarn("loadNextPage: no filter set")
            return
        }
        if (!hasMore) {
            WepWawetLogger.workshopDebug("loadNextPage: no more pages")
            return
        }
        val page = currentPage + 1
        _browseState.value = BrowseState.Loading(page)
        // v0.5.13-alpha: log currentPage at start so we can verify it's
        // incrementing correctly across pages (the user's log showed
        // loadNextPage starting page=1 multiple times instead of advancing
        // to page=2, page=3, etc. — likely because startBrowse was called
        // multiple times, resetting currentPage to 0).
        WepWawetLogger.workshop("loadNextPage start",
            "page" to page,
            "currentPage_before" to currentPage,
            "sort" to filter.sort.displayName,
            "query_type" to filter.sort.queryType,
            "tags" to filter.tags.joinToString(","),
            "excluded_tags" to filter.excludedTags.joinToString(","),
            "mobile_compatible" to filter.mobileCompatible,
            "trend_days" to filter.sort.trendDays,
            "creator_steam_id" to (filter.creatorSteamId ?: "none"),
            "query" to (filter.query ?: "none"),
            "api_key_set" to (steamApi.apiKey != null))

        try {
            val result = if (filter.creatorSteamId != null) {
                // PRIMARY: JavaSteam GetUserFiles RPC (bypasses HTTP 429 on profile scraping)
                val creatorResult = steamApi.getUserFilesViaJavaSteam(
                    creatorSteamId = filter.creatorSteamId,
                    page = page,
                    tags = filter.tags,
                    excludedTags = filter.excludedTags,
                    mobileCompatible = filter.mobileCompatible,
                )
                if (creatorResult.isSuccess) {
                    creatorResult
                } else {
                    // FALLBACK: HTML scraping (may HTTP 429)
                    WepWawetLogger.workshopWarn(
                        "loadNextPage: JavaSteam getUserFiles failed — falling back to HTML",
                        "page" to page,
                        "creator_steam_id" to filter.creatorSteamId,
                        "exception" to (creatorResult.exceptionOrNull()?.javaClass?.name ?: "unknown"),
                    )
                    steamApi.browseCreatorWorkshop(
                        steamId = filter.creatorSteamId,
                        page = page,
                    )
                }
            } else {
                // PRIMARY PATH: JavaSteam native CM protocol (ISteamPublishedFile.QueryFiles
                // service RPC) — byte-identical to what WE PC's steam_api64.dll sends.
                // Bypasses the IPublishedFileService HTTP API's [1,7] days limit for
                // RankedByTrend — Steam honors any value (1, 7, 30, 365, ...) on the
                // service path. This is ABSOLUTE PARITY with WE PC.
                //
                // Falls back gracefully to HTTP API + HTML scraping if the JavaSteam
                // session is unavailable (user not logged in, Steam down, auth failed,
                // EPublishedFileQueryType.forNumber returned null, etc.).
                val javaSteamResult = steamApi.queryFilesViaJavaSteam(
                    sort = filter.sort,
                    page = page,
                    tags = filter.tags,
                    excludedTags = filter.excludedTags,
                    mobileCompatible = filter.mobileCompatible,
                    query = filter.query,
                )
                if (javaSteamResult.isSuccess) {
                    javaSteamResult
                } else {
                    // Log the failure then fall through to HTTP API + HTML fallbacks.
                    WepWawetLogger.workshopWarn(
                        "loadNextPage: JavaSteam path failed — falling back to HTTP/HTML",
                        "page" to page,
                        "sort" to filter.sort.displayName,
                        "exception" to (javaSteamResult.exceptionOrNull()?.javaClass?.name ?: "unknown"),
                        "message" to (javaSteamResult.exceptionOrNull()?.message ?: ""),
                    )
                    // FALLBACK 1: IPublishedFileService HTTP API (server-side sorting, requires API key).
                    // For trend sorts with days > 7, this silently clamps to 7 — so MOST_POPULAR_YEAR
                    // (365) and MOST_POPULAR_MONTH (30) would return identical results to
                    // MOST_POPULAR_WEEK (7). WorkshopRepository will then route trend sorts to
                    // HTML endpoint (FALLBACK 2) which DOES accept arbitrary day values.
                    //
                    // v0.7.4: the routing described above NOW ACTUALLY HAPPENS. The old
                    // code called the HTTP API even for trendDays > 7 and used its
                    // (silently clamped-to-7) "success" result — MOST_POPULAR_YEAR and
                    // MOST_POPULAR_MONTH returned week-trending items whenever the
                    // JavaSteam session was unavailable. We now skip FALLBACK 1 outright
                    // for trendDays > 7 and go straight to the HTML endpoint.
                    val httpResult = if (filter.sort.trendDays > 7) {
                        WepWawetLogger.workshop(
                            "loadNextPage: trend days > 7 — skipping HTTP API (clamps to 7), using HTML endpoint",
                            "page" to page,
                            "sort" to filter.sort.displayName,
                            "trend_days" to filter.sort.trendDays,
                        )
                        null
                    } else {
                        steamApi.queryFiles(
                            sort = filter.sort,
                            page = page,
                            tags = filter.tags,
                            excludedTags = filter.excludedTags,
                            mobileCompatible = filter.mobileCompatible,
                            query = filter.query,
                        )
                    }
                    if (httpResult != null && httpResult.isSuccess) {
                        httpResult
                    } else {
                        // FALLBACK 2: HTML scraping endpoint (steamcommunity.com/workshop/browse).
                        // Accepts arbitrary `days` values for trend sorts (verified: 1, 7, 30,
                        // 365 all return distinct results for WE appid 431960). Always used for
                        // trend sorts when both JavaSteam and HTTP API fail.
                        if (filter.query != null) {
                            steamApi.searchWorkshop(
                                query = filter.query,
                                page = page,
                                tags = filter.tags,
                                excludedTags = filter.excludedTags,
                                mobileCompatible = filter.mobileCompatible,
                            )
                        } else {
                            steamApi.browseWorkshop(
                                sort = filter.sort,
                                page = page,
                                tags = filter.tags,
                                excludedTags = filter.excludedTags,
                                mobileCompatible = filter.mobileCompatible,
                            )
                        }
                    }
                }
            }
            val items = result.getOrElse { e ->
                _browseState.value = BrowseState.Error(
                    e.message ?: "Unknown error", page
                )
                WepWawetLogger.workshopWarn("loadNextPage failed",
                    "page" to page,
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
                return
            }

            if (items.isEmpty()) {
                hasMore = false
                _browseState.value = BrowseState.Done
                WepWawetLogger.workshop("loadNextPage: end of results",
                    "page" to page)
                return
            }

            // Client-side filtering + sorting pipeline
            val filteredItems = applyClientFilters(items, filter)

            // v0.5.12-alpha: track consecutive empty pages to prevent infinite
            // auto-preload loop when the user's filter is too restrictive
            // (e.g., mobileCompatible=true + 18 default-excluded resolution
            // tags + age-rating exclusion → every page returns 30 items
            // from API, all 30 filtered out client-side, 0 new items added,
            // auto_preload triggers next page load, repeat forever).
            // After MAX_CONSECUTIVE_EMPTY_PAGES (20) consecutive empty pages,
            // give up and set hasMore=false so the UI shows "no more results"
            // instead of "loading more" forever.
            if (filteredItems.isEmpty()) {
                consecutiveEmptyPages++
                if (consecutiveEmptyPages >= MAX_CONSECUTIVE_EMPTY_PAGES) {
                    hasMore = false
                    _browseState.value = BrowseState.Done
                    WepWawetLogger.workshop("loadNextPage: max consecutive empty pages reached, giving up",
                        "page" to page,
                        "consecutive_empty" to consecutiveEmptyPages,
                        "max" to MAX_CONSECUTIVE_EMPTY_PAGES)
                    return
                }
            } else {
                consecutiveEmptyPages = 0  // reset on non-empty page
            }

            // Cache each item by id for fast detail-screen lookup
            filteredItems.forEach { detailCache[it.id] = it }

            _browseItems.value = (_browseItems.value + filteredItems).distinctBy { it.id }
            currentPage = page
            _browseState.value = BrowseState.Loaded(page, hasMore)

            // Resolve creator display names — TRULY fire-and-forget.
            // Launch on the application scope so it doesn't block the lock
            // or delay showing items to the user.
            WepWawetApplication.appScope.launch {
                resolveCreatorsForItems(filteredItems)
            }

            WepWawetLogger.workshop("loadNextPage done",
                "page" to page,
                "new_items" to filteredItems.size,
                "filtered_out" to (items.size - filteredItems.size),
                "total_items" to _browseItems.value.size)
        } catch (e: CancellationException) {
            // Coroutine cancellation (e.g., user navigated away from the
            // WorkshopBrowserScreen while loadNextPage was in flight). MUST
            // be re-thrown — swallowing it would break structured concurrency
            // and would also leave the browseState stuck on Loading(page).
            //
            // The previous code (catch Exception) put BrowseState.Error and
            // logged LeftCompositionCancellationException as a warning, which
            // polluted the logs and showed a fake error state to the user.
            WepWawetLogger.workshopDebug("loadNextPage cancelled (coroutine scope disposed)",
                "page" to currentPage)
            throw e
        } catch (e: Exception) {
            _browseState.value = BrowseState.Error(e.message ?: "Unknown error", currentPage)
            WepWawetLogger.workshopWarn("loadNextPage exception",
                "page" to currentPage,
                "exception" to e.javaClass.name)
        }
    }

    /**
     * Pull-to-refresh: reload page 1 of the current session, replace all items.
     */
    suspend fun refresh() {
        _isRefreshing.value = true
        try {
            val filter = currentFilter
            val query = currentSearchQuery
            if (query != null) {
                // Refresh search
                browseLock.withLock {
                    currentSearchPage = 0
                    searchHasMore = true
                    _searchItems.value = emptyList()
                    // v0.5.32 (audit H1): reset consecutiveEmptyPages too.
                    // The counter is shared between search and browse paths
                    // (loadNextPageLocked checks it). If the previous browse
                    // session ran the counter up to 19/20, then the user
                    // switched to search and pulled-to-refresh, the stale
                    // counter would make the very next transient Steam miss
                    // trigger hasMore=false prematurely. Reset on every
                    // refresh — same as startBrowse/startSearch would.
                    consecutiveEmptyPages = 0
                    // v0.7.1: reset the SEARCH-specific counter too — same
                    // rationale. The search counter is kept separate from
                    // the browse counter so a long restrictive browse
                    // session doesn't bleed into search, but a long
                    // restrictive SEARCH session that ran the SEARCH
                    // counter up to 19/20 still needs to be reset on
                    // refresh, otherwise the very next transient Steam
                    // miss would stop the search early.
                    consecutiveEmptySearchPages = 0
                    loadNextSearchPageLocked()
                }
            } else if (filter != null) {
                // Refresh browse
                browseLock.withLock {
                    currentPage = 0
                    hasMore = true
                    // v0.5.32 (audit H1): reset consecutiveEmptyPages on
                    // refresh — otherwise a restrictive filter that ran the
                    // counter to 19/20 leaves it there, and the very next
                    // transient Steam miss triggers hasMore=false permanently.
                    // The user has to fully exit the screen and re-enter to
                    // recover.
                    consecutiveEmptyPages = 0
                    steamApi.resetQueryFilesCursor()
                    _browseItems.value = emptyList()
                    loadNextPageLocked()
                }
            }
        } finally {
            _isRefreshing.value = false
        }
    }

    // ====== Search ======

    /**
     * Start a new text search. Replaces the current search session.
     * Search uses [SteamApiService.searchWorkshop] which scrapes the browse HTML
     * with `searchtext=<query>`.
     *
     * v0.7.1: Added an optional [filter] parameter. When the user changes
     * the sort (or any other filter field) while a search is active, the
     * UI re-runs the search with the new filter — without it, the search
     * would keep using the OLD filter (from the previous `startBrowse`
     * call) for subsequent page loads, so a sort change wouldn't actually
     * take effect on the search results. The filter's `sort` field is
     * applied via `applyClientFilters` (which currently doesn't sort
     * client-side — see the note in [applyClientFilters] — but the other
     * filter fields like `tags`, `excludedTags`, `mobileCompatible` DO
     * get applied to search page loads via [loadNextSearchPageLocked]).
     *
     * @param query search string (e.g. "Cyberpunk")
     * @param filter optional new [Filter] to apply to this search session.
     *   When null (the default), the existing `currentFilter` is kept —
     *   matching the previous behavior. Pass a non-null filter when
     *   re-running the search after a sort/filter change.
     */
    suspend fun startSearch(query: String, filter: Filter? = null) {
        if (query.isBlank()) {
            clearSearch()
            return
        }
        browseLock.withLock {
            // Apply the new filter (if any) before resetting the search
            // session so the first page load uses the updated sort/tags.
            if (filter != null) {
                currentFilter = filter
                WepWawetLogger.workshop("startSearch filter updated",
                    "sort" to filter.sort.name,
                    "mobile_compatible" to filter.mobileCompatible,
                    "excluded_tags_count" to filter.excludedTags.size)
            }
            currentSearchQuery = query
            currentSearchPage = 0
            searchHasMore = true
            // v0.7.1: reset the SEARCH empty-page counter on every new
            // search session — same rationale as the browse counter reset
            // in startBrowse(). A previous search that ran the counter up
            // to 19/20 (because the user's query + filters matched nothing
            // for many pages) shouldn't immediately stop the next search.
            consecutiveEmptySearchPages = 0
            _searchItems.value = emptyList()
            _searchState.value = BrowseState.Idle
            // v0.4.7/v0.5.9-alpha: keep _searchQueryText in sync (UI uses it
            // to populate the search box after navigation back; if it's stale,
            // the user sees an empty search box but the results are still
            // showing — confusing).
            _searchQueryText.value = query
            loadNextSearchPageLocked()
        }
    }

    /**
     * Clear the active search session — UI falls back to showing browse items.
     *
     * v0.7.4: now `suspend` + acquires [browseLock]. Previously it mutated the
     * session state (currentSearchQuery, searchHasMore, _searchItems, …)
     * WITHOUT the lock — if loadNextSearchPageLocked() was in flight when the
     * user cleared the search, the in-flight load completed afterwards and
     * clobbered the freshly-cleared state, making the old search results
     * REAPPEAR after the user cleared them.
     */
    suspend fun clearSearch() {
        browseLock.withLock {
            currentSearchQuery = null
            currentSearchPage = 0
            searchHasMore = false
            // v0.7.1: reset the search empty-page counter on clear too, so the
            // next search session starts fresh.
            consecutiveEmptySearchPages = 0
            _searchItems.value = emptyList()
            _searchState.value = BrowseState.Idle
            // v0.4.7/v0.5.9-alpha: clear the hoisted query text so the search
            // box is empty next time the user opens it (matches clearSearch
            // semantics).
            _searchQueryText.value = ""
            WepWawetLogger.workshop("clearSearch")
        }
    }

    private suspend fun loadNextSearchPage() {
        browseLock.withLock { loadNextSearchPageLocked() }
    }

    private suspend fun loadNextSearchPageLocked() {
        val query = currentSearchQuery ?: return
        if (!searchHasMore) return
        val page = currentSearchPage + 1
        _searchState.value = BrowseState.Loading(page)
        val filter = currentFilter
        val mobileCompat = filter?.mobileCompatible ?: true
        val tags = filter?.tags ?: emptyList()
        val excludedTags = filter?.excludedTags ?: emptyList()
        WepWawetLogger.workshop("loadNextSearchPage start",
            "query" to query,
            "page" to page,
            "mobile_compatible" to mobileCompat,
            "tags" to tags.joinToString(","),
            "excluded_tags" to excludedTags.joinToString(","),
            "api_key_set" to (steamApi.apiKey != null))

        try {
            // === v0.7.1: SEARCH BACKEND FALLBACK CHAIN ===
            //
            // Previously search went straight to steamApi.searchWorkshop()
            // (the HTML scraper with `searchtext=`), which uses a DIFFERENT
            // filter format from browse (HTML: requiredtags[]/excludedtags[]/
            // kvs[...]; JavaSteam/HTTP: requiredTags + matchAllTags=true +
            // requiredKvTags). The same filters gave different result sets
            // between browse and search — items that matched the user's
            // filters would be missing from search.
            //
            // Now search uses the SAME 3-step fallback chain as browse so
            // the two paths return consistent results:
            //   PRIMARY:    queryFilesViaJavaSteam (Steam CM protocol)
            //   FALLBACK 1: queryFiles (IPublishedFileService HTTP API)
            //   FALLBACK 2: searchWorkshop (HTML scrape, last resort only)
            //
            // IMPORTANT: we ALWAYS pass sort = Sort.MOST_RELEVANT
            // (queryType=12, RankedByTextSearch) for search.
            // queryFilesViaJavaSteam() uses sort.queryType DIRECTLY and does
            // NOT override it when `query` is set (unlike queryFiles which
            // DOES override). So passing the browse sort would run a trend
            // (queryType=3) or popularity (queryType=9) query with searchText
            // — NOT a text-search ranking — Steam would either return the
            // trend/popularity results ignoring the search text entirely, or
            // return an empty result set. Passing MOST_RELEVANT makes the
            // JavaSteam path query with RankedByTextSearch as intended.
            //
            // queryFiles() (HTTP API) ALREADY overrides to MOST_RELEVANT
            // when query != null (see SteamApiService.queryFiles line ~366:
            //   `val effectiveQueryType = if (query != null)
            //    Sort.MOST_RELEVANT.queryType else sort.queryType`),
            // so passing MOST_RELEVANT to it is technically redundant but
            // documents the intent clearly.
            //
            // searchWorkshop (HTML) ignores the `sort` argument entirely
            // (it always passes Sort.MOST_RELEVANT to its internal
            // fetchWorkshopIds call) — passing MOST_RELEVANT here is a no-op
            // but keeps the call site self-documenting.
            val javaSteamResult = steamApi.queryFilesViaJavaSteam(
                sort = SteamApiService.Sort.MOST_RELEVANT,
                page = page,
                tags = tags,
                excludedTags = excludedTags,
                mobileCompatible = mobileCompat,
                query = query,
            )

            // Track which backend actually served this page so we can log
            // it in the "done" line — mirrors the "loadNextPage: JavaSteam
            // path failed" warning pattern in the browse fallback chain.
            val finalResult: Result<List<WorkshopItemTyped>>
            val servedBy: String

            if (javaSteamResult.isSuccess) {
                finalResult = javaSteamResult
                servedBy = "javasteam"
            } else {
                // Log the failure then fall through to HTTP API + HTML fallbacks.
                // Mirrors the existing "loadNextPage: JavaSteam path failed"
                // warning in the browse fallback chain (see loadNextPageLocked).
                WepWawetLogger.workshopWarn(
                    "loadNextSearchPage: JavaSteam path failed — falling back to HTTP API",
                    "page" to page,
                    "query" to query.take(80),
                    "exception" to (javaSteamResult.exceptionOrNull()?.javaClass?.name ?: "unknown"),
                    "message" to (javaSteamResult.exceptionOrNull()?.message ?: ""),
                )
                // FALLBACK 1: IPublishedFileService HTTP API. Already overrides
                // query_type to MOST_RELEVANT when query != null — so the sort
                // we pass here is effectively a no-op for the query_type, but
                // the trend_days branch (`if (sort.queryType == 3 && sort.trendDays > 0)`)
                // is skipped because MOST_RELEVANT.queryType == 12 (not 3), so
                // no `&days=` param is appended (correct — search has no trend).
                val httpResult = steamApi.queryFiles(
                    sort = SteamApiService.Sort.MOST_RELEVANT,
                    page = page,
                    tags = tags,
                    excludedTags = excludedTags,
                    mobileCompatible = mobileCompat,
                    query = query,
                )

                if (httpResult.isSuccess) {
                    finalResult = httpResult
                    servedBy = "http_api"
                } else {
                    // FALLBACK 2 (last resort): HTML scraping endpoint
                    // (steamcommunity.com/workshop/browse with searchtext=).
                    // This is the ONLY path the previous implementation used;
                    // it remains as the final fallback when both JavaSteam
                    // and the HTTP API are unavailable (no API key, Steam
                    // down, user not logged in to JavaSteam session, etc.).
                    WepWawetLogger.workshopWarn(
                        "loadNextSearchPage: HTTP API path failed — falling back to HTML scrape",
                        "page" to page,
                        "query" to query.take(80),
                        "exception" to (httpResult.exceptionOrNull()?.javaClass?.name ?: "unknown"),
                        "message" to (httpResult.exceptionOrNull()?.message ?: ""),
                    )
                    finalResult = steamApi.searchWorkshop(
                        query = query,
                        page = page,
                        tags = tags,
                        excludedTags = excludedTags,
                        mobileCompatible = mobileCompat,
                    )
                    servedBy = "html_scrape"
                }
            }

            val items = finalResult.getOrElse { e ->
                _searchState.value = BrowseState.Error(
                    e.message ?: "Unknown error", page
                )
                WepWawetLogger.workshopWarn("loadNextSearchPage failed",
                    "page" to page,
                    "served_by" to servedBy,
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
                return
            }

            if (items.isEmpty()) {
                // Steam returned 0 items for this page — true end of results.
                // (Distinct from the "items came back but client-side filters
                // stripped them all" case, handled below — that case keeps
                // loading the next page up to MAX_CONSECUTIVE_EMPTY_PAGES.)
                searchHasMore = false
                _searchState.value = BrowseState.Done
                WepWawetLogger.workshop("loadNextSearchPage: end of results",
                    "page" to page, "served_by" to servedBy)
                return
            }

            // Apply the same client-side filtering + sorting pipeline as browse
            val filteredItems = if (filter != null) {
                applyClientFilters(items, filter)
            } else {
                // No filter context — still apply mobile-compatible filter by default
                applyClientFilters(items, Filter())
            }

            // v0.7.1: Track consecutive empty SEARCH pages — same pattern as
            // browse. When the API returns 30 items but applyClientFilters
            // strips them all (e.g. mobileCompatible=true + 18 default-
            // excluded resolution tags + age-rating exclusion → all 30 items
            // have an excluded tag), keep loading the next page up to
            // MAX_CONSECUTIVE_EMPTY_PAGES (20) consecutive empties before
            // giving up. This prevents the search from stopping on page 1
            // when the user's filters are restrictive but matching items
            // exist further along.
            //
            // The counter is SEARCH-SPECIFIC (consecutiveEmptySearchPages,
            // not the shared consecutiveEmptyPages) so a long restrictive
            // BROWSE session doesn't bleed into search and vice versa.
            // Reset in startSearch/clearSearch/refresh()/loadPage() — every
            // entry point that resets currentSearchQuery/currentSearchPage.
            if (filteredItems.isEmpty()) {
                consecutiveEmptySearchPages++
                if (consecutiveEmptySearchPages >= MAX_CONSECUTIVE_EMPTY_PAGES) {
                    searchHasMore = false
                    _searchState.value = BrowseState.Done
                    WepWawetLogger.workshop(
                        "loadNextSearchPage: max consecutive empty pages reached, giving up",
                        "page" to page,
                        "consecutive_empty" to consecutiveEmptySearchPages,
                        "max" to MAX_CONSECUTIVE_EMPTY_PAGES,
                        "served_by" to servedBy)
                    return
                }
                // Still advance the page counter + set Loaded state so the
                // UI knows it can trigger the next page load (the next
                // loadNextSearchPage call will increment currentSearchPage
                // from `page` to `page + 1` and try again).
                currentSearchPage = page
                _searchState.value = BrowseState.Loaded(page, searchHasMore)
                WepWawetLogger.workshop(
                    "loadNextSearchPage: empty after client filter, continuing",
                    "page" to page,
                    "consecutive_empty" to consecutiveEmptySearchPages,
                    "filtered_out" to items.size,
                    "served_by" to servedBy)
                return
            } else {
                // Non-empty page — reset the counter.
                consecutiveEmptySearchPages = 0
            }

            filteredItems.forEach { detailCache[it.id] = it }

            // Resolve creator names for the new search items too.
            // v0.7.4: fire-and-forget on the app scope, matching the browse
            // path (loadNextPageLocked). It previously ran INLINE while
            // holding browseLock — resolveCreatorsForItems performs network
            // calls (GetPlayerSummaries with 100ms inter-chunk delays), so a
            // search result could hold the lock for seconds, serializing
            // every other browse/search/refresh/loadPage behind it.
            WepWawetApplication.appScope.launch {
                resolveCreatorsForItems(filteredItems)
            }

            _searchItems.value = (_searchItems.value + filteredItems).distinctBy { it.id }
            currentSearchPage = page
            _searchState.value = BrowseState.Loaded(page, searchHasMore)
            WepWawetLogger.workshop("loadNextSearchPage done",
                "page" to page,
                "new_items" to filteredItems.size,
                "filtered_out" to (items.size - filteredItems.size),
                "total_items" to _searchItems.value.size,
                "served_by" to servedBy)
        } catch (e: CancellationException) {
            // See loadNextPageLocked for rationale — cancellation must propagate.
            WepWawetLogger.workshopDebug("loadNextSearchPage cancelled (coroutine scope disposed)",
                "page" to currentSearchPage)
            throw e
        } catch (e: Exception) {
            _searchState.value = BrowseState.Error(e.message ?: "Unknown error", currentSearchPage)
            WepWawetLogger.workshopWarn("loadNextSearchPage exception",
                "page" to currentSearchPage, "exception" to e.javaClass.name)
        }
    }

    // ====== Subscriptions ======

    /**
     * Add a subscription locally — the actual Steam-side subscription is initiated by
     * emulated Steam client via `steam://subscribe/<id>`.
     */
    suspend fun subscribe(itemId: String) {
        // v0.7.4: atomic update — the old read-modify-write
        // (`value = value + itemId`) lost concurrent subscriptions.
        _subscribedIds.update { it + itemId }
        WepWawetLogger.workshop("subscribe", "id" to itemId)
    }

    suspend fun unsubscribe(itemId: String) {
        _subscribedIds.update { it - itemId }
        WepWawetLogger.workshop("unsubscribe", "id" to itemId)
    }

    fun isSubscribed(itemId: String): Boolean = itemId in _subscribedIds.value

    // ====== Lookup ======

    /**
     * Get a single workshop item by ID.
     * Cache-first: try the in-memory detail cache, then the current browse/search
     * session, then fall back to a fresh API call.
     */
    suspend fun getById(id: String): WorkshopItemTyped? {
        // v0.7.0: Fix descriptions — the browse/search cache items only carry
        // the SHORT description (Steam's return_short_description=true). Many
        // workshop items have empty short descriptions even when their full
        // description is populated. When a cached item has an empty/very short
        // description, we fetch the FULL description via getPublishedFileDetails
        // and update the cache. This ensures the detail screen always shows the
        // real description instead of "No description available."
        detailCache[id]?.let {
            // If the cached description is too short, refresh it
            if (it.description.length < 10) {
                WepWawetLogger.workshop("getById refreshing short description", "id" to id)
                // v0.7.24 (BUG 26 from v0.7.22 audit): was runCatching — it
                // catches CancellationException (extends Throwable), so
                // cancelling the calling coroutine (back-navigation from the
                // detail screen) was swallowed and converted to a null
                // return. Explicit try/catch re-throws cancellation.
                try {
                    val fresh = steamApi.getPublishedFileDetails(listOf(id)).getOrThrow().firstOrNull()
                    if (fresh != null && fresh.description.length > it.description.length) {
                        detailCache[id] = fresh
                        resolveCreatorsForItems(listOf(fresh))
                        return fresh
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // refresh failed — fall through to the cached value
                }
            }
            WepWawetLogger.workshopDebug("getById cache hit", "id" to id)
            return it
        }
        _browseItems.value.firstOrNull { it.id == id }?.let {
            // If the browse item's description is too short, fetch full details
            if (it.description.length < 10) {
                WepWawetLogger.workshop("getById fetching full description for browse item", "id" to id)
                // v0.7.24 (BUG 26 from v0.7.22 audit): was runCatching — it
                // catches CancellationException (extends Throwable), so
                // cancelling the calling coroutine (back-navigation from the
                // detail screen) was swallowed and converted to a null
                // return. Explicit try/catch re-throws cancellation.
                try {
                    val fresh = steamApi.getPublishedFileDetails(listOf(id)).getOrThrow().firstOrNull()
                    if (fresh != null && fresh.description.length > it.description.length) {
                        detailCache[id] = fresh
                        resolveCreatorsForItems(listOf(fresh))
                        return fresh
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // refresh failed — fall through to the cached value
                }
            }
            detailCache[id] = it
            return it
        }
        _searchItems.value.firstOrNull { it.id == id }?.let {
            if (it.description.length < 10) {
                WepWawetLogger.workshop("getById fetching full description for search item", "id" to id)
                // v0.7.24 (BUG 26 from v0.7.22 audit): was runCatching — it
                // catches CancellationException (extends Throwable), so
                // cancelling the calling coroutine (back-navigation from the
                // detail screen) was swallowed and converted to a null
                // return. Explicit try/catch re-throws cancellation.
                try {
                    val fresh = steamApi.getPublishedFileDetails(listOf(id)).getOrThrow().firstOrNull()
                    if (fresh != null && fresh.description.length > it.description.length) {
                        detailCache[id] = fresh
                        resolveCreatorsForItems(listOf(fresh))
                        return fresh
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // refresh failed — fall through to the cached value
                }
            }
            detailCache[id] = it
            return it
        }
        // Fresh fetch
        // v0.7.24 (BUG 26): was runCatching — see the cached-path fix above;
        // the same CancellationException swallow applied to this fetch.
        WepWawetLogger.workshop("getById fresh fetch", "id" to id)
        return try {
            val typed = steamApi.getPublishedFileDetails(listOf(id)).getOrThrow().firstOrNull()
            if (typed != null) {
                detailCache[id] = typed
                // Resolve creator name for this item too
                resolveCreatorsForItems(listOf(typed))
            }
            typed
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WepWawetLogger.workshopWarn("getById failed",
                "id" to id, "exception" to e.javaClass.name)
            null
        }
    }

    // ====== Client-side filtering / sorting helpers ======

    /**
     * Apply client-side filters and sorts to a freshly-fetched page of items.
     *
     * Pipeline (applied in order):
     *   1. **Web/Application type filter** — ALWAYS drop Web and Application
     *      types. These can never work on WE Android (Web uses HTML/CSS/JS
     *      which the WE Android renderer doesn't support; Application is
     *      interactive EXE-based which Android can't run). All other types
     *      (Scene, Video, Image, Playlist, Unknown) are kept — they CAN be
     *      packed into a .mpkg by MpkgPacker (Image-type gets packed as a
     *      Scene wallpaper via packSceneWallpaper; Playlist/Unknown are
     *      rare edge cases handled case-by-case).
     *   2. **Excluded-tags filter** — drop items whose tag list contains ANY
     *      of [Filter.excludedTags]. (Steam's `excludedtags[]` URL param
     *      applies server-side for browse/search, but creator-workshop URLs
     *      don't support it, so we re-apply client-side to be safe.)
     *   3. **Required-tags filter** — drop items that don't have ALL of
     *      [Filter.tags]. (Steam already applies this server-side for
     *      browse/search, but again, creator-workshop URLs don't support it.)
     *   4. **Client-side sort** — if [SteamApiService.Sort.isClientSide] is
     *      true, re-sort the result set by the appropriate metric descending.
     *
     * === v0.5.10-alpha REGRESSION FIX (re-applied in v0.5.11-alpha after
     *     a parallel agent reset dev to v0.5.9-alpha state) ===
     * Previously this function ALSO applied a client-side "mobile-compatible"
     * filter (drop items where WorkshopTags.isMobileCompatible(type)
     * returned false — i.e., everything except Scene/Video). That filter was
     * OVER-AGGRESSIVE: the Steam API already filters by the AUTHOR's
     * mobile-compatible permission flag via the
     * `required_kv_tags[app_workshop_eula_version]=3` URL param (set when
     * mobileCompatible=true). The author flag is the SAME logic WE PC uses
     * (canUploadWallpaperToMobileDevice). The client-side type check was
     * redundant AND wrong — it dropped Image-type wallpapers that the author
     * had explicitly marked as mobile-compatible. Result: with
     * mobileCompatible=true + 18 default-excluded resolution tags, only
     * ~4 items survived per 30-item API page ("loadNextPage done page=1
     * new_items=4 filtered_out=26 total_items=4" per the user's log).
     *
     * User report: "the browser is only showing 4. there is an issue.
     * there should be way more. it did not do this in previous versions."
     *
     * Fix: REMOVE the client-side mobileCompatible type filter. The
     * mobileCompatible toggle now ONLY controls the API URL param
     * (which is what the user means by "marked like the real wallpaper
     * engine").
     */
    private fun applyClientFilters(
        items: List<WorkshopItemTyped>,
        filter: Filter,
    ): List<WorkshopItemTyped> {
        var filtered = items

        // 1. ALWAYS filter out Web and Application types (cannot work on WE Android)
        //    This is independent of the mobileCompatible toggle — even if the
        //    author marked them as mobile-compatible, the WE Android renderer
        //    cannot run Web (HTML/CSS/JS) or Application (EXE-based) wallpapers.
        //    All other types (Scene, Video, Image, Playlist, Unknown) are kept
        //    — MpkgPacker.packSceneWallpaper can pack any of these into a
        //    .mpkg the WE Android renderer can decode.
        val beforeAll = filtered.size
        filtered = filtered.filter {
            it.type != WallpaperType.WEB && it.type != WallpaperType.APPLICATION
        }
        if (beforeAll != filtered.size) {
            WepWawetLogger.workshopDebug("applyClientFilters web_app_excluded",
                "before" to beforeAll,
                "after" to filtered.size,
                "dropped" to (beforeAll - filtered.size))
        }

        // (v0.5.10-alpha: removed the redundant client-side mobileCompatible
        //  type filter here — the API URL param required_kv_tags
        //  [app_workshop_eula_version]=3 already filters by the AUTHOR's
        //  mobile-compatible permission flag server-side. Re-applying a
        //  stricter type check client-side dropped Image-type wallpapers
        //  that the author had explicitly marked as mobile-compatible,
        //  matching neither WE PC's logic nor the user's expectation.
        //  See the function docstring above for full regression history.)

        // 2. Excluded-tags filtering (NOT logic)
        if (filter.excludedTags.isNotEmpty()) {
            val excludedLower = filter.excludedTags.map { it.lowercase() }.toSet()
            val before = filtered.size
            filtered = filtered.filter { typed ->
                val tagSetLower = typed.tags.map { it.lowercase() }.toSet()
                // Keep the item if NONE of its tags are in the excluded set
                tagSetLower.intersect(excludedLower).isEmpty()
            }
            if (before != filtered.size) {
                WepWawetLogger.workshopDebug("applyClientFilters excluded_tags",
                    "before" to before,
                    "after" to filtered.size,
                    "dropped" to (before - filtered.size),
                    "excluded" to filter.excludedTags.joinToString(","))
            }
        }

        // 3. Required-tags filtering (AND logic) — only meaningful for
        // creator-workshop fetches (browse/search already apply this server-side)
        if (filter.creatorSteamId != null && filter.tags.isNotEmpty()) {
            val requiredLower = filter.tags.map { it.lowercase() }.toSet()
            val before = filtered.size
            filtered = filtered.filter { typed ->
                val tagSetLower = typed.tags.map { it.lowercase() }.toSet()
                requiredLower.intersect(tagSetLower).size == requiredLower.size
            }
            if (before != filtered.size) {
                WepWawetLogger.workshopDebug("applyClientFilters required_tags",
                    "before" to before,
                    "after" to filtered.size,
                    "dropped" to (before - filtered.size),
                    "required" to filter.tags.joinToString(","))
            }
        }

        // 4. No client-side sorting — all sorting is server-side via
        //    IPublishedFileService/QueryFiles (when API key is set) or
        //    HTML browse (trend/toprated/mostrecent). The Sort enum no
        //    longer has isClientSide — Most Up Votes and Most Subscriptions
        //    use query_type=10 and 12 server-side.

        return filtered
    }

    // ====== Creator name resolution ======

    /**
     * Resolve the display name for every distinct creator Steam ID64 found in
     * [items]. Updates [creatorNames] as each lookup completes.
     *
     * === v0.5.11-alpha: switched from per-ID HTML scraping to batched
     * GetPlayerSummaries API ===
     *
     * Previously: dispatched up to 8 PARALLEL HTTP calls to
     * steamcommunity.com/profiles/<steamId> per page (one per creator).
     * Steam rate-limited these HTML-scraping requests almost immediately
     * (HTTP 429) when N > 4 — user's log showed EVERY creator name
     * resolution failing with 429, cache_size=0, all creator names showing
     * as raw steam IDs.
     *
     * Now: SINGLE API call to ISteamUser/GetPlayerSummaries/v2/ with up
     * to 100 steam IDs per request. Steam's rate limit for this official
     * API endpoint is much higher (~10 req/sec vs ~4 req/min for profile
     * HTML scraping). The result is cached both in-memory (SteamApiService.
     * creatorNameCache) AND persistently (SharedPreferences
     * "wepwawet_creator_names") so future app launches don't need to
     * re-resolve previously-seen creators.
     *
     * Falls back to per-ID HTML scraping if no API key is set.
     */
    private suspend fun resolveCreatorsForItems(items: List<WorkshopItemTyped>) {
        // Use `creator` (the Steam ID64 returned by the API) — NOT `creatorSteamId`
        // which is a different field that may not exist in the QueryFiles response.
        val steamIds = items
            .map { it.creator }
            .filter { it.isNotBlank() && it.length >= 10 }
            .distinct()
        if (steamIds.isEmpty()) return

        // Snapshot the currently-resolving set under the lock to dedupe
        // concurrent calls — a steamId already in `resolvingIds` is in flight.
        val toResolve = synchronized(resolvingIds) {
            steamIds.filter { it !in resolvingIds }.also { newIds ->
                resolvingIds.addAll(newIds)
            }
        }
        if (toResolve.isEmpty()) {
            WepWawetLogger.workshopDebug("resolveCreatorsForItems no new ids",
                "ids" to steamIds.size)
            return
        }

        WepWawetLogger.workshop("resolveCreatorsForItems start",
            "total_distinct" to steamIds.size,
            "to_resolve" to toResolve.size)
        try {
            // v0.5.11-alpha: SINGLE batched API call (chunks of 100 IDs)
            // instead of N parallel per-ID HTML scraping calls.
            val results = steamApi.getPlayerSummaries(toResolve).getOrDefault(emptyMap())
            // Merge results into the StateFlow snapshot
            // v0.7.4: atomic update — the old read-modify-write on
            // _creatorNames.value lost concurrent merges when disjoint ID
            // sets were resolved simultaneously.
            if (results.isNotEmpty()) {
                _creatorNames.update { existing ->
                    val merged = existing.toMutableMap()
                    for ((steamId, name) in results) {
                        merged[steamId] = name
                    }
                    merged
                }
            }
            WepWawetLogger.workshop("resolveCreatorsForItems done",
                "looked_up" to toResolve.size,
                "resolved" to results.size,
                "fallback" to (toResolve.size - results.size),
                "cache_size" to _creatorNames.value.size)
        } finally {
            // Clear the in-flight marker — the SteamApiService cache is the
            // authoritative dedup; this local set just prevents re-dispatching
            // the same ID during a long-running resolve.
            synchronized(resolvingIds) {
                resolvingIds.removeAll(toResolve.toSet())
            }
        }
    }

    // ====== Types ======

    /**
     * Active filter for the browse session.
     *
     * v0.5.1 OVERHAUL:
     *   - **Removed** the `maturity` and `resolution` fields. They're now
     *     expressed through `excludedTags` — the Filter bottom sheet shows
     *     ALL tags (including maturity/resolution) as toggleable chips, all ON
     *     by default. Toggling one OFF adds it to `excludedTags`.
     *   - **Renamed** `mobileOnly` → `mobileCompatible`. Default still `true`.
     *   - **Added** `creatorSteamId` to scope the browse to a single creator's
     *     workshop page (used when the user taps a creator name on a tile).
     *
     * @param query optional free-text search query — if set, routes to searchWorkshop
     * @param tags list of required tags (AND logic) — e.g. ["Scene", "Anime"].
     *   Applied server-side via `requiredtags[]` for browse/search; client-side
     *   for creator-workshop fetches.
     * @param excludedTags list of tags to exclude — items with ANY of these
     *   tags are hidden. Applied server-side via `excludedtags[]` for
     *   browse/search; client-side for creator-workshop fetches.
     * @param mobileCompatible if true, adds kvs[app_workshop_eula_version]=3
     *   to the Steam query (author permission flag, NOT a type check).
     *   Also excludes Application and Web tags. Default: true.
     * @param sort browse sort mode. Default: MOST_POPULAR_YEAR (trend&days=365).
     *   The [SteamApiService.Sort.trendDays] field is used for trend-based sorts.
     *   [SteamApiService.Sort.isClientSide] sorts fetch using toprated and re-sort
     *   client-side.
     * @param creatorSteamId optional Steam ID64 — when set, fetches from creator's
     *   /profiles/<id>/myworkshop/ page instead of the global browse.
     */
    data class Filter(
        val query: String? = null,
        val tags: List<String> = emptyList(),
        val excludedTags: List<String> = emptyList(),
        val mobileCompatible: Boolean = true,
        val sort: SteamApiService.Sort = SteamApiService.Sort.MOST_POPULAR_YEAR,
        val creatorSteamId: String? = null,
    )

    /**
     * Browse/search state machine.
     *
     * - [Idle]: no session active
     * - [Loading]: page N loading
     * - [Loaded]: page N loaded, hasMore flag indicates if more pages exist
     * - [Error]: page N errored (errorMessage)
     * - [Done]: no more results (end of list)
     */
    sealed class BrowseState {
        data object Idle : BrowseState()
        data class Loading(val page: Int) : BrowseState()
        data class Loaded(val page: Int, val hasMore: Boolean) : BrowseState()
        data class Error(val message: String, val page: Int) : BrowseState()
        data object Done : BrowseState()
    }
}
