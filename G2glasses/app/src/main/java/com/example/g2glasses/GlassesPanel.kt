package com.example.g2glasses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.g2glasses.gateway.ConnectionPhase
import com.example.g2glasses.gateway.GatewayLogEntry
import com.example.g2glasses.gateway.GatewayState
import com.example.g2glasses.gateway.LensState
import com.example.g2glasses.glassesui.GlassesDisplay
import com.example.g2glasses.glassesui.RenderGovernor

/**
 * Glasses connection and test screen, modelled on the men-g2-ble-gateway browser UI: status,
 * a "fast text" test message, and the event log. Stateless: MainActivity owns the gateway.
 */
@Composable
fun GlassesPanel(
    state: GatewayState,
    log: List<GatewayLogEntry>,
    mirrorCaptions: Boolean,
    livePartials: Boolean,
    governorStats: RenderGovernor.Stats,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onMirrorCaptionsChange: (Boolean) -> Unit,
    onLivePartialsChange: (Boolean) -> Unit,
    onSendTest: (String) -> Unit,
    onClearGlasses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var testText by rememberSaveable { mutableStateOf("Hello from Offline_AI") }
    val connecting = state.phase != ConnectionPhase.DISCONNECTED && !state.ready
    val bytes = testText.toByteArray(Charsets.UTF_8).size

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Status
        Text(phaseLabel(state), style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(
                state.serial.ifEmpty { null }?.let { "Glasses $it" },
                "Left ${lensLabel(state.left)}",
                "Right ${lensLabel(state.right)}",
                state.battery?.let { "Battery $it%" + if (state.charging == true) " (charging)" else "" },
                state.firmware.ifEmpty { null }?.let { "Firmware $it" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.lastError.isNotEmpty()) {
            Text(state.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.phase == ConnectionPhase.DISCONNECTED) {
                Button(onClick = onConnect) { Text("Connect") }
            } else {
                Button(onClick = onDisconnect) { Text(if (connecting) "Cancel" else "Disconnect") }
            }
            TextButton(onClick = onForget, enabled = state.phase == ConnectionPhase.DISCONNECTED && state.serial.isNotEmpty()) {
                Text("Forget glasses")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show captions on glasses", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = mirrorCaptions, onCheckedChange = onMirrorCaptionsChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Show live speech on glasses", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Words appear on the bottom line before they are translated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = livePartials, onCheckedChange = onLivePartialsChange, enabled = mirrorCaptions)
        }
        Text(
            governorLabel(governorStats),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()

        // Test message, like the gateway UI's "Fast Text"
        Text("Test message", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = testText,
            onValueChange = { testText = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            supportingText = {
                Text(
                    "$bytes / ${GlassesDisplay.MAX_TEXT_BYTES} bytes",
                    color = if (bytes > GlassesDisplay.MAX_TEXT_BYTES) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSendTest(testText) }, enabled = state.ready && testText.isNotBlank() && bytes <= GlassesDisplay.MAX_TEXT_BYTES) {
                Text("Send")
            }
            OutlinedButton(onClick = onClearGlasses, enabled = state.ready) { Text("Clear glasses") }
        }
        HorizontalDivider()

        // Event log, newest first
        Text("Event log", style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.weight(1f)) {
            items(log) { e ->
                Text(
                    "%7.1f  %s".format(e.timeMs / 1000.0, e.message),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private fun phaseLabel(s: GatewayState) = when (s.phase) {
    ConnectionPhase.DISCONNECTED -> "Glasses not connected"
    ConnectionPhase.SCANNING -> "Looking for glasses…"
    ConnectionPhase.CONNECTING -> "Connecting…"
    ConnectionPhase.INITIALIZING -> "Setting up glasses…"
    ConnectionPhase.READY -> "Glasses connected"
}

/** e.g. "Render governor: 12 pages sent · 30 merged · 4 unchanged skipped". */
private fun governorLabel(s: RenderGovernor.Stats) =
    "Render governor: ${s.sent} pages sent · ${s.merged} merged · ${s.unchanged} unchanged skipped"

private fun lensLabel(l: LensState) = when {
    !l.connected -> "off"
    l.authenticated -> "ready"
    else -> "connected"
}
