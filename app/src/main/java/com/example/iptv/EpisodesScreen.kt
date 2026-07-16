package com.example.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.iptv.ui.tvFocusFrame

/** Episodenliste einer Serie (nach Staffel/Episode sortiert), komplett D-Pad-bedienbar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodesScreen(mainViewModel: MainViewModel) {
    val series by mainViewModel.selectedSeries
    val episodes by mainViewModel.seriesEpisodes
    val contentInfo by mainViewModel.contentInfo
    val resumePositions by mainViewModel.resumePositions

    // Zurück-Taste schließt die Episodenliste.
    BackHandler { mainViewModel.closeSeries() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Serien-Cover als dezenter Hero-Backdrop mit Scrim-Verlauf zum Hintergrund.
        if (series?.cover != null) {
            AsyncImage(
                model = series?.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.3f,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
        }

    Column(modifier = Modifier.fillMaxSize().padding(28.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (series?.cover != null) {
                AsyncImage(
                    model = series?.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(20.dp))
            }
            Text(
                text = series?.name ?: "Serie",
                style = MaterialTheme.typography.displaySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // Metadaten + Beschreibung der Serie (aus dem Anbieter-Katalog)
        val meta = listOfNotNull(
            series?.releaseDate?.take(4),
            series?.genre,
            series?.rating?.let { "★ $it" }
        ).joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        val plot = series?.plot
        if (plot != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = plot,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            if (episodes.isEmpty()) {
                Text(
                    text = contentInfo.ifEmpty { "Keine Episoden." },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(episodes, key = { it.id }) { episode ->
                        val episodeUrl = mainViewModel.episodeUrl(episode)
                        EpisodeRow(
                            episode = episode,
                            hasResume = episodeUrl != null && (resumePositions[episodeUrl] ?: 0L) > 10_000,
                            onClick = { mainViewModel.playEpisode(episode) }
                        )
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: SeriesEpisode,
    hasResume: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFrame(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "S%02d E%02d  %s".format(episode.season, episode.episode, episode.title),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
        )
        if (hasResume) {
            Text(
                text = "▶ Weiterschauen",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
