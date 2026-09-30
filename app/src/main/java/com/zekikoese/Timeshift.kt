package com.zekikoese

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.ceil

private const val TS_PACKET = 188
private const val TS_SYNC: Byte = 0x47

/**
 * Zerlegt einen MPEG-TS-Livestream in abspielbare Segmente (für die lokale Timeshift-HLS-Liste).
 *
 * Liest PAT/PMT mit, um die Video-PID (bzw. ersatzweise die erste Elementarstream-PID) als Takt
 * zu finden, und schneidet an PES-Anfängen dieser PID, sobald ein Segment [targetDurationMs]
 * erreicht hat — bevorzugt an Keyframes (random_access_indicator), spätestens beim Doppelten.
 * Jedes Segment beginnt mit den zuletzt gesehenen PAT/PMT-Paketen, damit es eigenständig
 * dekodierbar ist. Reine Logik ohne I/O — dadurch unit-testbar.
 */
class TsSegmenter(private val targetDurationMs: Long = 2_000) {

    /** Ein fertiges Segment: Nutzdaten, Dauer und ob davor die Zeitbasis gesprungen ist. */
    class Segment(val data: ByteArray, val durationMs: Long, val discontinuity: Boolean)

    private var pmtPid = -1
    private var clockPid = -1
    private var patPacket: ByteArray? = null
    private var pmtPacket: ByteArray? = null

    private var current = java.io.ByteArrayOutputStream(1 shl 20)
    private var segmentStartPts = -1L
    private var lastPts = -1L
    private var currentDiscontinuity = false

    /** Verarbeitet genau ein 188-Byte-Paket; liefert ein Segment, wenn eines abgeschlossen wurde. */
    fun onPacket(packet: ByteArray): Segment? {
        val pid = ((packet[1].toInt() and 0x1F) shl 8) or (packet[2].toInt() and 0xFF)
        val payloadStart = (packet[1].toInt() and 0x40) != 0
        val adaptation = (packet[3].toInt() shr 4) and 0x03
        var offset = 4
        var randomAccess = false
        if (adaptation == 2 || adaptation == 3) {
            val length = packet[4].toInt() and 0xFF
            if (length > 0) randomAccess = (packet[5].toInt() and 0x40) != 0
            offset += 1 + length
        }
        val hasPayload = (adaptation == 1 || adaptation == 3) && offset < TS_PACKET

        if (pid == 0 && payloadStart && hasPayload) {
            parsePat(packet, offset)
            patPacket = packet.copyOf()
        } else if (pid == pmtPid && payloadStart && hasPayload) {
            parsePmt(packet, offset)
            pmtPacket = packet.copyOf()
        }

        var finished: Segment? = null
        if (pid == clockPid && payloadStart && hasPayload) {
            val pts = readPts(packet, offset)
            if (pts >= 0) {
                val jumped = lastPts >= 0 && (pts < lastPts || pts - lastPts > 10 * 90_000L)
                if (segmentStartPts < 0) {
                    segmentStartPts = pts
                } else if (jumped) {
                    // Zeitbasis gesprungen (Reconnect/Senderwechsel beim Anbieter): Segment schließen,
                    // das nächste wird als Diskontinuität markiert.
                    finished = closeSegment(durationMs = (lastPts - segmentStartPts) / 90 + 40)
                    segmentStartPts = pts
                    currentDiscontinuity = true
                } else {
                    val durationMs = (pts - segmentStartPts) / 90
                    if (durationMs >= targetDurationMs && (randomAccess || durationMs >= 2 * targetDurationMs)) {
                        finished = closeSegment(durationMs)
                        segmentStartPts = pts
                    }
                }
                lastPts = pts
            }
        }
        if (current.size() == 0) {
            // Neues Segment: zuerst PAT/PMT, damit es für sich dekodierbar ist.
            patPacket?.takeIf { pid != 0 }?.let { current.write(it) }
            pmtPacket?.takeIf { pid != pmtPid }?.let { current.write(it) }
        }
        current.write(packet)
        return finished
    }

    private fun closeSegment(durationMs: Long): Segment? {
        val data = current.toByteArray()
        current = java.io.ByteArrayOutputStream(1 shl 20)
        val discontinuity = currentDiscontinuity
        currentDiscontinuity = false
        return if (durationMs > 0 && data.isNotEmpty()) Segment(data, durationMs, discontinuity) else null
    }

    private fun parsePat(packet: ByteArray, payloadOffset: Int) {
        val start = payloadOffset + 1 + (packet[payloadOffset].toInt() and 0xFF) // pointer_field
        if (start + 8 > TS_PACKET || (packet[start].toInt() and 0xFF) != 0x00) return
        val sectionLength = ((packet[start + 1].toInt() and 0x0F) shl 8) or (packet[start + 2].toInt() and 0xFF)
        val end = minOf(start + 3 + sectionLength - 4, TS_PACKET) // ohne CRC
        var i = start + 8
        while (i + 4 <= end) {
            val program = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            val pid = ((packet[i + 2].toInt() and 0x1F) shl 8) or (packet[i + 3].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = pid
                return
            }
            i += 4
        }
    }

    private fun parsePmt(packet: ByteArray, payloadOffset: Int) {
        val start = payloadOffset + 1 + (packet[payloadOffset].toInt() and 0xFF)
        if (start + 12 > TS_PACKET || (packet[start].toInt() and 0xFF) != 0x02) return
        val sectionLength = ((packet[start + 1].toInt() and 0x0F) shl 8) or (packet[start + 2].toInt() and 0xFF)
        val end = minOf(start + 3 + sectionLength - 4, TS_PACKET)
        val programInfoLength = ((packet[start + 10].toInt() and 0x0F) shl 8) or (packet[start + 11].toInt() and 0xFF)
        var i = start + 12 + programInfoLength
        var firstPid = -1
        var videoPid = -1
        while (i + 5 <= end) {
            val streamType = packet[i].toInt() and 0xFF
            val pid = ((packet[i + 1].toInt() and 0x1F) shl 8) or (packet[i + 2].toInt() and 0xFF)
            val esInfoLength = ((packet[i + 3].toInt() and 0x0F) shl 8) or (packet[i + 4].toInt() and 0xFF)
            if (firstPid < 0) firstPid = pid
            if (videoPid < 0 && streamType in VIDEO_STREAM_TYPES) videoPid = pid
            i += 5 + esInfoLength
        }
        val clock = if (videoPid >= 0) videoPid else firstPid
        if (clock >= 0) clockPid = clock
    }

    /** PTS (90 kHz) am Anfang eines PES-Pakets, sonst -1. */
    private fun readPts(packet: ByteArray, offset: Int): Long {
        if (offset + 14 > TS_PACKET) return -1
        if (packet[offset].toInt() != 0 || packet[offset + 1].toInt() != 0 || packet[offset + 2].toInt() != 1) return -1
        val ptsFlags = (packet[offset + 7].toInt() shr 6) and 0x03
        if (ptsFlags and 0x02 == 0) return -1
        val p = offset + 9
        return (((packet[p].toLong() shr 1) and 0x07) shl 30) or
            ((packet[p + 1].toLong() and 0xFF) shl 22) or
            (((packet[p + 2].toLong() and 0xFF) shr 1) shl 15) or
            ((packet[p + 3].toLong() and 0xFF) shl 7) or
            ((packet[p + 4].toLong() and 0xFF) shr 1)
    }

    private companion object {
        // MPEG-1/2, MPEG-4 Part 2, H.264, HEVC, AVS, VC-1
        val VIDEO_STREAM_TYPES = setOf(0x01, 0x02, 0x10, 0x1B, 0x24, 0x42, 0xEA)
    }
}

/**
 * Timeshift für Live-Sender: nimmt den TS-Livestream im Hintergrund auf, zerlegt ihn in
 * Segmente und pflegt daraus eine lokale HLS-Liste (Schiebefenster). Der Player spielt diese
 * Liste ab — damit lässt sich Live-TV pausieren und bis zu [maxDurationMs] zurückspulen.
 * Es wird nur EINE Verbindung zum Anbieter genutzt (wichtig bei Konten mit Verbindungslimit).
 */
class TimeshiftRecorder(
    private val url: String,
    private val userAgent: String,
    private val referrer: String?,
    private val dir: File,
    private val maxBytes: Long,
    private val maxDurationMs: Long = 30 * 60_000L
) {
    private class StoredSegment(val file: File, val durationMs: Long, val discontinuity: Boolean, val size: Long)

    private val segments = ArrayDeque<StoredSegment>()
    private var nextIndex = 0L
    private var mediaSequence = 0L
    private var discontinuitySequence = 0L
    private var totalBytes = 0L
    private var totalDurationMs = 0L

    @Volatile private var running = false
    @Volatile var failure: Exception? = null
        private set
    private var thread: Thread? = null
    private val lock = Any()

    val playlistFile: File get() = File(dir, "timeshift.m3u8")

    fun start() {
        dir.deleteRecursively()
        dir.mkdirs()
        running = true
        thread = Thread(::recordLoop, "ZekIPTV-Timeshift").apply { isDaemon = true; start() }
    }

    /** True, sobald die lokale Liste mindestens ein Segment enthält. */
    val isReady: Boolean get() = synchronized(lock) { segments.isNotEmpty() }

    /** Wartet (abbrechbar) bis die Liste abspielbar ist; false bei Fehler/Timeout. */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isReady) return true
            if (failure != null || !running) return false
            kotlinx.coroutines.delay(100)
        }
        return isReady
    }

    fun stop() {
        running = false
        thread?.interrupt()
        // Aufräumen im Hintergrund — der Aufnahme-Thread kann noch kurz im read() hängen.
        Thread { thread?.join(3_000); dir.deleteRecursively() }.apply { isDaemon = true; start() }
    }

    private fun recordLoop() {
        var attempt = 0
        var needDiscontinuity = false
        while (running) {
            try {
                openStream().use { input ->
                    attempt = 0
                    val segmenter = TsSegmenter()
                    var first = true
                    readPackets(input) { packet ->
                        val segment = segmenter.onPacket(packet) ?: return@readPackets
                        val disc = segment.discontinuity || (first && needDiscontinuity)
                        first = false
                        addSegment(segment.data, segment.durationMs, disc)
                    }
                }
                // Stream vom Server beendet — neu verbinden.
                needDiscontinuity = true
            } catch (e: Exception) {
                if (!running) break
                Log.w("Timeshift", "Aufnahme unterbrochen (${Http.redact(url)})", e)
                needDiscontinuity = true
                attempt++
                if (attempt > 5) {
                    failure = e
                    break
                }
                try {
                    Thread.sleep(1_000L * attempt)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun openStream(): InputStream {
        var current = url
        repeat(5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                referrer?.let { setRequestProperty("Referer", it) }
            }
            when (val code = connection.responseCode) {
                in 200..299 -> return connection.inputStream
                in 300..399 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $code")
                    connection.disconnect()
                    current = URL(URL(current), location).toString()
                }
                else -> {
                    connection.disconnect()
                    throw IOException("HTTP $code")
                }
            }
        }
        throw IOException("Zu viele Weiterleitungen")
    }

    /** Liest 188-Byte-Pakete und synchronisiert sich bei Bedarf neu auf das Sync-Byte. */
    private inline fun readPackets(input: InputStream, onPacket: (ByteArray) -> Unit) {
        val buffer = ByteArray(TS_PACKET * 512)
        var filled = 0
        while (running) {
            val read = input.read(buffer, filled, buffer.size - filled)
            if (read < 0) return
            filled += read
            var pos = 0
            while (filled - pos >= TS_PACKET) {
                if (buffer[pos] != TS_SYNC) {
                    pos++ // Resync
                    continue
                }
                onPacket(buffer.copyOfRange(pos, pos + TS_PACKET))
                pos += TS_PACKET
            }
            System.arraycopy(buffer, pos, buffer, 0, filled - pos)
            filled -= pos
        }
    }

    private fun addSegment(data: ByteArray, durationMs: Long, discontinuity: Boolean) {
        val file = File(dir, "seg_${nextIndex++}.ts")
        FileOutputStream(file).use { it.write(data) }
        synchronized(lock) {
            segments.addLast(StoredSegment(file, durationMs, discontinuity, data.size.toLong()))
            totalBytes += data.size
            totalDurationMs += durationMs
            // Schiebefenster: älteste Segmente löschen, wenn Dauer- oder Speichergrenze erreicht.
            while (segments.size > 3 && (totalBytes > maxBytes || totalDurationMs > maxDurationMs)) {
                val removed = segments.removeFirst()
                removed.file.delete()
                totalBytes -= removed.size
                totalDurationMs -= removed.durationMs
                mediaSequence++
                if (segments.firstOrNull()?.discontinuity == true) {
                    // Die Diskontinuität "vor" dem neuen ersten Segment zählt jetzt zur Sequenz.
                    discontinuitySequence++
                }
            }
            writePlaylist()
        }
    }

    private fun writePlaylist() {
        val target = ceil((segments.maxOfOrNull { it.durationMs } ?: 2_000L) / 1000.0).toInt().coerceAtLeast(1)
        val text = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:$target\n")
            append("#EXT-X-MEDIA-SEQUENCE:$mediaSequence\n")
            append("#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuitySequence\n")
            segments.forEachIndexed { index, segment ->
                if (segment.discontinuity && index > 0) append("#EXT-X-DISCONTINUITY\n")
                append(String.format(Locale.US, "#EXTINF:%.3f,\n", segment.durationMs / 1000.0))
                append(segment.file.name).append('\n')
            }
        }
        // Atomar ersetzen, damit der Player nie eine halb geschriebene Liste liest.
        val tmp = File(dir, "timeshift.m3u8.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(playlistFile)) {
            playlistFile.delete()
            tmp.renameTo(playlistFile)
        }
    }

    companion object {
        /** HLS-Quellen haben ein eigenes Zeitfenster beim Anbieter — dort kein eigener Rekorder. */
        fun supports(url: String): Boolean {
            val path = url.substringBefore('?').lowercase()
            return (url.startsWith("http://") || url.startsWith("https://")) && !path.endsWith(".m3u8") && !path.endsWith(".m3u")
        }

        /** Basisverzeichnis; jede Sitzung bekommt ein eigenes Unterverzeichnis. */
        fun baseDir(cacheDir: File) = File(cacheDir, "timeshift")

        /** Reste früherer Sitzungen löschen (z. B. nach einem Absturz). */
        fun cleanUp(cacheDir: File) {
            baseDir(cacheDir).deleteRecursively()
        }

        /** Speichergrenze: höchstens 1 GB bzw. ein Viertel des freien Cache-Speichers. */
        fun maxBytesFor(cacheDir: File): Long = minOf(1L shl 30, cacheDir.usableSpace / 4).coerceAtLeast(64L shl 20)
    }
}
