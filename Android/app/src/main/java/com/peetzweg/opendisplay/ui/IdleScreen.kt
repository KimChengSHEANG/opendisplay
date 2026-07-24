package com.peetzweg.opendisplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peetzweg.opendisplay.settings.ConnectionMode
import com.peetzweg.opendisplay.version.VersionGate

/**
 * No-Mac-connected screen — Android's take on `IdleView` in
 * `iOS/OpenSidecarPhoneApp.swift`: app title, a status dot, USB/WiFi
 * how-to-connect hints (filtered by [connectionMode]), and Settings.
 */
@Composable
fun IdleScreen(
    status: String,
    connectionMode: ConnectionMode,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    recommendedUpdate: VersionGate.Update? = null,
    onUpdateRecommended: () -> Unit = {},
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("OpenDisplay", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.padding(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(8.dp)
                        .background(
                            if (status == "connected") Color(0xFF2E7D32) else Color(0xFFEF6C00),
                            CircleShape,
                        ),
                )
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.padding(4.dp))
            Text(
                connectionMode.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(12.dp))
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 420.dp)) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (connectionMode) {
                        ConnectionMode.Usb -> {
                            Text("• Plug in USB (or adb connect) and start the Mac app")
                            Text("• WiFi advertising is off — Mac won’t list this device on the network")
                        }
                        ConnectionMode.Wifi -> {
                            Text("• Choose this device under WiFi in the Mac app")
                            Text("• Both devices must be on the same network")
                        }
                        ConnectionMode.Both -> {
                            Text("• Plug in USB and start the Mac app — connects automatically")
                            Text("• Or choose this device under WiFi in the Mac app")
                        }
                    }
                    Text("• Keep this app open — streaming starts automatically")
                    Text("• Rotate the device for a vertical second monitor")
                }
            }
            if (recommendedUpdate != null) {
                Spacer(Modifier.padding(8.dp))
                Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 420.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(recommendedUpdate.message, textAlign = TextAlign.Center)
                        Button(onClick = onUpdateRecommended, modifier = Modifier.fillMaxWidth()) {
                            Text("Update")
                        }
                    }
                }
            }
            Spacer(Modifier.padding(16.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(0.6f)) {
                Text("Settings & Help")
            }
        }
    }
}
