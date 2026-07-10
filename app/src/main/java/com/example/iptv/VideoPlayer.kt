package com.example.iptv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(url: String, onBack: () -> Unit) {
    val context = LocalContext.current

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
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
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

    // Hardware-Zurück-Taste der Fernbedienung kehrt zur Kanalliste zurück.
    BackHandler(onBack = onBack)

    // Player-Ressourcen freigeben, wenn die Wiedergabe verlassen wird.
    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true            // D-Pad-freundliche Standard-Transportleiste
                keepScreenOn = true             // Bildschirm bleibt während der Wiedergabe an
                setShowNextButton(false)
                setShowPreviousButton(false)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        }
    )
}
