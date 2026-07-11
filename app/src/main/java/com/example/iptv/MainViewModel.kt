package com.example.iptv

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

sealed interface PlaylistUiState {
    data object Idle : PlaylistUiState
    data object Loading : PlaylistUiState
    data object Success : PlaylistUiState
    data class Error(val message: String) : PlaylistUiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlaylistRepository(app.applicationContext)

    // Alle geladenen Kanäle (Quelle für Filterung).
    private val allChannels = mutableStateOf<List<Channel>>(emptyList())

    val url = mutableStateOf("")
    val uiState = mutableStateOf<PlaylistUiState>(PlaylistUiState.Idle)
    val selectedChannel = mutableStateOf<Channel?>(null)
    val favorites = mutableStateOf<Set<String>>(emptySet())

    // Navigation: Einstellungs-Screen sichtbar?
    val showSettings = mutableStateOf(false)

    // Einstellungen
    val playlists = mutableStateOf<List<PlaylistEntry>>(emptyList())
    val themeMode = mutableStateOf("dark") // dark | light | system
    val epgSources = mutableStateOf<Set<String>>(emptySet())
    val epgInfo = mutableStateOf("")

    // EPG: EPG-Kanal-ID (kleingeschrieben) -> Sendungen; epgNow = aktuell laufender Titel je ID.
    // epgNameToId: normalisierter Sendername -> EPG-ID (Fallback, wenn tvg-id nicht matcht,
    // z. B. bei Anbietern mit Hash-IDs).
    private var epgData: Map<String, List<EpgProgramme>> = emptyMap()
    private var epgNameToId: Map<String, String> = emptyMap()
    val epgNow = mutableStateOf<Map<String, String>>(emptyMap())

    // Filter-Zustände
    val searchQuery = mutableStateOf("")
    val selectedGroup = mutableStateOf<String?>(null) // null = "Alle"

    // Fokus-Index, damit nach Rückkehr aus dem Player der zuletzt gewählte Kanal fokussiert bleibt.
    var lastFocusedIndex: Int = 0

    // Nur wenn true stellt MainScreen den Listen-Fokus her (Erstladung / Rückkehr aus dem Player).
    // Bewusst KEINE Refokussierung bei Such-/Gruppenänderungen, sonst wird der Fokus beim
    // Tippen aus dem Suchfeld gestohlen.
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

    init {
        // Persistierte Daten laden -> App startet ohne erneute Eingabe/Netzabruf.
        viewModelScope.launch {
            url.value = repository.urlFlow.first()
            themeMode.value = repository.themeFlow.first()
            epgSources.value = repository.epgSourcesFlow.first()

            var storedPlaylists = repository.playlistsFlow.first()
            // Migration: eine früher gespeicherte Einzel-URL in den Playlist-Manager übernehmen.
            if (storedPlaylists.isEmpty() && url.value.isNotBlank()) {
                storedPlaylists = listOf(PlaylistEntry("Meine Playlist", url.value))
                repository.savePlaylists(storedPlaylists)
            }
            playlists.value = storedPlaylists

            val saved = repository.channelsFlow.first()
            if (saved.isNotEmpty()) {
                allChannels.value = saved
                uiState.value = PlaylistUiState.Success
                pendingListFocus.value = true
                refreshEpg()
            }
        }
        // Favoriten laufend beobachten.
        viewModelScope.launch {
            repository.favoritesFlow.collect { favorites.value = it }
        }
        // "Jetzt läuft"-Zuordnung minütlich aktualisieren.
        viewModelScope.launch {
            while (true) {
                updateEpgNow()
                delay(60_000)
            }
        }
    }

    fun onUrlChange(value: String) {
        url.value = value
    }

    fun onSearchChange(value: String) {
        searchQuery.value = value
    }

    fun selectGroup(group: String?) {
        selectedGroup.value = group
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        viewModelScope.launch { repository.saveTheme(mode) }
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
        // Matching über tvg-id UND normalisierte Sendernamen (viele Anbieter nutzen Hash-IDs).
        val wantedIds = channels.mapNotNull { it.tvgId?.lowercase()?.ifEmpty { null } }.toSet()
        val wantedNames = channels.map { normalizeChannelName(it.name) }.filterTo(HashSet()) { it.isNotEmpty() }
        epgInfo.value = "EPG wird geladen…"
        viewModelScope.launch {
            val (result, failed) = withContext(Dispatchers.IO) {
                val accProgrammes = HashMap<String, MutableList<EpgProgramme>>()
                val accNameToId = HashMap<String, String>()
                var failures = 0
                for (source in sources) {
                    runCatching {
                        maybeGunzip(openStream(source)).use { input ->
                            val parsed = XmltvParser().parse(input, wantedIds, wantedNames)
                            parsed.programmes.forEach { (id, programmes) ->
                                accProgrammes.getOrPut(id) { mutableListOf() }.addAll(programmes)
                            }
                            parsed.nameToId.forEach { (name, id) -> accNameToId.putIfAbsent(name, id) }
                        }
                    }.onFailure { failures++ }
                }
                (accProgrammes to accNameToId) to failures
            }
            epgData = result.first
            epgNameToId = result.second
            updateEpgNow()
            val programmeCount = result.first.values.sumOf { it.size }
            epgInfo.value = buildString {
                append("EPG: $programmeCount Sendungen für ${result.first.size} Sender geladen.")
                if (failed > 0) append(" $failed Quelle(n) fehlgeschlagen.")
            }
        }
    }

    /** Aktuell laufende Sendung für einen Kanal (per tvg-id, sonst per Sendername), oder null. */
    fun nowPlayingFor(channel: Channel): String? {
        val now = epgNow.value
        channel.tvgId?.lowercase()?.let { id -> now[id]?.let { return it } }
        val epgId = epgNameToId[normalizeChannelName(channel.name)] ?: return null
        return now[epgId]
    }

    private fun updateEpgNow() {
        if (epgData.isEmpty()) {
            if (epgNow.value.isNotEmpty()) epgNow.value = emptyMap()
            return
        }
        val now = System.currentTimeMillis()
        epgNow.value = buildMap {
            epgData.forEach { (id, programmes) ->
                programmes.firstOrNull { now >= it.startMs && now < it.stopMs }
                    ?.let { put(id, it.title) }
            }
        }
    }

    /** Entpackt GZIP transparent (erkannt an den Magic-Bytes, unabhängig von der Dateiendung). */
    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
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
                    openStream(target).use { M3uParser().parse(it) }
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
        selectedChannel.value = channel
    }

    fun deselectChannel() {
        selectedChannel.value = null
        pendingListFocus.value = true
    }

    /** Zappt vom aktuellen Sender aus um [delta] weiter (+1 = nächster, -1 = vorheriger). */
    fun zapChannel(delta: Int) {
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

    /**
     * Öffnet eine URL mit explizitem User-Agent (manche Anbieter blocken den Java-Default)
     * und folgt Redirects auch über Protokollwechsel hinweg (HttpURLConnection folgt
     * http→https nicht automatisch).
     */
    private fun openStream(url: String): InputStream {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IPTV/1.0 (Android TV)")
            }
            when (val code = connection.responseCode) {
                in 200..299 -> return connection.inputStream
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Weiterleitung ohne Ziel (HTTP $code)")
                    connection.disconnect()
                    current = URL(URL(current), location).toString()
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    companion object {
        const val FAVORITES_GROUP = "★ Favoriten"
        private const val MAX_REDIRECTS = 5
    }
}
