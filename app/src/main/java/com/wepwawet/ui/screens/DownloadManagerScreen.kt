package com.wepwawet.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wepwawet.data.CompletedDownload
import com.wepwawet.data.DownloadManager
import com.wepwawet.data.DownloadProgress
import com.wepwawet.data.DownloadStatus
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Download manager screen.
 *
 * Lists:
 *   - **Active downloads**: each row has thumbnail + title + status message +
 *     progress bar + a Cancel button. Updates live as [DownloadManager.activeDownloads]
 *     emits new [DownloadProgress] states.
 *   - **Completed downloads**: each row has thumbnail + title + completion date +
 *     file size + device name + a Remove button. Status badge distinguishes
 *     Completed (green check) from Failed (red warning) from Cancelled (gray).
 *   - "Clear all completed" button in the top app bar when the completed list
 *     is non-empty.
 *
 * Empty state when neither list has any items.
 *
 * The screen is reached from the WorkshopBrowserScreen top app bar "Downloads"
 * icon — see [com.wepwawet.ui.WepWawetNavHost] for the navigation route.
 *
 * @param onBack invoked when the back arrow is tapped
 */
@Composable
fun DownloadManagerScreen(
    onBack: () -> Unit,
    onItemClick: (String) -> Unit = {},
) {
    val active by DownloadManager.activeDownloads.collectAsState()
    val completed by DownloadManager.completedDownloads.collectAsState()

    // v0.7.24 (M-U6): derivedStateOf — the sorted copy was recomputed on every
    // recomposition of the screen (not just when the active map changed).
    val activeList by remember { androidx.compose.runtime.derivedStateOf { active.values.toList().sortedBy { it.title } } }
    val completedList = completed // most-recent-first ordering enforced by DownloadManager

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Downloads",
                onBack = {
                    WepWawetLogger.workshop("download_manager back tapped")
                    onBack()
                },
                actions = {
                    if (completedList.isNotEmpty()) {
                        IconButton(onClick = {
                            WepWawetLogger.workshop(
                                "download_manager clear_all",
                                "completed_count" to completedList.size,
                            )
                            DownloadManager.clearCompleted()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear all completed")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (activeList.isEmpty() && completedList.isEmpty()) {
            EmptyDownloadsState(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeList.isNotEmpty()) {
                item {
                    SectionHeader(title = "Active (${activeList.size})")
                }
                items(activeList, key = { "active_${it.itemId}" }) { download ->
                    ActiveDownloadCard(download = download, onItemClick = onItemClick)
                }
            }

            if (completedList.isNotEmpty()) {
                item {
                    SectionHeader(title = "Completed (${completedList.size})")
                }
                items(completedList, key = { "completed_${it.itemId}" }) { download ->
                    CompletedDownloadCard(download = download, onItemClick = onItemClick)
                }
            }
        }
    }
}

// ====== Sub-composables ======

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    )
}

/**
 * Active download row card.
 *
 * Shows: thumbnail (if available) + title + status message + progress bar +
 * "Cancel" button.
 */
@Composable
private fun ActiveDownloadCard(download: DownloadProgress, onItemClick: (String) -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick(download.itemId) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DownloadThumbnail(
                previewUrl = download.previewUrl,
                modifier = Modifier.size(64.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = download.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { download.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${(download.progress * 100).toInt().coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = {
                WepWawetLogger.workshop(
                    "download_manager cancel",
                    "item_id" to download.itemId,
                )
                // v0.5.9-alpha (R8): cancel the underlying WorkshopDownloadJob
                // coroutine (running on WepWawetApplication.appScope) — otherwise
                // the JavaSteam download keeps going in the background even
                // after the user removes the entry from the active list.
                // v0.7.20: cancel the SPECIFIC item's job (was cancel() which
                // cancelled ALL concurrent downloads).
                com.wepwawet.pairing.WorkshopDownloadJob.cancel(download.itemId)
                DownloadManager.cancelDownload(download.itemId)
            }) {
                Icon(
                    Icons.Filled.Cancel,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Completed/Failed/Cancelled download row card.
 *
 * Shows: status icon + thumbnail + title + date + file size + device name +
 * "Remove" button.
 */
@Composable
private fun CompletedDownloadCard(download: CompletedDownload, onItemClick: (String) -> Unit = {}) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US) }
    Card(
        modifier = Modifier.fillMaxWidth()
            .clickable { onItemClick(download.itemId) },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DownloadThumbnail(
                previewUrl = download.previewUrl,
                modifier = Modifier.size(64.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(download.status, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = dateFormat.format(Date(download.completedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                val meta = buildString {
                    if (download.fileSizeBytes > 0) {
                        append(formatBytes(download.fileSizeBytes))
                    }
                    if (download.deviceName.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(download.deviceName)
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (download.status == DownloadStatus.FAILED && download.errorMessage != null) {
                    Text(
                        text = download.errorMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(onClick = {
                WepWawetLogger.workshop(
                    "download_manager remove_completed",
                    "item_id" to download.itemId,
                )
                DownloadManager.removeCompleted(download.itemId)
            }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Square thumbnail with a dark background fallback when [previewUrl] is null.
 */
@Composable
private fun DownloadThumbnail(
    previewUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1F1F28)),
        contentAlignment = Alignment.Center,
    ) {
        if (previewUrl != null) {
            AsyncImage(
                model = previewUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Status icon: green check for completed, red ! for failed, gray for cancelled.
 */
@Composable
private fun StatusIcon(status: DownloadStatus, modifier: Modifier = Modifier) {
    val (icon, color) = when (status) {
        DownloadStatus.COMPLETED -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        DownloadStatus.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        DownloadStatus.CANCELLED -> Icons.Filled.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        icon,
        contentDescription = status.name,
        tint = color,
        modifier = modifier,
    )
}

@Composable
private fun EmptyDownloadsState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Filled.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "No downloads yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Wallpapers you download will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ====== Helpers ======

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
