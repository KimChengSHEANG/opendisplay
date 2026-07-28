package com.peetzweg.opendisplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peetzweg.opendisplay.session.PerfStats

/**
 * Opt-in HUD (Settings > Performance overlay) — core metrics from iOS
 * `PerfOverlay` / `PerfStats` (no bar graphs in this pass).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerfOverlay(stats: PerfStats, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(12.dp), contentAlignment = Alignment.BottomStart) {
        Column(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TransportBadge(stats.transport, stats.videoTransport)
                if (stats.videoTransport == "udp" && stats.lossPct != null) {
                    Metric("loss", "${stats.lossPct.toInt()}%")
                }
                if (stats.fecRecoveries > 0) {
                    Metric("fec", "${stats.fecRecoveries}")
                }
                if (stats.totalLatencyP50 > 0) {
                    Metric("total", "${stats.totalLatencyP50.toInt()} ms")
                }
                if (stats.e2eP50 > 0) {
                    Metric("latency", "${stats.e2eP50.toInt()} ms")
                    Metric("p95", "${stats.e2eP95.toInt()} ms")
                    Metric("encode", "${stats.encodeP50.toInt()} ms")
                }
                if (stats.decodeP50 > 0) {
                    Metric("decode", "${stats.decodeP50.toInt()} ms")
                    Metric("decode p95", "${stats.decodeP95.toInt()} ms")
                }
                if (stats.inputP50 > 0) {
                    Metric("input", "${stats.inputP50.toInt()} ms")
                }
                if (stats.rttMs > 0) {
                    Metric("rtt", "${stats.rttMs.toInt()} ms")
                }
                Metric("FPS", "${stats.fps}")
                if (stats.capFps > 0) {
                    Metric("Mac cap", "${stats.capFps}")
                }
                Metric("Mbit/s", String.format(java.util.Locale.US, "%.1f", stats.mbps))
                Metric("stalls", "${stats.stalls}")
                Metric("enc↓", "${stats.macEncDrops}")
                Metric("net↓", "${stats.macNetDrops}")
                if (stats.macPending > 0) {
                    Metric("queue", "${stats.macPending}")
                }
                if (!stats.offsetKnown) {
                    Metric("sync", "…")
                }
            }
        }
    }
}

@Composable
private fun TransportBadge(linkTransport: String, videoTransport: String) {
    val label = if (videoTransport == "udp") "UDP" else linkTransport
    val bg = when {
        videoTransport == "udp" -> Color(0xFF6A1B9A).copy(alpha = 0.55f)
        linkTransport == "USB" -> Color(0xFF2E7D32).copy(alpha = 0.55f)
        linkTransport == "WiFi" -> Color(0xFF1565C0).copy(alpha = 0.55f)
        else -> Color.Gray.copy(alpha = 0.4f)
    }
    Text(
        label,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}
