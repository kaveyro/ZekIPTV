package com.zekikoese

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.tvFocusFrame
import kotlinx.coroutines.android.awaitFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fokussierbare Dialog-Zeile mit sichtbarem D-Pad-Fokus. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DialogRow(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .tvFocusFrame(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                restColor = Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

/** Dialog zum Hinzufügen/Bearbeiten einer Playlist (Name + URL). */
@Composable
fun PlaylistInputDialog(
    onConfirm: (name: String, url: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    val widthModifier = if (isTv) Modifier.width(500.dp) else Modifier.fillMaxWidth()

    LaunchedEffect(Unit) {
        awaitFrame()
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp).then(widthModifier)) {
                Text(
                    text = "Playlist hinzufügen",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Anzeigename (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("M3U-URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TvButton(
                        text = "Abbrechen",
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 8.dp),
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    TvButton(
                        text = "Hinzufügen",
                        onClick = { onConfirm(name, url); onDismiss() }
                    )
                }
            }
        }
    }
}

/** Einfacher Dialog für eine einzelne Texteingabe (z. B. EPG-URL). */
@Composable
fun SingleTextInputDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val isTv = LocalIsTv.current
    val widthModifier = if (isTv) Modifier.width(500.dp) else Modifier.fillMaxWidth()

    LaunchedEffect(Unit) {
        awaitFrame()
        focusRequester.requestFocus()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp).then(widthModifier)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TvButton(
                        text = "Abbrechen",
                        onClick = onDismiss,
                        modifier = Modifier.padding(end = 8.dp),
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    TvButton(
                        text = "Hinzufügen",
                        onClick = { onConfirm(text); onDismiss() }
                    )
                }
            }
        }
    }
}

/** Lang-Druck-Menü eines Senders: Tagesprogramm und Favoriten-Toggle. */
@Composable
fun ChannelActionsDialog(
    channel: Channel,
    isFavorite: Boolean,
    hasEpg: Boolean,
    onShowEpg: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit
) {
    // TV: feste 10-Fuß-Breite; Smartphone: an die Dialogbreite des Systems anpassen.
    val widthModifier = if (LocalIsTv.current) Modifier.width(420.dp) else Modifier.fillMaxWidth()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp).then(widthModifier)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                if (hasEpg) {
                    DialogRow("Tagesprogramm anzeigen") { onShowEpg() }
                }
                DialogRow(if (isFavorite) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten hinzufügen") {
                    onToggleFavorite()
                    onDismiss()
                }
                DialogRow("Schließen") { onDismiss() }
            }
        }
    }
}

/** Tagesprogramm eines Senders aus den geladenen EPG-Daten. */
@Composable
fun EpgDayDialog(
    channelName: String,
    programmes: List<EpgProgramme>,
    onDismiss: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now = System.currentTimeMillis()
    val isTv = LocalIsTv.current
    val widthModifier = if (isTv) Modifier.width(560.dp) else Modifier.fillMaxWidth()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(24.dp).then(widthModifier)) {
                Text(
                    text = "Programm: $channelName",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = if (isTv) 560.dp else 440.dp)) {
                    items(programmes) { programme ->
                        val isNow = now >= programme.startMs && now < programme.stopMs
                        DialogRow(
                            label = "%s – %s   %s".format(
                                timeFormat.format(Date(programme.startMs)),
                                timeFormat.format(Date(programme.stopMs)),
                                programme.title
                            ),
                            highlighted = isNow,
                            onClick = {}
                        )
                    }
                }
                DialogRow("Schließen") { onDismiss() }
            }
        }
    }
}
