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
import com.peetzweg.opendisplay.session.ChromebookRecoverPolicy
import com.peetzweg.opendisplay.session.ConnectTimingPolicy
import com.peetzweg.opendisplay.session.InstallId
import com.peetzweg.opendisplay.session.PanelMetrics
import com.peetzweg.opendisplay.session.PerfStats
import com.peetzweg.opendisplay.session.ReceiverSession
import com.peetzweg.opendisplay.session.SessionTelemetry
import com.peetzweg.opendisplay.session.VideoStallPolicy
import com.peetzweg.opendisplay.settings.AppSettings
import com.peetzweg.opendisplay.settings.ConnectionMode
import com.peetzweg.opendisplay.sleep.HostSleepController
import com.peetzweg.opendisplay.sleep.PanelBacklight
import com.peetzweg.opendisplay.sleep.ScreenLockPolicy
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
import java.util.concurrent.atomic.AtomicBoolean

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
     *
     * ARC VDA allocate often takes >3s after screen wake; forcing a redial at
     * ~2.5s orphans the in-flight allocate and wedges the decoder (black panel).
     * Chromebook waits longer before tearing the socket.
     */
    private var recoverAttempt = 0
    private val recoverScheduled = AtomicBoolean(false)
    private val recoverIntervalMs: Long
        get() = if (isChromebook) 1_000L else 500L
    private val recoverForceAfterAttempts: Int
        get() = if (isChromebook) 12 else 5
    private val recoverRunnable = object : Runnable {
        override fun run() {
            recoverScheduled.set(false)
            val d = decoder ?: return
            if (session?.isConnected != true) return
            if (d.hasRendered) {
                recoverAttempt = 0
                return
            }
            recoverAttempt++
            session?.sendControl(mapOf("type" to "kf"))
            if (recoverAttempt >= recoverForceAfterAttempts) {
                // Phone ~2.5s / Chromebook ~12s of no paint. Tear TCP only when
                // still in the post-connect window (stuck first paint). After
                // that, keep asking for IDRs — mid-session forcePeerReconnect
                // remounts ARC VDA and flashes black/green while the Mac redials.
                if (allowForcedReconnect()) {
                    session?.forcePeerReconnect("no video frame rendered after reconnect")
                } else {
                    android.util.Log.w(
                        "MainActivity",
                        "recover: skipping TCP tear (chromebook=$isChromebook) — keep requesting kf",
                    )
                }
                recoverAttempt = 0
                // Chromebook mid-session: keep polling; phones stop after the tear attempt.
                if (isChromebook && session?.isConnected == true) {
                    scheduleRecover(recoverIntervalMs)
                }
                return
            }
            scheduleRecover(recoverIntervalMs)
        }
    }

    /** Start/continue the no-paint recover loop (idempotent). */
    private fun scheduleRecover(delayMs: Long = recoverIntervalMs) {
        if (!recoverScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed(recoverRunnable, delayMs)
    }

    private fun cancelRecover() {
        mainHandler.removeCallbacks(recoverRunnable)
        recoverScheduled.set(false)
        recoverAttempt = 0
    }

    /**
     * Mid-session: TCP pings can keep the silence watchdog happy while video
     * (especially UDP) or ARC VDA is wedged on the last frame. Poll age of the
     * last video AU and escalate kf → in-place codec rebuild.
     */
    private val stallWatchScheduled = AtomicBoolean(false)
    private var lastStallKfAtMs: Long = 0
    private var lastStallRebuildAtMs: Long = 0
    private val stallWatchRunnable = object : Runnable {
        override fun run() {
            stallWatchScheduled.set(false)
            val s = session
            if (s?.isConnected != true) return
            val action = VideoStallPolicy.action(
                ageMs = s.videoFrameAgeMs(),
                hasPaintedThisConnection = paintedThisConnection,
                connected = true,
            )
            val now = System.currentTimeMillis()
            when (action) {
                VideoStallPolicy.Action.None -> Unit
                VideoStallPolicy.Action.RequestKeyframe -> {
                    if (now - lastStallKfAtMs >= 1_000) {
                        lastStallKfAtMs = now
                        s.sendControl(mapOf("type" to "kf"))
                        android.util.Log.w("MainActivity", "video stall — requesting kf")
                    }
                }
                VideoStallPolicy.Action.RebuildCodec -> {
                    if (now - lastStallRebuildAtMs >= 10_000) {
                        lastStallRebuildAtMs = now
                        lastStallKfAtMs = now
                        decoder?.rebuildCodecInPlace()
                        s.sendControl(mapOf("type" to "kf"))
                        android.util.Log.w("MainActivity", "video stall — codec rebuild + kf")
                    } else if (now - lastStallKfAtMs >= 1_000) {
                        lastStallKfAtMs = now
                        s.sendControl(mapOf("type" to "kf"))
                    }
                }
            }
            scheduleStallWatch()
        }
    }

    private fun scheduleStallWatch() {
        if (!isChromebook) return
        if (session?.isConnected != true) return
        if (!stallWatchScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed(stallWatchRunnable, 1_000L)
    }

    private fun cancelStallWatch() {
        mainHandler.removeCallbacks(stallWatchRunnable)
        stallWatchScheduled.set(false)
    }

    /** Throttle decoder-driven keyframe asks so scroll doesn't IDR-spam. */
    private var lastDecoderKfAtMs: Long = 0
    /** Throttle green-sample recover actions to avoid VDA thrash. */
    private var lastGreenRecoverAtMs: Long = 0
    /** Throttle keyframe asks triggered by green detection. */
    private var lastGreenKfAtMs: Long = 0
    /** Track whether green is persistent across multiple callbacks. */
    private var lastGreenEventAtMs: Long = 0
    private var greenEventStreak: Int = 0
    /** Rate-limit forced TCP reconnects so a permanent failure can't loop. */
    private var lastForcedReconnectAtMs: Long = 0
    private var forcedReconnectsWindow = 0
    private var forcedReconnectsWindowStartMs: Long = 0
    /**
     * Wall time of the latest TCP connect (diagnostics / future policy).
     */
    private var connectedAtMs: Long = 0
    /** True once the decoder has painted at least one frame this TCP session. */
    private var paintedThisConnection = false
    private var advertiser: DiscoveryAdvertiser? = null
    private var lockReceiverRegistered = false
    /** True between [onStart] and [onStop]; gates [resumeAccepting] while backgrounded. */
    private var activityStarted = false
    /** Set when unlock arrives before the activity is visible again. */
    private var pendingResumeAccepting = false

    private val isChromebook: Boolean
        get() = ReceiverSession.deviceKind(this) == "Chromebook"

    private lateinit var panelBacklight: PanelBacklight

    /** Wires Android window/lock/session APIs to the pure sleep state machine — see `HostSleepController`. */
    private val hostSleep = HostSleepController(
        // Sync flush so `sleeping`/`closing` reach the Mac before stop() tears
        // the socket — otherwise wake-reconnect never arms.
        sendControl = { session?.sendControlSync(it) },
        setBrightness = { value -> panelBacklight.set(value) },
        setKeepScreenOn = { keep ->
            if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        },
        stopAccepting = {
            cancelRecover()
            cancelStallWatch()
            session?.stop(reason = "screenOff/stop")
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
                // Always disconnect on screen-off — Chromebooks often have no
                // Android keyguard (`isDeviceSecure == false`), so gating on
                // lock previously left the Mac streaming into a dark panel for
                // tens of minutes until the session looked frozen/unclickable.
                Intent.ACTION_SCREEN_OFF -> {
                    if (ScreenLockPolicy.shouldStopOnScreenOff()) {
                        hostSleep.onDeviceWillLock()
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    // Secure phones: wait for USER_PRESENT (unlock). Chromebook /
                    // no lock: screen-on is enough to resume listening.
                    if (ScreenLockPolicy.shouldResumeOnScreenOn(km?.isDeviceSecure == true)) {
                        hostSleep.onDeviceUnlocked()
                    }
                }
                Intent.ACTION_USER_PRESENT -> hostSleep.onDeviceUnlocked()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panelBacklight = PanelBacklight(
            window = window,
            context = this,
            chromebook = isChromebook,
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        deviceName = DiscoveryAdvertiser.deviceName(this)
        showAnalytics = AppSettings.showAnalytics(this)
        connectionMode = AppSettings.connectionMode(this)
        if (!lockReceiverRegistered) {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
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
                    d.onDecodeError = { consecutive ->
                        handleDecodeError(consecutive)
                    }
                    decoder = d
                    val pending = pendingSyncFrame
                    pendingSyncFrame = null
                    if (pending != null) d.feedAnnexB(pending)
                    if (session?.isConnected == true) {
                        session?.sendControl(mapOf("type" to "kf"))
                        cancelRecover()
                        scheduleRecover(recoverIntervalMs)
                    }
                },
                onSurfaceDestroyed = {
                    cancelRecover()
                    decoder?.release()
                    decoder = null
                },
                onGreenScreen = {
                    // Warm-up ARC VDA is solid green before the first paint —
                    // ignoring it avoids a TCP tear that flashes black/green.
                    if (ChromebookRecoverPolicy.shouldActOnGreenSample(paintedThisConnection)) {
                        val d = decoder
                        val now = System.currentTimeMillis()
                        // After paint: green samples mean the VDA might be wedged.
                        // Always request an IDR first; only rebuild the codec if
                        // green persists across another callback (to reduce the
                        // visible ~1s flash caused by releasing/recreating the codec).
                        greenEventStreak = if (now - lastGreenEventAtMs <= 2_000) {
                            greenEventStreak + 1
                        } else {
                            1
                        }
                        lastGreenEventAtMs = now
                        if (now - lastGreenKfAtMs >= 1_000) {
                            lastGreenKfAtMs = now
                            session?.sendControl(mapOf("type" to "kf"))
                        }
                        val shouldRebuild = greenEventStreak >= 2 &&
                            (now - lastGreenRecoverAtMs >= 10_000)
                        if (shouldRebuild) {
                            lastGreenRecoverAtMs = now
                            d?.rebuildCodecInPlace()
                        }
                        android.util.Log.w(
                            "MainActivity",
                            "green screen — streak=$greenEventStreak requesting kf${if (shouldRebuild) " + codec rebuild" else ""}",
                        )
                    } else {
                        android.util.Log.d(
                            "MainActivity",
                            "green sample ignored (warm-up, not yet painted)",
                        )
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
            session?.stop(reason = "appQuit")
            session = null
            advertiser?.stop()
            advertiser = null
        }
        decoder?.release()
        decoder = null
        cancelRecover()
        cancelStallWatch()
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
     *
     * Chromebook: only allow TCP tear before the first successful paint of
     * this session. Mid-session tears remount ARC VDA and look like random
     * black/green reconnects after long healthy streams.
     */
    private fun handleDecodeError(consecutiveErrors: Int) {
        val s = session ?: return
        val transport = s.activeVideoTransport
        if (transport == "udp") s.noteUdpDecodeError()
        when (ChromebookRecoverPolicy.onDecodeError(transport, consecutiveErrors)) {
            ChromebookRecoverPolicy.Action.RequestKeyframe,
            ChromebookRecoverPolicy.Action.RebuildCodec,
            -> {
                val now = System.currentTimeMillis()
                if (now - lastDecoderKfAtMs >= 2000) {
                    lastDecoderKfAtMs = now
                    s.sendControl(mapOf("type" to "kf"))
                }
            }
            ChromebookRecoverPolicy.Action.TearSession -> {
                if (allowForcedReconnect()) {
                    s.forcePeerReconnect("decode errors ($consecutiveErrors)")
                } else {
                    android.util.Log.w(
                        "MainActivity",
                        "decode error — skipping TCP tear (transport=$transport)",
                    )
                    val now = System.currentTimeMillis()
                    if (now - lastDecoderKfAtMs >= 2000) {
                        lastDecoderKfAtMs = now
                        s.sendControl(mapOf("type" to "kf"))
                    }
                }
            }
        }
    }

    private fun allowForcedReconnect(): Boolean {
        val now = System.currentTimeMillis()
        // Rate-limit TCP tears so we don't loop.
        // Even after the first successful paint, ARC VDA can wedge and stay
        // solid-green; in those severe cases we prefer a reconnect over
        // leaving the panel stuck indefinitely.
        if (now - forcedReconnectsWindowStartMs > 30_000) {
            forcedReconnectsWindowStartMs = now
            forcedReconnectsWindow = 0
        }
        if (forcedReconnectsWindow >= 2) return false
        // Chromebook: give VDA time to finish release/allocate between redials.
        val minGapMs = if (isChromebook) 8_000L else 3_000L
        if (now - lastForcedReconnectAtMs < minGapMs) return false
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

    private inner class ReceiverListener : ReceiverSession.Listener {
        override fun onConnected() {
            runOnUiThread {
                connected = true
                connectedAtMs = System.currentTimeMillis()
                paintedThisConnection = false
                // Fresh TCP session — don't inherit rate-limit debt from the
                // previous peer (otherwise a wedged first paint can't tear).
                forcedReconnectsWindow = 0
                forcedReconnectsWindowStartMs = connectedAtMs
                lastForcedReconnectAtMs = 0
                hostSleep.onConnected()
                hostDisplayOff = hostSleep.hostDisplayOff
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Chromebook ARC often leaves the ARC window below system
                // brightness — pin full brightness while the stream owns the panel.
                if (isChromebook) {
                    panelBacklight.pinFull()
                }
                applyImmersive()
                // Immersive + real panel size: re-announce if chrome changed
                // the window metrics between listen and fullscreen.
                val panel = PanelMetrics.of(this@MainActivity)
                session?.updatePanel(panel.wide, panel.high, panel.density)
                perf = PerfStats()
                scheduleStallWatch()
            }
        }

        override fun onDisconnected() {
            runOnUiThread {
                connected = false
                cursorController.hide()
                pendingSyncFrame = null
                cancelRecover()
                cancelStallWatch()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Mac parks after hostSleeping then ends TCP. Clear the blank
                // overlay so we don't look frozen/unclickable until a mystery tap;
                // wake reconnect will paint again when the Mac is usable.
                if (hostSleep.hostDisplayOff) {
                    hostSleep.wake()
                }
                hostDisplayOff = false
                panelBacklight.set(null)
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
            if (d.hasRendered) {
                val s = session
                val connectedAt = s?.connectedAtWallMs ?: 0L
                if (connectedAt > 0L && !paintedThisConnection) {
                    val ttff = System.currentTimeMillis() - connectedAt
                    val slow = ConnectTimingPolicy.isSlowTtff(ttff)
                    android.util.Log.i("MainActivity", "ttff=${ttff}ms slow=$slow")
                }
                paintedThisConnection = true
            }
            // Mid-session VDA death clears hasRendered — re-arm recover so we
            // don't stay black until the user manually reconnects.
            if (!d.hasRendered && session?.isConnected == true) {
                scheduleRecover(recoverIntervalMs)
            }
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
