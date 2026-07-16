package com.example.iptv

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.iptv.ui.tvFocusFrame

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
    val hasChannels = mainViewModel.groups.value.isNotEmpty() || mainViewModel.visibleChannels.value.isNotEmpty()

    val hasRows = continueWatching.isNotEmpty() || recentChannels.isNotEmpty() || favoriteChannels.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
        ScreenTitle("Home")

        if (!hasRows) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 120.dp)) {
                Text(
                    text = if (hasChannels) {
                        "Gesehene Sender, Favoriten und angefangene Filme erscheinen hier."
                    } else {
                        "Willkommen! Über das Menü links unter „Einstellungen“ eine Playlist hinzufügen."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            return@Column
        }

        if (continueWatching.isNotEmpty()) {
            HomeRowTitle("Weiter schauen")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(continueWatching, key = { it.url }) { item ->
                    ContinueWatchingCard(item) { mainViewModel.playContinueWatching(item) }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        if (recentChannels.isNotEmpty()) {
            HomeRowTitle("Zuletzt gesehen")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Kein URL-Key: Playlists können denselben Stream mehrfach enthalten.
                itemsIndexed(recentChannels) { _, channel ->
                    ChannelCard(
                        channel = channel,
                        epg = mainViewModel.epgFor(channel),
                        onClick = { mainViewModel.selectChannel(channel) },
                        onLongClick = { onChannelLongPress(channel) }
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        if (favoriteChannels.isNotEmpty()) {
            HomeRowTitle("★ Favoriten")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(favoriteChannels) { _, channel ->
                    ChannelCard(
                        channel = channel,
                        epg = mainViewModel.epgFor(channel),
                        onClick = { mainViewModel.selectChannel(channel) },
                        onLongClick = { onChannelLongPress(channel) }
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
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(240.dp)
            .tvFocusFrame(onClick = onClick, focusedScale = 1.08f, restColor = Color.Transparent)
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
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .tvFocusFrame(onClick = onClick, onLongClick = onLongClick, focusedScale = 1.08f)
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
