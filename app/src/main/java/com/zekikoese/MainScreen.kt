package com.zekikoese

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.LoadingPlaceholder
import com.zekikoese.ui.tvFocusFrame
import kotlinx.coroutines.android.awaitFrame

/** Sprungweite (Einträge) beim Blättern mit den Vor-/Zurückspulen-Tasten in langen Listen. */
private const val PAGE_JUMP = 10

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mainViewModel: MainViewModel = viewModel()) {
    val destination by mainViewModel.currentDestination
    val search by mainViewModel.searchQuery
    val selectedGroup by mainViewModel.selectedGroup
    val vodCategory by mainViewModel.selectedVodCategory
    val seriesCategory by mainViewModel.selectedSeriesCategory
    val xtreamAvailable by mainViewModel.xtreamAvailable
    val favorites by mainViewModel.favorites

    var actionsChannel by remember { mutableStateOf<Channel?>(null) }

    BackHandler(enabled = destination != NavDestination.HOME) {
        when {
            destination == NavDestination.LIVE && selectedGroup != null ->
                mainViewModel.selectGroup(null)
            destination == NavDestination.MOVIES && vodCategory != null ->
                mainViewModel.selectVodCategory(null)
            destination == NavDestination.SERIES && seriesCategory != null ->
                mainViewModel.selectSeriesCategory(null)
            destination == NavDestination.SEARCH && search.isNotEmpty() ->
                mainViewModel.onSearchChange("")
            else -> mainViewModel.navigate(NavDestination.HOME)
        }
    }

    val mainContent: @Composable () -> Unit = {
        AnimatedContent(
            targetState = destination,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "destinationTransition"
        ) { dest ->
            when (dest) {
                NavDestination.SEARCH -> SearchScreen(mainViewModel) { actionsChannel = it }
                NavDestination.HOME -> HomeScreen(mainViewModel) { actionsChannel = it }
                NavDestination.LIVE -> LiveScreen(mainViewModel) { actionsChannel = it }
                NavDestination.MOVIES -> VodContent(mainViewModel)
                NavDestination.SERIES -> SeriesContent(mainViewModel)
                NavDestination.SETTINGS -> SettingsScreen(mainViewModel)
            }
        }
    }

    if (LocalIsTv.current) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().padding(start = 72.dp)) {
                mainContent()
            }
            NavRail(
                current = destination,
                xtreamAvailable = xtreamAvailable,
                onNavigate = mainViewModel::navigate
            )
        }
    } else {
        PhoneMainScreen(mainViewModel) {
            mainContent()
        }
    }

    val dialogChannel = actionsChannel
    if (dialogChannel != null) {
        ChannelActionsDialog(
            channel = dialogChannel,
            isFavorite = dialogChannel.url in favorites,
            hasEpg = mainViewModel.programmesFor(dialogChannel).isNotEmpty(),
            onShowEpg = {
                actionsChannel = null
                mainViewModel.openEpgFor(dialogChannel)
            },
            onToggleFavorite = { mainViewModel.toggleFavorite(dialogChannel) },
            onDismiss = { actionsChannel = null }
        )
    }

    val epgDialogChannel by mainViewModel.epgChannel
    if (epgDialogChannel != null) {
        EpgDayDialog(
            channelName = epgDialogChannel!!.name,
            programmes = mainViewModel.programmesFor(epgDialogChannel!!),
            onDismiss = { mainViewModel.closeEpg() }
        )
    }
}

@Composable
internal fun ScreenTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(bottom = 16.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchScreen(
    mainViewModel: MainViewModel,
    onChannelLongPress: (Channel) -> Unit
) {
    val search by mainViewModel.searchQuery
    val history by mainViewModel.searchHistory
    val focusManager = LocalFocusManager.current
    val escapeDownOnDpad = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            focusManager.moveFocus(FocusDirection.Down)
            true
        } else {
            false
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) mainViewModel.onSearchChange(spoken)
    }
    var voiceAvailable by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 32.dp, top = 24.dp, bottom = 24.dp)) {
        ScreenTitle("Suche")
        OutlinedTextField(
            value = search,
            onValueChange = mainViewModel::onSearchChange,
            label = { Text("Sender, Filme, Serien…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().then(escapeDownOnDpad)
        )
        if (search.isBlank()) {
            Spacer(Modifier.height(16.dp))
            if (voiceAvailable) {
                GroupChip("🎤 Sprachsuche", selected = false) {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Wonach suchst du?")
                    runCatching { voiceLauncher.launch(intent) }
                        .onFailure { voiceAvailable = false }
                }
                Spacer(Modifier.height(20.dp))
            }
            if (history.isNotEmpty()) {
                Text(
                    text = "Zuletzt gesucht:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    history.forEach { query ->
                        GroupChip(query, selected = false) { mainViewModel.onSearchChange(query) }
                    }
                }
            }
            EmptyState(
                icon = Icons.Filled.Info,
                title = "Suchbegriff eingeben",
                subtitle = "Durchsucht Live-TV, Filme und Serien."
            )
        } else {
            SearchResults(mainViewModel, onChannelLongPress)
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
            modifier = Modifier.size(100.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 48.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResults(
    mainViewModel: MainViewModel,
    onChannelLongPress: (Channel) -> Unit
) {
    val channels by mainViewModel.searchChannels
    val vod by mainViewModel.searchVod
    val series by mainViewModel.searchSeries
    val favorites by mainViewModel.favorites

    if (channels.isEmpty() && vod.isEmpty() && series.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Search,
            title = "Keine Treffer",
            subtitle = "Versuche es mit einem anderen Suchbegriff."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (channels.isNotEmpty()) {
            item { SearchSectionTitle("Sender (${channels.size})") }
            items(channels, key = { "c" + it.url + it.name }) { channel ->
                ChannelRow(
                    channel = channel,
                    isFavorite = channel.url in favorites,
                    epg = mainViewModel.epgFor(channel),
                    onClick = {
                        mainViewModel.recordSearchQuery()
                        mainViewModel.selectChannel(channel)
                    },
                    onLongClick = { onChannelLongPress(channel) }
                )
            }
        }
        if (vod.isNotEmpty()) {
            item { SearchSectionTitle("Filme (${vod.size})") }
            item {
                FlowRow(
                    maxItemsInEachRow = 5,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    vod.forEach { item ->
                        PosterCard(
                            title = item.name,
                            icon = item.icon,
                            isFavorite = mainViewModel.vodFavKey(item) in favorites,
                            modifier = Modifier.width(160.dp),
                            onClick = {
                                mainViewModel.recordSearchQuery()
                                mainViewModel.openVod(item)
                            },
                            onLongClick = { mainViewModel.toggleVodFavorite(item) },
                            onFocusChange = { focused ->
                                if (focused) mainViewModel.focusedBackdrop.value = item.icon
                            }
                        )
                    }
                }
            }
        }
        if (series.isNotEmpty()) {
            item { SearchSectionTitle("Serien (${series.size})") }
            item {
                FlowRow(
                    maxItemsInEachRow = 5,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    series.forEach { item ->
                        PosterCard(
                            title = item.name,
                            icon = item.cover,
                            isFavorite = mainViewModel.seriesFavKey(item) in favorites,
                            modifier = Modifier.width(160.dp),
                            onClick = {
                                mainViewModel.recordSearchQuery()
                                mainViewModel.openSeries(item)
                            },
                            onLongClick = { mainViewModel.toggleSeriesFavorite(item) },
                            onFocusChange = { focused ->
                                if (focused) mainViewModel.focusedBackdrop.value = item.cover
                            }
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun LiveScreen(
    mainViewModel: MainViewModel,
    onChannelLongPress: (Channel) -> Unit
) {
    val uiState by mainViewModel.uiState
    val channels by mainViewModel.visibleChannels
    val groups by mainViewModel.groups
    val groupCounts by mainViewModel.groupCounts
    val allCount by mainViewModel.unhiddenChannelCount
    val selectedGroup by mainViewModel.selectedGroup
    val favorites by mainViewModel.favorites
    val hasChannels = channels.isNotEmpty() || groups.isNotEmpty()

    val isTv = LocalIsTv.current
    val listState = rememberLazyListState()
    val listFocusRequester = remember { FocusRequester() }
    val targetFocusIndex = mainViewModel.lastFocusedIndex.coerceIn(0, maxOf(0, channels.lastIndex))
    val pendingListFocus by mainViewModel.pendingListFocus

    var focusedIndex by remember { mutableStateOf(0) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }

    LaunchedEffect(pendingListFocus, channels.size) {
        if (!pendingListFocus || channels.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(targetFocusIndex)
        if (!isTv) {
            mainViewModel.pendingListFocus.value = false
            return@LaunchedEffect
        }
        repeat(10) {
            awaitFrame()
            if (runCatching { listFocusRequester.requestFocus() }.isSuccess) {
                mainViewModel.pendingListFocus.value = false
                return@LaunchedEffect
            }
        }
        mainViewModel.pendingListFocus.value = false
    }

    fun jumpBy(delta: Int) {
        if (channels.isEmpty()) return
        mainViewModel.lastFocusedIndex = (focusedIndex + delta).coerceIn(0, channels.lastIndex)
        mainViewModel.pendingListFocus.value = true
    }

    val categoryEntries = buildList {
        add(CategoryEntry(null, "Alle", allCount))
        groups.forEach { add(CategoryEntry(it, it, groupCounts[it])) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 32.dp, top = 24.dp, bottom = 24.dp)) {
        ScreenTitle("Live-TV")

        if (!isTv && groups.isNotEmpty()) {
            PhoneCategoryPicker(
                entries = categoryEntries,
                selectedKey = selectedGroup,
                onSelect = mainViewModel::selectGroup
            )
            Spacer(Modifier.height(12.dp))
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (isTv && groups.isNotEmpty()) {
                CategoryColumn(
                    entries = categoryEntries,
                    selectedKey = selectedGroup,
                    onSelect = { group ->
                        mainViewModel.pendingListFocus.value = false
                        mainViewModel.selectGroup(group)
                    }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (uiState) {
                    is PlaylistUiState.Loading -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(8) {
                                Box(modifier = Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(12.dp))) {
                                    LoadingPlaceholder()
                                }
                            }
                        }
                    }
                    is PlaylistUiState.Error -> {
                        EmptyState(
                            icon = Icons.Filled.Info,
                            title = "Fehler",
                            subtitle = (uiState as PlaylistUiState.Error).message
                        )
                    }
                    else -> {
                        if (channels.isEmpty()) {
                            EmptyState(
                                icon = Icons.Filled.Info,
                                title = "Keine Sender",
                                subtitle = if (hasChannels) "In dieser Kategorie gibt es keine Treffer." else "Über das Menü links unter „Einstellungen“ eine Playlist hinzufügen."
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onPreviewKeyEvent { event ->
                                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (event.key) {
                                            Key.Menu -> focusedChannel?.let { onChannelLongPress(it); true } ?: false
                                            Key.MediaFastForward -> { jumpBy(+PAGE_JUMP); true }
                                            Key.MediaRewind -> { jumpBy(-PAGE_JUMP); true }
                                            else -> false
                                        }
                                    },
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(channels) { index, channel ->
                                    ChannelRow(
                                        channel = channel,
                                        isFavorite = channel.url in favorites,
                                        epg = mainViewModel.epgFor(channel),
                                        showGroup = selectedGroup == null,
                                        modifier = if (index == targetFocusIndex) {
                                            Modifier.focusRequester(listFocusRequester)
                                        } else Modifier,
                                        onClick = { mainViewModel.selectChannel(channel) },
                                        onLongClick = { onChannelLongPress(channel) },
                                        onFocusChange = { focused ->
                                            if (focused) {
                                                focusedIndex = index
                                                focusedChannel = channel
                                            }
                                        }
                                    )
                                }
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
    val allItems by mainViewModel.vodItems
    val categories by mainViewModel.vodCategories
    val categoryCounts by mainViewModel.vodCategoryCounts
    val favCount by mainViewModel.vodFavoriteCount
    val selectedCategory by mainViewModel.selectedVodCategory
    val contentInfo by mainViewModel.contentInfo
    val favorites by mainViewModel.favorites

    val isTv = LocalIsTv.current
    val gridState = rememberLazyGridState()
    val gridFocusRequester = remember { FocusRequester() }
    val pendingFocus by mainViewModel.pendingVodFocus
    val targetIndex = mainViewModel.vodFocusIndex.coerceIn(0, maxOf(0, vod.lastIndex))

    LaunchedEffect(pendingFocus, vod.size) {
        if (!pendingFocus || vod.isEmpty()) return@LaunchedEffect
        gridState.scrollToItem(targetIndex)
        if (!isTv) {
            mainViewModel.pendingVodFocus.value = false
            return@LaunchedEffect
        }
        repeat(10) {
            awaitFrame()
            if (runCatching { gridFocusRequester.requestFocus() }.isSuccess) {
                mainViewModel.pendingVodFocus.value = false
                return@LaunchedEffect
            }
        }
        mainViewModel.pendingVodFocus.value = false
    }

    val categoryEntries = buildList {
        add(CategoryEntry(null, "Alle", allItems.size))
        add(CategoryEntry(MainViewModel.FAV_CATEGORY, "★ Favoriten", favCount))
        categories.forEach { (id, name) -> add(CategoryEntry(id, name, categoryCounts[id])) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 32.dp, top = 24.dp, bottom = 24.dp)) {
        ScreenTitle("Filme")

        if (!isTv && categories.isNotEmpty()) {
            PhoneCategoryPicker(
                entries = categoryEntries,
                selectedKey = selectedCategory,
                onSelect = mainViewModel::selectVodCategory
            )
            Spacer(Modifier.height(12.dp))
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (isTv && categories.isNotEmpty()) {
                CategoryColumn(
                    entries = categoryEntries,
                    selectedKey = selectedCategory,
                    onSelect = { category ->
                        mainViewModel.pendingVodFocus.value = false
                        mainViewModel.selectVodCategory(category)
                    }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (vod.isEmpty()) {
                    if (contentInfo.contains("laden", ignoreCase = true)) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(10) {
                                Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f/3f).clip(RoundedCornerShape(12.dp))) {
                                    LoadingPlaceholder()
                                }
                            }
                        }
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Info,
                            title = "Keine Filme",
                            subtitle = contentInfo.ifEmpty { "In dieser Kategorie gibt es keine Treffer." }
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(vod, key = { _, item -> item.id }) { index, item ->
                            PosterCard(
                                title = item.name,
                                icon = item.icon,
                                isFavorite = mainViewModel.vodFavKey(item) in favorites,
                                modifier = if (index == targetIndex) {
                                    Modifier.focusRequester(gridFocusRequester)
                                } else Modifier,
                                onClick = { mainViewModel.openVod(item) },
                                onLongClick = { mainViewModel.toggleVodFavorite(item) },
                                onFocusChange = { focused ->
                                    if (focused) {
                                        mainViewModel.vodFocusIndex = index
                                        mainViewModel.focusedBackdrop.value = item.icon
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesContent(mainViewModel: MainViewModel) {
    val series by mainViewModel.visibleSeries
    val allItems by mainViewModel.seriesItems
    val categories by mainViewModel.seriesCategories
    val categoryCounts by mainViewModel.seriesCategoryCounts
    val favCount by mainViewModel.seriesFavoriteCount
    val selectedCategory by mainViewModel.selectedSeriesCategory
    val contentInfo by mainViewModel.contentInfo
    val favorites by mainViewModel.favorites

    val isTv = LocalIsTv.current
    val gridState = rememberLazyGridState()
    val gridFocusRequester = remember { FocusRequester() }
    val pendingFocus by mainViewModel.pendingSeriesFocus
    val targetIndex = mainViewModel.seriesFocusIndex.coerceIn(0, maxOf(0, series.lastIndex))

    LaunchedEffect(pendingFocus, series.size) {
        if (!pendingFocus || series.isEmpty()) return@LaunchedEffect
        gridState.scrollToItem(targetIndex)
        if (!isTv) {
            mainViewModel.pendingSeriesFocus.value = false
            return@LaunchedEffect
        }
        repeat(10) {
            awaitFrame()
            if (runCatching { gridFocusRequester.requestFocus() }.isSuccess) {
                mainViewModel.pendingSeriesFocus.value = false
                return@LaunchedEffect
            }
        }
        mainViewModel.pendingSeriesFocus.value = false
    }

    val categoryEntries = buildList {
        add(CategoryEntry(null, "Alle", allItems.size))
        add(CategoryEntry(MainViewModel.FAV_CATEGORY, "★ Favoriten", favCount))
        categories.forEach { (id, name) -> add(CategoryEntry(id, name, categoryCounts[id])) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 32.dp, top = 24.dp, bottom = 24.dp)) {
        ScreenTitle("Serien")

        if (!isTv && categories.isNotEmpty()) {
            PhoneCategoryPicker(
                entries = categoryEntries,
                selectedKey = selectedCategory,
                onSelect = mainViewModel::selectSeriesCategory
            )
            Spacer(Modifier.height(12.dp))
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (isTv && categories.isNotEmpty()) {
                CategoryColumn(
                    entries = categoryEntries,
                    selectedKey = selectedCategory,
                    onSelect = { category ->
                        mainViewModel.pendingSeriesFocus.value = false
                        mainViewModel.selectSeriesCategory(category)
                    }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (series.isEmpty()) {
                    if (contentInfo.contains("laden", ignoreCase = true)) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 150.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(10) {
                                Box(modifier = Modifier.fillMaxWidth().aspectRatio(2f/3f).clip(RoundedCornerShape(12.dp))) {
                                    LoadingPlaceholder()
                                }
                            }
                        }
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Info,
                            title = "Keine Serien",
                            subtitle = contentInfo.ifEmpty { "In dieser Kategorie gibt es keine Treffer." }
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(series, key = { _, item -> item.id }) { index, item ->
                            PosterCard(
                                title = item.name,
                                icon = item.cover,
                                isFavorite = mainViewModel.seriesFavKey(item) in favorites,
                                modifier = if (index == targetIndex) {
                                    Modifier.focusRequester(gridFocusRequester)
                                } else Modifier,
                                onClick = { mainViewModel.openSeries(item) },
                                onLongClick = { mainViewModel.toggleSeriesFavorite(item) },
                                onFocusChange = { focused ->
                                    if (focused) {
                                        mainViewModel.seriesFocusIndex = index
                                        mainViewModel.focusedBackdrop.value = item.cover
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun PosterCard(
    title: String,
    icon: String?,
    isFavorite: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocusChange: ((Boolean) -> Unit)? = null
) {
    Column(
        modifier = modifier
            .tvFocusFrame(
                onClick = onClick,
                onLongClick = onLongClick,
                focusedScale = 1.05f,
                restColor = Color.Transparent,
                onFocusChange = onFocusChange,
                focusRoom = 10.dp
            )
            .padding(6.dp)
    ) {
        Box {
            if (icon != null) {
                AsyncImage(
                    model = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = title.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
            if (isFavorite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favorit",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun ChannelRow(
    channel: Channel,
    isFavorite: Boolean,
    epg: EpgNowNext,
    showGroup: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocusChange: ((Boolean) -> Unit)? = null
) {
    val fg = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusFrame(onClick = onClick, onLongClick = onLongClick, onFocusChange = onFocusChange)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.name,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (showGroup && channel.group != null) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = channel.group,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.alpha(0.6f)
                    )
                }
            }
            if (epg.now != null) {
                Text(
                    text = buildString {
                        append("Jetzt: ${epg.now}")
                        if (epg.next != null) append("  ·  Gleich: ${epg.next}")
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                val progress = epg.progress
                if (progress != null) {
                    Spacer(Modifier.height(4.dp))
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
        if (isFavorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Favorit",
                tint = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

@Composable
private fun MediaRow(
    title: String,
    subtitle: String?,
    icon: String?,
    showResume: Boolean,
    onClick: () -> Unit
) {
    val fg = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFrame(onClick = onClick)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
internal fun GroupChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .tvFocusFrame(
                onClick = onClick,
                shape = RoundedCornerShape(20.dp),
                restColor = MaterialTheme.colorScheme.surfaceVariant,
                isSelected = selected
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp).padding(end = 8.dp)
            )
        }
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    borderColor: Color = Color.Transparent,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    Box(
        modifier = modifier
            .tvFocusFrame(
                onClick = onClick,
                restColor = containerColor,
                shape = shape,
                unfocusedBorderColor = borderColor,
                unfocusedBorderWidth = if (borderColor != Color.Transparent) 1.5.dp else 0.dp
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
