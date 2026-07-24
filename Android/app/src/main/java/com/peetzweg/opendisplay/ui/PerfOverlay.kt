package com.peetzweg.opendisplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Minimal opt-in HUD (Settings > "Performance overlay") — just decoded-frame
 * fps for now. Stands in for iOS's much richer `PerfOverlay` (latency/bitrate
 * graphs); a fuller port is a follow-up once Android has the same telemetry.
 */
@Composable
fun PerfOverlay(fps: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(12.dp)) {
        Text(
            "$fps fps",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
