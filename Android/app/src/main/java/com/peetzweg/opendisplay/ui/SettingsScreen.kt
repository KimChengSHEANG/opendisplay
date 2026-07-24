package com.peetzweg.opendisplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
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

/**
 * Settings & help sheet — Android's counterpart to `SettingsView` in
 * `iOS/OpenSidecarPhoneApp.swift`. [deviceName]/[onDeviceNameChange] persist
 * the NSD display name (`DiscoveryAdvertiser.setSavedName`); [connectionStatus]
 * mirrors the "Status" section; [showAnalytics]/[onShowAnalyticsChange] gate
 * the minimal FPS HUD in `StreamingScreen`.
 */
@Composable
fun SettingsScreen(
    deviceName: String,
    onDeviceNameChange: (String) -> Unit,
    connectionStatus: String,
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
                )
                Text(
                    "Shown in the Mac app's WiFi connection menu.",
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
                    Text("Performance overlay (FPS)")
                    Switch(checked = showAnalytics, onCheckedChange = onShowAnalyticsChange)
                }
            }
            Divider()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("How to connect", style = MaterialTheme.typography.labelLarge)
                Text("• Both devices on the same WiFi, then pick this device in the Mac app's Connection menu.")
                Text("• Keep this app open — streaming starts automatically.")
                Text("• Touch: tap to click, drag to drag, two-finger pan to scroll.")
            }
            Divider()

            Button(onClick = onOpenProjectSite) { Text("Get the Mac app") }
        }
    }
}
