package com.zekikoese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class XmltvParserTest {

    // Feste "Jetzt"-Zeit für deterministische Tests: 2026-07-10 12:00 UTC.
    private val nowMs = utc("20260710120000")

    private fun utc(stamp: String): Long =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(stamp)!!.time

    private fun parse(xml: String, wantedIds: Set<String> = emptySet(), wantedNames: Set<String> = emptySet()) =
        XmltvParser(nowMs = nowMs).parse(xml.byteInputStream(), wantedIds, wantedNames)

    @Test
    fun `parses programme within window for wanted channel id`() {
        val result = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <programme start="20260710113000 +0000" stop="20260710123000 +0000" channel="DasErste.de">
                <title>Tagesschau</title>
              </programme>
            </tv>
            """.trimIndent(),
            wantedIds = setOf("daserste.de")
        )
        assertEquals(1, result.programmes["daserste.de"]?.size)
        assertEquals("Tagesschau", result.programmes["daserste.de"]?.first()?.title)
    }

    @Test
    fun `matches channel by normalized display name when tvg-id is a hash`() {
        val result = parse(
            """
            <tv>
              <channel id="beIN.SPORTS.1.tr">
                <display-name>beIN SPORTS 1</display-name>
              </channel>
              <programme start="20260710113000 +0000" stop="20260710123000 +0000" channel="beIN.SPORTS.1.tr">
                <title>Süper Lig</title>
              </programme>
            </tv>
            """.trimIndent(),
            wantedIds = setOf("efef0ea19b7b6a76335a7fdd979788de"), // Hash-ID des Anbieters
            wantedNames = setOf(normalizeChannelName("SD: beIN Sports 1"))
        )
        // Namens-Matching muss die EPG-ID auflösen und die Sendung liefern.
        assertEquals("bein.sports.1.tr", result.nameToId[normalizeChannelName("SD: beIN Sports 1")])
        assertEquals("Süper Lig", result.programmes["bein.sports.1.tr"]?.first()?.title)
    }

    @Test
    fun `normalize strips country prefix quality tags and special chars`() {
        assertEquals("daserste", normalizeChannelName("DE: Das Erste HD"))
        assertEquals("daserste", normalizeChannelName("Das Erste"))
        assertEquals("beinsports1", normalizeChannelName("SD: beIN Sports 1"))
        assertEquals("beinsports1", normalizeChannelName("beIN SPORTS 1"))
        assertEquals("trtcocuk", normalizeChannelName("TR: TRT Çocuk"))
        assertEquals("trtcocuk", normalizeChannelName("TRT Cocuk"))
    }

    @Test
    fun `ignores unwanted channels`() {
        val result = parse(
            """
            <tv>
              <programme start="20260710113000 +0000" stop="20260710123000 +0000" channel="Other.ch">
                <title>Irrelevant</title>
              </programme>
            </tv>
            """.trimIndent(),
            wantedIds = setOf("daserste.de")
        )
        assertTrue(result.programmes.isEmpty())
    }

    @Test
    fun `ignores programmes far outside the window`() {
        val result = parse(
            """
            <tv>
              <programme start="20260720113000 +0000" stop="20260720123000 +0000" channel="DasErste.de">
                <title>Zu weit in der Zukunft</title>
              </programme>
            </tv>
            """.trimIndent(),
            wantedIds = setOf("daserste.de")
        )
        assertTrue(result.programmes.isEmpty())
    }

    @Test
    fun `handles timezone offset without space`() {
        val result = parse(
            """
            <tv>
              <programme start="20260710133000+0200" stop="20260710143000+0200" channel="zdf.de">
                <title>heute</title>
              </programme>
            </tv>
            """.trimIndent(),
            wantedIds = setOf("zdf.de")
        )
        // 13:30 +0200 == 11:30 UTC -> läuft um 12:00 UTC
        val programme = result.programmes["zdf.de"]?.first()
        assertEquals("heute", programme?.title)
        assertTrue(programme!!.startMs <= nowMs && nowMs < programme.stopMs)
    }

    @Test
    fun `sorts programmes by start time and matches channel id case-insensitively`() {
        val result = parse(
            """
            <tv>
              <programme start="20260710140000 +0000" stop="20260710150000 +0000" channel="RTL.de">
                <title>Später</title>
              </programme>
              <programme start="20260710110000 +0000" stop="20260710120000 +0000" channel="rtl.de">
                <title>Früher</title>
              </programme>
            </tv>
            """.trimIndent(),
            wantedIds = setOf("rtl.de")
        )
        val titles = result.programmes["rtl.de"]?.map { it.title }
        assertEquals(listOf("Früher", "Später"), titles)
    }
}
