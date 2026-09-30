package com.zekikoese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsSegmenterTest {

    private val videoPid = 0x100
    private val pmtPid = 0x1000

    private fun packet(pid: Int, payloadStart: Boolean, randomAccess: Boolean = false, payload: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (((if (payloadStart) 0x40 else 0) or (pid shr 8)) and 0xFF).toByte()
        p[2] = (pid and 0xFF).toByte()
        var offset = 4
        if (randomAccess) {
            p[3] = 0x30 // Adaptation Field + Payload
            p[4] = 1    // Länge
            p[5] = 0x40 // random_access_indicator
            offset = 6
        } else {
            p[3] = 0x10 // nur Payload
        }
        payload.copyInto(p, offset, 0, minOf(payload.size, 188 - offset))
        return p
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun pat() = packet(0, true, payload = bytes(
        0x00, // pointer_field
        0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, // Header, section_length=13
        0x00, 0x01, 0xE0 or (pmtPid shr 8), pmtPid and 0xFF, // program 1 -> PMT-PID
        0, 0, 0, 0 // CRC (ungeprüft)
    ))

    private fun pmt() = packet(pmtPid, true, payload = bytes(
        0x00,
        0x02, 0xB0, 0x12, 0x00, 0x01, 0xC1, 0x00, 0x00, // section_length=18
        0xE0 or (videoPid shr 8), videoPid and 0xFF, // PCR-PID
        0xF0, 0x00, // program_info_length=0
        0x1B, 0xE0 or (videoPid shr 8), videoPid and 0xFF, 0xF0, 0x00, // H.264
        0, 0, 0, 0
    ))

    private fun videoPes(pts: Long, keyframe: Boolean): ByteArray {
        val header = bytes(
            0x00, 0x00, 0x01, 0xE0, 0x00, 0x00, 0x80, 0x80, 0x05,
            (0x21 or (((pts shr 30) and 0x07).toInt() shl 1)),
            ((pts shr 22) and 0xFF).toInt(),
            ((((pts shr 15) and 0x7F).toInt() shl 1) or 1),
            ((pts shr 7) and 0xFF).toInt(),
            (((pts and 0x7F).toInt() shl 1) or 1)
        )
        return packet(videoPid, true, randomAccess = keyframe, payload = header)
    }

    /** 25 fps, Keyframe jede Sekunde; liefert die fertigen Segmente. */
    private fun feed(segmenter: TsSegmenter, frames: IntRange, ptsOffset: Long = 0): List<TsSegmenter.Segment> {
        val out = mutableListOf<TsSegmenter.Segment>()
        for (frame in frames) {
            if (frame % 25 == 0) {
                segmenter.onPacket(pat())?.let(out::add)
                segmenter.onPacket(pmt())?.let(out::add)
            }
            segmenter.onPacket(videoPes(ptsOffset + frame * 3_600L, keyframe = frame % 25 == 0))?.let(out::add)
            // etwas Nicht-PES-Nutzlast dazwischen
            segmenter.onPacket(packet(videoPid, false, payload = ByteArray(10)))?.let(out::add)
        }
        return out
    }

    @Test
    fun `cuts segments of target duration at keyframes`() {
        val segments = feed(TsSegmenter(targetDurationMs = 2_000), 0 until 250) // 10 s
        assertEquals(4, segments.size) // 0-2, 2-4, 4-6, 6-8 s abgeschlossen; 8-10 läuft noch
        segments.forEach { assertEquals(2_000L, it.durationMs) }
        assertTrue(segments.none { it.discontinuity })
    }

    @Test
    fun `every segment starts with pat and pmt`() {
        val segments = feed(TsSegmenter(targetDurationMs = 2_000), 0 until 250)
        segments.drop(1).forEach { segment ->
            val firstPid = ((segment.data[1].toInt() and 0x1F) shl 8) or (segment.data[2].toInt() and 0xFF)
            val secondPid = ((segment.data[189].toInt() and 0x1F) shl 8) or (segment.data[190].toInt() and 0xFF)
            assertEquals(0, firstPid)
            assertEquals(pmtPid, secondPid)
            assertEquals(0, segment.data.size % 188)
        }
    }

    @Test
    fun `timestamp jump marks a discontinuity`() {
        val segmenter = TsSegmenter(targetDurationMs = 2_000)
        val before = feed(segmenter, 0 until 60)
        assertTrue(before.none { it.discontinuity })
        // Anbieter-Reconnect: Zeitstempel springen zurück
        val after = feed(segmenter, 0 until 150, ptsOffset = 0)
        assertFalse(after.first().discontinuity) // Rest vor dem Sprung
        assertTrue(after[1].discontinuity)
    }

    @Test
    fun `hls sources are not recorded locally`() {
        assertTrue(TimeshiftRecorder.supports("http://h/live/u/p/1.ts"))
        assertTrue(TimeshiftRecorder.supports("http://h/u/p/1"))
        assertFalse(TimeshiftRecorder.supports("http://h/live/u/p/1.m3u8?token=x"))
        assertFalse(TimeshiftRecorder.supports("rtmp://h/app/stream"))
    }
}
