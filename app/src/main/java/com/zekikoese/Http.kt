package com.zekikoese

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
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

    /**
     * True, wenn die URL auf ein Gerät im lokalen Netz zeigt (private/Link-local-Adressen,
     * *.local) — ab Android 17 braucht die App dafür die Berechtigung ACCESS_LOCAL_NETWORK.
     * Hostnamen werden per DNS aufgelöst (erlaubt) — blockierend, auf IO aufrufen.
     */
    fun isLocalNetworkUrl(url: String): Boolean {
        val host = runCatching { URL(url.trim()).host }.getOrNull()?.trim('[', ']')?.lowercase()
        if (host.isNullOrEmpty() || host == "localhost") return false
        if (host.endsWith(".local")) return true
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.any { address ->
            !address.isLoopbackAddress && (
                address.isSiteLocalAddress || address.isLinkLocalAddress ||
                    // IPv6 Unique Local (fc00::/7) meldet isSiteLocalAddress nicht
                    (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
                )
        }
    }

    /** Maskiert Zugangsdaten in URLs (username=/password=) — für Logausgaben. */
    fun redact(url: String): String = url.replace(CREDENTIAL_PARAM, "$1***")

    /** Lädt eine URL vollständig als Text (für JSON-API-Antworten). */
    fun readText(url: String): String = openStream(url).use { it.readBytes().decodeToString() }
}
