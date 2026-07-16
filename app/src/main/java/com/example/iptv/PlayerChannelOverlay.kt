package com.example.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.android.awaitFrame

/**
 * Senderlisten-Overlay im Player (öffnet mit DPAD-LINKS bei Live-Wiedergabe):
 * Zappen ohne den Player zu verlassen — mit Gruppenfilter und "Jetzt läuft"-EPG.
 * Der Gruppenfilter ist lokal und lässt den Filter des Live-Bereichs unangetastet.
 */
@Composable
fun PlayerChannelOverlay(
    visible: Boolean,
    mainViewModel: MainViewModel,
    currentUrl: String?,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
    ) {
        // Zurück-Taste schließt nur das Overlay, nicht den Player.
        BackHandler(onBack = onDismiss)

        var group by remember { mutableStateOf<String?>(null) }
        val groups by mainViewModel.groups
        val channels = mainViewModel.channelsForGroup(group)
        val favorites by mainViewModel.favorites

        val listState = rememberLazyListState()
        val rowFocusRequester = remember { FocusRequester() }
        val targetIndex = channels.indexOfFirst { it.url == currentUrl }.coerceAtLeast(0)

        // Beim Öffnen (und nach Gruppenwechsel) zum laufenden Sender scrollen und fokussieren.
        // Fokus mit Frame-Wiederholung anfordern: direkt nach scrollToItem ist die Zeile u.U.
        // noch nicht attached, und die PlayerView (Android-View) gibt den Fokus nicht sofort her.
        LaunchedEffect(group) {
            if (channels.isEmpty()) return@LaunchedEffect
            listState.scrollToItem(targetIndex)
            repeat(10) {
                awaitFrame()
                if (runCatching { rowFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(440.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .padding(20.dp)
            ) {
                Text(
                    text = "Senderliste",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (groups.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            GroupChip("Alle", selected = group == null) { group = null }
                        }
                        items(groups) { g ->
                            GroupChip(g, selected = group == g) { group = g }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        // RECHTS aus der Liste heraus schließt das Overlay (zurück zum Player).
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                onDismiss()
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    // Kein URL-Key: reale Playlists enthalten denselben Stream mehrfach.
                    itemsIndexed(channels) { index, channel ->
                        ChannelRow(
                            channel = channel,
                            isFavorite = channel.url in favorites,
                            epg = mainViewModel.epgFor(channel),
                            modifier = if (index == targetIndex) {
                                Modifier.focusRequester(rowFocusRequester)
                            } else Modifier,
                            onClick = {
                                mainViewModel.selectChannel(channel)
                                onDismiss()
                            },
                            onLongClick = {}
                        )
                    }
                }
            }

            // Freie Fläche rechts: Antippen (Touch-Geräte) schließt das Overlay.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
    }
}
