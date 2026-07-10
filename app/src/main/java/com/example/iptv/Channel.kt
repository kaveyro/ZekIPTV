package com.example.iptv

data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null
)
