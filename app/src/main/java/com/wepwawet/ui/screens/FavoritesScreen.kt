package com.wepwawet.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wepwawet.data.SteamApiService
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar

/**
 * Favorites screen — list of saved creators with profile pictures.
 *
 * v0.7.1: Replaces the previous "Favorites coming soon" placeholder. Reads
 * the persisted set of saved-creator Steam IDs (stored under the
 * `saved_creators` StringSet key in the `wepwawet_settings` SharedPreferences
 * file — see [WallpaperDetailScreen]'s favorite-heart toggle) and renders
 * each as a Card row.
 *
 * Each row shows:
 *   - A 40dp circular profile picture (loaded via Coil from the Steam
 *     `avatarfull` URL, cached in [SteamApiService.creatorAvatarCache] as
 *     a side-effect of [SteamApiService.getPlayerSummaries]).
 *   - The creator's display name (resolved in bulk via
 *     [SteamApiService.getPlayerSummaries] on first entry — falls back to
 *     the raw Steam ID while loading).
 *   - The raw Steam ID below the name in small gray text (useful for
 *     debugging and for the case where name resolution failed).
 *   - A filled heart IconButton to unfavorite the creator (removes the
 *     ID from the StringSet).
 *   - The row itself is clickable to navigate to the creator's workshop
 *     page (`creator/{steamId}` route in [com.wepwawet.ui.WepWawetNavHost]).
 *
 * While names are being resolved (a single [LaunchedEffect] kicks off
 * [SteamApiService.getPlayerSummaries] for the whole saved set on first
 * composition), a centered "Loading…" indicator is shown.
 *
 * When the saved set is empty, shows a centered empty-state message.
 *
 * @param onBack invoked when the user taps the top-bar back arrow.
 * @param onCreatorClick invoked with a Steam ID64 when the user taps a row.
 *   Typically wires up to `nav.navigate("creator/$steamId")` in the NavHost.
 * @param steamApi shared [SteamApiService] used to bulk-resolve display
 *   names + cache avatar URLs.
 */
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onCreatorClick: (String) -> Unit,
    steamApi: SteamApiService,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("wepwawet_settings", Context.MODE_PRIVATE)
    }
    var savedCreators by remember {
        mutableStateOf(
            prefs.getStringSet("saved_creators", emptySet()) ?: emptySet()
        )
    }

    // Resolved display names (steamId -> displayName). Populated by the
    // LaunchedEffect below; falls back to the raw Steam ID while loading
    // or if the bulk resolve fails (rate limit, network, private profile).
    val resolvedNames = remember { mutableStateMapOf<String, String>() }
    var namesLoading by remember { mutableStateOf(false) }

    // Bulk-resolve display names + cache avatar URLs the moment we have a
    // non-empty saved set. Re-runs if the set changes (e.g. after unfav-
    // oriting one and the list still has others) so the new "top" entry
    // gets a name too. We deliberately key off the *list of IDs* (sorted
    // for stability) so Compose can deduplicate across recompositions —
    // a simple `true` key would re-run on every state change.
    val idsToResolve = savedCreators.toList().sorted()
    LaunchedEffect(idsToResolve) {
        if (idsToResolve.isEmpty()) return@LaunchedEffect
        namesLoading = true
        try {
            val result = steamApi.getPlayerSummaries(idsToResolve)
            val map = result.getOrNull().orEmpty()
            // Snapshot the avatar cache + name map for the UI thread.
            // getPlayerSummaries writes into creatorAvatarCache + creatorNameCache
            // as a side-effect, so even entries NOT present in the returned map
            // (private profiles / failed lookups) won't have an avatar — and the
            // name fallback (raw ID) is handled by the UI below.
            for (id in idsToResolve) {
                map[id]?.let { name -> resolvedNames[id] = name }
            }
            WepWawetLogger.workshop(
                "favorites_names_resolved",
                "requested" to idsToResolve.size,
                "resolved" to map.size,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v0.7.21 BUG FIX: re-throw CancellationException — was swallowed
            // by catch (e: Exception) which broke structured concurrency.
            throw e
        } catch (e: Exception) {
            WepWawetLogger.proxyWarn(
                "favorites_names_resolve_failed",
                "exception" to e.javaClass.name,
                "message" to (e.message ?: ""),
            )
        } finally {
            namesLoading = false
        }
    }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Favorites",
                onBack = onBack,
            )
        },
    ) { padding ->
        if (savedCreators.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No favorites yet. Tap the heart icon on a wallpaper to save creators.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (namesLoading && resolvedNames.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Sort so the order is stable across recompositions
                // (SharedPreferences StringSet iteration order is undefined).
                items(savedCreators.toSortedSet().toList()) { steamId ->
                    FavoritesRow(
                        steamId = steamId,
                        resolvedName = resolvedNames[steamId],
                        avatarUrl = steamApi.getCreatorAvatarUrl(steamId),
                        onCreatorClick = onCreatorClick,
                        onUnfavorite = {
                            val newSet = savedCreators.toMutableSet()
                            newSet.remove(steamId)
                            savedCreators = newSet
                            prefs.edit()
                                .putStringSet("saved_creators", newSet)
                                .apply()
                            WepWawetLogger.workshop(
                                "favorites_creator_removed",
                                "steam_id" to steamId,
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Single row in the Favorites list — a [Card] containing a 40dp circular
 * profile picture, the creator's name + ID, and a heart IconButton to
 * unfavorite.
 *
 * Extracted to its own composable so the parent [LazyColumn] can skip
 * recomposition for unchanged rows (a stable `key` isn't required since
 * the row is parameterless w.r.t. other rows).
 */
@Composable
private fun FavoritesRow(
    steamId: String,
    resolvedName: String?,
    avatarUrl: String?,
    onCreatorClick: (String) -> Unit,
    onUnfavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                WepWawetLogger.workshop(
                    "favorites_creator_tap",
                    "steam_id" to steamId,
                )
                onCreatorClick(steamId)
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Avatar (40dp circle). Falls back to a flat surfaceVariant
            // disc when the avatar URL isn't cached yet (e.g. private
            // profile, bulk resolve in-flight, or network failure).
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            // Name + ID column.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    resolvedName ?: steamId,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    steamId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Unfavorite button.
            IconButton(onClick = onUnfavorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Remove from favorites",
                    tint = Color.Red,
                )
            }
        }
    }
}
