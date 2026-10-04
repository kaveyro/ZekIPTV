package com.zekikoese

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Ladezustand eines Anbieter-Katalogs (Filme, Serien, Episoden). */
sealed interface ContentState {
    data object Idle : ContentState
    data object Loading : ContentState
    data object Ready : ContentState
    data class Error(val message: String) : ContentState
}

/** Kurze Rückmeldung am unteren Bildschirmrand (Snackbar), optional mit Aktion. */
data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    val id: Long = System.nanoTime()
)

/**
 * Nächster Sender beim Zappen innerhalb der Liste, aus der die Wiedergabe gestartet wurde.
 * Ist der laufende Sender nicht (mehr) in der Liste, zählt [fallbackIndex].
 */
internal fun zapTarget(list: List<Channel>, currentUrl: String?, fallbackIndex: Int, delta: Int): Channel? {
    if (list.isEmpty()) return null
    val index = list.indexOfFirst { it.url == currentUrl }.takeIf { it >= 0 }
        ?: fallbackIndex.coerceIn(0, list.lastIndex)
    return list[(index + delta).mod(list.size)]
}

/**
 * Feste Sendernummern (1-basiert) nach der Reihenfolge der vollständigen Liste — unabhängig vom
 * gerade gewählten Kategorie-Filter. Steht ein Stream mehrfach in der Playlist, zählt der erste.
 */
internal fun channelNumberIndex(channels: List<Channel>): Map<String, Int> {
    val numbers = HashMap<String, Int>(channels.size * 2)
    channels.forEachIndexed { index, channel -> numbers.putIfAbsent(channel.url, index + 1) }
    return numbers
}

/** Prüft eine Playlist-/EPG-Adresse; null = in Ordnung, sonst der Fehlertext fürs Eingabefeld. */
internal fun validateStreamUrl(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return "Bitte eine Adresse eingeben."
    val scheme = trimmed.substringBefore("://", "").lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return "Die Adresse muss mit http:// oder https:// beginnen."
    if (trimmed.substringAfter("://").substringBefore('/').isBlank()) return "Die Adresse enthält keinen Server."
    return null
}

/** Verständliche Fehlermeldung für einen Wiedergabefehler (statt des Media3-Fehlercodes). */
internal fun playbackErrorMessage(errorCode: Int, httpStatus: Int?, codeName: String): String = when {
    httpStatus == 401 || httpStatus == 403 ->
        "Zugriff verweigert (HTTP $httpStatus) – Abo abgelaufen oder Verbindungslimit erreicht."
    httpStatus == 404 || httpStatus == 410 ->
        "Dieser Stream ist derzeit nicht verfügbar (HTTP $httpStatus)."
    httpStatus != null && httpStatus >= 500 ->
        "Der Server des Anbieters meldet einen Fehler (HTTP $httpStatus). Später erneut versuchen."
    httpStatus != null -> "Der Server hat die Wiedergabe abgelehnt (HTTP $httpStatus)."
    errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "Keine Verbindung zum Server – Internetverbindung prüfen."
    errorCode == PlaybackException.ERROR_CODE_TIMEOUT ->
        "Der Server antwortet nicht rechtzeitig."
    errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
        "Das Stream-Format wird nicht unterstützt oder der Stream ist beschädigt."
    errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
        "Dieses Gerät kann das Video- oder Tonformat nicht abspielen."
    else -> "Wiedergabe fehlgeschlagen ($codeName)."
}

/**
 * Anzeigename einer Tonspur: Sprache ausgeschrieben, dazu Format und Kanäle — damit zwei
 * deutsche Spuren (z. B. Stereo und Dolby 5.1) unterscheidbar sind.
 */
internal fun audioTrackLabel(
    label: String?,
    language: String?,
    mimeType: String?,
    channelCount: Int,
    index: Int
): String {
    val languageName = language
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.GERMAN).takeIf(String::isNotBlank) ?: it.uppercase() }
        ?.replaceFirstChar { it.titlecase(Locale.GERMAN) }
    val codec = when (mimeType) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
        MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_OPUS -> "Opus"
        else -> null
    }
    val channels = when (channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }
    val head = label?.takeIf { it.isNotBlank() } ?: languageName ?: "Spur ${index + 1}"
    val details = listOfNotNull(
        languageName?.takeIf { label != null && !label.contains(it, ignoreCase = true) },
        codec,
        channels
    )
    return (listOf(head) + details).joinToString(" · ")
}

// ---------- TV-Guide (EPG-Raster) ----------

internal const val HALF_HOUR_MS = 30 * 60_000L

/** Auf die volle bzw. halbe Stunde abrunden (Raster des TV-Guides). */
internal fun floorToHalfHour(ms: Long): Long = ms - Math.floorMod(ms, HALF_HOUR_MS)

/** Auf die nächste volle bzw. halbe Stunde aufrunden. */
internal fun ceilToHalfHour(ms: Long): Long = floorToHalfHour(ms + HALF_HOUR_MS - 1)

/** Sendungen, die sich mit dem Zeitraum [from, to) überschneiden. */
internal fun programmesInRange(programmes: List<EpgProgramme>, from: Long, to: Long): List<EpgProgramme> =
    programmes.filter { it.stopMs > from && it.startMs < to }

/**
 * Linker Rand des sichtbaren Zeitfensters (Breite [spanMs]), nachdem die Sendung
 * [cellStart, cellStop) fokussiert wurde: unverändert, solange sie ganz sichtbar ist oder das
 * Fenster überdeckt; sonst so verschoben, dass ihr Anfang (und möglichst ihr Ende) sichtbar wird.
 * Begrenzt auf den geladenen Bereich [rangeStart, rangeEnd].
 */
internal fun guideWindowFor(
    windowStart: Long,
    spanMs: Long,
    cellStart: Long,
    cellStop: Long,
    rangeStart: Long,
    rangeEnd: Long
): Long {
    val windowEnd = windowStart + spanMs
    val target = when {
        cellStart >= windowStart && cellStop <= windowEnd -> windowStart
        cellStart <= windowStart && cellStop >= windowEnd -> windowStart
        cellStart < windowStart -> floorToHalfHour(cellStart)
        else -> minOf(ceilToHalfHour(cellStop - spanMs), floorToHalfHour(cellStart))
    }
    return target.coerceIn(rangeStart, (rangeEnd - spanMs).coerceAtLeast(rangeStart))
}

// ---------- Player ----------

/** Wirkung der Zurück-Taste im Player — Schritt für Schritt statt sofort zu beenden. */
internal enum class PlayerBackAction { CLEAR_NUMBER, HIDE_CONTROLS, EXIT }

/**
 * Zurück im Player: erst eine angefangene Sender-Direktwahl verwerfen, dann (TV) die sichtbare
 * Steuerleiste ausblenden, erst danach den Player verlassen — so geht z. B. der Timeshift-Puffer
 * nicht durch einen einzigen Tastendruck verloren. Auf dem Handy beendet Zurück direkt (dort
 * ist die Steuerleiste fast immer sichtbar und hat einen eigenen Zurück-Knopf).
 */
internal fun playerBackAction(numberEntry: Boolean, controlsVisible: Boolean, isTv: Boolean): PlayerBackAction =
    when {
        numberEntry -> PlayerBackAction.CLEAR_NUMBER
        isTv && controlsVisible -> PlayerBackAction.HIDE_CONTROLS
        else -> PlayerBackAction.EXIT
    }

/** Uhrzeit (HH:mm) in der Zeitzone des Geräts. */
internal fun clockLabel(ms: Long): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(ms))

/** Restzeit kurz und lesbar: „noch 38 min“ bzw. „noch 1:12 h“. */
internal fun remainingLabel(remainingMs: Long): String {
    val minutes = ((remainingMs + 59_999) / 60_000).coerceAtLeast(1)
    return if (minutes < 60) "noch $minutes min" else "noch %d:%02d h".format(minutes / 60, minutes % 60)
}

/** Zeitfenster der laufenden Sendung mit Restzeit, z. B. „20:15–21:45 · noch 38 min“. */
internal fun programmeTimesLabel(startMs: Long, stopMs: Long, now: Long): String =
    "${clockLabel(startMs)}–${clockLabel(stopMs)} · ${remainingLabel(stopMs - now)}"

// ---------- Listen ----------

/**
 * Eindeutige, stabile Keys für Senderlisten. Die URL allein reicht nicht — derselbe Stream
 * steht oft mehrfach in einer Playlist. Die laufende Nummer zählt daher nur Wiederholungen
 * derselben URL; Einfügungen anderer Sender verschieben den Key nicht.
 */
internal fun stableChannelKeys(channels: List<Channel>): List<String> {
    val seen = HashMap<String, Int>(channels.size * 2)
    return channels.map { channel ->
        val n = seen.merge(channel.url, 1, Int::plus) ?: 1
        if (n == 1) channel.url else "${channel.url}#$n"
    }
}

// ---------- Einstellungen ----------

/** Stand des Programmführers, z. B. „heute 06:12“, „gestern 22:40“ oder „01.10. 06:12“. */
internal fun epgUpdatedLabel(savedMs: Long, now: Long): String {
    val zone = TimeZone.getDefault()
    val dayDiff = Math.floorDiv(now + zone.getOffset(now), DAY_MS) - Math.floorDiv(savedMs + zone.getOffset(savedMs), DAY_MS)
    val time = clockLabel(savedMs)
    return when (dayDiff) {
        0L -> "heute $time"
        1L -> "gestern $time"
        else -> SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(savedMs)) + " $time"
    }
}

/** Vorgeschlagener Dateiname beim Export, mit Datum — mehrere Sicherungen bleiben unterscheidbar. */
internal fun backupFileName(now: Long): String =
    "zekiptv-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now)) + ".json"

private const val DAY_MS = 24L * 60 * 60 * 1000
