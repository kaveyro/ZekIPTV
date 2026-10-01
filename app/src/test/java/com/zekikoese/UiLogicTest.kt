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
