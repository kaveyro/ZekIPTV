package com.zekikoese

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Gemeinsamer HTTP-Helfer für Playlist-, EPG- und Xtream-API-Abrufe. */
object Http {

    private const val MAX_REDIRECTS = 5

    private val CREDENTIAL_PARAM = Regex("(?i)((?:username|password)=)[^&]*")

    /**
     * Öffnet eine URL mit explizitem User-Agent (manche Anbieter blocken den Java-Default)
     * und folgt Redirects auch über Protokollwechsel hinweg (HttpURLConnection folgt
     * http→https nicht automatisch).
     */
    fun openStream(url: String): InputStream {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "IPTV/1.2 (Android TV)")
            }
            when (val code = connection.responseCode) {
                in 200..299 -> return connection.inputStream
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Weiterleitung ohne Ziel (HTTP $code)")
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

    /** Entpackt GZIP transparent (erkannt an den Magic-Bytes, unabhängig von der Dateiendung). */
    fun maybeGunzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered) else buffered
    }

    /** Maskiert Zugangsdaten in URLs (username=/password=) — für Logausgaben. */
    fun redact(url: String): String = url.replace(CREDENTIAL_PARAM, "$1***")

    /** Lädt eine URL vollständig als Text (für JSON-API-Antworten). */
    fun readText(url: String): String = openStream(url).use { it.readBytes().decodeToString() }
}
