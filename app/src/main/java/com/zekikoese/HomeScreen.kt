package com.zekikoese

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.tvFocusFrame
import kotlinx.coroutines.android.awaitFrame

/**
 * Home: horizontale Reihen mit "Weiter schauen" (angefangene Filme/Episoden),
 * "Zuletzt gesehen" (Sender) und "★ Favoriten" (Sender) — alles offline ableitbar.
 */
@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    onChannelLongPress: (Channel) -> Unit
) {
    val continueWatching by mainViewModel.continueWatching
    val recentChannels by mainViewModel.recentChannels
    val favoriteChannels by mainViewModel.favoriteChannels
    val favorites by mainViewModel.favorites
    val hasChannels = mainViewModel.groups.value.isNotEmpty() || mainViewModel.visibleChannels.value.isNotEmpty()

    val hasRows = continueWatching.isNotEmpty() || recentChannels.isNotEmpty() || favoriteChannels.isNotEmpty()
    // Echter Leerzustand erst nach dem Laden der gespeicherten Sender (sonst kurzes Aufblitzen).
    val noPlaylist = mainViewModel.startupDone.value && !hasChannels

    // Beim App-Start (und nach Rückkehr zu Home) den Fokus auf die erste Karte setzen,
    // statt ihn auf der Navigations-Rail zu lassen. Nur auf dem TV — programmatischer
    // Fokus würde auf Touch-Geräten den Fokusrahmen auf eine Karte malen.
    val isTv = LocalIsTv.current
    val pendingHomeFocus by mainViewModel.pendingHomeFocus
    val firstCardFocus = remember { FocusRequester() }
    val addPlaylistFocus = remember { FocusRequester() }
    LaunchedEffect(pendingHomeFocus, hasRows, noPlaylist) {
        if (!pendingHomeFocus) return@LaunchedEffect
        // Ohne Inhalt: Fokus auf "Playlist hinzufügen" (sonst bliebe er auf der Rail).
        val target = when {
            hasRows -> firstCardFocus
            noPlaylist -> addPlaylistFocus
            else -> return@LaunchedEffect
        }
        if (!isTv) {
            mainViewModel.pendingHomeFocus.value = false
            return@LaunchedEffect
        }
        repeat(10) {
            awaitFrame()
            if (runCatching { target.requestFocus() }.isSuccess) {
                mainViewModel.pendingHomeFocus.value = false
                return@LaunchedEffect
            }
        }
        mainViewModel.pendingHomeFocus.value = false
    }

    // Zuletzt fokussierter Sender — die MENÜ-Taste öffnet dafür das Aktionsmenü.
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Menu) {
                    focusedChannel?.let { onChannelLongPress(it); true } ?: false
                } else {
                    false
                }
            }
            .padding(start = 16.dp, end = 32.dp, top = 24.dp, bottom = 24.dp)
    ) {
        ScreenTitle("Home")

        if (!hasRows) {
            EmptyState(
                icon = Icons.Filled.Home,
                title = "Willkommen bei ZekIPTV",
                subtitle = if (!noPlaylist) {
                    "Gesehene Sender, Favoriten und angefangene Filme erscheinen hier."
                } else {
                    "Füge deine erste Playlist hinzu — als M3U-Adresse oder mit deinem Xtream-Zugang."
                },
                action = if (!noPlaylist) null else {
                    {
                        TvButton(
                            text = "Playlist hinzufügen",
                            onClick = { mainViewModel.showPlaylistDialog.value = true },
                            modifier = Modifier.focusRequester(addPlaylistFocus)
                        )
                    }
                }
            )
            return@Column
        }

        if (continueWatching.isNotEmpty()) {
            HomeRowTitle("Weiter schauen")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(continueWatching) { index, item ->
                    ContinueWatchingCard(
                        item = item,
                        modifier = if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                        onFocusChange = { focused ->
                            if (focused) mainViewModel.focusedBackdrop.value = item.poster
                        }
                    ) { mainViewModel.playContinueWatching(item) }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        // ★ Favoriten als kompakte Karten-Reihe VOR der Liste
        if (favoriteChannels.isNotEmpty()) {
            HomeRowTitle("★ Favoriten")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(favoriteChannels) { index, channel ->
                    ChannelCard(
                        channel = channel,
                        epg = mainViewModel.epgFor(channel),
                        modifier = if (index == 0 && continueWatching.isEmpty()) {
                            Modifier.focusRequester(firstCardFocus)
                        } else Modifier,
                        // Aus den Favoriten heraus wird innerhalb der Favoriten gezappt.
                        onClick = { mainViewModel.selectChannel(channel, favoriteChannels) },
                        onLongClick = { onChannelLongPress(channel) },
                        onFocusChange = { focused ->
                            if (focused) {
                                focusedChannel = channel
                                mainViewModel.focusedBackdrop.value = channel.logo
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        // Zuletzt gesehene Sender
        if (recentChannels.isNotEmpty()) {
            HomeRowTitle("Zuletzt gesehen")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recentChannels.forEachIndexed { index, channel ->
                    ChannelRow(
                        channel = channel,
                        isFavorite = channel.url in favorites,
                        epg = mainViewModel.epgFor(channel),
                        showGroup = true,
                        modifier = if (index == 0 && continueWatching.isEmpty() && favoriteChannels.isEmpty()) {
                            Modifier.focusRequester(firstCardFocus)
                        } else Modifier,
                        // Zappen in der vollständigen Liste (die Verlaufsliste ändert sich beim Zappen).
                        onClick = { mainViewModel.selectChannel(channel, mainViewModel.channelsForGroup(null)) },
                        onLongClick = { onChannelLongPress(channel) },
                        onMore = { onChannelLongPress(channel) },
                        onFocusChange = { focused ->
                            if (focused) {
                                focusedChannel = channel
                                mainViewModel.focusedBackdrop.value = channel.logo
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun HomeRowTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

/** Querformat-Karte eines angefangenen Films/einer Episode mit Fortschrittsbalken. */
@Composable
private fun ContinueWatchingCard(
    item: ContinueWatchingItem,
    modifier: Modifier = Modifier,
    onFocusChange: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .width(240.dp)
            .tvFocusFrame(
                onClick = onClick,
                focusedScale = 1.08f,
                restColor = Color.Transparent,
                onFocusChange = onFocusChange,
                // Platz für die Vergrößerung: verhindert Abschneiden an den Reihen-Rändern.
                focusRoom = 10.dp
            )
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (item.poster != null) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = item.title.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            val progress = item.progress
            if (progress != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.tertiary)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/** Kompakte Sender-Karte für Home-Reihen (Logo + Name + "Jetzt läuft"). */
@Composable
private fun ChannelCard(
    channel: Channel,
    epg: EpgNowNext,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocusChange: ((Boolean) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .width(180.dp)
            .tvFocusFrame(
                onClick = onClick,
                onLongClick = onLongClick,
                focusedScale = 1.08f,
                onFocusChange = onFocusChange,
                focusRoom = 10.dp
            )
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        ) {
            if (channel.logo != null) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).align(Alignment.Center)
                )
            } else {
                Text(
                    text = channel.name.take(1).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = channel.name,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
        )
        if (epg.now != null) {
            Text(
                text = epg.now,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            val progress = epg.progress
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(3.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}
