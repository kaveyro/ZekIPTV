package com.zekikoese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamApiTest {

    private val account = XtreamAccount("http://host.tv:80", "user1", "pass1")

    @Test
    fun `detects xtream account from get_php url`() {
        val account = detectXtream("http://eu4tv.com/get.php?username=abc&password=xyz&type=adv_m3u_icon&output=ts")
        assertEquals("http://eu4tv.com", account?.baseUrl)
        assertEquals("abc", account?.username)
        assertEquals("xyz", account?.password)
    }

    @Test
    fun `keeps explicit port in base url`() {
        val account = detectXtream("http://host.tv:8080/get.php?username=u&password=p")
        assertEquals("http://host.tv:8080", account?.baseUrl)
    }

    @Test
    fun `rejects non xtream urls`() {
        assertNull(detectXtream("http://example.com/playlist.m3u"))
        assertNull(detectXtream("http://example.com/get.php?username=u")) // ohne Passwort
        assertNull(detectXtream("kein url"))
    }

    @Test
    fun `parses vod categories and streams`() {
        val responses = mapOf(
            "get_vod_categories" to """[{"category_id":"7","category_name":"Action"}]""",
            "get_vod_streams" to """[
                {"stream_id":42,"name":"Ein Film","stream_icon":"http://x/poster.png","category_id":"7","container_extension":"mkv"},
                {"stream_id":43,"name":"Ohne Extras"}
            ]"""
        )
        val api = XtreamApi(account) { url -> responses.entries.first { url.contains(it.key) }.value }

        assertEquals(mapOf("7" to "Action"), api.getVodCategories())
        val streams = api.getVodStreams()
        assertEquals(2, streams.size)
        assertEquals(VodItem(42, "Ein Film", "http://x/poster.png", "7", "mkv"), streams[0])
        assertEquals("mp4", streams[1].ext) // Fallback-Container
    }

    @Test
    fun `parses series episodes sorted by season and episode`() {
        val json = """{
            "info": {"name":"Serie"},
            "episodes": {
                "2": [{"id":"201","title":"Zwei-Eins","episode_num":1,"season":2,"container_extension":"mkv"}],
                "1": [
                    {"id":"102","title":"Eins-Zwei","episode_num":"2","season":1},
                    {"id":"101","title":"Eins-Eins","episode_num":1,"season":1}
                ]
            }
        }"""
        val api = XtreamApi(account) { json }

        val episodes = api.getSeriesEpisodes(5)
        assertEquals(listOf("101", "102", "201"), episodes.map { it.id })
        assertEquals(2, episodes[1].episode) // episode_num als String geparst
        assertEquals("mp4", episodes[0].ext)
    }

    @Test
    fun `parses vod info details`() {
        val json = """{
            "info": {"plot":"Ein Film über Dinge.","genre":"Drama","releasedate":"2024-03-01",
                     "rating":"7.3","duration":"01:52:00","director":"Jane Doe","cast":"A, B"},
            "movie_data": {"stream_id":42}
        }"""
        val api = XtreamApi(account) { json }
        val info = api.getVodInfo(42)
        assertEquals("Ein Film über Dinge.", info.plot)
        assertEquals("Drama", info.genre)
        assertEquals("2024-03-01", info.releaseDate)
        assertEquals("7.3", info.rating)
        assertEquals("Jane Doe", info.director)
    }

    @Test
    fun `parses series metadata`() {
        val json = """[{"series_id":9,"name":"Serie","cover":"http://x/c.jpg","category_id":"3",
            "plot":"Spannend.","genre":"Krimi","releaseDate":"2020-01-01","rating":"8"}]"""
        val api = XtreamApi(account) { json }
        val series = api.getSeries().first()
        assertEquals("Spannend.", series.plot)
        assertEquals("Krimi", series.genre)
        assertEquals("8", series.rating)
    }

    @Test
    fun `builds stream urls`() {
        val api = XtreamApi(account) { "" }
        assertEquals(
            "http://host.tv:80/movie/user1/pass1/42.mkv",
            api.vodStreamUrl(VodItem(42, "Film", null, null, "mkv"))
        )
        assertEquals(
            "http://host.tv:80/series/user1/pass1/101.mp4",
            api.episodeStreamUrl(SeriesEpisode("101", "Ep", 1, 1, "mp4"))
        )
    }

    @Test
    fun `decodes percent encoded credentials without double encoding`() {
        val account = detectXtream("http://host.tv/get.php?username=max%40mail.de&password=a%26b%20c&type=m3u")
        assertEquals("max@mail.de", account?.username)
        assertEquals("a&b c", account?.password)

        val api = XtreamApi(account!!) { "" }
        assertEquals("http://host.tv/xmltv.php?username=max%40mail.de&password=a%26b+c", api.epgUrl())
        assertEquals(
            "http://host.tv/movie/max%40mail.de/a%26b%20c/42.mp4",
            api.vodStreamUrl(VodItem(42, "Film", null, null, "mp4"))
        )
    }

    @Test
    fun `drops duplicate catalog entries`() {
        val json = """[
            {"stream_id":42,"name":"Film","category_id":"1"},
            {"stream_id":42,"name":"Film","category_id":"2"}
        ]"""
        val api = XtreamApi(account) { json }
        assertEquals(1, api.getVodStreams().size)
    }

    @Test
    fun `redacts credentials in urls`() {
        assertEquals(
            "http://host.tv/xmltv.php?username=***&password=***",
            Http.redact("http://host.tv/xmltv.php?username=max&password=geheim")
        )
    }

    @Test
    fun `parses account info`() {
        val json = """{
            "user_info": {"status":"Active","exp_date":"1767225600","max_connections":"2",
                          "active_cons":1,"is_trial":"0"},
            "server_info": {"timezone":"Europe/Berlin"}
        }"""
        val info = XtreamApi(account) { json }.getAccountInfo()
        assertEquals("Active", info.status)
        assertEquals(1_767_225_600_000L, info.expiresAtMs)
        assertEquals(2, info.maxConnections)
        assertEquals(1, info.activeConnections)
        assertEquals(false, info.isTrial)
        assertEquals("Europe/Berlin", info.serverTimezone)
    }

    @Test
    fun `unlimited account has no expiry`() {
        val info = XtreamApi(account) { """{"user_info":{"status":"Active","exp_date":null}}""" }.getAccountInfo()
        assertNull(info.expiresAtMs)
    }

    @Test
    fun `reads archive days of live streams`() {
        val json = """[
            {"stream_id":1,"tv_archive":1,"tv_archive_duration":"7"},
            {"stream_id":2,"tv_archive":"0","tv_archive_duration":"7"},
            {"stream_id":3,"tv_archive":"1","tv_archive_duration":3}
        ]"""
        assertEquals(mapOf("1" to 7, "3" to 3), XtreamApi(account) { json }.getLiveArchiveDays())
    }

    @Test
    fun `extracts stream id from live urls`() {
        assertEquals("1234", xtreamStreamIdOf("http://host.tv/live/u/p/1234.ts"))
        assertEquals("1234", xtreamStreamIdOf("http://host.tv/u/p/1234"))
        assertEquals("55", xtreamStreamIdOf("http://host.tv/live/u/p/55.m3u8"))
        assertNull(xtreamStreamIdOf("http://host.tv/hls/kanal.m3u8"))
        assertNull(xtreamStreamIdOf("kein url"))
    }

    @Test
    fun `builds timeshift url in server timezone`() {
        val api = XtreamApi(account) { "" }
        // 2026-01-10 18:00 UTC = 19:00 in Berlin, Dauer 45 min (aufgerundet)
        val start = 1_768_068_000_000L
        val url = api.timeshiftUrl("99", start, start + 44 * 60_000 + 1, "Europe/Berlin")
        assertEquals("http://host.tv:80/timeshift/user1/pass1/45/2026-01-10:19-00/99.ts", url)
    }

    @Test
    fun `detects xtream account from m3u stream urls`() {
        val channels = listOf(
            Channel("A", "http://prov.tv:8080/live/max/geheim/101.ts"),
            Channel("B", "http://prov.tv:8080/max/geheim/102"),
            Channel("C", "http://prov.tv:8080/movie/max/geheim/7.mkv"),
            Channel("Fremd", "https://cdn.example.com/hls/kanal/index.m3u8")
        )
        assertEquals(XtreamAccount("http://prov.tv:8080", "max", "geheim"), detectXtreamFromChannels(channels))
    }

    @Test
    fun `picks the most common account and decodes credentials`() {
        val channels = listOf(
            Channel("A", "http://a.tv/live/u%40x/p%26w/1.ts"),
            Channel("B", "http://a.tv/live/u%40x/p%26w/2.ts"),
            Channel("C", "http://b.tv/live/other/pw/3.ts")
        )
        assertEquals(XtreamAccount("http://a.tv", "u@x", "p&w"), detectXtreamFromChannels(channels))
    }

    @Test
    fun `no xtream account in plain hls playlists`() {
        val channels = listOf(
            Channel("A", "https://cdn.example.com/hls/kanal/index.m3u8"),
            Channel("B", "rtmp://host/app/stream")
        )
        assertNull(detectXtreamFromChannels(channels))
    }

    @Test
    fun `builds get php playlist url and preferred output`() {
        val account = XtreamAccount("http://prov.tv:8080", "max", "a&b")
        assertEquals(
            "http://prov.tv:8080/get.php?username=max&password=a%26b&type=m3u_plus&output=m3u8",
            xtreamPlaylistUrl(account, "m3u8")
        )
        // Rückweg: die erzeugte URL wird wieder als Xtream erkannt.
        assertEquals(account, detectXtream(xtreamPlaylistUrl(account)))
        assertEquals("m3u8", preferredXtreamOutput(listOf(Channel("A", "http://h/live/u/p/1.m3u8"))))
        assertEquals("ts", preferredXtreamOutput(listOf(Channel("A", "http://h/live/u/p/1.ts"))))
    }

    @Test
    fun `normalizes entered server addresses`() {
        assertEquals("http://prov.tv:8080", normalizeXtreamServer(" prov.tv:8080/ "))
        assertEquals("https://prov.tv", normalizeXtreamServer("https://prov.tv"))
    }

    @Test
    fun `reads auth flag of account info`() {
        val ok = XtreamApi(account) { """{"user_info":{"auth":1,"status":"Active"}}""" }.getAccountInfo()
        val denied = XtreamApi(account) { """{"user_info":{"auth":0}}""" }.getAccountInfo()
        assertEquals(true, ok.authenticated)
        assertEquals(false, denied.authenticated)
    }
}
