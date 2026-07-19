package com.zekikoese

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.zekikoese.ui.LocalIsTv

/** Detail-Seite eines Films: Poster, Metadaten, Beschreibung, Abspielen/Fortsetzen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VodDetailScreen(mainViewModel: MainViewModel) {
    val item by mainViewModel.selectedVod
    val info by mainViewModel.vodInfo
    val favorites by mainViewModel.favorites
    val resumePositions by mainViewModel.resumePositions

    val vod = item ?: return
    val vodUrl = mainViewModel.vodUrl(vod)
    val resumeMs = vodUrl?.let { resumePositions[it] } ?: 0L
    val isFavorite = mainViewModel.vodFavKey(vod) in favorites

    // Zurück-Taste schließt die Detail-Seite.
    BackHandler { mainViewModel.closeVod() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-Bleed-Backdrop: Poster hinter dem Inhalt, mit Scrim-Verlauf zum Hintergrund.
        // Bewusst kein Modifier.blur — braucht API 31+, FireTV-Geräte liegen darunter.
        if (vod.icon != null) {
            AsyncImage(
                model = vod.icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.35f,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
        }

        if (!LocalIsTv.current) {
            // Smartphone: einspaltiges, scrollendes Layout (Hochformat-tauglich).
            PhoneVodDetailContent(
                mainViewModel = mainViewModel,
                vod = vod,
                info = info,
                vodUrl = vodUrl,
                resumeMs = resumeMs,
                isFavorite = isFavorite
            )
            return@Box
        }

        Row(modifier = Modifier.fillMaxSize().padding(40.dp)) {
        // Poster
        if (vod.icon != null) {
            AsyncImage(
                model = vod.icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(280.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.width(32.dp))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = vod.name,
                style = MaterialTheme.typography.displaySmall
            )

            // Metadaten-Zeile: Jahr · Genre · Bewertung · Dauer
            val meta = listOfNotNull(
                info?.releaseDate?.take(4),
                info?.genre,
                info?.rating?.let { "★ $it" },
                info?.duration
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // Aktionen
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(
                    text = if (resumeMs > 10_000) "▶ Fortsetzen (${resumeMs / 60_000} min)" else "▶ Abspielen",
                    onClick = { mainViewModel.playVod(vod) }
                )

                if (resumeMs > 10_000 && vodUrl != null) {
                    TvButton(
                        text = "Von vorn",
                        onClick = {
                            mainViewModel.saveResume(vodUrl, 0)
                            mainViewModel.playVod(vod)
                        },
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                TvButton(
                    text = if (isFavorite) "★ Favorit" else "☆ Favorit",
                    onClick = { mainViewModel.toggleVodFavorite(vod) },
                    containerColor = Color.Transparent,
                    contentColor = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    borderColor = if (isFavorite) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // Beschreibung + Credits (scrollbar bei langen Texten)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val plot = info?.plot
                Text(
                    text = plot ?: "Beschreibung wird geladen…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (plot != null) 0.85f else 0.5f)
                )
                val credits = listOfNotNull(
                    info?.director?.let { "Regie: $it" },
                    info?.cast?.let { "Besetzung: $it" }
                )
                if (credits.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    credits.forEach {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Box(Modifier.height(24.dp))
            }
        }
        }
    }
}

/** Smartphone-Variante der Film-Detailseite: eine scrollende Spalte statt Poster+Text nebeneinander. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneVodDetailContent(
    mainViewModel: MainViewModel,
    vod: VodItem,
    info: VodInfo?,
    vodUrl: String?,
    resumeMs: Long,
    isFavorite: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        if (vod.icon != null) {
            AsyncImage(
                model = vod.icon,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Spacer(Modifier.height(16.dp))
        }

        Text(
            text = vod.name,
            style = MaterialTheme.typography.headlineMedium
        )

        val meta = listOfNotNull(
            info?.releaseDate?.take(4),
            info?.genre,
            info?.rating?.let { "★ $it" },
            info?.duration
        ).joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        Spacer(Modifier.height(16.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TvButton(
                text = if (resumeMs > 10_000) "▶ Fortsetzen (${resumeMs / 60_000} min)" else "▶ Abspielen",
                onClick = { mainViewModel.playVod(vod) }
            )

            if (resumeMs > 10_000 && vodUrl != null) {
                TvButton(
                    text = "Von vorn",
                    onClick = {
                        mainViewModel.saveResume(vodUrl, 0)
                        mainViewModel.playVod(vod)
                    },
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            TvButton(
                text = if (isFavorite) "★ Favorit" else "☆ Favorit",
                onClick = { mainViewModel.toggleVodFavorite(vod) },
                containerColor = Color.Transparent,
                contentColor = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                borderColor = if (isFavorite) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }

        Spacer(Modifier.height(16.dp))

        val plot = info?.plot
        Text(
            text = plot ?: "Beschreibung wird geladen…",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (plot != null) 0.85f else 0.5f)
        )
        val credits = listOfNotNull(
            info?.director?.let { "Regie: $it" },
            info?.cast?.let { "Besetzung: $it" }
        )
        if (credits.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            credits.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
