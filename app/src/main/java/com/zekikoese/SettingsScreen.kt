package com.zekikoese

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
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
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.tvFocusFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val isTv = LocalIsTv.current

    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showEpgDialog by remember { mutableStateOf(false) }
    // Jugendschutz: welcher PIN-Dialog offen ist ("unlock" | "set") + letzte Meldung.
    var pinDialog by remember { mutableStateOf<String?>(null) }
    var pinMessage by remember { mutableStateOf("") }
    val parentalPin by mainViewModel.parentalPin
    val parentalUnlocked by mainViewModel.parentalUnlocked
    val parentalLocked = parentalPin.isNotEmpty() && !parentalUnlocked

    // Backup per System-Dateidialog; ohne Dateidialog (viele Fire-TV-Geräte) in den App-Ordner.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) mainViewModel.exportBackup(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) mainViewModel.importBackup(uri) }

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
                // Zugangsdaten nicht im Klartext anzeigen (Xtream-URLs enthalten Benutzer/Passwort).
                subtitle = Http.redact(entry.url),
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
        // Erkannter, noch nicht übernommener Xtream-Zugang der aktiven M3U-Playlist.
        item {
            val suggestion by mainViewModel.xtreamSuggestion
            val current = suggestion
            if (current != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Xtream-Zugang erkannt (${current.account.baseUrl.substringAfter("://")}, " +
                            "Benutzer ${current.account.username}) — damit gibt es auch Filme, Serien und Catch-up.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TvButton(text = "Verknüpfen", onClick = { mainViewModel.linkXtreamSuggestion() })
                        TvButton(text = "Komplett umstellen", onClick = { mainViewModel.convertXtreamSuggestion() })
                        TvButton(
                            text = "Nein danke",
                            onClick = { mainViewModel.dismissXtreamSuggestion() },
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            borderColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
        item {
            TvButton(
                text = "Neue Playlist hinzufügen...",
                onClick = { showPlaylistDialog = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        // ---------- Anbieter-Konto (Xtream) ----------
        val xtreamAvailable by mainViewModel.xtreamAvailable
        if (xtreamAvailable) {
            item { SectionTitle("Anbieter-Konto", icon = Icons.Filled.AccountCircle) }
            item {
                val info by mainViewModel.accountInfo
                val error by mainViewModel.accountInfoError
                val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val current = info
                    if (current == null) {
                        Text(
                            error.ifEmpty { "Konto-Info wird geladen…" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        val linkedAccount by mainViewModel.xtreamLinked
                        if (linkedAccount) {
                            Text(
                                "Verknüpft mit der M3U-Playlist (erkannt aus den Stream-Adressen).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            "Status: ${current.status ?: "unbekannt"}" + if (current.isTrial) " (Testzugang)" else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Gültig bis: " + (current.expiresAtMs?.let { dateFormat.format(Date(it)) } ?: "unbegrenzt"),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (current.maxConnections != null) {
                            Text(
                                "Verbindungen: ${current.activeConnections ?: 0} von ${current.maxConnections}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TvButton(text = "Konto-Info aktualisieren", onClick = { mainViewModel.refreshAccountInfo() })
                        val linkedAccount by mainViewModel.xtreamLinked
                        if (linkedAccount) {
                            TvButton(
                                text = "Verknüpfung lösen",
                                onClick = { mainViewModel.unlinkXtream() },
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
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
            val timeshiftOn by mainViewModel.timeshift
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text("Timeshift (Live-TV pausieren/zurückspulen):", style = MaterialTheme.typography.bodyMedium)
                GroupChip("An", selected = timeshiftOn) { mainViewModel.setTimeshift(true) }
                GroupChip("Aus", selected = !timeshiftOn) { mainViewModel.setTimeshift(false) }
            }
        }
        item {
            Text(
                "Nimmt Live-Sender während des Schauens auf (bis 30 min, max. 1 GB Zwischenspeicher) — " +
                    "Pause und Zurückspulen wie beim Festplattenrekorder. Der Senderstart dauert ca. 2 s länger. " +
                    "HLS-Sender (.m3u8) nutzen das Zeitfenster des Anbieters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        // Automatische Bildwiederholrate — nur sinnvoll am Fernseher.
        if (isTv) {
            item {
                val afr by mainViewModel.autoFrameRate
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Bildwiederholrate an Video anpassen:", style = MaterialTheme.typography.bodyMedium)
                    GroupChip("An", selected = afr) { mainViewModel.setAutoFrameRate(true) }
                    GroupChip("Aus", selected = !afr) { mainViewModel.setAutoFrameRate(false) }
                }
            }
            item {
                Text(
                    "Schaltet den Fernseher z. B. für deutsches TV auf 50 Hz und für Filme auf 24 Hz — " +
                        "flüssigere Schwenks. Beim Umschalten kann das Bild kurz schwarz werden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
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
                    if (parentalLocked) {
                        "🔒 Durch die Jugendschutz-PIN gesperrt (siehe unten)."
                    } else {
                        "OK blendet eine Kategorie aus bzw. wieder ein. Ausgeblendete Kategorien (✕) " +
                            "erscheinen weder in Live-TV noch in der Suche."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (!parentalLocked) item {
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

        // ---------- App-Update ----------
        item { SectionTitle("App-Update", icon = Icons.Filled.Info) }
        item {
            val update by mainViewModel.availableUpdate
            val status by mainViewModel.updateStatus
            val inProgress by mainViewModel.updateInProgress
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Installierte Version: ${BuildConfig.VERSION_NAME}" +
                        (update?.let { " · verfügbar: ${it.version}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val available = update
                    if (available != null) {
                        TvButton(
                            text = if (inProgress) "Wird geladen…" else "Version ${available.version} installieren",
                            onClick = { mainViewModel.installUpdate() }
                        )
                    }
                    TvButton(
                        text = "Nach Updates suchen",
                        onClick = { mainViewModel.checkForUpdates(manual = true) },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (status.isNotEmpty()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // ---------- Jugendschutz ----------
        item { SectionTitle("Jugendschutz", icon = Icons.Filled.Lock) }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    parentalPin.isEmpty() ->
                        TvButton(text = "PIN festlegen...", onClick = { pinDialog = "set" })
                    parentalLocked ->
                        TvButton(text = "Entsperren...", onClick = { pinDialog = "unlock" })
                    else -> {
                        TvButton(text = "PIN ändern...", onClick = { pinDialog = "set" })
                        TvButton(
                            text = "PIN entfernen",
                            onClick = {
                                mainViewModel.setParentalPin("")
                                pinMessage = "Jugendschutz deaktiviert."
                            },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        item {
            Text(
                pinMessage.ifEmpty {
                    "Mit PIN lassen sich ausgeblendete Kategorien nur nach Eingabe wieder einblenden; " +
                        "ihre Sender erscheinen auch nicht auf Home. Der Backup-Import ist dann ebenfalls gesperrt."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
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
                    onClick = {
                        try {
                            exportLauncher.launch("zekiptv-backup.json")
                        } catch (e: ActivityNotFoundException) {
                            mainViewModel.exportBackup()
                        }
                    }
                )
                TvButton(
                    text = "Backup importieren",
                    onClick = {
                        try {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        } catch (e: ActivityNotFoundException) {
                            mainViewModel.importBackup()
                        }
                    }
                )
            }
        }
        item {
            val backupInfo by mainViewModel.backupInfo
            Text(
                backupInfo.ifEmpty {
                    "Sichert Playlists, Favoriten, EPG-Quellen und Einstellungen als JSON-Datei " +
                        "(Speicherort wählbar; ohne Dateiauswahl im App-Ordner). Achtung: Die Datei " +
                        "enthält die Playlist-URLs inklusive Zugangsdaten."
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
            onConfirmXtream = { name, server, user, pass -> mainViewModel.addXtreamLogin(name, server, user, pass) },
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
    when (pinDialog) {
        "unlock" -> SingleTextInputDialog(
            title = "Jugendschutz entsperren",
            label = "PIN",
            confirmText = "Entsperren",
            isPin = true,
            onConfirm = { pin ->
                pinMessage = if (mainViewModel.unlockParental(pin)) "Entsperrt bis zum nächsten App-Start." else "Falsche PIN."
            },
            onDismiss = { pinDialog = null }
        )
        "set" -> SingleTextInputDialog(
            title = "Jugendschutz-PIN festlegen",
            label = "Neue PIN (mind. 4 Ziffern)",
            confirmText = "Speichern",
            isPin = true,
            onConfirm = { pin ->
                if (pin.length >= 4) {
                    mainViewModel.setParentalPin(pin)
                    pinMessage = "PIN gespeichert."
                } else {
                    pinMessage = "Die PIN braucht mindestens 4 Ziffern."
                }
            },
            onDismiss = { pinDialog = null }
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
