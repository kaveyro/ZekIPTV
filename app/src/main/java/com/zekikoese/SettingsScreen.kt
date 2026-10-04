package com.zekikoese

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
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

/** Offene Sicherheitsabfrage bzw. Aktionsauswahl der Einstellungen. */
private sealed interface SettingsDialog {
    data class PlaylistActions(val entry: PlaylistEntry) : SettingsDialog
    data class RemovePlaylist(val entry: PlaylistEntry) : SettingsDialog
    data class RemoveEpgSource(val source: String) : SettingsDialog
    data object ClearEpgCache : SettingsDialog
    data object RemovePin : SettingsDialog
    data object AddEpgSource : SettingsDialog
    data object UnlockPin : SettingsDialog
    data object SetPin : SettingsDialog
    data object ImportBackup : SettingsDialog
    data class ManageCategories(val kind: CategoryKind) : SettingsDialog
}

/** Bereich, dessen Kategorien verwaltet werden. */
private enum class CategoryKind { LIVE, VOD, SERIES }

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
    // Bedienhinweise je Gerät: Fernbedienung (OK) oder Touch (Antippen).
    val tap = if (isTv) "OK" else "Antippen"

    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
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
        item(key = "settings_1") { ScreenTitle("Einstellungen") }

        // ---------- Playlists ----------
        item(key = "settings_2") { SectionTitle("Playlists", icon = Icons.AutoMirrored.Filled.List) }
        items(playlists, key = { it.url + it.name }) { entry ->
            val isActive = entry.url == activeUrl
            SettingsRow(
                title = entry.name,
                // Zugangsdaten nicht im Klartext anzeigen (Xtream-URLs enthalten Benutzer/Passwort).
                subtitle = Http.redact(entry.url),
                isSelected = isActive,
                badge = if (isActive) "aktiv" else null,
                onClick = { dialog = SettingsDialog.PlaylistActions(entry) },
                onLongClick = { dialog = SettingsDialog.PlaylistActions(entry) }
            )
        }
        item(key = "settings_3") {
            Hint(
                if (playlists.isEmpty()) "Noch keine Playlist gespeichert."
                else "$tap öffnet die Aktionen: aktivieren, neu laden oder entfernen."
            )
        }
        // Erkannter, noch nicht übernommener Xtream-Zugang der aktiven M3U-Playlist.
        item(key = "settings_4") {
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
        item(key = "settings_5") {
            TvButton(
                text = "Neue Playlist hinzufügen…",
                onClick = { mainViewModel.showPlaylistDialog.value = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        // ---------- EPG ----------
        item(key = "settings_6") { SectionTitle("Programmführer (EPG)", icon = Icons.Filled.DateRange) }
        items(epgSources.toList(), key = { it }) { source ->
            // Entfernen nur nach Rückfrage — Lang-Druck ist mit der D-Pad-Center-Taste auf
            // Fire TV unzuverlässig, daher öffnet schon OK die Abfrage.
            SettingsRow(
                // Anbieter-EPG (xmltv.php) enthält Benutzer/Passwort — wie bei Playlists maskieren.
                title = Http.redact(source),
                subtitle = null,
                onClick = { dialog = SettingsDialog.RemoveEpgSource(source) },
                onLongClick = { dialog = SettingsDialog.RemoveEpgSource(source) }
            )
        }
        item(key = "settings_7") {
            Hint(
                if (epgSources.isEmpty()) "Noch keine EPG-Quelle konfiguriert."
                else "$tap auf eine Quelle = entfernen (mit Rückfrage)."
            )
        }
        item(key = "settings_8") {
            Text(
                "Schnell hinzufügen (freie Quellen) — $tap fügt hinzu bzw. entfernt wieder:",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        item(key = "settings_9") {
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
        item(key = "settings_10") {
            // FlowRow statt Row: im Hochformat umbrechen, damit kein Knopf aus dem Bild rutscht.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvButton(
                    text = "Eigene Quelle hinzufügen…",
                    onClick = { dialog = SettingsDialog.AddEpgSource },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                val epgLoading by mainViewModel.epgLoading
                TvButton(
                    text = if (epgLoading) "Wird geladen…" else "EPG aktualisieren",
                    // Während des Ladens kein zweiter Abruf (der würde den laufenden abbrechen).
                    onClick = { if (!epgLoading) mainViewModel.refreshEpg(notify = true) }
                )
                TvButton(
                    text = "Cache löschen",
                    onClick = { dialog = SettingsDialog.ClearEpgCache },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        if (epgSources.isNotEmpty()) {
            item(key = "settings_11") {
                val epgLoading by mainViewModel.epgLoading
                val updatedAt by mainViewModel.epgUpdatedAt
                // Stand des Programmführers — der automatische Abruf läuft ohne sichtbare Meldung.
                val text = when {
                    epgLoading -> "EPG wird geladen…"
                    updatedAt != null -> "Stand: ${epgUpdatedLabel(updatedAt!!, System.currentTimeMillis())}" +
                        (epgInfo.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                    else -> "Noch kein Programm geladen."
                }
                Column {
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    Hint("Wird automatisch alle 12 Stunden aktualisiert (auch im Hintergrund).")
                }
            }
        }

        // ---------- Anbieter-Konto (Xtream) ----------
        val xtreamAvailable by mainViewModel.xtreamAvailable
        if (xtreamAvailable) {
            item(key = "settings_12") { SectionTitle("Anbieter-Konto", icon = Icons.Filled.AccountCircle) }
            item(key = "settings_13") {
                val info by mainViewModel.accountInfo
                val error by mainViewModel.accountInfoError
                val linkedAccount by mainViewModel.xtreamLinked
                val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val current = info
                    if (current == null) {
                        Text(
                            error.ifEmpty { "Konto-Info wird geladen…" },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        if (linkedAccount) {
                            Hint("Verknüpft mit der M3U-Playlist (erkannt aus den Stream-Adressen).")
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

        // ---------- Wiedergabe ----------
        item(key = "settings_14") { SectionTitle("Wiedergabe", icon = Icons.Filled.PlayArrow) }
        item(key = "settings_15") {
            val autoplay by mainViewModel.autoplayLast
            SwitchRow(
                title = "Letzten Sender beim Start abspielen",
                checked = autoplay,
                onToggle = { mainViewModel.setAutoplayLast(!autoplay) }
            )
        }
        item(key = "settings_16") {
            val timeshiftOn by mainViewModel.timeshift
            SwitchRow(
                title = "Timeshift (Live-TV pausieren/zurückspulen)",
                subtitle = "Nimmt Live-Sender während des Schauens auf (bis 30 min, max. 1 GB Zwischenspeicher) — " +
                    "Pause und Zurückspulen wie beim Festplattenrekorder. Der Senderstart dauert ca. 2 s länger. " +
                    "HLS-Sender (.m3u8) nutzen das Zeitfenster des Anbieters.",
                checked = timeshiftOn,
                onToggle = { mainViewModel.setTimeshift(!timeshiftOn) }
            )
        }
        // Automatische Bildwiederholrate — nur sinnvoll am Fernseher.
        if (isTv) {
            item(key = "settings_18") {
                val afr by mainViewModel.autoFrameRate
                SwitchRow(
                    title = "Bildwiederholrate an Video anpassen",
                    subtitle = "Schaltet den Fernseher z. B. für deutsches TV auf 50 Hz und für Filme auf 24 Hz — " +
                        "flüssigere Schwenks. Beim Umschalten kann das Bild kurz schwarz werden.",
                    checked = afr,
                    onToggle = { mainViewModel.setAutoFrameRate(!afr) }
                )
            }
        }

        // ---------- Kategorien (eigener Dialog statt hunderter Chips) ----------
        val xtreamForCategories by mainViewModel.xtreamAvailable
        if (allGroups.isNotEmpty() || xtreamForCategories) {
            item(key = "settings_20") { SectionTitle("Kategorien", icon = Icons.Filled.Star) }
            fun openManager(kind: CategoryKind) {
                // Mit Jugendschutz-PIN erst entsperren, dann verwalten.
                dialog = if (parentalLocked) SettingsDialog.UnlockPin else SettingsDialog.ManageCategories(kind)
            }
            val lockNote = if (parentalLocked) " · gesperrt (PIN)" else ""
            if (allGroups.isNotEmpty()) item(key = "settings_21") {
                SettingsRow(
                    title = "Live-TV-Kategorien verwalten…",
                    subtitle = "${hiddenGroups.count { it in allGroups }} von ${allGroups.size} ausgeblendet$lockNote",
                    onClick = { openManager(CategoryKind.LIVE) }
                )
            }
            if (xtreamForCategories) {
                item(key = "settings_22") {
                    val hiddenVod by mainViewModel.hiddenVodCategories
                    SettingsRow(
                        title = "Film-Kategorien verwalten…",
                        subtitle = "${hiddenVod.size} ausgeblendet$lockNote",
                        onClick = { openManager(CategoryKind.VOD) }
                    )
                }
                item(key = "settings_22b") {
                    val hiddenSeries by mainViewModel.hiddenSeriesCategories
                    SettingsRow(
                        title = "Serien-Kategorien verwalten…",
                        subtitle = "${hiddenSeries.size} ausgeblendet$lockNote",
                        onClick = { openManager(CategoryKind.SERIES) }
                    )
                }
            }
        }

        // ---------- Design ----------
        item(key = "settings_23") { SectionTitle("Design", icon = Icons.Filled.Edit) }
        item(key = "settings_24") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GroupChip("Dunkel", selected = themeMode == "dark") { mainViewModel.setThemeMode("dark") }
                GroupChip("Hell", selected = themeMode == "light") { mainViewModel.setThemeMode("light") }
                GroupChip("System", selected = themeMode == "system") { mainViewModel.setThemeMode("system") }
            }
        }

        // ---------- Bedienoberfläche (TV vs. Smartphone) ----------
        item(key = "settings_25") { SectionTitle("Bedienoberfläche", icon = Icons.Filled.Build) }
        item(key = "settings_26") {
            val uiMode by mainViewModel.uiModeOverride
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GroupChip("Automatisch", selected = uiMode == "auto") { mainViewModel.setUiMode("auto") }
                GroupChip("TV", selected = uiMode == "tv") { mainViewModel.setUiMode("tv") }
                GroupChip("Smartphone", selected = uiMode == "phone") { mainViewModel.setUiMode("phone") }
            }
        }
        item(key = "settings_27") {
            Hint(
                "„Automatisch“ erkennt den Gerätetyp. TV = D-Pad-Oberfläche mit Navigations-Rail, " +
                    "Smartphone = Touch-Oberfläche mit unterer Leiste und Hochformat."
            )
        }

        // ---------- Jugendschutz ----------
        item(key = "settings_28") { SectionTitle("Jugendschutz", icon = Icons.Filled.Lock) }
        item(key = "settings_29") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    parentalPin.isEmpty() ->
                        TvButton(text = "PIN festlegen…", onClick = { dialog = SettingsDialog.SetPin })
                    parentalLocked ->
                        TvButton(text = "Entsperren…", onClick = { dialog = SettingsDialog.UnlockPin })
                    else -> {
                        TvButton(text = "PIN ändern…", onClick = { dialog = SettingsDialog.SetPin })
                        TvButton(
                            text = "PIN entfernen",
                            onClick = { dialog = SettingsDialog.RemovePin },
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        item(key = "settings_30") {
            Hint(
                "Mit PIN lassen sich ausgeblendete Kategorien nur nach Eingabe wieder einblenden; " +
                    "ihre Sender erscheinen auch nicht auf Home. Der Backup-Import ist dann ebenfalls gesperrt."
            )
        }

        // ---------- Backup ----------
        item(key = "settings_31") { SectionTitle("Backup", icon = Icons.Filled.Share) }
        item(key = "settings_32") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvButton(
                    text = "Backup exportieren",
                    onClick = {
                        try {
                            exportLauncher.launch(backupFileName(System.currentTimeMillis()))
                        } catch (e: ActivityNotFoundException) {
                            mainViewModel.exportBackup()
                        }
                    }
                )
                TvButton(
                    text = "Backup importieren…",
                    // Erst nachfragen: der Import ersetzt Playlists, Favoriten und Einstellungen.
                    onClick = { dialog = SettingsDialog.ImportBackup }
                )
            }
        }
        item(key = "settings_33") {
            Hint(
                "Sichert Playlists, Favoriten, EPG-Quellen und Einstellungen als JSON-Datei " +
                    "(Speicherort wählbar; ohne Dateiauswahl im App-Ordner). Achtung: Die Datei " +
                    "enthält die Playlist-URLs inklusive Zugangsdaten."
            )
        }

        // ---------- App-Update ----------
        item(key = "settings_34") { SectionTitle("App-Update", icon = Icons.Filled.Refresh) }
        item(key = "settings_35") {
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
                if (status.isNotEmpty()) Hint(status)
            }
        }

        // ---------- Hilfe ----------
        item(key = "settings_36") { SectionTitle("Bedienung", icon = Icons.Filled.Info) }
        item(key = "settings_37") {
            Hint(
                if (isTv) {
                    "Im Player öffnet ◀ (links) die Senderliste zum Zappen, ▲ (hoch) die Programm-Info " +
                        "und die MENÜ-Taste (☰) Senderwechsel, Tonspur, Untertitel und Sleep-Timer. " +
                        "OK zeigt die Steuerleiste, Zifferntasten wählen einen Sender direkt. " +
                        "In Senderlisten öffnet MENÜ die Sender-Aktionen; die Spultasten blättern seitenweise."
                } else {
                    "Im Player zeigt Antippen die Steuerleiste; oben rechts liegen Programm-Info, " +
                        "Senderwechsel und das Menü (Tonspur, Untertitel, Bildformat, Sleep-Timer). " +
                        "In Senderlisten öffnet ⋮ (oder langes Drücken) die Sender-Aktionen."
                }
            )
        }
        item(key = "settings_38") { Spacer(Modifier.height(24.dp)) }
    }

    when (val current = dialog) {
        is SettingsDialog.PlaylistActions -> {
            val entry = current.entry
            val isActive = entry.url == activeUrl
            ActionsDialog(
                title = entry.name,
                subtitle = Http.redact(entry.url),
                actions = listOf(
                    DialogAction(if (isActive) "Neu laden" else "Aktivieren und laden") {
                        mainViewModel.selectPlaylist(entry)
                        mainViewModel.navigate(NavDestination.LIVE)
                    },
                    DialogAction("Entfernen…", destructive = true) {
                        dialog = SettingsDialog.RemovePlaylist(entry)
                    }
                ),
                onDismiss = { if (dialog == current) dialog = null }
            )
        }
        is SettingsDialog.RemovePlaylist -> ConfirmDialog(
            title = "Playlist entfernen?",
            message = "„${current.entry.name}“ wird aus der Liste gelöscht. Zum erneuten Hinzufügen " +
                "brauchst du die Adresse bzw. die Zugangsdaten.",
            confirmText = "Entfernen",
            onConfirm = { mainViewModel.removePlaylist(current.entry) },
            onDismiss = { dialog = null }
        )
        is SettingsDialog.RemoveEpgSource -> ConfirmDialog(
            title = "EPG-Quelle entfernen?",
            message = Http.redact(current.source),
            confirmText = "Entfernen",
            onConfirm = { mainViewModel.removeEpgSource(current.source) },
            onDismiss = { dialog = null }
        )
        SettingsDialog.ClearEpgCache -> ConfirmDialog(
            title = "EPG-Cache löschen?",
            message = "Das Programm wird beim nächsten Aktualisieren neu heruntergeladen — bei großen " +
                "Quellen kann das einige Minuten dauern.",
            confirmText = "Löschen",
            onConfirm = { mainViewModel.clearEpgCache() },
            onDismiss = { dialog = null }
        )
        SettingsDialog.RemovePin -> ConfirmDialog(
            title = "Jugendschutz deaktivieren?",
            message = "Ausgeblendete Kategorien lassen sich danach ohne PIN wieder einblenden.",
            confirmText = "PIN entfernen",
            onConfirm = {
                mainViewModel.setParentalPin("")
                mainViewModel.showMessage("Jugendschutz deaktiviert.")
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.AddEpgSource -> SingleTextInputDialog(
            title = "EPG-Quelle hinzufügen",
            label = "XMLTV-URL (.xml oder .xml.gz)",
            keyboardType = KeyboardType.Uri,
            validate = ::validateStreamUrl,
            onConfirm = { url -> mainViewModel.addEpgSource(url) },
            onDismiss = { dialog = null }
        )
        SettingsDialog.UnlockPin -> PinDialog(
            title = "Jugendschutz entsperren",
            confirmText = "Entsperren",
            confirmTwice = false,
            onSubmit = { pin ->
                if (mainViewModel.unlockParental(pin)) {
                    mainViewModel.showMessage("Entsperrt bis zum nächsten App-Start.")
                    null
                } else {
                    "Falsche PIN."
                }
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.SetPin -> PinDialog(
            title = "Jugendschutz-PIN festlegen",
            confirmText = "Speichern",
            confirmTwice = true,
            onSubmit = { pin ->
                if (pin.length < MIN_PIN_LENGTH) {
                    "Die PIN braucht mindestens $MIN_PIN_LENGTH Ziffern."
                } else {
                    mainViewModel.setParentalPin(pin)
                    mainViewModel.showMessage("PIN gespeichert.")
                    null
                }
            },
            onDismiss = { dialog = null }
        )
        SettingsDialog.ImportBackup -> ConfirmDialog(
            title = "Backup importieren?",
            message = "Playlists, Favoriten, EPG-Quellen und Einstellungen werden durch den Inhalt " +
                "der Sicherung ersetzt.",
            confirmText = "Datei wählen",
            onConfirm = {
                try {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                } catch (e: ActivityNotFoundException) {
                    mainViewModel.importBackup()
                }
            },
            onDismiss = { dialog = null }
        )
        is SettingsDialog.ManageCategories -> CategoryManager(mainViewModel, current.kind) { dialog = null }
        null -> Unit
    }
}

/** Kategorie-Verwaltung für einen Bereich — die Daten kommen direkt aus dem ViewModel. */
@Composable
private fun CategoryManager(mainViewModel: MainViewModel, kind: CategoryKind, onDismiss: () -> Unit) {
    when (kind) {
        CategoryKind.LIVE -> {
            val groups by mainViewModel.allGroups
            val counts by mainViewModel.groupCounts
            val hidden by mainViewModel.hiddenGroups
            CategoryManagerDialog(
                title = "Live-TV-Kategorien",
                categories = groups.map { ManagedCategory(it, it, counts[it]) },
                hidden = hidden,
                loading = false,
                onSetHidden = mainViewModel::setGroupsHidden,
                onDismiss = onDismiss
            )
        }
        CategoryKind.VOD, CategoryKind.SERIES -> {
            // Kataloge werden sonst erst beim Öffnen von Filme/Serien geladen.
            LaunchedEffect(Unit) { mainViewModel.ensureCatalogs() }
            val isVod = kind == CategoryKind.VOD
            val categories by if (isVod) mainViewModel.vodCategories else mainViewModel.seriesCategories
            val counts by if (isVod) mainViewModel.vodCategoryCounts else mainViewModel.seriesCategoryCounts
            val hidden by if (isVod) mainViewModel.hiddenVodCategories else mainViewModel.hiddenSeriesCategories
            val state by if (isVod) mainViewModel.vodState else mainViewModel.seriesState
            CategoryManagerDialog(
                title = if (isVod) "Film-Kategorien" else "Serien-Kategorien",
                categories = categories.map { (id, name) -> ManagedCategory(id, name, counts[id]) },
                hidden = hidden,
                loading = state is ContentState.Loading,
                onSetHidden = { keys, hide ->
                    if (isVod) mainViewModel.setVodCategoriesHidden(keys, hide)
                    else mainViewModel.setSeriesCategoriesHidden(keys, hide)
                },
                onDismiss = onDismiss
            )
        }
    }
}

/** Hinweistext unter einer Einstellung (auf dem TV gut lesbar: bodySmall 14 sp, gedämpfte Farbe). */
@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
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
    badge: String? = null,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (badge != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
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
