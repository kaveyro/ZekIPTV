package com.zekikoese

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Eine EPG-Sendung aus einer XMLTV-Quelle. */
data class EpgProgramme(
    val channelId: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String
)

/** Ergebnis eines XMLTV-Durchlaufs. */
data class XmltvResult(
    /** EPG-Kanal-ID (kleingeschrieben) -> Sendungen, sortiert nach Startzeit. */
    val programmes: Map<String, List<EpgProgramme>>,
    /** Normalisierter Anzeigename -> EPG-Kanal-ID (für Namens-Matching, wenn tvg-id nicht passt). */
    val nameToId: Map<String, String>
)

/**
 * Normalisiert Sendernamen für das EPG-Matching: Länder-/Qualitätspräfixe ("DE:", "SD:"),
 * Qualitäts-Tags (HD/FHD/4K …) und Sonderzeichen entfernen, Umlaute transliterieren.
 * "DE: Das Erste HD" und "Das Erste" werden so beide zu "daserste".
 */
fun normalizeChannelName(raw: String): String {
    var s = raw.lowercase().trim()
    // Führendes Präfix wie "de:", "tr:", "sd:", "vip:" entfernen.
    s = s.replace(PREFIX_REGEX, "")
    // Qualitäts-Tags als eigenständige Wörter entfernen.
    s = s.replace(QUALITY_TAG_REGEX, " ")
    // Umlaute/Sonderbuchstaben transliterieren (ä->a, ç->c, ...); ı und ß manuell.
    s = s.replace("ı", "i").replace("ß", "ss")
    s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING_MARKS_REGEX, "")
    return s.replace(NON_ALNUM_REGEX, "")
}

// Einmal kompiliert: normalizeChannelName läuft für jeden Sender und jeden XMLTV-display-name.
private val PREFIX_REGEX = Regex("^[a-z]{2,4}\\s*:\\s*")
private val QUALITY_TAG_REGEX = Regex("\\b(sd|hd|fhd|uhd|4k|8k|hevc|h265|raw)\\b")
private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")
private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

/**
 * Streaming-Parser für XMLTV-EPG-Dateien.
 *
 * Matching zweistufig: über die Kanal-ID (tvg-id der Playlist) UND über normalisierte
 * Anzeigenamen — viele Anbieter (z. B. mit Hash-IDs) liefern keine standardkonformen
 * tvg-ids. Es werden nur Sendungen relevanter Kanäle innerhalb eines Zeitfensters
 * behalten, damit auch sehr große EPG-Dateien (100+ MB XML) speicherverträglich bleiben.
 */
class XmltvParser(
    private val nowMs: Long = System.currentTimeMillis(),
    private val pastWindowMs: Long = 24L * 60 * 60 * 1000,    // 24 h zurück (Catch-up)
    private val futureWindowMs: Long = 36L * 60 * 60 * 1000   // 36 h voraus
) {

    /**
     * @param wantedChannelIds tvg-ids der Playlist, kleingeschrieben.
     * @param wantedNames normalisierte Sendernamen der Playlist (siehe [normalizeChannelName]).
     */
    fun parse(
        input: InputStream,
        wantedChannelIds: Set<String>,
        wantedNames: Set<String> = emptySet()
    ): XmltvResult {
        val programmes = HashMap<String, MutableList<EpgProgramme>>()
        val nameToId = HashMap<String, String>()
        // Über Anzeigenamen aufgelöste EPG-IDs (zusätzlich zu direkten tvg-id-Treffern).
        val resolvedIds = HashSet<String>()
        val parser = newPullParser().apply { setInput(input, null) }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "tv" -> { /* Root-Tag: einfach weiter zum Inhalt */ }

                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id")?.lowercase()
                        if (id != null) {
                            val displayNames = readDisplayNames(parser)
                            val wantedByName = displayNames.any { normalizeChannelName(it) in wantedNames }
                            if (wantedByName || id in wantedChannelIds) {
                                resolvedIds.add(id)
                                displayNames.forEach { dn ->
                                    val normalized = normalizeChannelName(dn)
                                    if (normalized in wantedNames) nameToId[normalized] = id
                                }
                            }
                        } else {
                            skipElement(parser)
                        }
                    }

                    "programme" -> {
                        val channelId = parser.getAttributeValue(null, "channel")?.lowercase()
                        // Kanal zuerst prüfen: Zeiten nur für relevante Sender parsen
                        // (der Großteil einer großen XMLTV-Datei betrifft fremde Sender).
                        val wantedChannel = channelId != null &&
                            (channelId in wantedChannelIds || channelId in resolvedIds)
                        val start = if (wantedChannel) parseTime(parser.getAttributeValue(null, "start")) else null
                        var stop = if (start != null) parseTime(parser.getAttributeValue(null, "stop")) else null

                        // Falls Stop-Zeit fehlt: temporär 4h annehmen, wird später korrigiert.
                        if (start != null && stop == null) {
                            stop = start + 4 * 60 * 60 * 1000
                        }

                        val relevant = wantedChannel &&
                            start != null && stop != null &&
                            stop > nowMs - pastWindowMs && start < nowMs + futureWindowMs
                        if (relevant) {
                            val title = readTitle(parser)
                            if (title != null) {
                                programmes.getOrPut(channelId) { mutableListOf() }
                                    .add(EpgProgramme(channelId, start, stop, title))
                            }
                        } else {
                            skipElement(parser)
                        }
                    }
                    else -> skipElement(parser)
                }
            }
            event = parser.next()
        }

        programmes.values.forEach { list ->
            list.sortBy(EpgProgramme::startMs)
            inferStopTimes(list)
        }
        return XmltvResult(programmes, nameToId)
    }

    /** Hilfsmethode zum Überspringen nicht benötigter Tags samt Inhalt. */
    private fun skipElement(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth > 0) {
            val next = try { parser.next() } catch (e: Exception) { XmlPullParser.END_DOCUMENT }
            when (next) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Liest alle <display-name>-Einträge innerhalb des aktuellen <channel>-Elements. */
    private fun readDisplayNames(parser: XmlPullParser): List<String> {
        val names = mutableListOf<String>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "channel")) {
            if (event == XmlPullParser.START_TAG) {
                if (parser.name == "display-name") {
                    parser.nextText().trim().ifEmpty { null }?.let(names::add)
                } else {
                    skipElement(parser)
                }
            }
            event = parser.next()
        }
        return names
    }

    /** Liest den ersten <title> innerhalb des aktuellen <programme>-Elements. */
    private fun readTitle(parser: XmlPullParser): String? {
        var title: String? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "programme")) {
            if (event == XmlPullParser.START_TAG) {
                if (parser.name == "title" && title == null) {
                    title = parser.nextText().trim().ifEmpty { null }
                } else {
                    skipElement(parser)
                }
            }
            event = parser.next()
        }
        return title
    }

    // Pro Parser-Instanz wiederverwendet (statt pro Sendung neu erzeugt); eine Instanz
    // wird nur von einem Thread benutzt, daher ist SimpleDateFormat hier unkritisch.
    private val zonedFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val utcFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** XMLTV-Zeitformat: "yyyyMMddHHmmss [+-]HHMM" (Zeitzone optional, dann UTC angenommen). */
    private fun parseTime(raw: String?): Long? {
        if (raw == null || raw.length < 14) return null
        return runCatching {
            if (raw.length > 14) {
                // Zeitzonen-Teil normalisieren: Leerzeichen erzwingen und Doppelpunkt im Offset (+HH:MM) entfernen.
                val datePart = raw.substring(0, 14)
                val tzPart = raw.substring(14).trim().replace(":", "")
                val normalized = "$datePart $tzPart"
                zonedFormat.parse(normalized)!!.time
            } else {
                utcFormat.parse(raw)!!.time
            }
        }.getOrNull()
    }

    private fun newPullParser(): XmlPullParser = try {
        XmlPullParserFactory.newInstance().newPullParser()
    } catch (e: Exception) {
        // Fallback für JVM-Unit-Tests, falls die Factory-Discovery fehlschlägt.
        Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser
    }

    companion object {
        /**
         * Schließt Lücken: Wenn eine Sendung keine Endzeit hat (oder diese unrealistisch weit
         * in der Zukunft liegt), wird sie auf den Beginn der nächsten Sendung gesetzt.
         */
        fun inferStopTimes(list: MutableList<EpgProgramme>) {
            if (list.size < 2) return
            for (i in 0 until list.size - 1) {
                val current = list[i]
                val next = list[i + 1]
                // Falls die aktuelle Sendung nach der nächsten endet (oder genau dann),
                // kürzen wir sie auf den Start der nächsten.
                if (current.stopMs > next.startMs) {
                    list[i] = current.copy(stopMs = next.startMs)
                }
            }
        }
    }
}
