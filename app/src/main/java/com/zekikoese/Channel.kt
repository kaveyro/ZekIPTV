package com.zekikoese

data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    // Stream-spezifische HTTP-Header aus der Playlist (#EXTVLCOPT) — manche Anbieter verlangen sie.
    val userAgent: String? = null,
    val referrer: String? = null
)
