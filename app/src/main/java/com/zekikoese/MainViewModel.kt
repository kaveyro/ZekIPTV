package com.zekikoese

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PlaylistUiState {
    data object Idle : PlaylistUiState
    data object Loading : PlaylistUiState
    data object Success : PlaylistUiState
    data class Error(val message: String) : PlaylistUiState
}

/** Ziel-Bereiche der linken Navigations-Rail. */
enum class NavDestination { SEARCH, HOME, LIVE, MOVIES, SERIES, SETTINGS }

/** "Jetzt läuft"-Info eines Senders: aktueller/nächster Titel + Fortschritt (0..1). */
data class EpgNowNext(val now: String?, val next: String?, val progress: Float?)

/** Eintrag der "Weiter schauen"-Reihe auf dem Home-Bildschirm. */
data class ContinueWatchingItem(
    val url: String,
    val title: String,
    val poster: String?,
    val positionMs: Long,
    val durationMs: Long
) {
    /** Fortschritt 0..1, sofern die Gesamtdauer bekannt ist. */
    val progress: Float? get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlaylistRepository(app.applicationContext)

    // Alle geladenen Kanäle (Quelle für Filterung).
    private val allChannels = mutableStateOf<List<Channel>>(emptyList())

    val url = mutableStateOf("")
    val uiState = mutableStateOf<PlaylistUiState>(PlaylistUiState.Idle)
    val selectedChannel = mutableStateOf<Channel?>(null)
    val favorites = mutableStateOf<Set<String>>(emptySet())

    // Navigation (linke Rail)
    val currentDestination = mutableStateOf(NavDestination.HOME)

    // Einstellungen
    val playlists = mutableStateOf<List<PlaylistEntry>>(emptyList())
    val themeMode = mutableStateOf("dark") // dark | light | system
    val epgSources = mutableStateOf<Set<String>>(emptySet())
    val epgInfo = mutableStateOf("")
    val autoplayLast = mutableStateOf(false)

    // Xtream (Filme/Serien) — verfügbar, wenn die Playlist-URL eine get.php-URL ist.
    val xtreamAvailable = mutableStateOf(false)
    private var xtream: XtreamApi? = null
    val vodItems = mutableStateOf<List<VodItem>>(emptyList())
    val vodCategories = mutableStateOf<Map<String, String>>(emptyMap())
    val selectedVodCategory = mutableStateOf<String?>(null)
    val seriesItems = mutableStateOf<List<SeriesItem>>(emptyList())
    val seriesCategories = mutableStateOf<Map<String, String>>(emptyMap())
    val selectedSeriesCategory = mutableStateOf<String?>(null)
    val selectedSeries = mutableStateOf<SeriesItem?>(null)
    val seriesEpisodes = mutableStateOf<List<SeriesEpisode>>(emptyList())
    val contentInfo = mutableStateOf("") // Lade-/Fehlerstatus für Filme/Serien

    // VOD-Detail-Seite
    val selectedVod = mutableStateOf<VodItem?>(null)
    val vodInfo = mutableStateOf<VodInfo?>(null)

    // Programmführer (Tagesprogramm eines Senders)
    val epgChannel = mutableStateOf<Channel?>(null)

    // Sender-Rücksprung ("letzter Sender")
    private var previousChannel: Channel? = null

    // Backup-Status für die Einstellungen
    val backupInfo = mutableStateOf("")

    // Wiedergabe (VOD/Episoden; Live läuft über selectedChannel)
    val playingMedia = mutableStateOf<PlayingMedia?>(null)
    val resumePositions = mutableStateOf<Map<String, Long>>(emptyMap())

    // Home: zuletzt gesehene Sender (URLs, neueste zuerst) + Metadaten angefangener Streams
    private val recentChannelUrls = mutableStateOf<List<String>>(emptyList())
    private val watchMeta = mutableStateOf<Map<String, WatchMeta>>(emptyMap())
    val sleepTimerMinutes = mutableStateOf<Int?>(null)
    private var sleepJob: Job? = null

    // EPG: EPG-Kanal-ID (kleingeschrieben) -> Sendungen; Namens-Fallback über epgNameToId.
    private var epgData: Map<String, List<EpgProgramme>> = emptyMap()
    private var epgNameToId: Map<String, String> = emptyMap()

    // Minuten-Ticker: UI liest diesen State, damit "Jetzt läuft"/Fortschritt aktuell bleibt.
    val epgTick = mutableStateOf(0L)

    // Filter-Zustände
    val searchQuery = mutableStateOf("")
    val selectedGroup = mutableStateOf<String?>(null) // null = "Alle"

    // Ausgeblendete Live-Kategorien (Einstellungen) — erscheinen weder im Filter noch in "Alle"/Suche.
    val hiddenGroups = mutableStateOf<Set<String>>(emptySet())

    // Suchverlauf (letzte Suchbegriffe, neueste zuerst).
    val searchHistory = mutableStateOf<List<String>>(emptyList())

    // Gruppenfilter des Senderlisten-Overlays im Player — überlebt Schließen/Öffnen des Overlays.
    val playerOverlayGroup = mutableStateOf<String?>(null)

    // Fokus-Index, damit nach Rückkehr aus dem Player der zuletzt gewählte Kanal fokussiert bleibt.
    var lastFocusedIndex: Int = 0

    // Nur wenn true stellt MainScreen den Listen-Fokus her (Erstladung / Rückkehr aus dem Player).
    val pendingListFocus = mutableStateOf(false)

    // Fokus-Wiederherstellung der Poster-Grids (Filme/Serien) und der Home-Reihen.
    var vodFocusIndex: Int = 0
    var seriesFocusIndex: Int = 0
    val pendingVodFocus = mutableStateOf(false)
    val pendingSeriesFocus = mutableStateOf(false)
    val pendingHomeFocus = mutableStateOf(true) // App-Start: Fokus auf den Inhalt statt auf die Rail

    /** Gruppen für die Kategorie-Auswahl: "★ Favoriten" (falls vorhanden) + group-title-Werte. */
    val groups: State<List<String>> = derivedStateOf {
        buildList {
            // Nur zeigen, wenn es LIVE-Favoriten gibt — der Favoriten-Speicher enthält auch
            // Film-/Serien-Favoriten, und eine leere Pseudogruppe leert sonst die Senderliste.
            if (allChannels.value.any { it.url in favorites.value }) add(FAVORITES_GROUP)
            addAll(
                allChannels.value.mapNotNull { it.group }.distinct().sorted()
                    .filter { it !in hiddenGroups.value }
            )
        }
    }

    /** Alle Gruppen der Playlist (inkl. ausgeblendeter) — für die Verwaltung in den Einstellungen. */
    val allGroups: State<List<String>> = derivedStateOf {
        allChannels.value.mapNotNull { it.group }.distinct().sorted()
    }

    /** Senderanzahl je Gruppe (für die Kategorie-Spalte), inkl. Favoriten-Pseudogruppe. */
    val groupCounts: State<Map<String, Int>> = derivedStateOf {
        buildMap<String, Int> {
            allChannels.value.forEach { channel ->
                channel.group?.let { put(it, (get(it) ?: 0) + 1) }
            }
            put(FAVORITES_GROUP, allChannels.value.filter { it.url in favorites.value }.distinctBy { it.url }.size)
        }
    }

    /** Anzahl der Sender unter "Alle" (ausgeblendete Kategorien nicht mitgezählt). */
    val unhiddenChannelCount: State<Int> = derivedStateOf {
        allChannels.value.count { it.group == null || it.group !in hiddenGroups.value }
    }

    /** Nach Gruppe und Suchbegriff gefilterte Kanäle. */
    val visibleChannels: State<List<Channel>> = derivedStateOf {
        val group = selectedGroup.value
        val query = searchQuery.value.trim()
        allChannels.value.asSequence()
            .filter { channel ->
                when (group) {
                    null -> channel.group == null || channel.group !in hiddenGroups.value
                    FAVORITES_GROUP -> channel.url in favorites.value
                    else -> channel.group == group
                }
            }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .toList()
    }

    /** Nach Kategorie gefilterte Filme (FAV_CATEGORY = nur Favoriten). */
    val visibleVod: State<List<VodItem>> = derivedStateOf {
        val category = selectedVodCategory.value
        vodItems.value.filter { item ->
            when (category) {
                null -> true
                FAV_CATEGORY -> vodFavKey(item) in favorites.value
                else -> item.categoryId == category
            }
        }
    }

    /** Nach Kategorie gefilterte Serien (FAV_CATEGORY = nur Favoriten). */
    val visibleSeries: State<List<SeriesItem>> = derivedStateOf {
        val category = selectedSeriesCategory.value
        seriesItems.value.filter { item ->
            when (category) {
                null -> true
                FAV_CATEGORY -> seriesFavKey(item) in favorites.value
                else -> item.categoryId == category
            }
        }
    }

    /** Titelanzahl je Film-Kategorie (für die Kategorie-Spalte). */
    val vodCategoryCounts: State<Map<String, Int>> = derivedStateOf {
        vodItems.value.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
    }

    /** Titelanzahl je Serien-Kategorie (für die Kategorie-Spalte). */
    val seriesCategoryCounts: State<Map<String, Int>> = derivedStateOf {
        seriesItems.value.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
    }

    /** Anzahl favorisierter Filme (für die ★-Kategorie). */
    val vodFavoriteCount: State<Int> = derivedStateOf {
        vodItems.value.count { vodFavKey(it) in favorites.value }
    }

    /** Anzahl favorisierter Serien (für die ★-Kategorie). */
    val seriesFavoriteCount: State<Int> = derivedStateOf {
        seriesItems.value.count { seriesFavKey(it) in favorites.value }
    }

    // ---------- Home-Reihen ----------

    /** Zuletzt gesehene Sender (gegen die geladene Playlist aufgelöst). */
    val recentChannels: State<List<Channel>> = derivedStateOf {
        val byUrl = allChannels.value.associateBy { it.url }
        recentChannelUrls.value.mapNotNull { byUrl[it] }
    }

    /** Favorisierte Sender (für die Home-Reihe; VOD/Serien-Favoriten laufen über die Tabs). */
    val favoriteChannels: State<List<Channel>> = derivedStateOf {
        allChannels.value.filter { it.url in favorites.value }.distinctBy { it.url }
    }

    /** Angefangene Filme/Episoden: Resume-Position vorhanden + Metadaten bekannt. */
    val continueWatching: State<List<ContinueWatchingItem>> = derivedStateOf {
        val resumes = resumePositions.value
        watchMeta.value.entries
            .filter { (url, _) -> (resumes[url] ?: 0L) > 10_000 }
            .sortedByDescending { it.value.ts }
            .take(15)
            .map { (url, meta) ->
                ContinueWatchingItem(url, meta.title, meta.poster, resumes[url] ?: 0L, meta.durationMs)
            }
    }

    /** Startet einen angefangenen Titel erneut — der Player setzt über resumeFor() automatisch fort. */
    fun playContinueWatching(item: ContinueWatchingItem) {
        playingMedia.value = PlayingMedia(item.url, item.title, isLive = false)
    }

    // ---------- Globale Suche (über Live + Filme + Serien, ignoriert Kategorien) ----------

    val searchChannels: State<List<Channel>> = derivedStateOf {
        val query = searchQuery.value.trim()
        if (query.isEmpty()) emptyList()
        else allChannels.value
            .filter { it.group == null || it.group !in hiddenGroups.value }
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(50)
    }

    val searchVod: State<List<VodItem>> = derivedStateOf {
        val query = searchQuery.value.trim()
        if (query.isEmpty()) emptyList()
        else vodItems.value.filter { it.name.contains(query, ignoreCase = true) }.take(50)
    }

    val searchSeries: State<List<SeriesItem>> = derivedStateOf {
        val query = searchQuery.value.trim()
        if (query.isEmpty()) emptyList()
        else seriesItems.value.filter { it.name.contains(query, ignoreCase = true) }.take(50)
    }

    init {
        // Persistierte Daten laden -> App startet ohne erneute Eingabe/Netzabruf.
        viewModelScope.launch {
            url.value = repository.urlFlow.first()
            themeMode.value = repository.themeFlow.first()
            epgSources.value = repository.epgSourcesFlow.first()
            autoplayLast.value = repository.autoplayFlow.first()
            resumePositions.value = repository.resumePositionsFlow.first()

            var storedPlaylists = repository.playlistsFlow.first()
            // Migration: eine früher gespeicherte Einzel-URL in den Playlist-Manager übernehmen.
            if (storedPlaylists.isEmpty() && url.value.isNotBlank()) {
                storedPlaylists = listOf(PlaylistEntry("Meine Playlist", url.value))
                repository.savePlaylists(storedPlaylists)
            }
            playlists.value = storedPlaylists

            setupXtream(url.value, autoAddEpg = false)

            val saved = repository.channelsFlow.first()
            if (saved.isNotEmpty()) {
                allChannels.value = saved
                uiState.value = PlaylistUiState.Success
                pendingListFocus.value = true
            }

            // EPG: erst aus dem Datei-Cache, nur bei veraltetem/fehlendem Cache neu laden.
            val cached = repository.loadEpgCache(EPG_CACHE_MAX_AGE_MS)
            if (cached != null) {
                epgData = cached.first
                epgNameToId = cached.second
                epgTick.value = System.currentTimeMillis()
                epgInfo.value = "EPG aus Cache (${cached.first.values.sumOf { it.size }} Sendungen)."
            } else if (saved.isNotEmpty()) {
                refreshEpg()
            }

            // Autostart: zuletzt gesehenen Sender direkt abspielen.
            if (autoplayLast.value && saved.isNotEmpty()) {
                val last = repository.lastChannelFlow.first()
                allChannels.value.find { it.url == last }?.let(::selectChannel)
            }
        }
        // Favoriten laufend beobachten. Verschwindet die Favoriten-Pseudogruppe (kein
        // Live-Favorit mehr), darf sie nicht als leerer Filter hängen bleiben.
        viewModelScope.launch {
            repository.favoritesFlow.collect { favs ->
                favorites.value = favs
                val hasLiveFavorites = allChannels.value.any { it.url in favs }
                if (!hasLiveFavorites) {
                    if (selectedGroup.value == FAVORITES_GROUP) selectedGroup.value = null
                    if (playerOverlayGroup.value == FAVORITES_GROUP) playerOverlayGroup.value = null
                }
            }
        }
        // Home-Reihen laufend beobachten (zuletzt gesehen / weiter schauen).
        viewModelScope.launch {
            repository.recentChannelsFlow.collect { recentChannelUrls.value = it }
        }
        viewModelScope.launch {
            repository.watchMetaFlow.collect { watchMeta.value = it }
        }
        // Ausgeblendete Kategorien + Suchverlauf laufend beobachten.
        viewModelScope.launch {
            repository.hiddenGroupsFlow.collect { hiddenGroups.value = it }
        }
        viewModelScope.launch {
            repository.searchHistoryFlow.collect { searchHistory.value = it }
        }
        // Minuten-Ticker für "Jetzt läuft"/Fortschrittsbalken.
        viewModelScope.launch {
            while (true) {
                epgTick.value = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }

    fun onUrlChange(value: String) {
        url.value = value
    }

    fun onSearchChange(value: String) {
        searchQuery.value = value
        // Globale Suche braucht die Filme-/Serien-Kataloge — bei Bedarf nachladen.
        if (value.isNotBlank() && xtream != null) {
            if (vodItems.value.isEmpty()) loadVod()
            if (seriesItems.value.isEmpty()) loadSeries()
        }
    }

    /** Merkt den aktuellen Suchbegriff im Verlauf (beim Öffnen eines Treffers aufgerufen). */
    fun recordSearchQuery() {
        val query = searchQuery.value.trim()
        if (query.length < 2) return
        viewModelScope.launch { repository.addSearchQuery(query) }
    }

    fun selectGroup(group: String?) {
        selectedGroup.value = group
    }

    /** Blendet eine Live-Kategorie aus bzw. ein; aktive Filter auf die Kategorie werden gelöst. */
    fun toggleHiddenGroup(group: String) {
        if (group !in hiddenGroups.value) {
            if (selectedGroup.value == group) selectedGroup.value = null
            if (playerOverlayGroup.value == group) playerOverlayGroup.value = null
        }
        viewModelScope.launch { repository.toggleHiddenGroup(group) }
    }

    /** Kanäle einer Gruppe (für die Senderlisten-Overlay im Player, unabhängig vom globalen Filter). */
    fun channelsForGroup(group: String?): List<Channel> = when (group) {
        null -> allChannels.value.filter { it.group == null || it.group !in hiddenGroups.value }
        FAVORITES_GROUP -> allChannels.value.filter { it.url in favorites.value }
        else -> allChannels.value.filter { it.group == group }
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        viewModelScope.launch { repository.saveTheme(mode) }
    }

    fun setAutoplayLast(enabled: Boolean) {
        autoplayLast.value = enabled
        viewModelScope.launch { repository.saveAutoplay(enabled) }
    }

    // ---------- Navigation / Xtream-Inhalte ----------

    fun navigate(dest: NavDestination) {
        currentDestination.value = dest
        if (dest != NavDestination.SEARCH) searchQuery.value = ""
        // Nach OK auf der Rail den Fokus direkt in den Inhalt setzen (statt auf der Rail zu bleiben).
        when (dest) {
            NavDestination.HOME -> pendingHomeFocus.value = true
            NavDestination.LIVE -> pendingListFocus.value = true
            NavDestination.MOVIES -> {
                if (vodItems.value.isEmpty()) loadVod()
                pendingVodFocus.value = true
            }
            NavDestination.SERIES -> {
                if (seriesItems.value.isEmpty()) loadSeries()
                pendingSeriesFocus.value = true
            }
            else -> Unit
        }
    }

    private fun setupXtream(playlistUrl: String, autoAddEpg: Boolean) {
        val account = detectXtream(playlistUrl)
        xtream = account?.let { XtreamApi(it) }
        xtreamAvailable.value = xtream != null
        // Ohne Xtream gibt es keine Filme/Serien-Bereiche mehr — ggf. dorthin navigierte Nutzer umleiten.
        if (xtream == null && currentDestination.value in setOf(NavDestination.MOVIES, NavDestination.SERIES)) {
            currentDestination.value = NavDestination.HOME
        }
        // Anbieter-EPG automatisch als Quelle ergänzen: dessen Kanal-IDs matchen die Playlist exakt.
        if (autoAddEpg) {
            xtream?.epgUrl()?.let { epg -> if (epg !in epgSources.value) addEpgSource(epg) }
        }
    }

    private fun loadVod() {
        val api = xtream ?: return
        contentInfo.value = "Filme werden geladen…"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.getVodCategories() to api.getVodStreams() }
            }.fold(
                onSuccess = { (categories, items) ->
                    vodCategories.value = categories
                    vodItems.value = items
                    contentInfo.value = if (items.isEmpty()) "Keine Filme gefunden." else ""
                },
                onFailure = { contentInfo.value = "Filme laden fehlgeschlagen: ${it.message}" }
            )
        }
    }

    private fun loadSeries() {
        val api = xtream ?: return
        contentInfo.value = "Serien werden geladen…"
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { api.getSeriesCategories() to api.getSeries() }
            }.fold(
                onSuccess = { (categories, items) ->
                    seriesCategories.value = categories
                    seriesItems.value = items
                    contentInfo.value = if (items.isEmpty()) "Keine Serien gefunden." else ""
                },
                onFailure = { contentInfo.value = "Serien laden fehlgeschlagen: ${it.message}" }
            )
        }
    }

    fun selectVodCategory(categoryId: String?) {
        selectedVodCategory.value = categoryId
    }

    fun selectSeriesCategory(categoryId: String?) {
        selectedSeriesCategory.value = categoryId
    }

    fun openVod(item: VodItem) {
        selectedVod.value = item
        vodInfo.value = null
        val api = xtream ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.getVodInfo(item.id) } }
                .onSuccess { vodInfo.value = it }
        }
    }

    fun closeVod() {
        selectedVod.value = null
        // Zurück im Grid: zuletzt fokussiertes Poster wieder fokussieren.
        pendingVodFocus.value = true
    }

    fun openSeries(series: SeriesItem) {
        selectedSeries.value = series
        seriesEpisodes.value = emptyList()
        val api = xtream ?: return
        contentInfo.value = "Episoden werden geladen…"
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.getSeriesEpisodes(series.id) } }
                .fold(
                    onSuccess = {
                        seriesEpisodes.value = it
                        contentInfo.value = if (it.isEmpty()) "Keine Episoden gefunden." else ""
                    },
                    onFailure = { contentInfo.value = "Episoden laden fehlgeschlagen: ${it.message}" }
                )
        }
    }

    fun closeSeries() {
        selectedSeries.value = null
        pendingSeriesFocus.value = true
    }

    // ---------- Wiedergabe ----------

    fun playVod(item: VodItem) {
        val api = xtream ?: return
        val streamUrl = api.vodStreamUrl(item)
        playingMedia.value = PlayingMedia(streamUrl, item.name, isLive = false)
        // Titel/Poster für die "Weiter schauen"-Reihe merken.
        viewModelScope.launch { repository.saveWatchMeta(streamUrl, item.name, item.icon) }
    }

    fun playEpisode(episode: SeriesEpisode) {
        val api = xtream ?: return
        val seriesName = selectedSeries.value?.name ?: ""
        val title = "%s S%02dE%02d – %s".format(seriesName, episode.season, episode.episode, episode.title)
        val streamUrl = api.episodeStreamUrl(episode)
        playingMedia.value = PlayingMedia(streamUrl, title, isLive = false)
        viewModelScope.launch { repository.saveWatchMeta(streamUrl, title, selectedSeries.value?.cover) }
    }

    fun stopPlayback() {
        selectedChannel.value = null
        playingMedia.value = null
        // Fokus dorthin zurückgeben, wo die Wiedergabe gestartet wurde.
        when (currentDestination.value) {
            NavDestination.HOME -> pendingHomeFocus.value = true
            NavDestination.MOVIES -> pendingVodFocus.value = true
            NavDestination.SERIES -> pendingSeriesFocus.value = true
            else -> pendingListFocus.value = true
        }
    }

    fun saveResume(url: String, positionMs: Long, durationMs: Long = 0L) {
        resumePositions.value = resumePositions.value.toMutableMap().apply {
            if (positionMs > 0) put(url, positionMs) else remove(url)
        }
        viewModelScope.launch {
            repository.saveResumePosition(url, positionMs)
            // Gesamtdauer nachtragen — erst der Player kennt sie (für den Fortschrittsbalken).
            repository.updateWatchDuration(url, durationMs)
        }
    }

    fun resumeFor(url: String): Long = resumePositions.value[url] ?: 0L

    fun vodUrl(item: VodItem): String? = xtream?.vodStreamUrl(item)

    fun episodeUrl(episode: SeriesEpisode): String? = xtream?.episodeStreamUrl(episode)

    /** Sleep-Timer durchschalten: Aus -> 30 -> 60 -> 90 -> Aus. */
    fun cycleSleepTimer() {
        val next = when (sleepTimerMinutes.value) {
            null -> 30
            30 -> 60
            60 -> 90
            else -> null
        }
        sleepTimerMinutes.value = next
        sleepJob?.cancel()
        sleepJob = next?.let { minutes ->
            viewModelScope.launch {
                delay(minutes * 60_000L)
                sleepTimerMinutes.value = null
                stopPlayback()
            }
        }
    }

    // ---------- Playlist-Manager ----------

    fun addPlaylist(name: String, playlistUrl: String) {
        val trimmedUrl = playlistUrl.trim()
        if (trimmedUrl.isEmpty()) return
        val trimmedName = name.trim().ifEmpty { "Playlist ${playlists.value.size + 1}" }
        val updated = playlists.value.filter { it.url != trimmedUrl } + PlaylistEntry(trimmedName, trimmedUrl)
        playlists.value = updated
        viewModelScope.launch { repository.savePlaylists(updated) }
        // Erste hinzugefügte Playlist direkt aktivieren.
        if (url.value.isBlank()) selectPlaylist(updated.last())
    }

    fun removePlaylist(entry: PlaylistEntry) {
        val updated = playlists.value - entry
        playlists.value = updated
        viewModelScope.launch { repository.savePlaylists(updated) }
    }

    fun selectPlaylist(entry: PlaylistEntry) {
        url.value = entry.url
        loadPlaylist()
    }

    // ---------- EPG ----------

    fun addEpgSource(source: String) {
        val trimmed = source.trim()
        if (trimmed.isEmpty() || trimmed in epgSources.value) return
        val updated = epgSources.value + trimmed
        epgSources.value = updated
        viewModelScope.launch { repository.saveEpgSources(updated) }
        refreshEpg()
    }

    fun removeEpgSource(source: String) {
        val updated = epgSources.value - source
        epgSources.value = updated
        viewModelScope.launch { repository.saveEpgSources(updated) }
    }

    fun refreshEpg() {
        val sources = epgSources.value
        if (sources.isEmpty()) {
            epgInfo.value = "Keine EPG-Quellen konfiguriert."
            return
        }
        val channels = allChannels.value
        if (channels.isEmpty()) {
            epgInfo.value = "Zuerst eine Playlist laden."
            return
        }
        epgInfo.value = "EPG wird geladen…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { EpgFetcher.fetch(channels, sources) }
            epgData = result.programmes
            epgNameToId = result.nameToId
            epgTick.value = System.currentTimeMillis()
            repository.saveEpgCache(result.programmes, result.nameToId)
            val programmeCount = result.programmes.values.sumOf { it.size }
            epgInfo.value = buildString {
                append("EPG: $programmeCount Sendungen für ${result.programmes.size} Sender geladen.")
                if (result.failedSources > 0) append(" ${result.failedSources} Quelle(n) fehlgeschlagen.")
            }
        }
    }

    /** Jetzt/Gleich + Fortschritt für einen Kanal (per tvg-id, sonst per Sendername). */
    fun epgFor(channel: Channel): EpgNowNext {
        val now = epgTick.value // State-Read: Recomposition bei jedem Ticker-Update
        if (now == 0L || epgData.isEmpty()) return EMPTY_EPG
        val id = channel.tvgId?.lowercase()?.takeIf { it in epgData }
            ?: epgNameToId[normalizeChannelName(channel.name)]
            ?: return EMPTY_EPG
        val programmes = epgData[id] ?: return EMPTY_EPG
        val index = programmes.indexOfFirst { now >= it.startMs && now < it.stopMs }
        if (index < 0) {
            val upcoming = programmes.firstOrNull { it.startMs > now }
            return EpgNowNext(null, upcoming?.title, null)
        }
        val current = programmes[index]
        val progress = ((now - current.startMs).toFloat() / (current.stopMs - current.startMs))
            .coerceIn(0f, 1f)
        return EpgNowNext(current.title, programmes.getOrNull(index + 1)?.title, progress)
    }

    // ---------- Playlist laden ----------

    fun loadPlaylist() {
        val target = url.value.trim()
        if (target.isEmpty()) {
            uiState.value = PlaylistUiState.Error("Bitte zuerst eine M3U-URL eingeben.")
            return
        }
        uiState.value = PlaylistUiState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Http.openStream(target).use { M3uParser().parse(it) }
                }
            }
            result.fold(
                onSuccess = { channels ->
                    if (channels.isEmpty()) {
                        uiState.value = PlaylistUiState.Error("Keine Kanäle in der Playlist gefunden.")
                    } else {
                        allChannels.value = channels
                        selectedGroup.value = null
                        searchQuery.value = ""
                        playerOverlayGroup.value = null
                        lastFocusedIndex = 0
                        vodFocusIndex = 0
                        seriesFocusIndex = 0
                        uiState.value = PlaylistUiState.Success
                        pendingListFocus.value = true
                        // Filme/Serien der alten Playlist verwerfen; Xtream neu erkennen.
                        vodItems.value = emptyList()
                        seriesItems.value = emptyList()
                        selectedVodCategory.value = null
                        selectedSeriesCategory.value = null
                        setupXtream(target, autoAddEpg = true)
                        repository.saveUrl(target)
                        repository.saveChannels(channels)
                        refreshEpg()
                    }
                },
                onFailure = { e ->
                    uiState.value = PlaylistUiState.Error("Laden fehlgeschlagen: ${e.message ?: "Unbekannter Fehler"}")
                }
            )
        }
    }

    fun selectChannel(channel: Channel) {
        lastFocusedIndex = visibleChannels.value.indexOf(channel).coerceAtLeast(0)
        // Für den Sender-Rücksprung den bisher laufenden Sender merken.
        selectedChannel.value?.takeIf { it.url != channel.url }?.let { previousChannel = it }
        selectedChannel.value = channel
        viewModelScope.launch {
            repository.saveLastChannel(channel.url)
            repository.addRecentChannel(channel.url)
        }
    }

    /** Springt zum zuvor gesehenen Sender zurück (klassische "letzter Sender"-Taste). */
    fun swapToPreviousChannel() {
        previousChannel?.let(::selectChannel)
    }

    fun deselectChannel() {
        stopPlayback()
    }

    /** Zappt vom aktuellen Sender aus um [delta] weiter (+1 = nächster, -1 = vorheriger). */
    fun zapChannel(delta: Int) {
        if (playingMedia.value != null) return // kein Zapping in VOD/Serien
        val list = visibleChannels.value
        if (list.isEmpty()) return
        val current = selectedChannel.value
        val currentIndex = list.indexOf(current).takeIf { it >= 0 }
            ?: lastFocusedIndex.coerceIn(0, list.lastIndex)
        val next = list[(currentIndex + delta).mod(list.size)]
        selectChannel(next)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { repository.toggleFavorite(channel.url) }
    }

    // ---------- Favoriten für Filme/Serien (gleicher Favoriten-Speicher, eigene Keys) ----------

    fun vodFavKey(item: VodItem): String = vodUrl(item) ?: "vod:${item.id}"

    fun seriesFavKey(item: SeriesItem): String = "series:${item.id}"

    fun toggleVodFavorite(item: VodItem) {
        viewModelScope.launch { repository.toggleFavorite(vodFavKey(item)) }
    }

    fun toggleSeriesFavorite(item: SeriesItem) {
        viewModelScope.launch { repository.toggleFavorite(seriesFavKey(item)) }
    }

    // ---------- Programmführer ----------

    fun openEpgFor(channel: Channel) {
        epgChannel.value = channel
    }

    fun closeEpg() {
        epgChannel.value = null
    }

    /** Alle geladenen Sendungen eines Senders (für die Tagesprogramm-Ansicht). */
    fun programmesFor(channel: Channel): List<EpgProgramme> {
        val id = channel.tvgId?.lowercase()?.takeIf { it in epgData }
            ?: epgNameToId[normalizeChannelName(channel.name)]
            ?: return emptyList()
        return epgData[id] ?: emptyList()
    }

    // ---------- Backup ----------

    fun exportBackup() {
        viewModelScope.launch {
            runCatching { repository.exportBackup() }.fold(
                onSuccess = { backupInfo.value = "Backup gespeichert: $it" },
                onFailure = { backupInfo.value = "Backup fehlgeschlagen: ${it.message}" }
            )
        }
    }

    fun importBackup() {
        viewModelScope.launch {
            runCatching { repository.importBackup() }.fold(
                onSuccess = {
                    // Zustände neu einlesen und Playlist mit den importierten Daten laden.
                    url.value = repository.urlFlow.first()
                    themeMode.value = repository.themeFlow.first()
                    epgSources.value = repository.epgSourcesFlow.first()
                    autoplayLast.value = repository.autoplayFlow.first()
                    playlists.value = repository.playlistsFlow.first()
                    backupInfo.value = "Backup importiert — Playlist wird geladen…"
                    if (url.value.isNotBlank()) loadPlaylist()
                },
                onFailure = { backupInfo.value = "Import fehlgeschlagen: ${it.message}" }
            )
        }
    }

    companion object {
        const val FAVORITES_GROUP = "★ Favoriten"
        const val FAV_CATEGORY = "__favoriten__" // Pseudo-Kategorie für Filme/Serien
        private const val EPG_CACHE_MAX_AGE_MS = 12L * 60 * 60 * 1000 // 12 h
        private val EMPTY_EPG = EpgNowNext(null, null, null)
    }
}
