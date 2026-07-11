package com.example.iptv

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

/** Inhalts-Tabs: Live-TV (aus der M3U) sowie Filme/Serien (über die Xtream-API). */
enum class ContentTab { LIVE, MOVIES, SERIES }

/** "Jetzt läuft"-Info eines Senders: aktueller/nächster Titel + Fortschritt (0..1). */
data class EpgNowNext(val now: String?, val next: String?, val progress: Float?)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlaylistRepository(app.applicationContext)

    // Alle geladenen Kanäle (Quelle für Filterung).
    private val allChannels = mutableStateOf<List<Channel>>(emptyList())

    val url = mutableStateOf("")
    val uiState = mutableStateOf<PlaylistUiState>(PlaylistUiState.Idle)
    val selectedChannel = mutableStateOf<Channel?>(null)
    val favorites = mutableStateOf<Set<String>>(emptySet())

    // Navigation
    val showSettings = mutableStateOf(false)
    val contentTab = mutableStateOf(ContentTab.LIVE)

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

    // Fokus-Index, damit nach Rückkehr aus dem Player der zuletzt gewählte Kanal fokussiert bleibt.
    var lastFocusedIndex: Int = 0

    // Nur wenn true stellt MainScreen den Listen-Fokus her (Erstladung / Rückkehr aus dem Player).
    val pendingListFocus = mutableStateOf(false)

    /** Gruppen für die Kategorie-Auswahl: "★ Favoriten" (falls vorhanden) + group-title-Werte. */
    val groups: State<List<String>> = derivedStateOf {
        buildList {
            if (favorites.value.isNotEmpty()) add(FAVORITES_GROUP)
            addAll(allChannels.value.mapNotNull { it.group }.distinct().sorted())
        }
    }

    /** Nach Gruppe und Suchbegriff gefilterte Kanäle. */
    val visibleChannels: State<List<Channel>> = derivedStateOf {
        val group = selectedGroup.value
        val query = searchQuery.value.trim()
        allChannels.value.asSequence()
            .filter { channel ->
                when (group) {
                    null -> true
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

    // ---------- Globale Suche (über Live + Filme + Serien, ignoriert Kategorien) ----------

    val searchChannels: State<List<Channel>> = derivedStateOf {
        val query = searchQuery.value.trim()
        if (query.isEmpty()) emptyList()
        else allChannels.value.filter { it.name.contains(query, ignoreCase = true) }.take(50)
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
        // Favoriten laufend beobachten.
        viewModelScope.launch {
            repository.favoritesFlow.collect { favorites.value = it }
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

    fun selectGroup(group: String?) {
        selectedGroup.value = group
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        viewModelScope.launch { repository.saveTheme(mode) }
    }

    fun setAutoplayLast(enabled: Boolean) {
        autoplayLast.value = enabled
        viewModelScope.launch { repository.saveAutoplay(enabled) }
    }

    // ---------- Tabs / Xtream-Inhalte ----------

    fun selectTab(tab: ContentTab) {
        contentTab.value = tab
        searchQuery.value = ""
        when (tab) {
            ContentTab.MOVIES -> if (vodItems.value.isEmpty()) loadVod()
            ContentTab.SERIES -> if (seriesItems.value.isEmpty()) loadSeries()
            ContentTab.LIVE -> Unit
        }
    }

    private fun setupXtream(playlistUrl: String, autoAddEpg: Boolean) {
        val account = detectXtream(playlistUrl)
        xtream = account?.let { XtreamApi(it) }
        xtreamAvailable.value = xtream != null
        if (xtream == null) contentTab.value = ContentTab.LIVE
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
    }

    // ---------- Wiedergabe ----------

    fun playVod(item: VodItem) {
        val api = xtream ?: return
        playingMedia.value = PlayingMedia(api.vodStreamUrl(item), item.name, isLive = false)
    }

    fun playEpisode(episode: SeriesEpisode) {
        val api = xtream ?: return
        val seriesName = selectedSeries.value?.name ?: ""
        val title = "%s S%02dE%02d – %s".format(seriesName, episode.season, episode.episode, episode.title)
        playingMedia.value = PlayingMedia(api.episodeStreamUrl(episode), title, isLive = false)
    }

    fun stopPlayback() {
        selectedChannel.value = null
        playingMedia.value = null
        pendingListFocus.value = true
    }

    fun saveResume(url: String, positionMs: Long) {
        resumePositions.value = resumePositions.value.toMutableMap().apply {
            if (positionMs > 0) put(url, positionMs) else remove(url)
        }
        viewModelScope.launch { repository.saveResumePosition(url, positionMs) }
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
                        uiState.value = PlaylistUiState.Success
                        pendingListFocus.value = true
                        // Filme/Serien der alten Playlist verwerfen; Xtream neu erkennen.
                        vodItems.value = emptyList()
                        seriesItems.value = emptyList()
                        selectedVodCategory.value = null
                        selectedSeriesCategory.value = null
                        contentTab.value = ContentTab.LIVE
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
        viewModelScope.launch { repository.saveLastChannel(channel.url) }
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
