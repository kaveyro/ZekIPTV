package com.example.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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

/** Kuratierte, frei verfügbare XMLTV-EPG-Quellen für den Schnell-Adder. */
private val FREE_EPG_SOURCES = listOf(
    "Deutschland" to "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz",
    "Österreich" to "https://epgshare01.online/epgshare01/epg_ripper_AT1.xml.gz",
    "Schweiz" to "https://epgshare01.online/epgshare01/epg_ripper_CH1.xml.gz",
    "Türkei" to "https://epgshare01.online/epgshare01/epg_ripper_TR1.xml.gz",
    "Großbritannien" to "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz",
    "USA" to "https://epgshare01.online/epgshare01/epg_ripper_US1.xml.gz",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(mainViewModel: MainViewModel) {
    val playlists by mainViewModel.playlists
    val activeUrl by mainViewModel.url
    val themeMode by mainViewModel.themeMode
    val epgSources by mainViewModel.epgSources
    val epgInfo by mainViewModel.epgInfo

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

    // Zurück-Taste schließt die Einstellungen.
    BackHandler { mainViewModel.showSettings.value = false }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Einstellungen",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = { mainViewModel.showSettings.value = false }) {
                    Text("Zurück")
                }
            }
        }

        // ---------- Playlists ----------
        item { SectionTitle("Playlists") }
        items(playlists, key = { it.url + it.name }) { entry ->
            SettingsRow(
                title = if (entry.url == activeUrl) "${entry.name}   ✓ aktiv" else entry.name,
                subtitle = entry.url,
                onClick = {
                    mainViewModel.selectPlaylist(entry)
                    mainViewModel.showSettings.value = false
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
                "Tipp: Im Player öffnet die MENÜ-Taste (☰) Senderwechsel, Tonspur, Untertitel und " +
                    "Sleep-Timer. OK zeigt die Steuerleiste (Pause/Vor-/Zurückspulen).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
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
            SettingsRow(
                title = source,
                subtitle = null,
                onClick = {},
                onLongClick = { mainViewModel.removeEpgSource(source) }
            )
        }
        item {
            Text(
                if (epgSources.isEmpty()) "Noch keine EPG-Quelle konfiguriert."
                else "Lang drücken = entfernen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        item {
            Text("Schnell hinzufügen (freie Quellen):", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(FREE_EPG_SOURCES) { (label, sourceUrl) ->
                    GroupChip(label, selected = sourceUrl in epgSources) {
                        mainViewModel.addEpgSource(sourceUrl)
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
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(bg)
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = fg.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
