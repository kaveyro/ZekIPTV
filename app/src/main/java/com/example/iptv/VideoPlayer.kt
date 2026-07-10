package com.example.iptv

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    channel: Channel,
    onBack: () -> Unit,
    onZap: (Int) -> Unit
) {
    val context = LocalContext.current
    // Immer die aktuelle Callback-Referenz verwenden (factory läuft nur einmal).
    val currentOnZap by rememberUpdatedState(onZap)

    // ExoPlayer erkennt HLS (.m3u8), DASH, progressive Streams automatisch anhand der URL,
    // da die passenden Media3-Module (inkl. HLS) als Dependency eingebunden sind.
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            // Audio-Fokus anfordern + als Medien-Ton kennzeichnen: sorgt für korrekte
            // Tonausgabe/Routing auf TV-Geräten und pausiert/duckt andere Audioquellen.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Toast.makeText(
                            context,
                            "Wiedergabefehler: ${error.errorCodeName}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }
    }

    // Kanalwechsel (Zapping) ohne Player-Neuaufbau: nur das MediaItem tauschen.
    LaunchedEffect(channel.url) {
        exoPlayer.setMediaItem(MediaItem.fromUri(channel.url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Hardware-Zurück-Taste der Fernbedienung kehrt zur Kanalliste zurück.
    BackHandler(onBack = onBack)

    // HOME/Standby: Wiedergabe pausieren (nicht im Hintergrund weiterspielen);
    // bei Rückkehr fortsetzen. Ressourcen beim Verlassen freigeben.
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
            exoPlayer.release()
        }
    }

    // Sendername kurz einblenden, wenn ein (neuer) Kanal startet.
    var overlayVisible by remember { mutableStateOf(false) }
    LaunchedEffect(channel) {
        overlayVisible = true
        delay(3_000)
        overlayVisible = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // dispatchKeyEvent abfangen: solange die Transportleiste nicht sichtbar ist,
                // zappt DPAD hoch/runter zum nächsten/vorherigen Sender (klassisches TV-Verhalten).
                object : PlayerView(ctx) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN && !isControllerFullyVisible) {
                            when (event.keyCode) {
                                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                                    currentOnZap(+1); return true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                                    currentOnZap(-1); return true
                                }
                            }
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    player = exoPlayer
                    useController = true            // D-Pad-freundliche Standard-Transportleiste
                    keepScreenOn = true             // Bildschirm bleibt während der Wiedergabe an
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) // Spinner beim Senderstart/Zappen
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            }
        )

        if (overlayVisible) {
            Text(
                text = channel.name,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}
