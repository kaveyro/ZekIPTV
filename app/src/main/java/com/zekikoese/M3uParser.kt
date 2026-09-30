package com.zekikoese

import java.io.InputStream

class M3uParser {

    // tvg-logo="...", group-title="...", tvg-name="..." usw. aus der #EXTINF-Zeile ziehen.
    private val attrRegex = Regex("""([a-zA-Z0-9-]+)="([^"]*)"""")

    fun parse(inputStream: InputStream): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingTvgId: String? = null
        // #EXTGRP / #EXTVLCOPT dürfen vor oder nach #EXTINF stehen — gelten bis zur URL-Zeile.
        var pendingExtGroup: String? = null
        var pendingUserAgent: String? = null
        var pendingReferrer: String? = null

        inputStream.bufferedReader().forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }

                    // Anzeigename: Text nach dem ersten Komma HINTER dem letzten Attribut.
                    // Nicht substringAfterLast: Kanalnamen dürfen selbst Kommas enthalten
                    // ("Sky Sport, HD" würde sonst zu "HD").
                    val lastQuote = line.lastIndexOf('"')
                    val nameComma = line.indexOf(',', startIndex = maxOf(lastQuote, 0))
                    val displayName = if (nameComma >= 0) line.substring(nameComma + 1).trim() else ""
                    pendingName = displayName.ifEmpty { null }
                        ?: attrs["tvg-name"]?.ifEmpty { null }
                        ?: "Unbenannt"
                    pendingLogo = attrs["tvg-logo"]?.ifEmpty { null }
                    pendingGroup = attrs["group-title"]?.ifEmpty { null }
                    pendingTvgId = attrs["tvg-id"]?.ifEmpty { null }
                    // User-Agent als Attribut: "http-user-agent" (VLC-Stil) oder "user-agent" (Xtream-Panels).
                    (attrs["http-user-agent"] ?: attrs["user-agent"])?.ifEmpty { null }?.let { pendingUserAgent = it }
                    attrs["http-referrer"]?.ifEmpty { null }?.let { pendingReferrer = it }
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingExtGroup = line.substringAfter(':').trim().ifEmpty { null }
                }

                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim().ifEmpty { null }
                    when (key) {
                        "http-user-agent" -> pendingUserAgent = value
                        "http-referrer", "http-referer" -> pendingReferrer = value
                    }
                }

                line.isEmpty() || line.startsWith("#") -> {
                    // Kommentar/Direktive ohne URL -> ignorieren.
                }

                else -> {
                    // URL-Zeile: schließt den zuvor gesehenen #EXTINF ab.
                    val name = pendingName
                    if (name != null) {
                        channels.add(
                            Channel(
                                name = name,
                                url = line,
                                logo = pendingLogo,
                                group = pendingGroup ?: pendingExtGroup,
                                tvgId = pendingTvgId,
                                userAgent = pendingUserAgent,
                                referrer = pendingReferrer
                            )
                        )
                    }
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                    pendingTvgId = null
                    pendingExtGroup = null
                    pendingUserAgent = null
                    pendingReferrer = null
                }
            }
        }

        return channels
    }
}
