package com.zekikoese

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "iptv_prefs")

/** Eine gespeicherte Playlist (Name + M3U-URL) im Playlist-Manager. */
data class PlaylistEntry(val name: String, val url: String)

/**
 * Anzeige-Metadaten eines gestarteten VOD/Episoden-Streams für die "Weiter schauen"-Reihe.
 * Nötig, weil [PlaylistRepository]s Resume-Positionen nur URL→Position speichern und die
 * Xtream-Kataloge (Titel/Poster) erst lazy geladen werden.
 */
data class WatchMeta(val title: String, val poster: String?, val ts: Long, val durationMs: Long = 0L)

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
        val AUTOPLAY = stringPreferencesKey("autoplay_last") // "true"/"false"
        val LAST_CHANNEL = stringPreferencesKey("last_channel_url")
        val RESUME = stringPreferencesKey("resume_positions_json") // {url: positionMs}
        val RECENT_CHANNELS = stringPreferencesKey("recent_channels_json") // ["url", ...] neueste zuerst
        val WATCH_META = stringPreferencesKey("watch_meta_json") // {url: {title, poster, ts, duration}}
        val HIDDEN_GROUPS = stringSetPreferencesKey("hidden_groups") // ausgeblendete Live-Kategorien
        val SEARCH_HISTORY = stringPreferencesKey("search_history_json") // ["query", ...] neueste zuerst
        val UI_MODE = stringPreferencesKey("ui_mode") // auto | tv | phone
    }

    private companion object {
        const val MAX_RECENT_CHANNELS = 15
        const val MAX_WATCH_META = 30
        const val MAX_SEARCH_HISTORY = 8
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

    val autoplayFlow: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTOPLAY] == "true" }

    val lastChannelFlow: Flow<String> = context.dataStore.data.map { it[Keys.LAST_CHANNEL] ?: "" }

    val resumePositionsFlow: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        prefs[Keys.RESUME]?.let(::decodeResume) ?: emptyMap()
    }

    val recentChannelsFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.RECENT_CHANNELS]?.let(::decodeStringList) ?: emptyList()
    }

    val watchMetaFlow: Flow<Map<String, WatchMeta>> = context.dataStore.data.map { prefs ->
        prefs[Keys.WATCH_META]?.let(::decodeWatchMeta) ?: emptyMap()
    }

    val hiddenGroupsFlow: Flow<Set<String>> = context.dataStore.data.map { it[Keys.HIDDEN_GROUPS] ?: emptySet() }

    val searchHistoryFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.SEARCH_HISTORY]?.let(::decodeStringList) ?: emptyList()
    }

    val uiModeFlow: Flow<String> = context.dataStore.data.map { it[Keys.UI_MODE] ?: "auto" }

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

    suspend fun saveAutoplay(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTOPLAY] = enabled.toString() }
    }

    suspend fun saveUiMode(mode: String) {
        context.dataStore.edit { it[Keys.UI_MODE] = mode }
    }

    suspend fun saveLastChannel(url: String) {
        context.dataStore.edit { it[Keys.LAST_CHANNEL] = url }
    }

    /** Blendet eine Live-Kategorie aus bzw. wieder ein. */
    suspend fun toggleHiddenGroup(group: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_GROUPS] ?: emptySet()
            prefs[Keys.HIDDEN_GROUPS] = if (group in current) current - group else current + group
        }
    }

    /** Merkt einen Suchbegriff für den Suchverlauf (Dedupe, neueste zuerst, begrenzt). */
    suspend fun addSearchQuery(query: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SEARCH_HISTORY]?.let(::decodeStringList) ?: emptyList()
            val updated = (listOf(query) + current.filter { !it.equals(query, ignoreCase = true) })
                .take(MAX_SEARCH_HISTORY)
            prefs[Keys.SEARCH_HISTORY] = JSONArray(updated).toString()
        }
    }

    /** Merkt einen Sender als zuletzt gesehen (Dedupe, neueste zuerst, begrenzt). */
    suspend fun addRecentChannel(url: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_CHANNELS]?.let(::decodeStringList) ?: emptyList()
            val updated = (listOf(url) + current.filter { it != url }).take(MAX_RECENT_CHANNELS)
            prefs[Keys.RECENT_CHANNELS] = JSONArray(updated).toString()
        }
    }

    /** Speichert Titel/Poster eines gestarteten VOD/Episoden-Streams für "Weiter schauen". */
    suspend fun saveWatchMeta(url: String, title: String, poster: String?) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.WATCH_META]?.let(::decodeWatchMeta)?.toMutableMap() ?: mutableMapOf()
            val existing = current[url]
            current[url] = WatchMeta(title, poster, System.currentTimeMillis(), existing?.durationMs ?: 0L)
            val trimmed = current.entries
                .sortedByDescending { it.value.ts }
                .take(MAX_WATCH_META)
                .associate { it.key to it.value }
            prefs[Keys.WATCH_META] = encodeWatchMeta(trimmed)
        }
    }

    /** Ergänzt die Gesamtdauer eines Streams (bekannt erst, sobald der Player geladen hat). */
    suspend fun updateWatchDuration(url: String, durationMs: Long) {
        if (durationMs <= 0) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.WATCH_META]?.let(::decodeWatchMeta)?.toMutableMap() ?: return@edit
            val existing = current[url] ?: return@edit
            if (existing.durationMs == durationMs) return@edit
            current[url] = existing.copy(durationMs = durationMs)
            prefs[Keys.WATCH_META] = encodeWatchMeta(current)
        }
    }

    /** Speichert die Wiedergabeposition eines VOD-Titels (0 = zurücksetzen/fertig gesehen). */
    suspend fun saveResumePosition(url: String, positionMs: Long) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RESUME]?.let(::decodeResume)?.toMutableMap() ?: mutableMapOf()
            if (positionMs > 0) current[url] = positionMs else current.remove(url)
            prefs[Keys.RESUME] = JSONObject(current as Map<*, *>).toString()
        }
    }

    // ---------- EPG-Cache (Datei, damit kein Download bei jedem Start nötig ist) ----------

    private val epgCacheFile: File get() = File(context.filesDir, "epg_cache.json")

    suspend fun saveEpgCache(
        programmes: Map<String, List<EpgProgramme>>,
        nameToId: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            root.put("ts", System.currentTimeMillis())
            root.put("nameToId", JSONObject(nameToId as Map<*, *>))
            val progs = JSONObject()
            programmes.forEach { (id, list) ->
                val array = JSONArray()
                list.forEach { p ->
                    array.put(JSONArray().put(p.startMs).put(p.stopMs).put(p.title))
                }
                progs.put(id, array)
            }
            root.put("programmes", progs)
            epgCacheFile.writeText(root.toString())
        }
    }

    /** Lädt den EPG-Cache, falls vorhanden und jünger als [maxAgeMs]; sonst null. */
    suspend fun loadEpgCache(
        maxAgeMs: Long
    ): Pair<Map<String, List<EpgProgramme>>, Map<String, String>>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!epgCacheFile.exists()) return@runCatching null
            val root = JSONObject(epgCacheFile.readText())
            if (System.currentTimeMillis() - root.optLong("ts") > maxAgeMs) return@runCatching null
            val nameToId = buildMap {
                val obj = root.optJSONObject("nameToId") ?: JSONObject()
                obj.keys().forEach { put(it, obj.getString(it)) }
            }
            val programmes = buildMap<String, List<EpgProgramme>> {
                val obj = root.optJSONObject("programmes") ?: JSONObject()
                obj.keys().forEach { id ->
                    val array = obj.getJSONArray(id)
                    put(id, buildList {
                        for (i in 0 until array.length()) {
                            val p = array.getJSONArray(i)
                            add(EpgProgramme(id, p.getLong(0), p.getLong(1), p.getString(2)))
                        }
                    })
                }
            }
            programmes to nameToId
        }.getOrNull()
    }

    private fun decodeResume(json: String): Map<String, Long> = try {
        val obj = JSONObject(json)
        buildMap { obj.keys().forEach { put(it, obj.getLong(it)) } }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun decodeStringList(json: String): List<String> = try {
        val array = JSONArray(json)
        buildList { for (i in 0 until array.length()) add(array.getString(i)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun encodeWatchMeta(meta: Map<String, WatchMeta>): String {
        val root = JSONObject()
        meta.forEach { (url, m) ->
            root.put(url, JSONObject().apply {
                put("title", m.title)
                put("poster", m.poster ?: JSONObject.NULL)
                put("ts", m.ts)
                put("duration", m.durationMs)
            })
        }
        return root.toString()
    }

    private fun decodeWatchMeta(json: String): Map<String, WatchMeta> = try {
        val root = JSONObject(json)
        buildMap {
            root.keys().forEach { url ->
                val obj = root.getJSONObject(url)
                put(
                    url,
                    WatchMeta(
                        title = obj.optString("title"),
                        poster = obj.optString("poster").ifEmpty { null }?.takeIf { it != "null" },
                        ts = obj.optLong("ts"),
                        durationMs = obj.optLong("duration")
                    )
                )
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }

    // ---------- Backup (Playlists, Favoriten, EPG-Quellen, Einstellungen) ----------

    private val backupFile: File
        get() = File(context.getExternalFilesDir(null), "zekiptv-backup.json")

    /** Schreibt alle Einstellungen als JSON-Datei; gibt den Pfad zurück. */
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        val root = JSONObject().apply {
            put("url", prefs[Keys.URL] ?: "")
            put("playlists", prefs[Keys.PLAYLISTS] ?: "[]")
            put("favorites", JSONArray((prefs[Keys.FAVORITES] ?: emptySet()).toList()))
            put("epgSources", JSONArray((prefs[Keys.EPG_SOURCES] ?: emptySet()).toList()))
            put("theme", prefs[Keys.THEME] ?: "dark")
            put("autoplay", prefs[Keys.AUTOPLAY] ?: "false")
            put("recentChannels", prefs[Keys.RECENT_CHANNELS] ?: "[]")
            put("watchMeta", prefs[Keys.WATCH_META] ?: "{}")
            put("hiddenGroups", JSONArray((prefs[Keys.HIDDEN_GROUPS] ?: emptySet()).toList()))
            put("uiMode", prefs[Keys.UI_MODE] ?: "auto")
        }
        backupFile.writeText(root.toString(2))
        backupFile.absolutePath
    }

    /** Liest die Backup-Datei und übernimmt alle Einstellungen; gibt den Pfad zurück. */
    suspend fun importBackup(): String = withContext(Dispatchers.IO) {
        val root = JSONObject(backupFile.readText())
        fun jsonToSet(name: String): Set<String> {
            val array = root.optJSONArray(name) ?: JSONArray()
            return buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        }
        context.dataStore.edit { prefs ->
            prefs[Keys.URL] = root.optString("url")
            prefs[Keys.PLAYLISTS] = root.optString("playlists", "[]")
            prefs[Keys.FAVORITES] = jsonToSet("favorites")
            prefs[Keys.EPG_SOURCES] = jsonToSet("epgSources")
            prefs[Keys.THEME] = root.optString("theme", "dark")
            prefs[Keys.AUTOPLAY] = root.optString("autoplay", "false")
            prefs[Keys.RECENT_CHANNELS] = root.optString("recentChannels", "[]")
            prefs[Keys.WATCH_META] = root.optString("watchMeta", "{}")
            prefs[Keys.HIDDEN_GROUPS] = jsonToSet("hiddenGroups")
            prefs[Keys.UI_MODE] = root.optString("uiMode", "auto")
        }
        backupFile.absolutePath
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
