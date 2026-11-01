package com.wepwawet.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wepwawet.log.WepWawetLogger
import com.wepwawet.pairing.PairingService

/**
 * The WepWawet Pairing screen.
 *
 * v0.5.1: this screen no longer has Start / Stop proxy buttons. The pairing
 * proxy ALWAYS auto-starts in [com.wepwawet.MainActivity].onCreate via
 * [PairingService.start] — there's no user-facing decision to make.
 *
 * What the screen shows:
 *
 *   - A short instruction string telling the user what to do on the WE
 *     Android side
 *   - The current PIN in big bold type (refreshable via the "Refresh PIN"
 *     button — this re-invokes [PairingService.start] with the new PIN,
 *     which atomically stops the old servers and starts new ones)
 *   - A status card showing the protocol details (UDP port, TCP port, crypto)
 *
 * @param onBack Called when the user taps the back arrow in the TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // v0.7.0: Load the user's saved PIN from SharedPreferences (default "1234").
    // The user can change it in Settings or here on the Pairing screen.
    var pin by remember { mutableStateOf(PairingService.getPairingPin(context)) }

    // Best-effort: when the screen appears, re-issue PairingService.start
    // with our local PIN. This is a no-op if the service is already running
    // with the same PIN, and a clean restart otherwise. We deliberately do
    // NOT call PairingService.stop here — the proxy is meant to be always-on.
    DisposableEffect(Unit) {
        WepWawetLogger.lifecycle("PairingScreen appear — ensure proxy running",
            "pin" to pin)
        runCatching { PairingService.start(context, pin) }
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier
                    .statusBarsPadding()
                    .height(56.dp),
                title = { Text("Pairing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Pair your Wallpaper Engine Android app",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                "The pairing proxy is always running on UDP 7884 + TCP 7889.\n\n" +
                    "Open the Wallpaper Engine Android app.\n" +
                    "Either:\n" +
                    "  1. It should auto-discover 'WepWawet' — tap it\n" +
                    "  2. Or enter manual IP: 127.0.0.1\n\n" +
                    "Enter the PIN shown below when prompted.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // PIN display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = pin,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // v0.7.1: "Change PIN" button — opens a dialog with a text field.
            // Removed the inline text field and the "Random PIN" button.
            var showPinDialog by remember { mutableStateOf(false) }

            OutlinedButton(onClick = { showPinDialog = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Change PIN")
                Spacer(Modifier.width(8.dp))
                Text("Change PIN")
            }

            // PIN change dialog
            if (showPinDialog) {
                var dialogPin by remember { mutableStateOf(pin) }
                AlertDialog(
                    onDismissRequest = { showPinDialog = false },
                    title = { Text("Change PIN") },
                    text = {
                        OutlinedTextField(
                            value = dialogPin,
                            onValueChange = { newPin ->
                                if (newPin.all { it.isDigit() } && newPin.length <= 8) {
                                    dialogPin = newPin
                                }
                            },
                            label = { Text("Enter new PIN") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val pinToSave = dialogPin.ifBlank { "1234" }
                            WepWawetLogger.proxy("PairingScreen change pin", "pin" to pinToSave)
                            PairingService.setPairingPin(context, pinToSave)
                            pin = pinToSave
                            runCatching { PairingService.start(context, pinToSave) }
                            showPinDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showPinDialog = false }) { Text("Cancel") }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))

            // Status info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Server Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("• UDP 7884: Broadcasting WallpaperEngine/Ping")
                    Text("• TCP 7889: Listening for pairing connections")
                    Text("• RSA-1024 keypair generated (OAEP padding)")
                    Text("• AES-256-CBC + RSA/ECB/OAEPPadding")
                    Text("• PIN: $pin")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open the Wallpaper Engine Android app and enter the PIN above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
