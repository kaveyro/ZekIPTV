package com.zekikoese

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.zekikoese.ui.LocalInPictureInPicture
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.tvFocusFrame
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_RECONNECT_ATTEMPTS = 5
private const val DEFAULT_USER_AGENT = "IPTV/1.2 (Android TV)"
private const val MAX_NUMBER_DIGITS = 4

/** Ziffer einer Zahlentaste (Fernbedienung oder Nummernblock), sonst null. */
private fun digitOf(keyCode: Int): Int? = when (keyCode) {
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

@OptIn(UnstableApi::class)
private fun resizeModeOf(mode: String): Int = when (mode) {
    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun resizeLabelOf(mode: String): String = when (mode) {
    "zoom" -> "Zoom"
    "fill" -> "Strecken"
    else -> "Anpassen"
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    media: PlayingMedia,
    mainViewModel: MainViewModel,
    epg: EpgNowNext,
    channelNumber: Int?,
    resumeMs: Long,
    sleepMinutes: Int?,
    resizeMode: String,
    autoFrameRate: Boolean,
    timeshiftEnabled: Boolean,
    onCycleResize: () -> Unit,
    onJumpToNumber: ((Int) -> Boolean)?,
    onBack: () -> Unit,
    onZap: ((Int) -> Unit)?,
    onSwapLast: (() -> Unit)?,
    onCycleSleep: () -> Unit,
    onSaveResume: (String, Long, Long) -> Unit
) {
    val context = LocalContext.current
    // Immer die aktuellen Referenzen verwenden (factory/Listener laufen nur einmal).
    val currentOnZap by rememberUpdatedState(onZap)
    val currentOnSwapLast by rememberUpdatedState(onSwapLast)
    val currentMedia by rememberUpdatedState(media)
    val currentOnSaveResume by rememberUpdatedState(onSaveResume)
    val currentOnJumpToNumber by rememberUpdatedState(onJumpToNumber)
    val inPip = LocalInPictureInPicture.current

    var showMenu by remember { mutableStateOf(false) }
    // Senderlisten-Overlay (DPAD-LINKS bei Live): als MutableState, damit die einmalig
    // laufende View-Factory und der Visibility-Listener stets den aktuellen Wert sehen.
    val showChannelList = remember { mutableStateOf(false) }
    // DPAD-HOCH (Live): Info-Leiste erneut einblenden — Zähler als MutableState, damit die
    // einmalig laufende View-Factory ihn erhöhen kann und der LaunchedEffect neu anläuft.
    val infoTrigger = remember { mutableStateOf(0) }
    // Sender-Direktwahl: eingetippte Ziffern (als MutableState, damit die View-Factory sie sieht).
    val numberInput = remember { mutableStateOf("") }
    // Smartphone: Sichtbarkeit der Transportleiste — daran hängt die Touch-Button-Reihe.
    val isTv = LocalIsTv.current
    val controllerVisible = remember { mutableStateOf(false) }
    // Referenz auf die PlayerView, um den Tasten-Fokus nach Dialog/Steuerleiste zurückzuholen.
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    // Auto-Reconnect: Live-Streams reißen gern ab; mit Backoff neu verbinden.
    var reconnectAttempt by remember { mutableStateOf(0) }
    var reconnectTrigger by remember { mutableStateOf(0) }
    var playerError by remember { mutableStateOf<String?>(null) }
    // Timeshift: aktueller Rekorder + Abstand zum Live-Bild (ms) für Badge und Menü.
    val recorder = remember { mutableStateOf<TimeshiftRecorder?>(null) }
    var timeshiftActive by remember { mutableStateOf(false) }
    var liveOffsetMs by remember { mutableStateOf(0L) }
    // Nach Rückkehr aus dem Hintergrund neu einschalten (Aufnahme wird dort gestoppt).
    var retuneKey by remember { mutableStateOf(0) }
    // Timeshift-Start: bis der Rekorder bereit ist, ist der Player gestoppt (kein Buffering-Spinner).
    var timeshiftStarting by remember { mutableStateOf(false) }

    // Cross-Protocol-Redirects erlauben: VOD-/Serien-Streams dieses Anbieters leiten
    // von http auf https um — das folgt ExoPlayers HTTP-Quelle standardmäßig nicht.
    // Eigene Referenz, damit User-Agent/Referrer pro Stream gesetzt werden können (#EXTVLCOPT).
    val httpFactory = remember {
        DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
    }
    val exoPlayer = remember {
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        // FFmpeg-Decoder als Rückfallebene: Geräte-Decoder (inkl. Dolby-Passthrough am TV)
        // haben Vorrang, FFmpeg springt nur für sonst nicht abspielbare Tonformate ein.
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // Spul-Schritte der Steuerleiste: 10 s zurück / 30 s vor (VOD/Serien).
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            // Audio-Fokus anfordern + als Medien-Ton kennzeichnen: sorgt für korrekte
            // Tonausgabe/Routing auf TV-Geräten und pausiert/duckt andere Audioquellen.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Kopfhörer abgezogen / Bluetooth getrennt -> pausieren statt laut weiterspielen.
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    // MediaSession: Medientasten der Fernbedienung und System-Mediensteuerung. Eindeutige ID,
    // da beim Überblenden zwischen zwei Player-Instanzen kurz beide existieren können.
    val mediaSession = remember {
        MediaSession.Builder(context, exoPlayer)
            .setId("zekiptv-${System.nanoTime()}")
            .build()
    }

    // Listener separat registrieren, damit er die Compose-States sieht.
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val httpStatus = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                // 4xx (Zugang verweigert, nicht gefunden …) wird durch Wiederholen nicht besser —
                // sofort melden statt ~30 s Reconnect-Versuche abzuwarten (außer Timeout/Rate-Limit).
                val permanent = httpStatus != null && httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429
                if (!permanent && reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempt++
                    reconnectTrigger++
                } else {
                    reconnectAttempt = 0
                    playerError = playbackErrorMessage(error.errorCode, httpStatus, error.errorCodeName)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    reconnectAttempt = 0
                    playerError = null
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Verbindungsabbruch: mit wachsendem Abstand neu verbinden (2s, 4s, 6s, ...).
    LaunchedEffect(reconnectTrigger) {
        if (reconnectTrigger > 0) {
            delay(2_000L * reconnectAttempt)
            // Live: an den aktuellen Live-Rand springen — nach längerem Abbruch liegt die alte
            // Position sonst außerhalb des HLS-Fensters (BehindLiveWindowException).
            if (currentMedia.isLive) exoPlayer.seekToDefaultPosition()
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    // Kanal-/Medienwechsel ohne Player-Neuaufbau; VOD an gespeicherter Position fortsetzen.
    LaunchedEffect(media.url, timeshiftEnabled, retuneKey) {
        reconnectAttempt = 0
        playerError = null
        recorder.value?.stop()
        recorder.value = null
        timeshiftActive = false
        liveOffsetMs = 0L
        val userAgent = media.userAgent ?: DEFAULT_USER_AGENT
        httpFactory.setUserAgent(userAgent)
        httpFactory.setDefaultRequestProperties(
            media.referrer?.let { mapOf("Referer" to it) } ?: emptyMap()
        )
        val item = MediaItem.Builder()
            .setMediaMetadata(MediaMetadata.Builder().setTitle(media.title).build())
        if (media.isLive && timeshiftEnabled && TimeshiftRecorder.supports(media.url)) {
            // Erst die bisherige Verbindung schließen — viele Konten erlauben nur eine.
            exoPlayer.stop()
            val cacheDir = context.cacheDir
            val session = TimeshiftRecorder(
                url = media.url,
                userAgent = userAgent,
                referrer = media.referrer,
                dir = File(TimeshiftRecorder.baseDir(cacheDir), System.nanoTime().toString()),
                maxBytes = TimeshiftRecorder.maxBytesFor(cacheDir)
            )
            session.start()
            recorder.value = session
            timeshiftStarting = true
            val ready = try {
                session.awaitReady(15_000)
            } finally {
                timeshiftStarting = false
            }
            if (ready) {
                timeshiftActive = true
                item.setUri(Uri.fromFile(session.playlistFile))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    // Nah am Live-Rand starten (Standard wären 3 Segmentdauern Abstand).
                    .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(3_000).build())
            } else {
                // Aufnahme klappt nicht (z. B. kein TS-Stream) — direkt abspielen.
                session.stop()
                recorder.value = null
                item.setUri(media.url)
            }
        } else {
            item.setUri(media.url)
        }
        exoPlayer.setMediaItem(item.build())
        exoPlayer.prepare()
        if (!media.isLive && resumeMs > 10_000) {
            exoPlayer.seekTo(resumeMs)
        }
        exoPlayer.playWhenReady = true
        // Wiedergabeposition periodisch sichern (nur VOD/Serien).
        if (!media.isLive) {
            while (true) {
                delay(15_000)
                saveVodPosition(exoPlayer, currentMedia, currentOnSaveResume)
            }
        }
    }

    // Hardware-Zurück-Taste der Fernbedienung kehrt zur Liste zurück.
    BackHandler(onBack = onBack)

    // Smartphone: Wiedergabe im immersiven Vollbild — Status-/Navigationsleiste ausblenden und
    // ins Querformat drehen, damit das Bild den ganzen Schirm nutzt. Beim Verlassen zurücksetzen.
    // (Auf dem TV läuft ohnehin Vollbild-Querformat, daher nur für Touch-Geräte.)
    if (!isTv) {
        val activity = context as? Activity
        DisposableEffect(Unit) {
            val previousOrientation = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val controller = activity?.window?.let {
                WindowCompat.getInsetsController(it, it.decorView)
            }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                activity?.requestedOrientation =
                    previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // Abstand zum Live-Bild verfolgen (Timeshift-Badge, "Zum Live-Bild" im Menü).
    LaunchedEffect(media.url, timeshiftActive) {
        if (!media.isLive) return@LaunchedEffect
        while (true) {
            // currentLiveOffset braucht Uhrzeit-Tags (PROGRAM-DATE-TIME). Die lokale Timeshift-Liste
            // hat bewusst keine — sonst würde ExoPlayer nach einer Pause per Tempoanpassung heimlich
            // wieder zum Live-Bild aufholen. Dann: Fensterlänge minus Position.
            val offset = exoPlayer.currentLiveOffset.takeIf { it != C.TIME_UNSET }
                ?: exoPlayer.duration.takeIf { it != C.TIME_UNSET && exoPlayer.isCurrentMediaItemLive }
                    ?.let { (it - exoPlayer.currentPosition).coerceAtLeast(0L) }
            liveOffsetMs = offset ?: 0L
            delay(1_000)
        }
    }

    // HOME/Standby: Wiedergabe pausieren; bei Rückkehr fortsetzen. Ressourcen freigeben.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Nur fortsetzen, wenn vor dem Verlassen auch abgespielt wurde (nicht nach manueller Pause).
        var resumeOnStart = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = exoPlayer.playWhenReady
                    exoPlayer.pause()
                    // Keine endlose Hintergrund-Aufnahme: Timeshift stoppen, beim Zurückkehren neu starten.
                    if (recorder.value != null) {
                        recorder.value?.stop()
                        recorder.value = null
                        exoPlayer.stop()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (timeshiftActive && recorder.value == null) retuneKey++
                    else if (resumeOnStart) exoPlayer.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveVodPosition(exoPlayer, currentMedia, currentOnSaveResume)
            recorder.value?.stop()
            mediaSession.release()
            exoPlayer.release()
        }
    }

    // TV: Bildwiederholrate an das Video anpassen (25/50 fps -> 50 Hz, Filme -> 23,976/24 Hz),
    // damit Schwenks nicht ruckeln. Wir schalten den Anzeigemodus selbst — Media3s eigene
    // Frame-Rate-Strategie dann aus, sonst konkurrieren beide.
    val frameRateController = remember(autoFrameRate, isTv) {
        (context as? Activity)?.takeIf { autoFrameRate && isTv }?.let(::FrameRateController)
    }
    DisposableEffect(frameRateController) {
        if (frameRateController != null) {
            exoPlayer.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        }
        // Erst beim Verlassen des Players (nicht beim Zappen) den alten Modus wiederherstellen.
        onDispose { frameRateController?.restore() }
    }
    DisposableEffect(frameRateController, media.url) {
        val controller = frameRateController ?: return@DisposableEffect onDispose { }
        val estimator = FrameRateEstimator()
        val reported = AtomicBoolean(false)
        val mainHandler = Handler(Looper.getMainLooper())
        // Läuft auf dem Playback-Thread: Bildrate aus dem Format oder aus den Zeitstempeln.
        val listener = VideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
            if (reported.get()) return@VideoFrameMetadataListener
            val fps = format.frameRate.takeIf { it > 0f } ?: estimator.onFrame(presentationTimeUs)
            if (fps != null && reported.compareAndSet(false, true)) {
                mainHandler.post { controller.onFrameRate(fps) }
            }
        }
        exoPlayer.setVideoFrameMetadataListener(listener)
        onDispose { exoPlayer.clearVideoFrameMetadataListener(listener) }
    }

    // Direktwahl: 2 s nach der letzten Ziffer (oder sofort bei 4 Ziffern) umschalten.
    LaunchedEffect(numberInput.value) {
        val input = numberInput.value
        if (input.isEmpty()) return@LaunchedEffect
        if (input.length < MAX_NUMBER_DIGITS) delay(2_000)
        currentOnJumpToNumber?.invoke(input.toInt())
        numberInput.value = ""
    }

    // Info-Overlay kurz einblenden: bei jedem (neuen) Medium (auch nach Zappen) und
    // auf DPAD-HOCH (infoTrigger).
    var overlayVisible by remember { mutableStateOf(false) }
    LaunchedEffect(media.url, infoTrigger.value) {
        overlayVisible = true
        delay(5_000)
        overlayVisible = false
    }

    val retryFocus = remember { FocusRequester() }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // dispatchKeyEvent abfangen: DPAD hoch/runter zappt (nur Live, solange die
                // Transportleiste nicht sichtbar ist), MENÜ öffnet das Wiedergabe-Menü.
                object : PlayerView(ctx) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        // NUR die MENÜ-Taste (und ggf. physische Kanaltasten) abfangen.
                        // ALLE DPAD-Tasten gehen bewusst an die Standard-Steuerleiste, damit
                        // OK = Pause/Steuerleiste, Pfeile = navigieren usw. zuverlässig bleiben.
                        // (Zappen/Rücksprung laufen konfliktfrei über das MENÜ.)
                        // Solange die Senderliste offen ist, alle LEFT-Events schlucken — sonst
                        // zeigt das ACTION_UP des öffnenden Tastendrucks die Steuerleiste an.
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && showChannelList.value) {
                            return true
                        }
                        // HOCH = Programm-Info (nur Live, Steuerleiste unsichtbar). WICHTIG: beide
                        // Actions schlucken — die PlayerView blendet ihre Steuerleiste sonst schon
                        // beim ACTION_UP des Tastendrucks ein.
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                            currentMedia.isLive && !isControllerFullyVisible && !showChannelList.value
                        ) {
                            if (event.action == KeyEvent.ACTION_DOWN) infoTrigger.value++
                            return true
                        }
                        // Zifferntasten (nur Live): Sender-Direktwahl.
                        val digit = digitOf(event.keyCode)
                        if (digit != null && currentOnJumpToNumber != null) {
                            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                                numberInput.value.length < MAX_NUMBER_DIGITS &&
                                !(digit == 0 && numberInput.value.isEmpty()) // keine führende Null
                            ) {
                                numberInput.value += digit
                            }
                            return true
                        }
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_MENU -> {
                                    showMenu = true
                                    return true
                                }
                                KeyEvent.KEYCODE_DPAD_LEFT -> {
                                    // LINKS öffnet die Senderliste — nur bei Live und nur solange
                                    // die Steuerleiste unsichtbar ist (dort behält LINKS = Spulen).
                                    if (currentMedia.isLive && !isControllerFullyVisible && !showChannelList.value) {
                                        showChannelList.value = true
                                        return true
                                    }
                                }
                                KeyEvent.KEYCODE_CHANNEL_UP -> {
                                    currentOnZap?.let { it(+1); return true }
                                }
                                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                                    currentOnZap?.let { it(-1); return true }
                                }
                            }
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    player = exoPlayer
                    useController = true            // D-Pad-freundliche Standard-Transportleiste
                    // TV: Steuerleiste nicht automatisch einblenden — sie erscheint auf OK/Pfeil.
                    // Smartphone: beim Start/Antippen zeigen (Standard-Touch-Verhalten).
                    controllerAutoShow = !isTv
                    keepScreenOn = true             // Bildschirm bleibt während der Wiedergabe an
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) // Spinner beim Start/Zappen
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // Fokussierbar, damit MENÜ/Kanaltasten ankommen und die Steuerleiste reagiert.
                    isFocusable = true
                    isFocusableInTouchMode = true
                    // Wenn die Steuerleiste ausgeblendet wird, wandert der Fokus sonst ins Leere
                    // (die Controller-Buttons verschwinden) — dann käme MENÜ nicht mehr an.
                    // Fokus deshalb zurück auf die PlayerView holen.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            // Sichtbarkeit für die Touch-Button-Reihe (Smartphone) spiegeln.
                            controllerVisible.value = visibility == android.view.View.VISIBLE
                            // Nicht den Fokus an sich reißen, solange das Senderlisten-Overlay
                            // offen ist — sonst verliert dessen Liste die D-Pad-Steuerung.
                            if (visibility != android.view.View.VISIBLE && !showChannelList.value) {
                                requestFocus()
                            }
                        }
                    )
                    playerViewRef = this
                    requestFocus()
                }
            },
            update = { view ->
                view.resizeMode = resizeModeOf(resizeMode)
                // Bild-in-Bild: nur das Video, keine Steuerleiste.
                view.useController = !inPip
                if (inPip) view.hideController()
            }
        )

        // Timeshift: Rückstand zum Live-Bild oben rechts, solange man zeitversetzt schaut.
        // Handy: nicht über der Touch-Button-Reihe — bei sichtbarer Steuerleiste zeigt die Zeitleiste die Position.
        if (media.isLive && liveOffsetMs > 15_000 && !inPip && numberInput.value.isEmpty() &&
            (isTv || !controllerVisible.value)
        ) {
            val seconds = liveOffsetMs / 1000
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fast_rewind),
                    contentDescription = "Zeitversetzt",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "−%d:%02d".format(seconds / 60, seconds % 60),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // Direktwahl-Anzeige oben rechts.
        if (numberInput.value.isNotEmpty() && !inPip) {
            Text(
                text = numberInput.value,
                color = Color.White,
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }

        // Fehler: Fokus auf "Erneut versuchen", damit die Fernbedienung die Knöpfe erreicht.
        LaunchedEffect(playerError != null) {
            if (playerError == null || !isTv) return@LaunchedEffect
            repeat(10) {
                awaitFrame()
                if (runCatching { retryFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }

        // Timeshift startet: Hinweis statt minutenlang schwarzem Bild.
        if (timeshiftStarting && !inPip) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Sender wird gestartet (Timeshift)…",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        if (!inPip && (overlayVisible || reconnectAttempt > 0 || playerError != null)) {
            // Uhrzeit zum Einblende-Zeitpunkt (Overlay lebt nur wenige Sekunden).
            val timeText = remember(overlayVisible, infoTrigger.value, media.url) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            }
            // Scrim-Verlauf von oben statt flacher schwarzer Box — kinoartiger Look.
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                        )
                    )
                    // Handy: unter Zurück-Knopf und Knopfreihe beginnen, solange diese sichtbar sind.
                    .padding(
                        start = 40.dp,
                        end = 40.dp,
                        top = if (!isTv && controllerVisible.value) 76.dp else 28.dp,
                        bottom = 28.dp
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // Sendernummer (Position in der aktuellen Zap-Liste) vor dem Titel.
                        text = channelNumber?.let { "$it · ${media.title}" } ?: media.title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = timeText,
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (epg.now != null) {
                    Text(
                        text = "Jetzt: ${epg.now}",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    val progress = epg.progress
                    if (progress != null) {
                        Spacer(Modifier.height(6.dp))
                        // Fortschritt der laufenden Sendung.
                        Box(
                            modifier = Modifier
                                .width(360.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.25f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(4.dp)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (epg.next != null) {
                    Text(
                        text = "Gleich: ${epg.next}",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (reconnectAttempt > 0) {
                    Text(
                        text = "Verbindung unterbrochen – neuer Versuch ($reconnectAttempt/$MAX_RECONNECT_ATTEMPTS)…",
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                val error = playerError
                if (error != null) {
                    Text(
                        text = error,
                        color = Color(0xFFFFB4AB),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    // Kein Sackgassen-Fehler: direkt neu versuchen oder (Live) weiterzappen.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            text = "Erneut versuchen",
                            onClick = {
                                playerError = null
                                reconnectAttempt = 0
                                retuneKey++
                            },
                            modifier = Modifier.focusRequester(retryFocus)
                        )
                        val zap = currentOnZap
                        if (zap != null) {
                            TvButton(
                                text = "Nächster Sender",
                                onClick = { zap(+1) },
                                containerColor = Color.White.copy(alpha = 0.15f),
                                contentColor = Color.White
                            )
                        }
                    }
                }
                if (sleepMinutes != null) {
                    Text(
                        text = "Sleep-Timer: $sleepMinutes min",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Smartphone: sichtbarer Zurück-Button oben links (verlässt die Wiedergabe) — erscheint
        // zusammen mit der Transportleiste.
        if (!isTv && !inPip && controllerVisible.value && !showChannelList.value) {
            PhoneBackButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            )
        }

        // Smartphone: Touch-Buttons für die sonst nur per Fernbedienung erreichbaren
        // Funktionen (Programm-Info, Zappen, Wiedergabe-Menü). Erscheinen zusammen mit der
        // Transportleiste (Tippen auf das Bild). Die früher hier verankerte Senderliste
        // entfällt auf dem Handy — Senderwechsel per Zap-Buttons bzw. Zurück zur Live-Liste.
        if (!isTv && !inPip && controllerVisible.value && !showChannelList.value) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (media.isLive) {
                    IconButton(onClick = { infoTrigger.value++ }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Programm-Info",
                            tint = Color.White
                        )
                    }
                }
                currentOnZap?.let { zap ->
                    IconButton(onClick = { zap(+1) }) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Nächster Sender",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { zap(-1) }) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Vorheriger Sender",
                            tint = Color.White
                        )
                    }
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Wiedergabe-Menü",
                        tint = Color.White
                    )
                }
            }
        }

        // Senderliste als Overlay (nur Live, nur TV): Zappen per D-Pad ohne den Player zu
        // verlassen. Auf dem Handy entfällt das Overlay bewusst (Zap-Buttons / Zurück zur Liste).
        if (media.isLive && isTv) {
            PlayerChannelOverlay(
                visible = showChannelList.value,
                mainViewModel = mainViewModel,
                currentUrl = media.url,
                onDismiss = {
                    showChannelList.value = false
                    // Fokus zurück zur PlayerView, damit MENÜ/LINKS/Steuerleiste weiter reagieren.
                    playerViewRef?.requestFocus()
                }
            )
        }

        if (showMenu && !inPip) {
            PlayerMenuDialog(
                exoPlayer = exoPlayer,
                sleepMinutes = sleepMinutes,
                resizeMode = resizeMode,
                onCycleResize = onCycleResize,
                onGoLive = if (media.isLive && liveOffsetMs > 15_000) {
                    { exoPlayer.seekToDefaultPosition(); exoPlayer.play() }
                } else null,
                onZap = currentOnZap,
                onSwapLast = currentOnSwapLast,
                onCycleSleep = onCycleSleep,
                onDismiss = {
                    showMenu = false
                    // Fokus zurück zur PlayerView, damit MENÜ/Steuerleiste weiter reagieren.
                    playerViewRef?.requestFocus()
                }
            )
        }
    }
}

/** Position eines VOD-Titels sichern; kurz vor dem Ende zurücksetzen ("fertig gesehen"). */
private fun saveVodPosition(player: ExoPlayer, media: PlayingMedia, save: (String, Long, Long) -> Unit) {
    if (media.isLive) return
    val duration = player.duration
    val position = player.currentPosition
    val effective = if (duration > 0 && position > duration - 60_000) 0L else position
    save(media.url, effective, if (duration > 0) duration else 0L)
}

/** Wiedergabe-Menü (MENÜ-Taste): Tonspur-Auswahl und Sleep-Timer, D-Pad-bedienbar. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerMenuDialog(
    exoPlayer: ExoPlayer,
    sleepMinutes: Int?,
    resizeMode: String,
    onCycleResize: () -> Unit,
    onGoLive: (() -> Unit)?,
    onZap: ((Int) -> Unit)?,
    onSwapLast: (() -> Unit)?,
    onCycleSleep: () -> Unit,
    onDismiss: () -> Unit
) {
    // Ton- und Untertitelspuren beim Öffnen einlesen.
    val audioGroups = remember { exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO } }
    val textGroups = remember { exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }
    // Senderwechsel gehört nur ins TV-Menü (D-Pad-Bedienung). Auf dem Handy erledigen die
    // Zap-Buttons direkt im Player das — die Einträge wären dort redundant.
    val isTv = LocalIsTv.current
    // Bei vielen Ton-/Untertitelspuren kann das Menü länger als der Bildschirm werden —
    // daher scrollbar und in der Höhe auf 90 % der Bildschirmhöhe begrenzt.
    val menuScroll = rememberScrollState()
    val maxMenuHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = maxMenuHeight)
                    .verticalScroll(menuScroll)
                    .padding(24.dp)
            ) {
                Text(
                    "Wiedergabe",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Timeshift: zurück zum aktuellen Live-Bild.
                if (onGoLive != null) {
                    MenuRow(label = "Zum Live-Bild", iconRes = R.drawable.ic_fast_forward, onClick = { onGoLive(); onDismiss() })
                }

                // Senderwechsel (nur Live, nur TV) — konfliktfrei per Menü statt DPAD.
                if (isTv && onZap != null) {
                    MenuRow(label = "▲ Nächster Sender", onClick = { onZap(+1); onDismiss() })
                    MenuRow(label = "▼ Vorheriger Sender", onClick = { onZap(-1); onDismiss() })
                }
                if (isTv && onSwapLast != null) {
                    MenuRow(label = "Zuletzt gesehener Sender", onClick = { onSwapLast(); onDismiss() })
                }

                MenuRow(
                    label = "Sleep-Timer: " + (sleepMinutes?.let { "$it min" } ?: "Aus"),
                    onClick = onCycleSleep
                )
                MenuRow(label = "Bildformat: ${resizeLabelOf(resizeMode)}", onClick = onCycleResize)

                if (audioGroups.isNotEmpty()) {
                    Text(
                        "Tonspur",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    MenuRow(
                        label = "Automatisch",
                        onClick = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .build()
                            onDismiss()
                        }
                    )
                    audioGroups.forEachIndexed { index, group ->
                        val format = group.getTrackFormat(0)
                        // Sprache ausgeschrieben + Format/Kanäle (z. B. "Deutsch · Dolby Digital · 5.1").
                        val label = audioTrackLabel(
                            label = format.label,
                            language = format.language,
                            mimeType = format.sampleMimeType,
                            channelCount = format.channelCount,
                            index = index
                        )
                        MenuRow(
                            label = (if (group.isSelected) "✓ " else "") + label,
                            onClick = {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                    .build()
                                onDismiss()
                            }
                        )
                    }
                }

                if (textGroups.isNotEmpty()) {
                    Text(
                        "Untertitel",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    MenuRow(
                        label = "Aus",
                        onClick = {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            onDismiss()
                        }
                    )
                    textGroups.forEachIndexed { index, group ->
                        val format = group.getTrackFormat(0)
                        val label = format.label
                            ?: format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { lang ->
                                Locale.forLanguageTag(lang).getDisplayLanguage(Locale.GERMAN)
                                    .ifBlank { lang.uppercase() }
                                    .replaceFirstChar { it.titlecase(Locale.GERMAN) }
                            }
                            ?: "Untertitel ${index + 1}"
                        MenuRow(
                            label = (if (group.isSelected) "✓ " else "") + label,
                            onClick = {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                    .build()
                                onDismiss()
                            }
                        )
                    }
                }

                MenuRow(label = "Schließen", onClick = onDismiss)
            }
        }
    }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuRow(label: String, iconRes: Int? = null, onClick: () -> Unit) {
    DialogRow(label = label, iconRes = iconRes, maxLines = 2, onClick = onClick)
}
