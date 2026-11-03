package com.wepwawet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wepwawet.data.DownloadManager
import com.wepwawet.data.model.WorkshopItemTyped
import com.wepwawet.data.model.WorkshopTags
import com.wepwawet.log.WepWawetLogger

/**
 * Grid tile card for a [WorkshopItemTyped].
 *
 * Replaces the old horizontal-row [WallpaperCard] with a square-ish grid tile
 * matching Steam Workshop's own browser layout. The card:
 *
 *   - Fills the available column width; height is fixed by [tileSize].
 *   - Shows the preview via [AsyncImage] (Coil auto-plays GIFs because
 *     [com.wepwawet.WepWawetApplication] registers a Gif Decoder in the
 *     shared ImageLoader).
 *   - Bottom gradient overlay carries the title (1 line, ellipsis) + subs count
 *     on the first row, and a clickable "by <creator>" line below.
 *   - **Mobile badge** (top-right): shown when the item is mobile-compatible
 *     (Scene/Video — see [WorkshopTags.isMobileCompatible]).
 *   - **Maturity badge** (top-left): colored dot — green = Everyone,
 *     yellow = Questionable, red = Mature, gray = Unrated.
 *   - **Download status**: if the item is being tracked by [DownloadManager],
 *     show a "Downloading…" overlay with progress; if it has been downloaded
 *     before, show a "✓" badge in the top-right.
 *
 * @param item the workshop item to render
 * @param tileSize size bucket — controls height + (with the grid) column count
 * @param creatorName the resolved display name for the creator (e.g.
 *   "GothSees"), or `null` if the resolution hasn't completed yet. v0.5.2:
 *   when `null`, the "by …" line is hidden entirely (no raw Steam ID
 *   fallback). When non-null, the line is shown and is clickable.
 * @param onClick invoked when the card body is tapped (opens the detail screen)
 * @param onCreatorClick invoked with the creator's Steam ID64 when the user
 *   taps the "by <creator>" line. Opens a filtered browse showing only that
 *   creator's items. No-op if the item has no creator Steam ID.
 */
@Composable
fun WallpaperCard(
    item: WorkshopItemTyped,
    tileSize: TileSize,
    creatorName: String?,
    onClick: () -> Unit,
    onCreatorClick: (String) -> Unit,
) {
    // Subscribe to download manager state so the card updates live when
    // a download starts/finishes for this item.
    // v0.7.24 (H-U2 BUG FIX): was `activeDownloads.collectAsState()` +
    // `completedDownloads.collectAsState()` — every card collected the WHOLE
    // map/list, so one progress tick from ANY download recomposed EVERY card
    // in the grid (30 cards x 10+ ticks/sec). The per-item derived flows only
    // emit when THIS item's entry changes.
    // v0.7.24 (H-U2 + smart-cast): collect into LOCAL vals — delegated
    // State properties can't be smart-cast downstream.
    val activeDownloadState = DownloadManager.activeDownloadFor(item.id).collectAsState()
    val isCompletedState = DownloadManager.isCompletedFor(item.id).collectAsState()
    val activeDownload = activeDownloadState.value
    val isCompleted = isCompletedState.value

    val maturity = WorkshopTags.getMaturity(item.tags)
    val isMobile = WorkshopTags.isMobileCompatible(item.type)

    // The display name to show in the "by …" line. Preference order:
    //   1. resolved creatorName passed in from the repo's creatorNames cache
    //      (this is the user-friendly display name like "GothSees")
    //   2. item.creator (sometimes populated by Steam — usually empty)
    //
    // v0.5.2 — the raw creator_steam_id (17-digit Steam ID64) is NO LONGER
    // shown as a fallback. The "by …" line is hidden entirely until the
    // repo's creatorNames cache has resolved a friendly display name for
    // this creator. The clickable behavior is preserved when a creator
    // ID is available (the resolution can complete after the card
    // is first rendered — tapping still works because we use
    // `item.creator` directly for the click handler, not the displayed
    // name). We use `item.creator` (always populated by Steam) rather than
    // `item.creatorSteamId` (null for HTTP-API-sourced items) so the click
    // works regardless of which code path produced the item.
    val creatorId = item.creator
    val displayName: String? = when {
        !creatorName.isNullOrBlank() -> creatorName
        item.creator.isNotBlank() -> item.creator
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(tileSize.cardHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1F1F28))
            .clickable { onClick() },
    ) {
        // === Preview image (Coil auto-plays GIFs) ===
        val previewUrl = item.previewUrl
        if (previewUrl != null) {
            AsyncImage(
                model = previewUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Fallback when no preview URL — show a centered type label
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.type.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // === Bottom gradient + title + subs + creator ===
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.title,
                    style = if (tileSize == TileSize.COMPACT) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = item.subscriptionsFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
            // "by <creator>" — clickable when a creator steam ID is available
            if (displayName != null) {
                val clickableModifier = if (creatorId.isNotBlank()) {
                    Modifier.clickable {
                        WepWawetLogger.workshop(
                            "wallpaper_card_creator_tap",
                            "id" to item.id,
                            "creator_steam_id" to creatorId,
                            "creator_name" to displayName,
                        )
                        onCreatorClick(creatorId)
                    }
                } else {
                    Modifier
                }
                Text(
                    text = "by $displayName",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (creatorId.isNotBlank())
                        Color.White.copy(alpha = 0.95f)
                    else
                        Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (creatorId.isNotBlank())
                        TextDecoration.Underline
                    else TextDecoration.None,
                    modifier = clickableModifier,
                )
            }
        }

        // === Maturity badge (top-left colored dot) ===
        MaturityDot(
            maturity = maturity,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        )

        // === Mobile badge OR Completed badge (top-right) ===
        if (isCompleted) {
            CompletedBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        } else if (isMobile) {
            MobileBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
        }

        // === Active download overlay ===
        if (activeDownload != null) {
            DownloadingOverlay(
                progress = activeDownload.progress,
                message = activeDownload.statusMessage,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ====== Sub-composables ======

/**
 * Colored maturity dot. Green = Everyone, yellow = Questionable, red = Mature,
 * gray = Unrated.
 */
@Composable
private fun MaturityDot(
    maturity: WorkshopTags.Maturity,
    modifier: Modifier = Modifier,
) {
    val color = when (maturity) {
        WorkshopTags.Maturity.EVERYONE -> Color(0xFF4CAF50)
        WorkshopTags.Maturity.QUESTIONABLE -> Color(0xFFFFC107)
        WorkshopTags.Maturity.MATURE -> Color(0xFFE53935)
        WorkshopTags.Maturity.UNKNOWN -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Small phone icon badge — shown on mobile-compatible tiles.
 */
@Composable
private fun MobileBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Icon(
            Icons.Filled.PhoneAndroid,
            contentDescription = "Mobile compatible",
            tint = Color.White,
            modifier = Modifier
                .padding(3.dp)
                .size(10.dp),
        )
    }
}

/**
 * Green "✓" badge shown on tiles that have already been downloaded.
 */
@Composable
private fun CompletedBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(22.dp),
        color = Color(0xFF2E7D32),
        shape = CircleShape,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Downloaded",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Semi-transparent overlay + progress bar + status message, shown when this
 * item is currently being downloaded.
 */
@Composable
private fun DownloadingOverlay(
    progress: Float,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Downloading…",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
            )
            if (message.isNotBlank()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
