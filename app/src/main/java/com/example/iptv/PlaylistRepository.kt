package com.example.iptv

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "iptv_prefs")

/** Eine gespeicherte Playlist (Name + M3U-URL) im Playlist-Manager. */
data class PlaylistEntry(val name: String, val url: String)

/**
 * Persistiert App-Daten: aktive M3U-URL, geparste Kanäle (als JSON), Favoriten,
 * gespeicherte Playlists, Theme-Modus und EPG-Quellen — damit die App nach einem
 * Neustart ohne erneute Eingabe / erneuten Netzabruf startet.
 */
class PlaylistRepository(private val context: Context) {

    private object Keys {
        val URL = stringPreferencesKey("m3u_url")
        val CHANNELS = stringPreferencesKey("channels_json")
        val FAVORITES = stringSetPreferencesKey("favorite_urls")
        val PLAYLISTS = stringPreferencesKey("playlists_json")
        val THEME = stringPreferencesKey("theme_mode") // dark | light | system
        val EPG_SOURCES = stringSetPreferencesKey("epg_sources")
    }

    val urlFlow: Flow<String> = context.dataStore.data.map { it[Keys.URL] ?: "" }

    val channelsFlow: Flow<List<Channel>> = context.dataStore.data.map { prefs ->
        prefs[Keys.CHANNELS]?.let(::decodeChannels) ?: emptyList()
    }.flowOn(Dispatchers.Default) // JSON-Decode großer Playlists nicht auf dem Main-Thread

    val favoritesFlow: Flow<Set<String>> = context.dataStore.data.map { it[Keys.FAVORITES] ?: emptySet() }

    val playlistsFlow: Flow<List<PlaylistEntry>> = context.dataStore.data.map { prefs ->
        prefs[Keys.PLAYLISTS]?.let(::decodePlaylists) ?: emptyList()
    }

    val themeFlow: Flow<String> = context.dataStore.data.map { it[Keys.THEME] ?: "dark" }

    val epgSourcesFlow: Flow<Set<String>> = context.dataStore.data.map { it[Keys.EPG_SOURCES] ?: emptySet() }

    suspend fun saveUrl(url: String) {
        context.dataStore.edit { it[Keys.URL] = url }
    }

    suspend fun saveChannels(channels: List<Channel>) {
        context.dataStore.edit { it[Keys.CHANNELS] = encodeChannels(channels) }
    }

    suspend fun toggleFavorite(url: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FAVORITES] ?: emptySet()
            prefs[Keys.FAVORITES] = if (url in current) current - url else current + url
        }
    }

    suspend fun savePlaylists(playlists: List<PlaylistEntry>) {
        context.dataStore.edit { it[Keys.PLAYLISTS] = encodePlaylists(playlists) }
    }

    suspend fun saveTheme(mode: String) {
        context.dataStore.edit { it[Keys.THEME] = mode }
    }

    suspend fun saveEpgSources(sources: Set<String>) {
        context.dataStore.edit { it[Keys.EPG_SOURCES] = sources }
    }

    private fun encodePlaylists(playlists: List<PlaylistEntry>): String {
        val array = JSONArray()
        playlists.forEach { entry ->
            array.put(JSONObject().apply {
                put("name", entry.name)
                put("url", entry.url)
            })
        }
        return array.toString()
    }

    private fun decodePlaylists(json: String): List<PlaylistEntry> = try {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(PlaylistEntry(name = obj.optString("name"), url = obj.optString("url")))
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun encodeChannels(channels: List<Channel>): String {
        val array = JSONArray()
        channels.forEach { c ->
            array.put(
                JSONObject().apply {
                    put("name", c.name)
                    put("url", c.url)
                    put("logo", c.logo ?: JSONObject.NULL)
                    put("group", c.group ?: JSONObject.NULL)
                    put("tvgId", c.tvgId ?: JSONObject.NULL)
                }
            )
        }
        return array.toString()
    }

    private fun decodeChannels(json: String): List<Channel> = try {
        val array = JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    Channel(
                        name = obj.optString("name"),
                        url = obj.optString("url"),
                        logo = obj.optString("logo").ifEmpty { null }?.takeIf { it != "null" },
                        group = obj.optString("group").ifEmpty { null }?.takeIf { it != "null" },
                        tvgId = obj.optString("tvgId").ifEmpty { null }?.takeIf { it != "null" }
                    )
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
