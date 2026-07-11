package com.example.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mainViewModel: MainViewModel = viewModel()) {
    val url by mainViewModel.url
    val search by mainViewModel.searchQuery
    val uiState by mainViewModel.uiState
    val channels by mainViewModel.visibleChannels
    val groups by mainViewModel.groups
    val selectedGroup by mainViewModel.selectedGroup
    val favorites by mainViewModel.favorites

    val hasChannels = channels.isNotEmpty() || groups.isNotEmpty()

    val focusManager = LocalFocusManager.current
    // Compose-Textfelder konsumieren DPAD-Tasten für die Cursor-Steuerung und werden so
    // zur Fokus-Falle für die Fernbedienung. DPAD_DOWN reicht den Fokus explizit weiter.
    val escapeDownOnDpad = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            focusManager.moveFocus(FocusDirection.Down)
            true
        } else {
            false
        }
    }

    val listState = rememberLazyListState()
    val listFocusRequester = remember { FocusRequester() }
    val targetFocusIndex = mainViewModel.lastFocusedIndex.coerceIn(0, maxOf(0, channels.lastIndex))
    val pendingListFocus by mainViewModel.pendingListFocus

    // Fokus-Wiederherstellung NUR bei Erstladung oder Rückkehr aus dem Player (explizites Flag).
    // Nicht an channels.size koppeln — sonst wird beim Tippen in der Suche oder beim
    // Gruppenwechsel der Fokus aus dem gerade bedienten Element gestohlen.
    LaunchedEffect(pendingListFocus, channels.size) {
        if (pendingListFocus && channels.isNotEmpty()) {
            listState.scrollToItem(targetFocusIndex)
            runCatching { listFocusRequester.requestFocus() }
            mainViewModel.pendingListFocus.value = false
        }
    }

    // BACK setzt erst Filter zurück (Suche/Gruppe), statt die App sofort zu beenden.
    BackHandler(enabled = selectedGroup != null || search.isNotEmpty()) {
        mainViewModel.onSearchChange("")
        mainViewModel.selectGroup(null)
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Kopfzeile: Suche + Einstellungen-Umschalter
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("IPTV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
            if (hasChannels) {
                OutlinedTextField(
                    value = search,
                    onValueChange = mainViewModel::onSearchChange,
                    label = { Text("Suche") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).then(escapeDownOnDpad)
                )
                Spacer(Modifier.width(16.dp))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Button(onClick = { mainViewModel.showSettings.value = true }) {
                Text("Einstellungen")
            }
        }

        // Gruppen-/Kategorie-Auswahl
        if (groups.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    GroupChip("Alle", selected = selectedGroup == null) {
                        mainViewModel.selectGroup(null)
                    }
                }
                items(groups) { group ->
                    GroupChip(group, selected = selectedGroup == group) {
                        mainViewModel.selectGroup(group)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Inhalt je nach Zustand
        Box(modifier = Modifier.fillMaxSize()) {
            when (val state = uiState) {
                is PlaylistUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is PlaylistUiState.Error -> {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    if (channels.isEmpty()) {
                        Text(
                            text = if (hasChannels) "Keine Treffer." else "In den Einstellungen (oben rechts) eine Playlist hinzufügen.",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Kein URL-basierter Key: reale Playlists enthalten denselben Stream
                            // mehrfach (in verschiedenen Gruppen); doppelte Keys würden die
                            // LazyColumn crashen. Positionsbasierter Default-Key ist hier sicher.
                            itemsIndexed(channels) { index, channel ->
                                ChannelRow(
                                    channel = channel,
                                    isFavorite = channel.url in favorites,
                                    nowPlaying = mainViewModel.nowPlayingFor(channel),
                                    modifier = if (index == targetFocusIndex) {
                                        Modifier.focusRequester(listFocusRequester)
                                    } else Modifier,
                                    onClick = { mainViewModel.selectChannel(channel) },
                                    onToggleFavorite = { mainViewModel.toggleFavorite(channel) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    isFavorite: Boolean,
    nowPlaying: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            // combinedClickable macht das Item D-Pad-fokussierbar; Center = öffnen, Lang = Favorit.
            .combinedClickable(
                onClick = onClick,
                onLongClick = onToggleFavorite
            )
            .background(bg)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            if (nowPlaying != null) {
                // "Jetzt läuft" aus dem EPG (Zuordnung über tvg-id)
                Text(
                    text = "Jetzt: $nowPlaying",
                    color = fg.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (isFavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Favorit",
                tint = fg
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = label,
        color = fg,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick)
            .background(bg)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 18.dp, vertical = 10.dp)
    )
}
