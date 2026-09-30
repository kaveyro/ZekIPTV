package com.zekikoese

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "iptv_prefs")

// Prozessweit: ViewModel und RefreshWorker nutzen eigene Repository-Instanzen.
private val channelsFileLock = Mutex()

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
        // Nur noch für die Migration: Kanäle liegen jetzt in [channelsFile].
        val LEGACY_CHANNELS = stringPreferencesKey("channels_json")
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
        val RESIZE_MODE = stringPreferencesKey("resize_mode") // fit | zoom | fill
        val PARENTAL_PIN = stringPreferencesKey("parental_pin") // leer/fehlend = kein Jugendschutz
    }

    private companion object {
        const val MAX_RECENT_CHANNELS = 15
        const val MAX_WATCH_META = 30
        const val MAX_SEARCH_HISTORY = 8
        const val MAX_RESUME_POSITIONS = 100
    }

    val urlFlow: Flow<String> = context.dataStore.data.map { it[Keys.URL] ?: "" }

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

    val resizeModeFlow: Flow<String> = context.dataStore.data.map { it[Keys.RESIZE_MODE] ?: "fit" }

    val parentalPinFlow: Flow<String> = context.dataStore.data.map { it[Keys.PARENTAL_PIN] ?: "" }

    suspend fun saveUrl(url: String) {
        context.dataStore.edit { it[Keys.URL] = url }
    }

    // ---------- Kanäle (eigene Datei statt DataStore) ----------
    // DataStore schreibt bei jeder Änderung die komplette Preferences-Datei neu. Mit einer
    // großen Playlist darin würde jeder Favoriten-Toggle / jede Resume-Sicherung mehrere MB
    // schreiben — deshalb liegen die Kanäle separat.

    private val channelsFile: File get() = File(context.filesDir, "channels.json")

    suspend fun saveChannels(channels: List<Channel>) {
        withContext(Dispatchers.IO) {
            val json = encodeChannels(channels)
            channelsFileLock.withLock {
                // Atomar ersetzen: erst temporär schreiben, dann umbenennen.
                val tmp = File(context.filesDir, "channels.json.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(channelsFile)) {
                    channelsFile.delete()
                    tmp.renameTo(channelsFile)
                }
            }
        }
    }

    /** Lädt die gespeicherten Kanäle; übernimmt einmalig den alten DataStore-Eintrag. */
    suspend fun loadChannels(): List<Channel> = withContext(Dispatchers.IO) {
        val legacy = context.dataStore.data.first()[Keys.LEGACY_CHANNELS]
        val fromFile = channelsFileLock.withLock {
            if (channelsFile.exists()) {
                runCatching { decodeChannels(channelsFile.readText()) }.getOrNull()
            } else null
        }
        val channels = fromFile
            ?: legacy?.let(::decodeChannels)?.also { if (it.isNotEmpty()) saveChannels(it) }
            ?: emptyList()
        // Alten Eintrag entfernen, damit DataStore-Schreibvorgänge wieder klein sind.
        if (legacy != null) context.dataStore.edit { it.remove(Keys.LEGACY_CHANNELS) }
        channels
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

    suspend fun saveResizeMode(mode: String) {
        context.dataStore.edit { it[Keys.RESIZE_MODE] = mode }
    }

    suspend fun saveParentalPin(pin: String?) {
        context.dataStore.edit { prefs ->
            if (pin.isNullOrEmpty()) prefs.remove(Keys.PARENTAL_PIN) else prefs[Keys.PARENTAL_PIN] = pin
        }
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

    /**
     * Speichert die Wiedergabeposition eines VOD-Titels (0 = zurücksetzen/fertig gesehen) und
     * ergänzt die Gesamtdauer in den Watch-Metadaten (erst der Player kennt sie) — in einem
     * einzigen Schreibvorgang.
     */
    suspend fun saveProgress(url: String, positionMs: Long, durationMs: Long) {
        context.dataStore.edit { prefs ->
            val resumes = prefs[Keys.RESUME]?.let(::decodeResume) ?: emptyMap()
            prefs[Keys.RESUME] = JSONObject(
                updatedResumePositions(resumes, url, positionMs, MAX_RESUME_POSITIONS) as Map<*, *>
            ).toString()

            if (durationMs > 0) {
                val meta = prefs[Keys.WATCH_META]?.let(::decodeWatchMeta)
                val existing = meta?.get(url)
                if (existing != null && existing.durationMs != durationMs) {
                    prefs[Keys.WATCH_META] = encodeWatchMeta(meta + (url to existing.copy(durationMs = durationMs)))
                }
            }
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

    suspend fun clearEpgCache() = withContext(Dispatchers.IO) {
        if (epgCacheFile.exists()) epgCacheFile.delete()
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

    // Fallback ohne Dateiauswahl (Fire TV hat meist keinen System-Dateidialog).
    private val backupFile: File
        get() = File(context.getExternalFilesDir(null), "zekiptv-backup.json")

    /**
     * Schreibt alle Einstellungen als JSON — in die gewählte Datei ([target]) oder in den
     * App-Ordner. Gibt eine Ortsbeschreibung für die Statusmeldung zurück.
     */
    suspend fun exportBackup(target: Uri? = null): String = withContext(Dispatchers.IO) {
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
            put("resizeMode", prefs[Keys.RESIZE_MODE] ?: "fit")
        }
        val json = root.toString(2)
        if (target != null) {
            val out = context.contentResolver.openOutputStream(target, "wt")
                ?: throw java.io.IOException("Datei kann nicht geschrieben werden")
            out.bufferedWriter().use { it.write(json) }
            target.lastPathSegment ?: "gewählte Datei"
        } else {
            backupFile.writeText(json)
            backupFile.absolutePath
        }
    }

    /** Liest ein Backup ([source] oder App-Ordner) und übernimmt alle Einstellungen. */
    suspend fun importBackup(source: Uri? = null): String = withContext(Dispatchers.IO) {
        val text = if (source != null) {
            val input = context.contentResolver.openInputStream(source)
                ?: throw java.io.IOException("Datei kann nicht gelesen werden")
            input.bufferedReader().use { it.readText() }
        } else {
            backupFile.readText()
        }
        val root = JSONObject(text)
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
            prefs[Keys.RESIZE_MODE] = root.optString("resizeMode", "fit")
        }
        source?.lastPathSegment ?: backupFile.absolutePath
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
                    c.userAgent?.let { put("ua", it) }
                    c.referrer?.let { put("ref", it) }
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
                        tvgId = obj.optString("tvgId").ifEmpty { null }?.takeIf { it != "null" },
                        userAgent = obj.optString("ua").ifEmpty { null },
                        referrer = obj.optString("ref").ifEmpty { null }
                    )
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Neue Resume-Map: aktualisierter Eintrag wandert ans Ende (Einfügereihenfolge = Alter),
 * die ältesten Einträge über [limit] fallen weg — sonst wächst die Map mit jedem Titel.
 */
internal fun updatedResumePositions(
    current: Map<String, Long>,
    url: String,
    positionMs: Long,
    limit: Int = 100
): Map<String, Long> {
    val updated = LinkedHashMap(current)
    updated.remove(url)
    if (positionMs > 0) updated[url] = positionMs
    while (updated.size > limit) updated.remove(updated.keys.first())
    return updated
}
