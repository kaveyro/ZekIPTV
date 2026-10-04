package com.zekikoese

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {

    private fun ch(name: String, group: String? = null) = Channel(name, "http://x/$name", group = group)

    @Test
    fun zapStaysInStartList() {
        val favorites = listOf(ch("A"), ch("C"), ch("E"))
        // Gestartet aus den Favoriten: von C geht es zu E und dann zurück zu A — nicht zu D.
        assertEquals("E", zapTarget(favorites, "http://x/C", 0, +1)?.name)
        assertEquals("A", zapTarget(favorites, "http://x/E", 0, +1)?.name)
        assertEquals("E", zapTarget(favorites, "http://x/A", 0, -1)?.name)
    }

    @Test
    fun zapFallsBackToIndexWhenCurrentMissing() {
        val list = listOf(ch("A"), ch("B"), ch("C"))
        assertEquals("C", zapTarget(list, "http://x/unbekannt", 1, +1)?.name)
        assertEquals("A", zapTarget(list, null, 99, +1)?.name) // Index wird begrenzt
        assertNull(zapTarget(emptyList(), "http://x/A", 0, +1))
    }

    @Test
    fun channelNumbersFollowFullListAndIgnoreDuplicates() {
        val all = listOf(ch("A", "News"), ch("B", "Sport"), ch("A", "Favoriten"), ch("C", "News"))
        val numbers = channelNumberIndex(all)
        assertEquals(1, numbers["http://x/A"]) // erster Eintrag zählt
        assertEquals(2, numbers["http://x/B"])
        assertEquals(4, numbers["http://x/C"]) // Position in der vollständigen Liste, nicht in "News"
    }

    @Test
    fun validatesPlaylistUrls() {
        assertNull(validateStreamUrl("http://anbieter.tv:8080/get.php?username=a&password=b"))
        assertNull(validateStreamUrl("  HTTPS://example.org/list.m3u  "))
        assertNotNull(validateStreamUrl(""))
        assertNotNull(validateStreamUrl("anbieter.tv/list.m3u"))
        assertNotNull(validateStreamUrl("ftp://anbieter.tv/list.m3u"))
        assertNotNull(validateStreamUrl("http://"))
    }

    @Test
    fun playbackErrorsAreReadable() {
        val forbidden = playbackErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403, "ERROR_CODE_IO_BAD_HTTP_STATUS")
        assertTrue(forbidden, forbidden.contains("Verbindungslimit"))
        assertTrue(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404, "x").contains("nicht verfügbar"))
        assertTrue(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503, "x").contains("HTTP 503"))
        assertTrue(
            playbackErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null, "x")
                .contains("Internetverbindung")
        )
        assertTrue(
            playbackErrorMessage(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, null, "x")
                .contains("nicht abspielen")
        )
        assertTrue(playbackErrorMessage(PlaybackException.ERROR_CODE_UNSPECIFIED, null, "ERROR_CODE_UNSPECIFIED")
            .contains("ERROR_CODE_UNSPECIFIED"))
    }

    @Test
    fun audioTrackLabelsAreDistinguishable() {
        assertEquals("Deutsch · AAC · Stereo", audioTrackLabel(null, "de", MimeTypes.AUDIO_AAC, 2, 0))
        assertEquals("Deutsch · Dolby Digital · 5.1", audioTrackLabel(null, "de", MimeTypes.AUDIO_AC3, 6, 1))
        assertEquals("Englisch · Dolby Digital Plus", audioTrackLabel(null, "en", MimeTypes.AUDIO_E_AC3, 0, 2))
        // Eigenes Label des Streams bleibt vorn; Sprache nur ergänzt, wenn sie fehlt.
        assertEquals("Original · Englisch · MP2", audioTrackLabel("Original", "en", MimeTypes.AUDIO_MPEG_L2, 0, 0))
        assertEquals("Deutsch HD · AAC", audioTrackLabel("Deutsch HD", "de", MimeTypes.AUDIO_AAC, 0, 0))
        assertEquals("Spur 3", audioTrackLabel(null, "und", null, 0, 2))
    }
}

class GuideLogicTest {

    private val h = 60 * 60_000L
    private val m = 60_000L
    private val base = 1_700_000_000_000L - Math.floorMod(1_700_000_000_000L, 30 * m) // volle halbe Stunde

    @Test
    fun roundsToHalfHours() {
        assertEquals(base, floorToHalfHour(base + 29 * m))
        assertEquals(base + 30 * m, ceilToHalfHour(base + 1))
        assertEquals(base, ceilToHalfHour(base))
    }

    @Test
    fun filtersOverlappingProgrammes() {
        val list = listOf(
            EpgProgramme("x", base - h, base, "vorher"),
            EpgProgramme("x", base - 10 * m, base + 20 * m, "läuft rein"),
            EpgProgramme("x", base + h, base + 2 * h, "drin"),
            EpgProgramme("x", base + 3 * h, base + 4 * h, "danach")
        )
        assertEquals(listOf("läuft rein", "drin"), programmesInRange(list, base, base + 3 * h).map { it.title })
    }

    @Test
    fun windowOnlyMovesWhenCellIsCut() {
        val range = base - 3 * h to base + 12 * h
        val span = 2 * h
        // ganz sichtbar -> bleibt
        assertEquals(base, guideWindowFor(base, span, base + 30 * m, base + h, range.first, range.second))
        // überdeckt das Fenster -> bleibt
        assertEquals(base, guideWindowFor(base, span, base - h, base + 3 * h, range.first, range.second))
        // links angeschnitten -> Fenster beginnt mit der Sendung (halbe Stunde abgerundet)
        assertEquals(base - h, guideWindowFor(base, span, base - 50 * m, base + 10 * m, range.first, range.second))
        // rechts angeschnitten -> Ende wird sichtbar
        assertEquals(base + h, guideWindowFor(base, span, base + 100 * m, base + 3 * h, range.first, range.second))
        // länger als das Fenster -> Anfang sichtbar
        assertEquals(base + 90 * m, guideWindowFor(base, span, base + 100 * m, base + 6 * h, range.first, range.second))
        // nie über den geladenen Bereich hinaus
        assertEquals(range.second - span, guideWindowFor(base, span, base + 11 * h, base + 13 * h, range.first, range.second))
        assertEquals(range.first, guideWindowFor(base, span, base - 5 * h, base - 2 * h, range.first, range.second))
    }
}

class UiPolishLogicTest {

    @Test
    fun playerBackGoesStepByStep() {
        assertEquals(PlayerBackAction.CLEAR_NUMBER, playerBackAction(numberEntry = true, controlsVisible = true, isTv = true))
        assertEquals(PlayerBackAction.HIDE_CONTROLS, playerBackAction(numberEntry = false, controlsVisible = true, isTv = true))
        assertEquals(PlayerBackAction.EXIT, playerBackAction(numberEntry = false, controlsVisible = false, isTv = true))
        // Handy: Steuerleiste ist fast immer sichtbar — Zurück beendet direkt.
        assertEquals(PlayerBackAction.EXIT, playerBackAction(numberEntry = false, controlsVisible = true, isTv = false))
    }

    @Test
    fun remainingTimeIsReadable() {
        assertEquals("noch 1 min", remainingLabel(10_000))
        assertEquals("noch 38 min", remainingLabel(38 * 60_000L))
        assertEquals("noch 38 min", remainingLabel(37 * 60_000L + 1))
        assertEquals("noch 1:12 h", remainingLabel(72 * 60_000L))
    }

    @Test
    fun channelKeysAreUniqueAndStable() {
        val a = Channel("A", "http://x/a")
        val b = Channel("B", "http://x/b")
        val aAgain = Channel("A HD", "http://x/a", group = "HD")
        val keys = stableChannelKeys(listOf(a, b, aAgain))
        assertEquals(listOf("http://x/a", "http://x/b", "http://x/a#2"), keys)
        assertEquals(keys.size, keys.toSet().size)
        // Ein neuer Sender davor verschiebt die Keys der anderen nicht.
        val withNew = stableChannelKeys(listOf(Channel("N", "http://x/n"), a, b, aAgain))
        assertEquals(keys, withNew.drop(1))
    }

    @Test
    fun epgLabelNamesTheDay() {
        // Kalender statt fester 24 h — sonst schlägt der Test an Zeitumstellungstagen fehl.
        fun at(daysAgo: Int): Long = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, -daysAgo)
            set(java.util.Calendar.HOUR_OF_DAY, 6)
            set(java.util.Calendar.MINUTE, 12)
        }.timeInMillis
        val now = at(0) + 60_000
        assertEquals("heute 06:12", epgUpdatedLabel(at(0), now))
        assertEquals("gestern 06:12", epgUpdatedLabel(at(1), now))
        assertTrue(epgUpdatedLabel(at(3), now).matches(Regex("""\d\d\.\d\d\. 06:12""")))
    }

    @Test
    fun backupNameContainsDate() {
        assertTrue(backupFileName(System.currentTimeMillis()).matches(Regex("""zekiptv-backup-\d{4}-\d{2}-\d{2}\.json""")))
    }
}
