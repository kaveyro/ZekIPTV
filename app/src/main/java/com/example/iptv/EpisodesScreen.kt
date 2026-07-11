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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

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

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (series?.cover != null) {
                AsyncImage(
                    model = series?.cover,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text = series?.name ?: "Serie",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            Button(onClick = { mainViewModel.closeSeries() }) { Text("Zurück") }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    episode: SeriesEpisode,
    hasResume: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick)
            .background(bg)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "S%02d E%02d  %s".format(episode.season, episode.episode, episode.title),
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
        )
        if (hasResume) {
            Text(
                text = "▶ Weiterschauen",
                color = fg.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
