package com.zekikoese

/**
 * Lädt und parst alle konfigurierten EPG-Quellen für die gegebenen Kanäle.
 * Gemeinsame Logik für das ViewModel (manueller Refresh) und den
 * Hintergrund-Worker (periodischer Refresh). Blockierend — auf IO-Dispatcher aufrufen.
 */
object EpgFetcher {

    data class Result(
        val programmes: Map<String, List<EpgProgramme>>,
        val nameToId: Map<String, String>,
        val failedSources: Int
    )

    fun fetch(channels: List<Channel>, sources: Set<String>): Result {
        // Matching über tvg-id UND normalisierte Sendernamen (viele Anbieter nutzen Hash-IDs).
        val wantedIds = channels.mapNotNull { it.tvgId?.lowercase()?.ifEmpty { null } }.toSet()
        val wantedNames = channels.map { normalizeChannelName(it.name) }.filterTo(HashSet()) { it.isNotEmpty() }

        val programmes = HashMap<String, MutableList<EpgProgramme>>()
        val nameToId = HashMap<String, String>()
        var failures = 0
        for (source in sources) {
            runCatching {
                Http.maybeGunzip(Http.openStream(source)).use { input ->
                    val parsed = XmltvParser().parse(input, wantedIds, wantedNames)
                    parsed.programmes.forEach { (id, list) ->
                        programmes.getOrPut(id) { mutableListOf() }.addAll(list)
                    }
                    parsed.nameToId.forEach { (name, id) -> nameToId.putIfAbsent(name, id) }
                }
            }.onFailure { failures++ }
        }
        return Result(programmes, nameToId, failures)
    }
}
