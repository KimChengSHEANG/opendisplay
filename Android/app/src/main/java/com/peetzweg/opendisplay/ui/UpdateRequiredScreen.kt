package com.peetzweg.opendisplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.peetzweg.opendisplay.version.VersionGate

/**
 * Blocking, non-dismissible gate for [VersionGate.Status.Required] — the Mac
 * refuses this pairing until we update. Mirrors `UpdateRequiredView` on iOS.
 * There is no back/close affordance; the only action is [onUpdate].
 */
@Composable
fun UpdateRequiredScreen(update: VersionGate.Update, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Update required", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.padding(6.dp))
            Text(
                update.message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(12.dp))
            Button(onClick = onUpdate) { Text("Update") }
        }
    }
}
