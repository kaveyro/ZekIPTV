package com.example.iptv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private const val MAX_RECONNECT_ATTEMPTS = 5

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    media: PlayingMedia,
    nowPlaying: String?,
    resumeMs: Long,
    sleepMinutes: Int?,
    onBack: () -> Unit,
    onZap: ((Int) -> Unit)?,
    onCycleSleep: () -> Unit,
    onSaveResume: (String, Long) -> Unit
) {
    val context = LocalContext.current
    // Immer die aktuellen Referenzen verwenden (factory/Listener laufen nur einmal).
    val currentOnZap by rememberUpdatedState(onZap)
    val currentMedia by rememberUpdatedState(media)
    val currentOnSaveResume by rememberUpdatedState(onSaveResume)

    var showMenu by remember { mutableStateOf(false) }
    // Referenz auf die PlayerView, um den Tasten-Fokus nach Dialog/Steuerleiste zurückzuholen.
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    // Auto-Reconnect: Live-Streams reißen gern ab; mit Backoff neu verbinden.
    var reconnectAttempt by remember { mutableStateOf(0) }
    var reconnectTrigger by remember { mutableStateOf(0) }
    var playerError by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember {
        // Cross-Protocol-Redirects erlauben: VOD-/Serien-Streams dieses Anbieters leiten
        // von http auf https um — das folgt ExoPlayers HTTP-Quelle standardmäßig nicht.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("IPTV/1.2 (Android TV)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // Audio-Fokus anfordern + als Medien-Ton kennzeichnen: sorgt für korrekte
            // Tonausgabe/Routing auf TV-Geräten und pausiert/duckt andere Audioquellen.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
    }

    // Listener separat registrieren, damit er die Compose-States sieht.
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempt++
                    reconnectTrigger++
                } else {
                    playerError = "Wiedergabefehler: ${error.errorCodeName}"
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
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    // Kanal-/Medienwechsel ohne Player-Neuaufbau; VOD an gespeicherter Position fortsetzen.
    LaunchedEffect(media.url) {
        reconnectAttempt = 0
        playerError = null
        exoPlayer.setMediaItem(MediaItem.fromUri(media.url))
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

    // HOME/Standby: Wiedergabe pausieren; bei Rückkehr fortsetzen. Ressourcen freigeben.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_START -> exoPlayer.play()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveVodPosition(exoPlayer, currentMedia, currentOnSaveResume)
            exoPlayer.release()
        }
    }

    // Titel-Overlay kurz einblenden, wenn ein (neues) Medium startet.
    var overlayVisible by remember { mutableStateOf(false) }
    LaunchedEffect(media.url) {
        overlayVisible = true
        delay(4_000)
        overlayVisible = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // dispatchKeyEvent abfangen: DPAD hoch/runter zappt (nur Live, solange die
                // Transportleiste nicht sichtbar ist), MENÜ öffnet das Wiedergabe-Menü.
                object : PlayerView(ctx) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        val zap = currentOnZap
                        val isZapKey = event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                            event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
                            event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
                        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
                            showMenu = true
                            return true
                        }
                        // Wie am klassischen Fernseher: bei Live-TV zappt hoch/runter IMMER —
                        // sonst schluckt die (auch unabsichtlich eingeblendete) Steuerleiste die
                        // Tasten und Zappen wirkt unzuverlässig. Die Steuerleiste wird mit OK
                        // geöffnet und mit links/rechts bedient.
                        if (zap != null && isZapKey) {
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                when (event.keyCode) {
                                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
                                    else -> zap(-1)
                                }
                            }
                            return true // auch ACTION_UP konsumieren, sonst zeigt PlayerView die Leiste
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    player = exoPlayer
                    useController = true            // D-Pad-freundliche Standard-Transportleiste
                    // Steuerleiste NICHT automatisch einblenden: sonst schluckt sie nach dem
                    // ersten Zap alle DPAD-Tasten und weiteres Zappen ist ~5 s blockiert.
                    controllerAutoShow = false
                    keepScreenOn = true             // Bildschirm bleibt während der Wiedergabe an
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) // Spinner beim Start/Zappen
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // PlayerView ist standardmäßig nicht fokussierbar: ohne Fokus kommen
                    // Fernbedienungs-Tasten (Zapping/MENÜ) nach dem Ausblenden der
                    // Steuerleiste nicht mehr an. Explizit fokussierbar machen.
                    isFocusable = true
                    isFocusableInTouchMode = true
                    playerViewRef = this
                    requestFocus()
                }
            }
        )

        if (overlayVisible || reconnectAttempt > 0 || playerError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = media.title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                if (nowPlaying != null) {
                    Text(
                        text = "Jetzt: $nowPlaying",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.titleMedium
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

        if (showMenu) {
            PlayerMenuDialog(
                exoPlayer = exoPlayer,
                sleepMinutes = sleepMinutes,
                onCycleSleep = onCycleSleep,
                onDismiss = {
                    showMenu = false
                    // Fokus zurück zur PlayerView, damit Zapping/MENÜ weiter funktionieren.
                    playerViewRef?.requestFocus()
                }
            )
        }
    }
}

/** Position eines VOD-Titels sichern; kurz vor dem Ende zurücksetzen ("fertig gesehen"). */
private fun saveVodPosition(player: ExoPlayer, media: PlayingMedia, save: (String, Long) -> Unit) {
    if (media.isLive) return
    val duration = player.duration
    val position = player.currentPosition
    val effective = if (duration > 0 && position > duration - 60_000) 0L else position
    save(media.url, effective)
}

/** Wiedergabe-Menü (MENÜ-Taste): Tonspur-Auswahl und Sleep-Timer, D-Pad-bedienbar. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerMenuDialog(
    exoPlayer: ExoPlayer,
    sleepMinutes: Int?,
    onCycleSleep: () -> Unit,
    onDismiss: () -> Unit
) {
    // Tonspuren beim Öffnen einlesen.
    val audioGroups = remember { exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Wiedergabe",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                MenuRow(
                    label = "Sleep-Timer: " + (sleepMinutes?.let { "$it min" } ?: "Aus"),
                    onClick = onCycleSleep
                )

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
                        val label = format.label
                            ?: format.language?.uppercase()
                            ?: "Spur ${index + 1}"
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

                MenuRow(label = "Schließen", onClick = onDismiss)
            }
        }
    }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick)
            .background(
                if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}
