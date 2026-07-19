package com.zekikoese

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zekikoese.ui.tvFocusFrame

/** Kuratierte, frei verfügbare XMLTV-EPG-Quellen für den Schnell-Adder. */
private val FREE_EPG_SOURCES = listOf(
    "Deutschland" to "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz",
    "Österreich" to "https://epgshare01.online/epgshare01/epg_ripper_AT1.xml.gz",
    "Schweiz" to "https://epgshare01.online/epgshare01/epg_ripper_CH1.xml.gz",
    "Türkei" to "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz",
    "Großbritannien" to "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
    "USA" to "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
    // Alternative Quelle: iptv-epg.org (https://iptv-epg.org/guides)
    "Deutschland (iptv-epg.org)" to "https://iptv-epg.org/files/epg-de.xml.gz",
    "Österreich (iptv-epg.org)" to "https://iptv-epg.org/files/epg-at.xml.gz",
    "Schweiz (iptv-epg.org)" to "https://iptv-epg.org/files/epg-ch.xml.gz",
    "Türkei (iptv-epg.org)" to "https://iptv-epg.org/files/epg-tr.xml.gz",
    "Großbritannien (iptv-epg.org)" to "https://iptv-epg.org/files/epg-gb.xml.gz",
    "USA (iptv-epg.org)" to "https://iptv-epg.org/files/epg-us.xml.gz",
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

    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newEpgUrl by remember { mutableStateOf("") }

    val focusManager = LocalFocusManager.current
    // Textfelder verschlucken DPAD hoch/runter für die Cursor-Steuerung und werden so zur
    // Fokus-Falle. Hoch/runter reichen den Fokus deshalb explizit an das nächste/vorherige
    // Element weiter; links/rechts bleiben für die Cursor-Bewegung im Feld.
    val escapeVerticalOnDpad = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown) {
            when (event.key) {
                Key.DirectionDown -> { focusManager.moveFocus(FocusDirection.Down); true }
                Key.DirectionUp -> { focusManager.moveFocus(FocusDirection.Up); true }
                else -> false
            }
        } else {
            false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
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
        item { SectionTitle("Playlists") }
        items(playlists, key = { it.url + it.name }) { entry ->
            SettingsRow(
                title = if (entry.url == activeUrl) "${entry.name}   ✓ aktiv" else entry.name,
                subtitle = entry.url,
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
        // Felder untereinander (statt nebeneinander): so führt hoch/runter sauber
        // durch Name → URL → Button, ohne dass links/rechts nötig ist.
        item {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().then(escapeVerticalOnDpad)
            )
        }
        item {
            OutlinedTextField(
                value = newUrl,
                onValueChange = { newUrl = it },
                label = { Text("M3U-URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().then(escapeVerticalOnDpad)
            )
        }
        item {
            Button(
                onClick = {
                    mainViewModel.addPlaylist(newName, newUrl)
                    newName = ""
                    newUrl = ""
                }
            ) { Text("Playlist hinzufügen") }
        }

        // ---------- Design ----------
        item { SectionTitle("Design") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupChip("Dunkel", selected = themeMode == "dark") { mainViewModel.setThemeMode("dark") }
                GroupChip("Hell", selected = themeMode == "light") { mainViewModel.setThemeMode("light") }
                GroupChip("System", selected = themeMode == "system") { mainViewModel.setThemeMode("system") }
            }
        }

        // ---------- Bedienoberfläche (TV vs. Smartphone) ----------
        item { SectionTitle("Bedienoberfläche") }
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
        item { SectionTitle("Wiedergabe") }
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
            item { SectionTitle("Live-TV-Kategorien") }
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
        item { SectionTitle("Backup") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = { mainViewModel.exportBackup() }) { Text("Backup exportieren") }
                Button(onClick = { mainViewModel.importBackup() }) { Text("Backup importieren") }
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
        item { SectionTitle("EPG-Quellen (XMLTV)") }
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
            OutlinedTextField(
                value = newEpgUrl,
                onValueChange = { newEpgUrl = it },
                label = { Text("Eigene XMLTV-URL (.xml oder .xml.gz)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().then(escapeVerticalOnDpad)
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        mainViewModel.addEpgSource(newEpgUrl)
                        newEpgUrl = ""
                    }
                ) { Text("Hinzufügen") }
                Button(onClick = { mainViewModel.refreshEpg() }) { Text("EPG aktualisieren") }
            }
        }
        if (epgInfo.isNotEmpty()) {
            item {
                Text(epgInfo, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp)
    )
}

/** Fokussierbare Einstellungs-Zeile mit sichtbarem D-Pad-Fokus (wie ChannelRow). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusFrame(onClick = onClick, onLongClick = onLongClick)
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
