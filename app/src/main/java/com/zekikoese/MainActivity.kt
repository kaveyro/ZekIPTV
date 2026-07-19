package com.zekikoese

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun IptvApp(mainViewModel: MainViewModel = viewModel()) {
    val selectedChannel by mainViewModel.selectedChannel
    val vodMedia by mainViewModel.playingMedia
    val selectedVod by mainViewModel.selectedVod
    val selectedSeries by mainViewModel.selectedSeries
    val focusedBackdrop by mainViewModel.focusedBackdrop

    val media = vodMedia ?: selectedChannel?.let { PlayingMedia(it.url, it.name, isLive = true) }
    val liveChannel = if (media?.isLive == true) selectedChannel else null

    Box(modifier = Modifier.fillMaxSize()) {
        // Globaler immersiver Hintergrund: zeigt das fokussierte Logo/Poster dezent blurred.
        // Nur aktiv in Filme/Serien/Suche, um in Live/Settings Unruhe zu vermeiden.
        val showBackdrop = focusedBackdrop != null && media == null &&
            mainViewModel.currentDestination.value in setOf(NavDestination.MOVIES, NavDestination.SERIES, NavDestination.SEARCH)

        if (showBackdrop) {
            AsyncImage(
                model = focusedBackdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.22f, // Sehr dezent
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.4f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
        }

        SharedTransitionLayout {
            AnimatedContent(
                targetState = when {
                    media != null -> "player"
                    selectedVod != null -> "vod_detail"
                    selectedSeries != null -> "series_detail"
                    else -> "main"
                },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screenTransition"
            ) { target ->
                when (target) {
                    "player" -> media?.let {
                        VideoPlayer(
                            media = it,
                            mainViewModel = mainViewModel,
                            epg = liveChannel?.let { mainViewModel.epgFor(it) } ?: EpgNowNext(null, null, null),
                            channelNumber = liveChannel?.let { ch ->
                                mainViewModel.visibleChannels.value
                                    .indexOfFirst { it.url == ch.url }
                                    .takeIf { it >= 0 }
                                    ?.plus(1)
                            },
                            resumeMs = mainViewModel.resumeFor(it.url),
                            sleepMinutes = mainViewModel.sleepTimerMinutes.value,
                            onBack = { mainViewModel.stopPlayback() },
                            onZap = if (it.isLive) {
                                { delta -> mainViewModel.zapChannel(delta) }
                            } else null,
                            onSwapLast = if (it.isLive) {
                                { mainViewModel.swapToPreviousChannel() }
                            } else null,
                            onCycleSleep = { mainViewModel.cycleSleepTimer() },
                            onSaveResume = { url, position, duration -> mainViewModel.saveResume(url, position, duration) }
                        )
                    }

                    "vod_detail" -> VodDetailScreen(mainViewModel)

                    "series_detail" -> EpisodesScreen(mainViewModel)

                    else -> MainScreen(mainViewModel)
                }
            }
        }
    }
}
