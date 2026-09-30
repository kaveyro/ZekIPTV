package com.zekikoese

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.zekikoese.ui.LocalInPictureInPicture
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.isTvDevice
import com.zekikoese.ui.theme.IptvTheme

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    // Aktuell aufgelöste Bedienoberfläche (für Bild-in-Bild, das es nur auf dem Handy gibt).
    private var isTvUi = true
    private val inPictureInPicture = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ab targetSdk 35 Pflicht: App zeichnet hinter die Systemleisten; die Screens setzen
        // die Insets selbst (Handy-Shell per Scaffold, Detailseiten unten in IptvApp).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Playlist/EPG alle 12 h im Hintergrund aktualisieren.
        RefreshWorker.schedule(applicationContext)
        // Timeshift-Reste einer früheren (z. B. abgestürzten) Sitzung entfernen.
        if (savedInstanceState == null) {
            Thread { TimeshiftRecorder.cleanUp(cacheDir) }.start()
        }
        setContent {
            val mainViewModel = this@MainActivity.mainViewModel
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
                isTvUi = isTv
                requestedOrientation = if (isTv) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }

            // Android 17+: Heimnetz-Berechtigung anfragen, sobald das ViewModel sie braucht.
            val localNetworkLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted -> mainViewModel.onLocalNetworkPermissionResult(granted) }
            val localNetworkRequest by mainViewModel.localNetworkPermissionRequest
            LaunchedEffect(localNetworkRequest) {
                if (localNetworkRequest) {
                    runCatching { localNetworkLauncher.launch(MainViewModel.LOCAL_NETWORK_PERMISSION) }
                        .onFailure { mainViewModel.onLocalNetworkPermissionResult(false) }
                }
            }

            // Android 12+: Bild-in-Bild automatisch beim Verlassen starten (flüssigerer Übergang).
            val playing = mainViewModel.playingMedia.value != null || mainViewModel.selectedChannel.value != null
            LaunchedEffect(playing, isTv) {
                updateAutoEnterPip(playing && !isTv)
            }

            CompositionLocalProvider(
                LocalIsTv provides isTv,
                LocalInPictureInPicture provides inPictureInPicture.value
            ) {
                IptvTheme(darkTheme = darkTheme, isTv = isTv) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        IptvApp(mainViewModel)
                    }
                }
            }
        }
    }

    private fun updateAutoEnterPip(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            runCatching {
                setPictureInPictureParams(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational(16, 9))
                        .setAutoEnterEnabled(enabled)
                        .build()
                )
            }
        }
    }

    /**
     * Handy, Android 8–11: beim Verlassen der App (HOME) während der Wiedergabe in Bild-in-Bild
     * wechseln. Ab Android 12 übernimmt das setAutoEnterEnabled (siehe [updateAutoEnterPip]).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val playing = mainViewModel.playingMedia.value != null || mainViewModel.selectedChannel.value != null
        // SDK-Prüfung direkt hier (nicht in einer Hilfsfunktion), damit Lint den API-26-Aufruf erkennt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isTvUi && playing &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture.value = isInPictureInPictureMode
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

    val media = vodMedia ?: selectedChannel?.let {
        PlayingMedia(it.url, it.name, isLive = true, userAgent = it.userAgent, referrer = it.referrer)
    }
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
                            resizeMode = mainViewModel.resizeMode.value,
                            autoFrameRate = mainViewModel.autoFrameRate.value,
                            timeshiftEnabled = mainViewModel.timeshift.value,
                            onCycleResize = { mainViewModel.cycleResizeMode() },
                            onJumpToNumber = if (it.isLive) {
                                { number -> mainViewModel.jumpToChannelNumber(number) }
                            } else null,
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

                    // Detailseiten liegen außerhalb des Scaffolds — Systemleisten selbst freihalten.
                    "vod_detail" -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                        VodDetailScreen(mainViewModel)
                    }

                    "series_detail" -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                        EpisodesScreen(mainViewModel)
                    }

                    else -> MainScreen(mainViewModel)
                }
            }
        }
    }
}
