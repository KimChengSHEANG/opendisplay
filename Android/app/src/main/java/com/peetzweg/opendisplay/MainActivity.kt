package com.peetzweg.opendisplay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.peetzweg.opendisplay.net.DiscoveryAdvertiser
import com.peetzweg.opendisplay.session.InstallId
import com.peetzweg.opendisplay.session.PanelMetrics
import com.peetzweg.opendisplay.session.PerfStats
import com.peetzweg.opendisplay.session.ReceiverSession
import com.peetzweg.opendisplay.session.SessionTelemetry
import com.peetzweg.opendisplay.settings.AppSettings
import com.peetzweg.opendisplay.settings.ConnectionMode
import com.peetzweg.opendisplay.sleep.HostSleepController
import com.peetzweg.opendisplay.ui.CursorController
import com.peetzweg.opendisplay.ui.IdleScreen
import com.peetzweg.opendisplay.ui.PerfOverlay
import com.peetzweg.opendisplay.ui.SettingsScreen
import com.peetzweg.opendisplay.ui.StreamingScreen
import com.peetzweg.opendisplay.ui.UpdateRequiredScreen
import com.peetzweg.opendisplay.ui.decodeCursorPng
import com.peetzweg.opendisplay.version.VersionGate
import com.peetzweg.opendisplay.video.VideoDecoder
import com.peetzweg.opendisplay.wire.WireMessage

class MainActivity : ComponentActivity() {
    private var connected by mutableStateOf(false)
    private var hostDisplayOff by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var deviceName by mutableStateOf("")
    private var showAnalytics by mutableStateOf(false)
    private var connectionMode by mutableStateOf(ConnectionMode.Both)
    private var perf by mutableStateOf(PerfStats())
    private val cursorController by lazy {
        CursorController(chromebook = ReceiverSession.deviceKind(this) == "Chromebook")
    }
    private var updateRequired by mutableStateOf<VersionGate.Update?>(null)
    private var recommendedUpdate by mutableStateOf<VersionGate.Update?>(null)
    private val versionGate = VersionGate()
    private var session: ReceiverSession? = null
    @Volatile private var decoder: VideoDecoder? = null
    /** Latest SPS+PPS+IDR seen while the decode surface wasn't ready yet. */
    @Volatile private var pendingSyncFrame: ByteArray? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * Chromebook post-connect recovery: keep asking for IDRs, then force the
     * Mac to redial if the panel never paints (solid green / blank VDA).
     */
    private var recoverAttempt = 0
    private val recoverRunnable = object : Runnable {
        override fun run() {
            val d = decoder ?: return
            if (session?.isConnected != true) return
            if (d.hasRendered) {
                recoverAttempt = 0
                return
            }
            recoverAttempt++
            session?.sendControl(mapOf("type" to "kf"))
            if (recoverAttempt >= 5) {
                // ~2.5s of no paint while connected — tear the socket so Mac
                // reconnects with a fresh SurfaceView + IDR (no quality change).
                if (allowForcedReconnect()) {
                    session?.forcePeerReconnect("no video frame rendered after reconnect")
                }
                recoverAttempt = 0
                return
            }
            mainHandler.postDelayed(this, 500)
        }
    }
    /** Throttle decoder-driven keyframe asks so scroll doesn't IDR-spam. */
    private var lastDecoderKfAtMs: Long = 0
    /** Rate-limit forced TCP reconnects so a permanent failure can't loop. */
    private var lastForcedReconnectAtMs: Long = 0
    private var forcedReconnectsWindow = 0
    private var forcedReconnectsWindowStartMs: Long = 0
    private var advertiser: DiscoveryAdvertiser? = null
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var lockReceiverRegistered = false
    /** True between [onStart] and [onStop]; gates [resumeAccepting] while backgrounded. */
    private var activityStarted = false
    /** Set when unlock arrives before the activity is visible again. */
    private var pendingResumeAccepting = false

    /** Wires Android window/lock/session APIs to the pure sleep state machine — see `HostSleepController`. */
    private val hostSleep = HostSleepController(
        sendControl = { session?.sendControl(it) },
        setBrightness = { value -> setWindowBrightness(value) },
        setKeepScreenOn = { keep ->
            if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        },
        stopAccepting = {
            session?.stop()
            connected = false
        },
        resumeAccepting = {
            if (activityStarted) {
                session?.start()
                pendingResumeAccepting = false
            } else {
                pendingResumeAccepting = true
            }
        },
    )

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    if (km?.isDeviceSecure == true) hostSleep.onDeviceWillLock()
                }
                Intent.ACTION_USER_PRESENT -> hostSleep.onDeviceUnlocked()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        deviceName = DiscoveryAdvertiser.deviceName(this)
        showAnalytics = AppSettings.showAnalytics(this)
        connectionMode = AppSettings.connectionMode(this)
        if (!lockReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) }
            ContextCompat.registerReceiver(this, lockReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            lockReceiverRegistered = true
        }
        setContent {
            OpenDisplayApp(
                connected = connected,
                hostDisplayOff = hostDisplayOff,
                showSettings = showSettings,
                deviceName = deviceName,
                showAnalytics = showAnalytics,
                connectionMode = connectionMode,
                perf = perf,
                cursorController = cursorController,
                updateRequired = updateRequired,
                recommendedUpdate = recommendedUpdate,
                onWake = {
                    hostSleep.wake()
                    hostDisplayOff = false
                    applyImmersive()
                },
                onSurfaceReady = { d ->
                    // Streaming UI mounts only after `connected`, so the Mac's
                    // opening IDR is often already on the wire — stash + replay
                    // it, then ask for another keyframe in case the stash was
                    // only P-frames or the ARC decoder needed a second sync.
                    d.renderingPaused = false
                    decoder = d
                    val pending = pendingSyncFrame
                    pendingSyncFrame = null
                    if (pending != null) d.feedAnnexB(pending)
                    if (session?.isConnected == true) {
                        session?.sendControl(mapOf("type" to "kf"))
                        mainHandler.removeCallbacks(recoverRunnable)
                        recoverAttempt = 0
                        mainHandler.postDelayed(recoverRunnable, 500)
                    }
                },
                onSurfaceDestroyed = {
                    mainHandler.removeCallbacks(recoverRunnable)
                    recoverAttempt = 0
                    decoder?.release()
                    decoder = null
                },
                onGreenScreen = {
                    if (allowForcedReconnect()) {
                        session?.forcePeerReconnect("green screen detected")
                    }
                },
                onControl = { session?.sendControl(it) },
                onOpenSettings = { showSettings = true },
                onCloseSettings = { showSettings = false },
                onDeviceNameChange = { name ->
                    deviceName = name
                    DiscoveryAdvertiser.setSavedName(this, name)
                    advertiser?.updateServiceName(DiscoveryAdvertiser.deviceName(this))
                },
                onShowAnalyticsChange = { value ->
                    showAnalytics = value
                    AppSettings.setShowAnalytics(this, value)
                },
                onConnectionModeChange = { mode ->
                    connectionMode = mode
                    AppSettings.setConnectionMode(this, mode)
                    applyConnectionAdvertising()
                },
                onOpenProjectSite = { openUrl(VersionGate.PROJECT_SITE_URL) },
                onUpdate = { url -> openUrl(url) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (session == null) {
            val panel = PanelMetrics.of(this)
            val installId = InstallId.get(this)
            val s = ReceiverSession(listener = ReceiverListener())
            s.installId = installId
            s.device = ReceiverSession.deviceKind(this)
            s.pixelsWide = panel.wide
            s.pixelsHigh = panel.high
            s.scale = panel.density
            s.start()
            session = s
            applyConnectionAdvertising()
        } else {
            // App switch return: re-arm the listener (Android may have killed
            // the accept loop while we were suspended) and ask the Mac for a
            // keyframe if the TCP session survived — mirrors iOS
            // sceneDidActivate → ensureListening + setRenderingPaused(false).
            decoder?.renderingPaused = false
            session?.ensureListening()
            if (session?.isConnected == true) {
                session?.sendControl(mapOf("type" to "kf"))
            }
            applyConnectionAdvertising()
        }
        if (pendingResumeAccepting) {
            session?.start()
            pendingResumeAccepting = false
        }
    }

    /**
     * Start or stop Bonjour/NSD based on [connectionMode]. TCP listen stays
     * up either way — USB (`adb forward`) and WiFi both dial :9000.
     */
    private fun applyConnectionAdvertising() {
        if (connectionMode.advertisesWifi) {
            if (advertiser != null) return
            val a = DiscoveryAdvertiser(
                this,
                DiscoveryAdvertiser.deviceName(this),
                InstallId.get(this),
                deviceKind = ReceiverSession.deviceKind(this),
            )
            a.start(port = ReceiverSession.DEFAULT_PORT)
            advertiser = a
        } else {
            advertiser?.stop()
            advertiser = null
        }
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        // iOS setRenderingPaused(true) on background. ChromeOS ARC often
        // delivers onStop while the stream surface stays on-screen (immersive
        // / focus), which cleared the decode queue and left a black panel —
        // skip pause on Chromebook; phones/tablets still pause.
        if (ReceiverSession.deviceKind(this) != "Chromebook") {
            decoder?.renderingPaused = true
        }
        // Keep listening across a plain app switch (like iOS). Only lock /
        // quit tear the session down — see HostSleepController.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (lockReceiverRegistered) {
            unregisterReceiver(lockReceiver)
            lockReceiverRegistered = false
        }
        // ChromeOS can recreate the activity on config/density changes; don't
        // announce `closing` or tear the listener — mirrors iOS surviving
        // background linger. Real quit still reaches here without that flag.
        if (!isChangingConfigurations) {
            hostSleep.onAppQuitting()
            session?.stop()
            session = null
            advertiser?.stop()
            advertiser = null
        }
        decoder?.release()
        decoder = null
        mainHandler.removeCallbacks(recoverRunnable)
        recoverAttempt = 0
    }

    /** Rotation: keep the session alive, just tell the Mac about the new panel — see `ReceiverSession.updatePanel`. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val panel = PanelMetrics.of(this)
        session?.updatePanel(panel.wide, panel.high, panel.density)
    }

    /**
     * At most 2 forced Mac redials per 30s — enough to clear a green VDA
     * without spinning if the stream is permanently broken.
     */
    private fun allowForcedReconnect(): Boolean {
        val now = System.currentTimeMillis()
        if (now - forcedReconnectsWindowStartMs > 30_000) {
            forcedReconnectsWindowStartMs = now
            forcedReconnectsWindow = 0
        }
        if (forcedReconnectsWindow >= 2) return false
        if (now - lastForcedReconnectAtMs < 3_000) return false
        forcedReconnectsWindow++
        lastForcedReconnectAtMs = now
        return true
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // No browser/Play Store to handle it — nothing more we can do.
        }
    }

    /**
     * Edge-to-edge immersive mode while a session owns the panel (streaming or
     * host-display-off): hide the status/navigation bars so the mirrored
     * display fills the screen, matching the iOS receiver. Bars come back on a
     * swipe (transient) and whenever we return to idle/settings.
     */
    private fun applyImmersive() {
        val immersive = connected || hostDisplayOff
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The system restores the bars on focus loss (e.g. a dialog); reassert
        // immersive when we regain focus and still own the panel.
        if (hasFocus) applyImmersive()
    }

    private fun setWindowBrightness(value: Float?) {
        val attrs = window.attributes
        if (value == null) {
            attrs.screenBrightness = savedBrightness
        } else {
            savedBrightness = attrs.screenBrightness
            attrs.screenBrightness = value
        }
        window.attributes = attrs
    }

    private inner class ReceiverListener : ReceiverSession.Listener {
        override fun onConnected() {
            runOnUiThread {
                connected = true
                hostSleep.onConnected()
                hostDisplayOff = hostSleep.hostDisplayOff
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Chromebook ARC often leaves the ARC window below system
                // brightness — pin full brightness while the stream owns the panel.
                if (ReceiverSession.deviceKind(this@MainActivity) == "Chromebook") {
                    setWindowBrightness(1f)
                }
                applyImmersive()
                // Immersive + real panel size: re-announce if chrome changed
                // the window metrics between listen and fullscreen.
                val panel = PanelMetrics.of(this@MainActivity)
                session?.updatePanel(panel.wide, panel.high, panel.density)
                perf = PerfStats()
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                connected = false
                cursorController.hide()
                pendingSyncFrame = null
                mainHandler.removeCallbacks(recoverRunnable)
                recoverAttempt = 0
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (ReceiverSession.deviceKind(this@MainActivity) == "Chromebook") {
                    setWindowBrightness(null)
                }
                applyImmersive()
                perf = PerfStats()
            }
        }

        override fun onVideoFrame(data: ByteArray) {
            val d = decoder
            if (d == null) {
                // Hold the sync frame until SurfaceView is up — first-connect
                // green screen on Chromebook is almost always a missed IDR.
                if (VideoDecoder.containsIdr(data)) pendingSyncFrame = data.copyOf()
                return
            }
            // Non-blocking: decode runs on VideoDecoder's thread (iOS-style).
            d.feedAnnexB(data)
            if (d.consumeNeedsKeyframe()) {
                val now = System.currentTimeMillis()
                if (now - lastDecoderKfAtMs >= 2000) {
                    lastDecoderKfAtMs = now
                    session?.sendControl(mapOf("type" to "kf"))
                }
            }
        }

        override fun onPerf(stats: PerfStats) {
            // The session times the network; the decoder times the panel.
            // Join them here — the only object that holds both.
            val decode = decoder?.timings?.snapshotAndDrain().orEmpty()
            val merged = stats.copy(
                decodeP50 = SessionTelemetry.percentile(decode, 0.5),
                decodeP95 = SessionTelemetry.percentile(decode, 0.95),
            )
            runOnUiThread { perf = merged }
        }

        override fun onControl(map: Map<String, Any>) {
            when (map["type"]) {
                "cursor" -> {
                    val visible = (map["v"] as? Number)?.toInt() == 1
                    val x = (map["x"] as? Number)?.toFloat()
                    val y = (map["y"] as? Number)?.toFloat()
                    // Apply on the UI thread without Compose state — see CursorController.
                    when {
                        x != null && y != null -> cursorController.move(x, y, visible)
                        !visible -> cursorController.hide()
                    }
                }
                "cursorImg" -> {
                    val b64 = map["png"] as? String ?: return
                    val nw = (map["nw"] as? Number)?.toFloat() ?: return
                    val nh = (map["nh"] as? Number)?.toFloat() ?: return
                    val ax = (map["ax"] as? Number)?.toFloat() ?: 0f
                    val ay = (map["ay"] as? Number)?.toFloat() ?: 0f
                    val bmp = decodeCursorPng(b64) ?: return
                    cursorController.setSprite(bmp, nw, nh, ax, ay)
                }
                WireMessage.hostSleeping -> runOnUiThread {
                    hostSleep.onHostSleeping()
                    hostDisplayOff = true
                    applyImmersive()
                }
                // welcome/updateRequired: peer-driven update signals — see `VersionGate`.
                WireMessage.welcome, WireMessage.updateRequired -> {
                    versionGate.onControl(map)
                    runOnUiThread {
                        val status = versionGate.status
                        updateRequired = (status as? VersionGate.Status.Required)?.update
                        recommendedUpdate = (status as? VersionGate.Status.Recommended)?.update
                    }
                }
            }
        }

        override fun onStatus(status: String) {}
    }
}

/**
 * Top-level navigation. Priority mirrors `ReceiverScreen` on iOS: a blocking
 * [VersionGate.Status.Required] gate wins over everything (even mid-stream —
 * the Mac has already refused this pairing); otherwise host-display-off,
 * streaming, settings, and idle are mutually exclusive full-screen states.
 */
@Composable
fun OpenDisplayApp(
    connected: Boolean,
    hostDisplayOff: Boolean,
    showSettings: Boolean,
    deviceName: String,
    showAnalytics: Boolean,
    connectionMode: ConnectionMode,
    perf: PerfStats,
    cursorController: CursorController,
    updateRequired: VersionGate.Update?,
    recommendedUpdate: VersionGate.Update?,
    onWake: () -> Unit,
    onSurfaceReady: (VideoDecoder) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onGreenScreen: () -> Unit,
    onControl: (Map<String, Any>) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onShowAnalyticsChange: (Boolean) -> Unit,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    onOpenProjectSite: () -> Unit,
    onUpdate: (String) -> Unit,
) {
    MaterialTheme {
        if (updateRequired != null) {
            UpdateRequiredScreen(update = updateRequired, onUpdate = { onUpdate(updateRequired.url) })
        } else if (hostDisplayOff) {
            // Mac is asleep — blank the panel too; tap restores brightness
            // without ending the wait for it to wake. Mirrors iOS's
            // `hostDisplayOff` overlay in `OpenSidecarPhoneApp.swift`.
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(Color.Black)
                    .clickable(onClick = onWake),
            )
        } else if (connected) {
            Box(modifier = Modifier.fillMaxSize()) {
                StreamingScreen(
                    cursorController = cursorController,
                    onSurfaceReady = onSurfaceReady,
                    onSurfaceDestroyed = onSurfaceDestroyed,
                    onGreenScreen = onGreenScreen,
                    onControl = onControl,
                )
                if (showAnalytics) {
                    PerfOverlay(stats = perf, modifier = Modifier.fillMaxSize())
                }
            }
        } else if (showSettings) {
            SettingsScreen(
                deviceName = deviceName,
                onDeviceNameChange = onDeviceNameChange,
                connectionStatus = if (connected) "Connected" else "Waiting for Mac",
                connectionMode = connectionMode,
                onConnectionModeChange = onConnectionModeChange,
                showAnalytics = showAnalytics,
                onShowAnalyticsChange = onShowAnalyticsChange,
                onOpenProjectSite = onOpenProjectSite,
                onClose = onCloseSettings,
            )
        } else {
            IdleScreen(
                status = if (connected) "connected" else "waiting for Mac",
                connectionMode = connectionMode,
                recommendedUpdate = recommendedUpdate,
                onUpdateRecommended = { recommendedUpdate?.let { onUpdate(it.url) } },
                onOpenSettings = onOpenSettings,
            )
        }
    }
}
