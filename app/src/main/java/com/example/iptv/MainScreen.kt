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
import androidx.compose.runtime.LaunchedEffect
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mainViewModel: MainViewModel = viewModel()) {
    val search by mainViewModel.searchQuery
    val uiState by mainViewModel.uiState
    val channels by mainViewModel.visibleChannels
    val groups by mainViewModel.groups
    val selectedGroup by mainViewModel.selectedGroup
    val favorites by mainViewModel.favorites
    val contentTab by mainViewModel.contentTab
    val xtreamAvailable by mainViewModel.xtreamAvailable

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
    LaunchedEffect(pendingListFocus, channels.size, contentTab) {
        if (pendingListFocus && contentTab == ContentTab.LIVE && channels.isNotEmpty()) {
            listState.scrollToItem(targetFocusIndex)
            runCatching { listFocusRequester.requestFocus() }
            mainViewModel.pendingListFocus.value = false
        }
    }

    // BACK: erst Filter zurücksetzen, dann zum Live-Tab, erst dann die App beenden.
    val vodCategory by mainViewModel.selectedVodCategory
    val seriesCategory by mainViewModel.selectedSeriesCategory
    BackHandler(
        enabled = selectedGroup != null || search.isNotEmpty() ||
            contentTab != ContentTab.LIVE || vodCategory != null || seriesCategory != null
    ) {
        when {
            search.isNotEmpty() || selectedGroup != null || vodCategory != null || seriesCategory != null -> {
                mainViewModel.onSearchChange("")
                mainViewModel.selectGroup(null)
                mainViewModel.selectVodCategory(null)
                mainViewModel.selectSeriesCategory(null)
            }

            else -> mainViewModel.selectTab(ContentTab.LIVE)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        // Kopfzeile: Titel + Suche + Einstellungen
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ZekIPTV", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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

        // Inhalts-Tabs (nur bei Xtream-Anbietern mit Filmen/Serien)
        if (xtreamAvailable) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupChip("Live-TV", selected = contentTab == ContentTab.LIVE) {
                    mainViewModel.selectTab(ContentTab.LIVE)
                }
                GroupChip("Filme", selected = contentTab == ContentTab.MOVIES) {
                    mainViewModel.selectTab(ContentTab.MOVIES)
                }
                GroupChip("Serien", selected = contentTab == ContentTab.SERIES) {
                    mainViewModel.selectTab(ContentTab.SERIES)
                }
            }
        }

        when (contentTab) {
            ContentTab.LIVE -> LiveContent(
                mainViewModel = mainViewModel,
                uiState = uiState,
                channels = channels,
                groups = groups,
                selectedGroup = selectedGroup,
                favorites = favorites,
                hasChannels = hasChannels,
                listState = listState,
                listFocusRequester = listFocusRequester,
                targetFocusIndex = targetFocusIndex
            )

            ContentTab.MOVIES -> VodContent(mainViewModel)

            ContentTab.SERIES -> SeriesContent(mainViewModel)
        }
    }
}

@Composable
private fun LiveContent(
    mainViewModel: MainViewModel,
    uiState: PlaylistUiState,
    channels: List<Channel>,
    groups: List<String>,
    selectedGroup: String?,
    favorites: Set<String>,
    hasChannels: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    listFocusRequester: FocusRequester,
    targetFocusIndex: Int
) {
    Column {
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

        Box(modifier = Modifier.fillMaxSize()) {
            when (uiState) {
                is PlaylistUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is PlaylistUiState.Error -> {
                    Text(
                        text = uiState.message,
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
                            // mehrfach; doppelte Keys würden die LazyColumn crashen.
                            itemsIndexed(channels) { index, channel ->
                                ChannelRow(
                                    channel = channel,
                                    isFavorite = channel.url in favorites,
                                    epg = mainViewModel.epgFor(channel),
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

@Composable
private fun VodContent(mainViewModel: MainViewModel) {
    val vod by mainViewModel.visibleVod
    val categories by mainViewModel.vodCategories
    val selectedCategory by mainViewModel.selectedVodCategory
    val contentInfo by mainViewModel.contentInfo
    val resumePositions by mainViewModel.resumePositions

    Column {
        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    GroupChip("Alle", selected = selectedCategory == null) {
                        mainViewModel.selectVodCategory(null)
                    }
                }
                items(categories.entries.toList()) { (id, name) ->
                    GroupChip(name, selected = selectedCategory == id) {
                        mainViewModel.selectVodCategory(id)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            if (vod.isEmpty()) {
                Text(
                    text = contentInfo.ifEmpty { "Keine Filme." },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vod) { item ->
                        val itemUrl = mainViewModel.vodUrl(item)
                        MediaRow(
                            title = item.name,
                            subtitle = categories[item.categoryId],
                            icon = item.icon,
                            showResume = itemUrl != null && (resumePositions[itemUrl] ?: 0L) > 10_000,
                            onClick = { mainViewModel.playVod(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesContent(mainViewModel: MainViewModel) {
    val series by mainViewModel.visibleSeries
    val categories by mainViewModel.seriesCategories
    val selectedCategory by mainViewModel.selectedSeriesCategory
    val contentInfo by mainViewModel.contentInfo

    Column {
        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    GroupChip("Alle", selected = selectedCategory == null) {
                        mainViewModel.selectSeriesCategory(null)
                    }
                }
                items(categories.entries.toList()) { (id, name) ->
                    GroupChip(name, selected = selectedCategory == id) {
                        mainViewModel.selectSeriesCategory(id)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            if (series.isEmpty()) {
                Text(
                    text = contentInfo.ifEmpty { "Keine Serien." },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(series) { item ->
                        MediaRow(
                            title = item.name,
                            subtitle = categories[item.categoryId],
                            icon = item.cover,
                            showResume = false,
                            onClick = { mainViewModel.openSeries(item) }
                        )
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
    epg: EpgNowNext,
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
            if (epg.now != null) {
                // "Jetzt läuft"/"Gleich" aus dem EPG (Zuordnung über tvg-id oder Sendername)
                Text(
                    text = buildString {
                        append("Jetzt: ${epg.now}")
                        if (epg.next != null) append("  ·  Gleich: ${epg.next}")
                    },
                    color = fg.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                val progress = epg.progress
                if (progress != null) {
                    Spacer(Modifier.height(4.dp))
                    // Dünner Fortschrittsbalken der laufenden Sendung
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(fg.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .height(3.dp)
                                .background(fg)
                        )
                    }
                }
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

/** Zeile für Filme/Serien mit Poster, D-Pad-fokussierbar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    title: String,
    subtitle: String?,
    icon: String?,
    showResume: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick)
            .background(bg)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            AsyncImage(
                model = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
            val sub = buildString {
                if (subtitle != null) append(subtitle)
                if (showResume) {
                    if (isNotEmpty()) append("  ·  ")
                    append("▶ Weiterschauen")
                }
            }
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    color = fg.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
