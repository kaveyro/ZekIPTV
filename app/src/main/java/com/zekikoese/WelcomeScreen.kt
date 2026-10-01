package com.zekikoese

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.tvFocusFrame
import kotlinx.coroutines.android.awaitFrame

/**
 * Erststart-Assistent (noch keine Playlist): statt eines leeren Home-Bildschirms die drei Wege
 * zu den eigenen Sendern — M3U-Adresse, Xtream-Zugang oder ein vorhandenes Backup. Verschwindet
 * von selbst, sobald eine Playlist gespeichert ist.
 */
@Composable
fun WelcomeScreen(mainViewModel: MainViewModel) {
    val isTv = LocalIsTv.current
    val backupInfo by mainViewModel.backupInfo
    val firstOption = remember { FocusRequester() }
    val skipFocus = remember { FocusRequester() }

    // Backup per System-Dateidialog; ohne Dateidialog (viele Fire-TV-Geräte) aus dem App-Ordner.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) mainViewModel.importBackup(uri) }

    LaunchedEffect(isTv) {
        if (!isTv) return@LaunchedEffect
        repeat(10) {
            awaitFrame()
            if (runCatching { firstOption.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    fun openPlaylistDialog(xtream: Boolean) {
        mainViewModel.playlistDialogXtream.value = xtream
        mainViewModel.showPlaylistDialog.value = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Liegt außerhalb des Scaffolds — Status-/Navigationsleiste selbst freihalten.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isTv) 64.dp else 20.dp, vertical = if (isTv) 24.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(if (isTv) 64.dp else 72.dp)
        )
        Spacer(Modifier.height(if (isTv) 12.dp else 20.dp))
        Text(
            text = "Willkommen bei ZekIPTV",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "ZekIPTV bringt keine eigenen Sender mit. Du brauchst die Playlist-Adresse oder die " +
                "Zugangsdaten deines Anbieters — wie möchtest du starten?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 720.dp)
        )
        Spacer(Modifier.height(if (isTv) 24.dp else 28.dp))

        val options: List<Triple<ImageVector, String, String>> = listOf(
            Triple(Icons.AutoMirrored.Filled.List, "M3U-Playlist", "Adresse einer .m3u/.m3u8-Liste eingeben"),
            Triple(Icons.Filled.AccountCircle, "Xtream-Login", "Server, Benutzername und Passwort — mit Filmen, Serien und Catch-up"),
            Triple(Icons.Filled.Share, "Backup importieren", "Playlists, Favoriten und Einstellungen aus einer Sicherung")
        )
        val actions = listOf(
            { openPlaylistDialog(xtream = false) },
            { openPlaylistDialog(xtream = true) },
            {
                try {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                } catch (e: ActivityNotFoundException) {
                    mainViewModel.importBackup()
                }
            }
        )
        // TV: drei Karten nebeneinander; Handy: untereinander.
        if (isTv) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                options.forEachIndexed { index, (icon, title, text) ->
                    WelcomeOption(
                        icon = icon,
                        title = title,
                        description = text,
                        modifier = Modifier
                            .width(240.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstOption) else Modifier)
                            // RUNTER von jeder Karte zum mittigen Knopf (räumlich liegt er nur unter der mittleren).
                            .focusProperties { down = skipFocus },
                        onClick = actions[index]
                    )
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, (icon, title, text) ->
                    WelcomeOption(
                        icon = icon,
                        title = title,
                        description = text,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = actions[index]
                    )
                }
            }
        }

        if (backupInfo.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = backupInfo,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(if (isTv) 20.dp else 24.dp))
        TvButton(
            text = "Zuerst die Einstellungen öffnen",
            modifier = Modifier.focusRequester(skipFocus),
            onClick = {
                mainViewModel.welcomeDismissed.value = true
                mainViewModel.navigate(NavDestination.SETTINGS)
            },
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun WelcomeOption(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isTv = LocalIsTv.current
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (isTv) 40.dp else 32.dp)
        )
    }
    if (isTv) {
        Column(
            modifier = modifier
                .tvFocusFrame(onClick = onClick, shape = RoundedCornerShape(16.dp), focusedScale = 1.04f)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Row(
            modifier = modifier
                .tvFocusFrame(onClick = onClick, shape = RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
