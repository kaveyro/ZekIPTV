package com.example.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.iptv.ui.theme.IptvTheme

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
            IptvTheme(darkTheme = darkTheme, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    IptvApp(mainViewModel)
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
    val showSettings by mainViewModel.showSettings

    // Live-Sender oder VOD/Episode — beides läuft im selben Player.
    val channel = selectedChannel
    val media = vodMedia ?: channel?.let { PlayingMedia(it.url, it.name, isLive = true) }

    when {
        media != null -> VideoPlayer(
            media = media,
            nowPlaying = if (media.isLive) channel?.let { mainViewModel.epgFor(it).now } else null,
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
            onSaveResume = { url, position -> mainViewModel.saveResume(url, position) }
        )

        selectedVod != null -> VodDetailScreen(mainViewModel)

        selectedSeries != null -> EpisodesScreen(mainViewModel)

        showSettings -> SettingsScreen(mainViewModel)

        else -> MainScreen(mainViewModel)
    }
}
