package com.zekikoese

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Xtream-Codes-Zugangsdaten, aus einer get.php-Playlist-URL abgeleitet. */
data class XtreamAccount(val baseUrl: String, val username: String, val password: String)

/** Ein Film aus dem VOD-Katalog des Anbieters. */
data class VodItem(
    val id: Int,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val ext: String
)

/** Eine Serie aus dem Katalog des Anbieters (inkl. Metadaten für die Detail-Ansicht). */
data class SeriesItem(
    val id: Int,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: String? = null
)

/** Detail-Metadaten eines Films (get_vod_info). */
data class VodInfo(
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val duration: String?,
    val director: String?,
    val cast: String?
)

/** Eine einzelne Serien-Episode. */
data class SeriesEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val ext: String
)

/** Konto-Status beim Anbieter (player_api.php ohne action). */
data class XtreamAccountInfo(
    val status: String?,
    /** Ablaufdatum in ms seit Epoch; null = unbegrenzt/unbekannt. */
    val expiresAtMs: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    /** Zeitzone des Servers (für Catch-up-URLs), z. B. "Europe/Berlin". */
    val serverTimezone: String?
)

/**
 * Xtream-Stream-ID aus einer Live-URL der Playlist, z. B. http://host/live/u/p/1234.ts oder
 * http://host/u/p/1234 -> "1234". Null, wenn das letzte Pfadsegment keine Zahl ist.
 */
fun xtreamStreamIdOf(channelUrl: String): String? {
    val path = runCatching { URL(channelUrl.trim()).path }.getOrNull() ?: return null
    val last = path.substringAfterLast('/').substringBefore('.')
    return last.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

/** Ein abspielbares Medium: Live-Sender, Film, Serien-Episode oder Catch-up-Sendung. */
data class PlayingMedia(
    val url: String,
    val title: String,
    val isLive: Boolean,
    val userAgent: String? = null,
    val referrer: String? = null
)

/**
 * Erkennt Xtream-Codes-Zugangsdaten in einer get.php-Playlist-URL,
 * z. B. http://host/get.php?username=U&password=P&type=m3u.
 */
fun detectXtream(playlistUrl: String): XtreamAccount? {
    val url = runCatching { URL(playlistUrl.trim()) }.getOrNull() ?: return null
    if (!url.path.endsWith("/get.php")) return null
    val params = url.query?.split("&")?.mapNotNull { param ->
        // Werte decodieren — sonst würde ein %-kodiertes Passwort beim erneuten Encodieren
        // doppelt kodiert ("%40" -> "%2540").
        param.split("=", limit = 2).takeIf { it.size == 2 }
            ?.let { it[0] to runCatching { URLDecoder.decode(it[1], "UTF-8") }.getOrDefault(it[1]) }
    }?.toMap() ?: return null
    val username = params["username"]?.ifEmpty { null } ?: return null
    val password = params["password"]?.ifEmpty { null } ?: return null
    val port = if (url.port != -1) ":${url.port}" else ""
    return XtreamAccount("${url.protocol}://${url.host}$port", username, password)
}

/**
 * Minimaler Client für die Xtream-Codes player_api: Filme (VOD) und Serien inklusive
 * Episoden. Liefert außerdem die XMLTV-EPG-URL des Anbieters — deren Kanal-IDs passen
 * exakt zur Playlist, im Gegensatz zu externen EPG-Quellen.
 */
class XtreamApi(
    private val account: XtreamAccount,
    private val fetchText: (String) -> String = Http::readText
) {

    private val credentials: String
        get() {
            val u = URLEncoder.encode(account.username, "UTF-8")
            val p = URLEncoder.encode(account.password, "UTF-8")
            return "username=$u&password=$p"
        }

    /** Zugangsdaten als Pfadsegmente der Stream-URLs (Leerzeichen als %20, nicht als "+"). */
    private val pathCredentials: String
        get() = listOf(account.username, account.password)
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun apiUrl(action: String, extra: String = ""): String =
        "${account.baseUrl}/player_api.php?$credentials&action=$action$extra"

    /** XMLTV-EPG des Anbieters (IDs matchen die eigene Playlist). */
    fun epgUrl(): String = "${account.baseUrl}/xmltv.php?$credentials"

    /** Konto-Status: Ablaufdatum, Verbindungen, Server-Zeitzone. */
    fun getAccountInfo(): XtreamAccountInfo {
        val root = JSONObject(fetchText("${account.baseUrl}/player_api.php?$credentials"))
        val user = root.optJSONObject("user_info") ?: JSONObject()
        val server = root.optJSONObject("server_info") ?: JSONObject()
        fun text(obj: JSONObject, name: String): String? =
            obj.optString(name).ifEmpty { null }?.takeIf { it != "null" }
        return XtreamAccountInfo(
            status = text(user, "status"),
            // exp_date: Unix-Sekunden (als Zahl oder String); fehlt bei unbegrenzten Konten.
            expiresAtMs = text(user, "exp_date")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000),
            maxConnections = text(user, "max_connections")?.toIntOrNull(),
            activeConnections = text(user, "active_cons")?.toIntOrNull(),
            isTrial = text(user, "is_trial") == "1",
            serverTimezone = text(server, "timezone")
        )
    }

    /** Live-Sender mit Archiv (Catch-up): Stream-ID -> Archivtiefe in Tagen. */
    fun getLiveArchiveDays(): Map<String, Int> {
        val array = JSONArray(fetchText(apiUrl("get_live_streams")))
        return buildMap {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("stream_id").ifEmpty { null } ?: continue
                // tv_archive kommt je nach Panel als 1 oder "1".
                if (obj.optString("tv_archive") != "1") continue
                val days = obj.optString("tv_archive_duration").toIntOrNull() ?: 0
                if (days > 0) put(id, days)
            }
        }
    }

    /**
     * Catch-up-URL einer vergangenen Sendung. Die Startzeit erwartet der Server in seiner
     * eigenen Zeitzone ([timezone], sonst die des Geräts).
     */
    fun timeshiftUrl(streamId: String, startMs: Long, stopMs: Long, timezone: String?): String {
        val minutes = ((stopMs - startMs + 59_999) / 60_000).coerceAtLeast(1)
        val format = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply {
            timeZone = timezone?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
        }
        return "${account.baseUrl}/timeshift/$pathCredentials/$minutes/${format.format(Date(startMs))}/$streamId.ts"
    }

    fun getVodCategories(): Map<String, String> =
        parseCategories(fetchText(apiUrl("get_vod_categories")))

    fun getSeriesCategories(): Map<String, String> =
        parseCategories(fetchText(apiUrl("get_series_categories")))

    fun getVodStreams(): List<VodItem> {
        val array = JSONArray(fetchText(apiUrl("get_vod_streams")))
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optInt("stream_id", -1)
                val name = obj.optString("name")
                if (id < 0 || name.isEmpty()) continue
                add(
                    VodItem(
                        id = id,
                        name = name,
                        icon = obj.optString("stream_icon").ifEmpty { null },
                        categoryId = obj.optString("category_id").ifEmpty { null },
                        ext = obj.optString("container_extension").ifEmpty { "mp4" }
                    )
                )
            }
        }.distinctBy { it.id } // Manche Anbieter listen Titel doppelt -> doppelte Grid-Keys
    }

    fun getSeries(): List<SeriesItem> {
        val array = JSONArray(fetchText(apiUrl("get_series")))
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optInt("series_id", -1)
                val name = obj.optString("name")
                if (id < 0 || name.isEmpty()) continue
                add(
                    SeriesItem(
                        id = id,
                        name = name,
                        cover = obj.optString("cover").ifEmpty { null },
                        categoryId = obj.optString("category_id").ifEmpty { null },
                        plot = obj.optString("plot").ifEmpty { null },
                        genre = obj.optString("genre").ifEmpty { null },
                        releaseDate = obj.optString("releaseDate").ifEmpty { null },
                        rating = obj.optString("rating").ifEmpty { null }?.takeIf { it != "0" }
                    )
                )
            }
        }.distinctBy { it.id }
    }

    /** Detail-Metadaten eines Films (Plot, Jahr, Bewertung, …). */
    fun getVodInfo(vodId: Int): VodInfo {
        val obj = JSONObject(fetchText(apiUrl("get_vod_info", "&vod_id=$vodId")))
        val info = obj.optJSONObject("info") ?: JSONObject()
        fun field(name: String): String? = info.optString(name).ifEmpty { null }?.takeIf { it != "null" }
        return VodInfo(
            plot = field("plot") ?: field("description"),
            genre = field("genre"),
            releaseDate = field("releasedate") ?: field("release_date"),
            rating = field("rating")?.takeIf { it != "0" },
            duration = field("duration"),
            director = field("director"),
            cast = field("cast") ?: field("actors")
        )
    }

    /** Alle Episoden einer Serie, sortiert nach Staffel und Episodennummer. */
    fun getSeriesEpisodes(seriesId: Int): List<SeriesEpisode> {
        val obj = JSONObject(fetchText(apiUrl("get_series_info", "&series_id=$seriesId")))
        val episodes = obj.optJSONObject("episodes") ?: return emptyList()
        val result = mutableListOf<SeriesEpisode>()
        episodes.keys().forEach { seasonKey ->
            val seasonArray = episodes.optJSONArray(seasonKey) ?: return@forEach
            for (i in 0 until seasonArray.length()) {
                val ep = seasonArray.optJSONObject(i) ?: continue
                val id = ep.optString("id")
                if (id.isEmpty()) continue
                result.add(
                    SeriesEpisode(
                        id = id,
                        title = ep.optString("title").ifEmpty { "Episode" },
                        season = ep.optInt("season", seasonKey.toIntOrNull() ?: 0),
                        episode = ep.optInt("episode_num", i + 1),
                        ext = ep.optString("container_extension").ifEmpty { "mp4" }
                    )
                )
            }
        }
        return result.distinctBy { it.id }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    fun vodStreamUrl(item: VodItem): String =
        "${account.baseUrl}/movie/$pathCredentials/${item.id}.${item.ext}"

    fun episodeStreamUrl(episode: SeriesEpisode): String =
        "${account.baseUrl}/series/$pathCredentials/${episode.id}.${episode.ext}"

    private fun parseCategories(json: String): Map<String, String> {
        val array = JSONArray(json)
        return buildMap {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("category_id")
                val name = obj.optString("category_name")
                if (id.isNotEmpty() && name.isNotEmpty()) put(id, name)
            }
        }
    }
}
