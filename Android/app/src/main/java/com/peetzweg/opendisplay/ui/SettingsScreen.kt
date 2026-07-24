package com.peetzweg.opendisplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.peetzweg.opendisplay.settings.ConnectionMode

/**
 * Settings & help sheet — Android's counterpart to `SettingsView` in
 * `iOS/OpenSidecarPhoneApp.swift`. [deviceName]/[onDeviceNameChange] persist
 * the NSD display name (`DiscoveryAdvertiser.setSavedName`); [connectionMode]
 * gates Bonjour advertising (USB-only vs WiFi); [showAnalytics] gates the
 * performance HUD while streaming.
 */
@Composable
fun SettingsScreen(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    connectionStatus: String,
    connectionMode: ConnectionMode,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    showAnalytics: Boolean,
    onShowAnalyticsChange: (Boolean) -> Unit,
    onOpenProjectSite: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember(deviceName) { mutableStateOf(deviceName) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("OpenDisplay", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text("Done") }
            }
            Divider()

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Status", style = MaterialTheme.typography.labelLarge)
                Text("Listening on port 9000", style = MaterialTheme.typography.bodyMedium)
                Text(connectionStatus, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (connectionMode) {
                        ConnectionMode.Usb -> "Advertising off — USB / adb only"
                        ConnectionMode.Wifi -> "Advertising on WiFi"
                        ConnectionMode.Both -> "Advertising on WiFi · USB ready"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connection", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ConnectionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = connectionMode == mode,
                            onClick = { onConnectionModeChange(mode) },
                            label = { Text(mode.label) },
                        )
                    }
                }
                Text(
                    when (connectionMode) {
                        ConnectionMode.Usb ->
                            "Mac finds this device over USB (adb). Bonjour is off so it won’t appear in the WiFi list."
                        ConnectionMode.Wifi ->
                            "Mac finds this device on the same WiFi. Prefer this when a cable isn’t available."
                        ConnectionMode.Both ->
                            "USB and WiFi are both available. The Mac prefers USB when the cable is plugged in."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Name", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        onDeviceNameChange(it)
                    },
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectionMode.advertisesWifi,
                )
                Text(
                    if (connectionMode.advertisesWifi) {
                        "Shown in the Mac app's WiFi connection menu."
                    } else {
                        "Name is used for WiFi advertising (currently off)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Divider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Analytics", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Performance overlay")
                    Switch(checked = showAnalytics, onCheckedChange = onShowAnalyticsChange)
                }
            }
            Divider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How to connect", style = MaterialTheme.typography.labelLarge)
                Text("• USB: plug in the cable (or adb connect), run the Mac app — it connects through the wire (lowest latency).")
                Text("• WiFi: both devices on the same network, then pick this device in the Mac app's Connection menu.")
                Text("• Keep this app open — streaming starts automatically.")
                Text("• Touch: tap to click, drag to drag, two-finger pan to scroll. Chromebook: trackpad moves the Mac cursor.")
            }
            Divider()

            Button(onClick = onOpenProjectSite) { Text("Get the Mac app") }
        }
    }
}
