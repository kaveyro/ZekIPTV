package com.example.iptv

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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

    // Filter-Zustände
    val searchQuery = mutableStateOf("")
    val selectedGroup = mutableStateOf<String?>(null) // null = "Alle"

    // Fokus-Index, damit nach Rückkehr aus dem Player der zuletzt gewählte Kanal fokussiert bleibt.
    var lastFocusedIndex: Int = 0

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
            val saved = repository.channelsFlow.first()
            if (saved.isNotEmpty()) {
                allChannels.value = saved
                uiState.value = PlaylistUiState.Success
            }
        }
        // Favoriten laufend beobachten.
        viewModelScope.launch {
            repository.favoritesFlow.collect { favorites.value = it }
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
                    val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 15_000
                    }
                    connection.inputStream.use { M3uParser().parse(it) }
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
                        repository.saveUrl(target)
                        repository.saveChannels(channels)
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
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { repository.toggleFavorite(channel.url) }
    }

    companion object {
        const val FAVORITES_GROUP = "★ Favoriten"
    }
}
