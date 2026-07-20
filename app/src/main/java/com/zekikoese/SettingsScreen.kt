package com.zekikoese

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zekikoese.ui.tvFocusFrame

/** Kuratierte, frei verfügbare XMLTV-EPG-Quellen für den Schnell-Adder. */
private val FREE_EPG_SOURCES = listOf(
    "Deutschland (epgshare)" to "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz",
    "Deutschland (free-epg)" to "https://www.free-epg.de/api/epg/de.xml.gz",
    "Österreich" to "https://www.free-epg.de/api/epg/at.xml.gz",
    "Schweiz" to "https://www.free-epg.de/api/epg/ch.xml.gz",
    "Türkei (epg.pw)" to "https://epg.pw/xmltv/guide/tr.xml",
    "UK (EPGTalk)" to "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/UK_guide.xml.gz",
    "USA (US2)" to "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
    "USA (EPGTalk)" to "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/US_guide.xml.gz",
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(mainViewModel: MainViewModel) {
    val playlists by mainViewModel.playlists
    val activeUrl by mainViewModel.url
    val themeMode by mainViewModel.themeMode
    val epgSources by mainViewModel.epgSources
    val epgInfo by mainViewModel.epgInfo
    val allGroups by mainViewModel.allGroups
    val hiddenGroups by mainViewModel.hiddenGroups

    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showEpgDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 32.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Einstellungen",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // ---------- Playlists ----------
        item { SectionTitle("Playlists", icon = Icons.AutoMirrored.Filled.List) }
        items(playlists, key = { it.url + it.name }) { entry ->
            val isActive = entry.url == activeUrl
            SettingsRow(
                title = if (isActive) "${entry.name}   ✓" else entry.name,
                subtitle = entry.url,
                isSelected = isActive,
                onClick = {
                    mainViewModel.selectPlaylist(entry)
                    mainViewModel.navigate(NavDestination.LIVE)
                },
                onLongClick = { mainViewModel.removePlaylist(entry) }
            )
        }
        item {
            Text(
                if (playlists.isEmpty()) "Noch keine Playlist gespeichert."
                else "OK = aktivieren und laden · lang drücken = entfernen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        item {
            TvButton(
                text = "Neue Playlist hinzufügen...",
                onClick = { showPlaylistDialog = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        // ---------- Design ----------
        item { SectionTitle("Design", icon = Icons.Filled.Settings) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupChip("Dunkel", selected = themeMode == "dark") { mainViewModel.setThemeMode("dark") }
                GroupChip("Hell", selected = themeMode == "light") { mainViewModel.setThemeMode("light") }
                GroupChip("System", selected = themeMode == "system") { mainViewModel.setThemeMode("system") }
            }
        }

        // ---------- Bedienoberfläche (TV vs. Smartphone) ----------
        item { SectionTitle("Bedienoberfläche", icon = Icons.Filled.Settings) }
        item {
            val uiMode by mainViewModel.uiModeOverride
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupChip("Automatisch", selected = uiMode == "auto") { mainViewModel.setUiMode("auto") }
                GroupChip("TV", selected = uiMode == "tv") { mainViewModel.setUiMode("tv") }
                GroupChip("Smartphone", selected = uiMode == "phone") { mainViewModel.setUiMode("phone") }
            }
        }
        item {
            Text(
                "„Automatisch“ erkennt den Gerätetyp. TV = D-Pad-Oberfläche mit Navigations-Rail, " +
                    "Smartphone = Touch-Oberfläche mit unterer Leiste und Hochformat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        // ---------- Wiedergabe ----------
        item { SectionTitle("Wiedergabe", icon = Icons.Filled.Settings) }
        item {
            val autoplay by mainViewModel.autoplayLast
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Letzten Sender beim Start abspielen:", style = MaterialTheme.typography.bodyMedium)
                GroupChip("An", selected = autoplay) { mainViewModel.setAutoplayLast(true) }
                GroupChip("Aus", selected = !autoplay) { mainViewModel.setAutoplayLast(false) }
            }
        }
        item {
            Text(
                "Tipp: Im Player öffnet ◀ (links) die Senderliste zum Zappen, ▲ (hoch) die Programm-Info " +
                    "und die MENÜ-Taste (☰) Senderwechsel, Tonspur, Untertitel und Sleep-Timer. " +
                    "OK zeigt die Steuerleiste. In Senderlisten öffnet MENÜ die Sender-Aktionen; " +
                    "⏩/⏪ blättern seitenweise.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        // ---------- Live-TV-Kategorien ----------
        if (allGroups.isNotEmpty()) {
            item { SectionTitle("Live-TV-Kategorien", icon = Icons.Filled.Info) }
            item {
                Text(
                    "OK blendet eine Kategorie aus bzw. wieder ein. Ausgeblendete Kategorien (✕) " +
                        "erscheinen weder in Live-TV noch in der Suche.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    allGroups.forEach { group ->
                        val hidden = group in hiddenGroups
                        GroupChip(
                            label = if (hidden) "✕ $group" else group,
                            selected = !hidden
                        ) { mainViewModel.toggleHiddenGroup(group) }
                    }
                }
            }
        }

        // ---------- Backup ----------
        item { SectionTitle("Backup", icon = Icons.Filled.Info) }
        item {
            // FlowRow statt Row: im Hochformat umbrechen, damit kein Button aus dem Bild rutscht.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvButton(
                    text = "Backup exportieren",
                    onClick = { mainViewModel.exportBackup() }
                )
                TvButton(
                    text = "Backup importieren",
                    onClick = { mainViewModel.importBackup() }
                )
            }
        }
        item {
            val backupInfo by mainViewModel.backupInfo
            Text(
                backupInfo.ifEmpty {
                    "Sichert Playlists, Favoriten, EPG-Quellen und Einstellungen als JSON-Datei " +
                        "im App-Ordner (per Dateimanager/adb übertragbar)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        // ---------- EPG ----------
        item { SectionTitle("EPG-Quellen (XMLTV)", icon = Icons.Filled.Settings) }
        items(epgSources.toList(), key = { it }) { source ->
            // OK entfernt die Quelle direkt — Lang-Druck ist mit der D-Pad-Center-Taste
            // auf Fire TV unzuverlässig. Wieder hinzufügen geht jederzeit über die Chips.
            SettingsRow(
                title = source,
                subtitle = null,
                onClick = { mainViewModel.removeEpgSource(source) },
                onLongClick = { mainViewModel.removeEpgSource(source) }
            )
        }
        item {
            Text(
                if (epgSources.isEmpty()) "Noch keine EPG-Quelle konfiguriert."
                else "OK = Quelle entfernen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        item {
            Text(
                "Schnell hinzufügen (freie Quellen) — OK fügt hinzu bzw. entfernt wieder:",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FREE_EPG_SOURCES.forEach { (label, sourceUrl) ->
                    val active = sourceUrl in epgSources
                    GroupChip(label, selected = active) {
                        if (active) {
                            mainViewModel.removeEpgSource(sourceUrl)
                        } else {
                            mainViewModel.addEpgSource(sourceUrl)
                        }
                    }
                }
            }
        }
        item {
            // FlowRow statt Row: im Hochformat umbrechen, damit „Cache löschen" nicht rechts
            // aus dem Bild rutscht.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvButton(
                    text = "Eigene Quelle hinzufügen...",
                    onClick = { showEpgDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                TvButton(text = "EPG aktualisieren", onClick = { mainViewModel.refreshEpg() })
                TvButton(
                    text = "Cache löschen",
                    onClick = { mainViewModel.clearEpgCache() },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        if (epgInfo.isNotEmpty()) {
            item {
                Text(epgInfo, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showPlaylistDialog) {
        PlaylistInputDialog(
            onConfirm = { name, url -> mainViewModel.addPlaylist(name, url) },
            onDismiss = { showPlaylistDialog = false }
        )
    }
    if (showEpgDialog) {
        SingleTextInputDialog(
            title = "EPG-Quelle hinzufügen",
            label = "XMLTV-URL (.xml oder .xml.gz)",
            onConfirm = { url -> mainViewModel.addEpgSource(url) },
            onDismiss = { showEpgDialog = false }
        )
    }
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Fokussierbare Einstellungs-Zeile mit sichtbarem D-Pad-Fokus (wie ChannelRow). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFrame(
                onClick = onClick,
                onLongClick = onLongClick,
                isSelected = isSelected,
                restColor = Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
