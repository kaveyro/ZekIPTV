package com.zekikoese

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Aktualisiert Playlist und EPG periodisch im Hintergrund (alle 12 h), damit die App
 * beim Öffnen bereits frische Daten hat — wichtig auf dem Fire TV Stick, wo die App
 * selten lange offen bleibt.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = PlaylistRepository(applicationContext)
        val url = repository.urlFlow.first()
        if (url.isBlank()) return@withContext Result.success()

        // Playlist aktualisieren (Fehler tolerieren — alter Stand bleibt dann erhalten).
        val channels = runCatching {
            Http.openStream(url).use { M3uParser().parse(it) }
        }.getOrNull()
        if (channels != null && channels.isNotEmpty()) {
            repository.saveChannels(channels)
        }

        // EPG aktualisieren.
        val epgChannels = channels ?: repository.channelsFlow.first()
        val sources = repository.epgSourcesFlow.first()
        if (epgChannels.isNotEmpty() && sources.isNotEmpty()) {
            runCatching {
                val result = EpgFetcher.fetch(epgChannels, sources)
                if (result.programmes.isNotEmpty()) {
                    repository.saveEpgCache(result.programmes, result.nameToId)
                }
            }
        }
        Result.success()
    }

    companion object {
        /** Plant den periodischen Refresh (einmalig; bestehende Planung bleibt erhalten). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "zekiptv_auto_refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
