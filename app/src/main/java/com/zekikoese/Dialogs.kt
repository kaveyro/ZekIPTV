package com.zekikoese

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.tvFocusFrame
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
