package com.wepwawet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * v0.7.21: A slim (56dp + status bar) top app bar with properly vertically
 * centered title text.
 *
 * The Material 3 [androidx.compose.material3.TopAppBar] has an internal layout
 * that doesn't vertically center the title correctly at custom heights below
 * 64dp — the title appears shifted up. This composable uses a plain [Row]
 * with [Alignment.CenterVertically] to guarantee the title + navigation icon
 * + actions are all vertically centered regardless of the height.
 *
 * Usage:
 * ```
 * Scaffold(
 *     topBar = {
 *         SlimTopBar(
 *             title = "Settings",
 *             onBack = { nav.popBackStack() },
 *             actions = {
 *                 IconButton(onClick = { ... }) { Icon(...) }
 *             },
 *         )
 *     }
 * ) { padding -> ... }
 * ```
 */
@Composable
fun SlimTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left side: back button (if any) + title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Right side: actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}
