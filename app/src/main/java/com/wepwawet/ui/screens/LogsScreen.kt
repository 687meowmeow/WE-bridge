package com.wepwawet.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wepwawet.log.LogEntry
import com.wepwawet.log.PublicDownloadsWriter
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.ui.components.SlimTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events = remember { mutableStateListOf<LogEntry>() }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Initial load from the ring buffer snapshot
        events.clear()
        events.addAll(WepWawetLogger.snapshot())
        // Incremental updates — only new entries trigger recomposition.
        // Replaces the previous 500ms full-snapshot polling which caused
        // full-LazyColumn recomposition twice per second.
        WepWawetLogger.events.collect { entry ->
            events.add(entry)
            // v0.7.21: Increased from 2000 to 5000 to match RING_BUFFER_CAPACITY.
            while (events.size > 5000) events.removeAt(0)
        }
    }

    fun copyLogs() {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
        val text = events.reversed().joinToString("\n") { entry ->
            buildString {
                append("[${formatter.format(entry.timestamp)}] ")
                append("[${entry.domain.tag}] ${entry.level.name}: ${entry.message}")
                if (entry.context.isNotEmpty()) {
                    append(" ")
                    append(entry.context.entries.joinToString(" ") { "${it.key}=${it.value ?: "null"}" })
                }
                if (entry.exceptionStackTrace != null) {
                    append("\n")
                    append(entry.exceptionStackTrace.take(500))
                }
            }
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WepWawet Logs", text))
        Toast.makeText(context, "Copied ${events.size} log entries", Toast.LENGTH_SHORT).show()
    }

    /**
     * Download the full log buffer to the public Downloads/WepWawet/
     * directory as a timestamped .txt file. Uses MediaStore on Android 10+
     * (no permission needed). The file appears in the user's Downloads
     * folder immediately — they can attach it to bug reports or share it.
     */
    fun downloadLogs() {
        if (isDownloading) return
        isDownloading = true
        downloadStatus = "Writing ${events.size} log entries to Downloads/WepWawet/…"
        val snapshot = events.toList()
        val timestamp = ZonedDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        )
        val fileName = "wepwawet-logs-$timestamp.txt"
        // Build the log text on a background thread — for thousands of entries
        // this could be slow on the UI thread.
        scope.launch(Dispatchers.IO) {
            try {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault())
                val sb = StringBuilder(snapshot.size * 200)  // ~200 bytes per entry estimate
                sb.append("=== WepWawet log dump ===\n")
                sb.append("Generated: ").append(ZonedDateTime.now()).append("\n")
                sb.append("Entries: ").append(snapshot.size).append("\n")
                sb.append("\n")
                for (entry in snapshot) {  // snapshot is in oldest-first order
                    sb.append("[${formatter.format(entry.timestamp)}] ")
                    sb.append("[${entry.domain.tag}] ${entry.level.name}: ${entry.message}")
                    if (entry.context.isNotEmpty()) {
                        sb.append(" ")
                        sb.append(entry.context.entries.joinToString(" ") { "${it.key}=${it.value ?: "null"}" })
                    }
                    if (entry.exceptionStackTrace != null) {
                        sb.append("\n")
                        sb.append(entry.exceptionStackTrace)
                    }
                    sb.append("\n")
                }
                val text = sb.toString()
                val uri = PublicDownloadsWriter.writeText(context, fileName, text)
                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        downloadStatus = "Saved ${snapshot.size} entries to Downloads/WepWawet/$fileName"
                        Toast.makeText(context,
                            "Saved to Downloads/WepWawet/$fileName",
                            Toast.LENGTH_LONG).show()
                        WepWawetLogger.appInfo("LogsScreen.downloadLogs success",
                            "file" to fileName,
                            "bytes" to text.length,
                            "entries" to snapshot.size)
                    } else {
                        downloadStatus = "Failed to write log file (see logcat)"
                        Toast.makeText(context, "Failed to write log file", Toast.LENGTH_LONG).show()
                    }
                    isDownloading = false
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // v0.7.23 BUG LS-1: re-throw CancellationException — was
                // swallowed by catch (e: Exception) which showed a spurious
                // "Error" toast when the user navigated away mid-download.
                isDownloading = false
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    downloadStatus = "Error: ${e.message}"
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    WepWawetLogger.appError("LogsScreen.downloadLogs failed",
                        "exception" to e.javaClass.name,
                        "message" to (e.message ?: ""),
                        exception = e)
                    isDownloading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            SlimTopBar(
                title = "Logs (${events.size})",
                onBack = onBack,
                actions = {
                    // Download logs to Downloads/WepWawet/
                    IconButton(
                        onClick = { downloadLogs() },
                        enabled = !isDownloading && events.isNotEmpty(),
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp).size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Filled.Download, contentDescription = "Download logs")
                        }
                    }
                    IconButton(onClick = { copyLogs() }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = {
                        WepWawetLogger.clear()
                        events.clear()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Status message after downloadLogs (fades away after 5s)
            downloadStatus?.let { status ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        status,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LaunchedEffect(status) {
                    delay(5000)
                    downloadStatus = null
                }
            }
            if (events.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text(
                        "No logs yet. Start the pairing proxy from the Pairing tab to see protocol messages.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(events.reversed(), key = { it.seq }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val levelColor = when (entry.level) {
        WepWawetLogger.Level.TRACE, WepWawetLogger.Level.DEBUG -> Color(0xFF6B7280)
        WepWawetLogger.Level.INFO -> Color(0xFFE6E6E9)
        WepWawetLogger.Level.WARN -> Color(0xFFFFB72E)
        WepWawetLogger.Level.ERROR -> Color(0xFFFF6B5C)
        WepWawetLogger.Level.FATAL -> Color(0xFFFFB72E)
    }
    val timeStr = remember(entry.seq) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
        formatter.format(entry.timestamp)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (entry.level) {
                WepWawetLogger.Level.ERROR, WepWawetLogger.Level.FATAL -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = timeStr,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = entry.domain.tag,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = entry.level.name,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = entry.message,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (entry.context.isNotEmpty()) {
                Text(
                    text = entry.context.entries.joinToString(" ") { "${it.key}=${it.value ?: "null"}" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (entry.exceptionStackTrace != null) {
                Text(
                    text = entry.exceptionStackTrace.take(2000),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
