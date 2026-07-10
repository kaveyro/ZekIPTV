package com.example.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private fun parse(content: String): List<Channel> =
        M3uParser().parse(content.byteInputStream())

    @Test
    fun `parses simple channel without attributes`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1,Das Erste
            http://example.com/ard.m3u8
            """.trimIndent()
        )
        assertEquals(1, channels.size)
        assertEquals("Das Erste", channels[0].name)
        assertEquals("http://example.com/ard.m3u8", channels[0].url)
        assertNull(channels[0].logo)
        assertNull(channels[0].group)
    }

    @Test
    fun `parses tvg-logo and group-title attributes`() {
        val channels = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="zdf" tvg-logo="http://example.com/zdf.png" group-title="DEU: Nachrichten",ZDF HD
            http://example.com/zdf.m3u8
            """.trimIndent()
        )
        assertEquals(1, channels.size)
        assertEquals("ZDF HD", channels[0].name)
        assertEquals("http://example.com/zdf.png", channels[0].logo)
        assertEquals("DEU: Nachrichten", channels[0].group)
    }

    @Test
    fun `keeps commas in channel name`() {
        val channels = parse(
            """
            #EXTINF:-1 group-title="Sport",Sky Sport, HD
            http://example.com/sky.m3u8
            """.trimIndent()
        )
        assertEquals("Sky Sport, HD", channels[0].name)
    }

    @Test
    fun `keeps commas inside quoted attributes`() {
        val channels = parse(
            """
            #EXTINF:-1 group-title="News, Sport & Doku",Eurosport
            http://example.com/eurosport.m3u8
            """.trimIndent()
        )
        assertEquals("Eurosport", channels[0].name)
        assertEquals("News, Sport & Doku", channels[0].group)
    }

    @Test
    fun `falls back to tvg-name when display name is missing`() {
        val channels = parse(
            """
            #EXTINF:-1 tvg-name="RTL" group-title="DEU",
            http://example.com/rtl.m3u8
            """.trimIndent()
        )
        assertEquals("RTL", channels[0].name)
    }

    @Test
    fun `keeps duplicate stream urls as separate channels`() {
        val channels = parse(
            """
            #EXTINF:-1 group-title="A",Sender Eins
            http://example.com/same.m3u8
            #EXTINF:-1 group-title="B",Sender Zwei
            http://example.com/same.m3u8
            """.trimIndent()
        )
        assertEquals(2, channels.size)
        assertEquals("Sender Eins", channels[0].name)
        assertEquals("Sender Zwei", channels[1].name)
    }

    @Test
    fun `ignores comments blank lines and urls without extinf`() {
        val channels = parse(
            """
            #EXTM3U

            #EXTVLCOPT:http-user-agent=Foo
            #EXTINF:-1,Kanal
            http://example.com/a.m3u8

            http://example.com/orphan.m3u8
            """.trimIndent()
        )
        assertEquals(1, channels.size)
        assertEquals("Kanal", channels[0].name)
    }

    @Test
    fun `empty attribute values become null`() {
        val channels = parse(
            """
            #EXTINF:-1 tvg-logo="" group-title="",Kanal
            http://example.com/a.m3u8
            """.trimIndent()
        )
        assertNull(channels[0].logo)
        assertNull(channels[0].group)
    }
}
