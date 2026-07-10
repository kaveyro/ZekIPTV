package com.example.iptv

import java.io.InputStream

class M3uParser {

    // tvg-logo="...", group-title="...", tvg-name="..." usw. aus der #EXTINF-Zeile ziehen.
    private val attrRegex = Regex("""([a-zA-Z0-9-]+)="([^"]*)"""")

    fun parse(inputStream: InputStream): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null

        inputStream.bufferedReader().forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }

                    // Anzeigename: Text nach dem letzten Komma, sonst tvg-name.
                    val displayName = line.substringAfterLast(",", "").trim()
                    pendingName = displayName.ifEmpty { null }
                        ?: attrs["tvg-name"]?.ifEmpty { null }
                        ?: "Unbenannt"
                    pendingLogo = attrs["tvg-logo"]?.ifEmpty { null }
                    pendingGroup = attrs["group-title"]?.ifEmpty { null }
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
                                group = pendingGroup
                            )
                        )
                    }
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                }
            }
        }

        return channels
    }
}
