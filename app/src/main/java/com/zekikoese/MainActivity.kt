package com.zekikoese

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.isTvDevice
import com.zekikoese.ui.theme.IptvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Playlist/EPG alle 12 h im Hintergrund aktualisieren.
        RefreshWorker.schedule(applicationContext)
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val themeMode by mainViewModel.themeMode
            val darkTheme = when (themeMode) {
                "light" -> false
                "system" -> isSystemInDarkTheme()
                else -> true // "dark" ist der TV-Standard
            }

            // Bedienoberfläche: Einstellung ("tv"/"phone") übersteuert die Geräte-Erkennung.
            val detectedTv = remember { applicationContext.isTvDevice() }
            val uiModeOverride by mainViewModel.uiModeOverride
            val isTv = when (uiModeOverride) {
                "tv" -> true
                "phone" -> false
                else -> detectedTv
            }
            // TV: Querformat erzwingen (ersetzt den früheren Manifest-Lock);
            // Handy: Rotation dem System/Nutzer überlassen.
            LaunchedEffect(isTv) {
                requestedOrientation = if (isTv) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            CompositionLocalProvider(LocalIsTv provides isTv) {
                IptvTheme(darkTheme = darkTheme, isTv = isTv) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        IptvApp(mainViewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun IptvApp(mainViewModel: MainViewModel = viewModel()) {
    val selectedChannel by mainViewModel.selectedChannel
    val vodMedia by mainViewModel.playingMedia
    val selectedVod by mainViewModel.selectedVod
    val selectedSeries by mainViewModel.selectedSeries

    // Live-Sender oder VOD/Episode — beides läuft im selben Player.
    val channel = selectedChannel
    val media = vodMedia ?: channel?.let { PlayingMedia(it.url, it.name, isLive = true) }

    // Live: EPG-Infos und Position in der aktuellen Zap-Liste für die Info-Leiste des Players.
    val liveChannel = if (media?.isLive == true) channel else null

    when {
        media != null -> VideoPlayer(
            media = media,
            mainViewModel = mainViewModel,
            epg = liveChannel?.let { mainViewModel.epgFor(it) } ?: EpgNowNext(null, null, null),
            channelNumber = liveChannel?.let { ch ->
                mainViewModel.visibleChannels.value
                    .indexOfFirst { it.url == ch.url }
                    .takeIf { it >= 0 }
                    ?.plus(1)
            },
            resumeMs = mainViewModel.resumeFor(media.url),
            sleepMinutes = mainViewModel.sleepTimerMinutes.value,
            onBack = { mainViewModel.stopPlayback() },
            onZap = if (media.isLive) {
                { delta -> mainViewModel.zapChannel(delta) }
            } else null,
            onSwapLast = if (media.isLive) {
                { mainViewModel.swapToPreviousChannel() }
            } else null,
            onCycleSleep = { mainViewModel.cycleSleepTimer() },
            onSaveResume = { url, position, duration -> mainViewModel.saveResume(url, position, duration) }
        )

        selectedVod != null -> VodDetailScreen(mainViewModel)

        selectedSeries != null -> EpisodesScreen(mainViewModel)

        // MainScreen ist die Shell: Navigations-Rail links + aktiver Bereich rechts.
        else -> MainScreen(mainViewModel)
    }
}
