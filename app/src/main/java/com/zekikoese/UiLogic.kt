package com.zekikoese

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import java.util.Locale

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
