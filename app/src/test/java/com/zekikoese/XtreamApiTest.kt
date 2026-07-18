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
}
