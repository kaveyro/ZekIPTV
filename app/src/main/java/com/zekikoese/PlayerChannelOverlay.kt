package com.zekikoese

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

/** Sprungweite (Einträge) beim Blättern mit den Vor-/Zurückspulen-Tasten. */
private const val OVERLAY_PAGE_JUMP = 10

/**
 * Senderlisten-Overlay im Player (öffnet mit DPAD-LINKS bei Live-Wiedergabe):
 * Zappen ohne den Player zu verlassen. Links eine vertikale Gruppen-Spalte (Filter greift
 * beim Fokussieren), rechts die Senderliste — wie im Live-TV-Bereich. Der Gruppenfilter
 * wird im ViewModel gemerkt und überlebt Schließen/Öffnen des Overlays; der Filter des
 * Live-Bereichs bleibt unangetastet.
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

        val groups by mainViewModel.groups
        val groupCounts by mainViewModel.groupCounts
        val allCount by mainViewModel.unhiddenChannelCount
        val overlayGroup by mainViewModel.playerOverlayGroup
        // Gemerkte Gruppe validieren — sie kann inzwischen ausgeblendet/entfernt worden sein.
        val group = overlayGroup?.takeIf { it in groups }
        val channels = mainViewModel.channelsForGroup(group)
        val favorites by mainViewModel.favorites

        val listState = rememberLazyListState()
        val rowFocusRequester = remember { FocusRequester() }

        // Fokus-Ziel in der Liste: beim Öffnen der laufende Sender, bei FF/REW-Sprüngen der
        // Sprungindex. focusTrigger stößt Scroll+Fokus an. Bei Gruppenwechseln wird nur
        // gescrollt — der Fokus bleibt in der Kategorie-Spalte, sonst würde er beim
        // Durchlaufen der Kategorien in die Liste gerissen.
        var focusedIndex by remember { mutableStateOf(0) }
        var focusTarget by remember { mutableStateOf(0) }
        var focusTrigger by remember { mutableStateOf(0) }
        var initialFocusDone by remember { mutableStateOf(false) }

        LaunchedEffect(group) {
            focusTarget = channels.indexOfFirst { it.url == currentUrl }.coerceAtLeast(0)
            if (!initialFocusDone) {
                initialFocusDone = true
                focusTrigger++
            } else {
                listState.scrollToItem(focusTarget)
            }
        }

        // Fokus mit Frame-Wiederholung anfordern: direkt nach scrollToItem ist die Zeile u.U.
        // noch nicht attached, und die PlayerView (Android-View) gibt den Fokus nicht sofort her.
        LaunchedEffect(focusTrigger) {
            if (focusTrigger == 0 || channels.isEmpty()) return@LaunchedEffect
            listState.scrollToItem(focusTarget)
            repeat(10) {
                awaitFrame()
                if (runCatching { rowFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }

        fun jumpBy(delta: Int) {
            if (channels.isEmpty()) return
            focusTarget = (focusedIndex + delta).coerceIn(0, channels.lastIndex)
            focusTrigger++
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                    .padding(20.dp)
            ) {
                // Gruppen-Spalte: hoch/runter filtert beim Fokussieren, RECHTS/OK = Senderliste.
                if (groups.isNotEmpty()) {
                    Column {
                        Text(
                            text = "Kategorien",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        CategoryColumn(
                            entries = buildList {
                                add(CategoryEntry(null, "Alle", allCount))
                                groups.forEach { add(CategoryEntry(it, it, groupCounts[it])) }
                            },
                            selectedKey = group,
                            onSelect = { mainViewModel.playerOverlayGroup.value = it },
                            width = 208.dp,
                            // LINKS darf den Fokus nicht an die dahinterliegende PlayerView verlieren.
                            guardLeft = true
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                }

                Column(modifier = Modifier.width(400.dp).fillMaxHeight()) {
                    Text(
                        text = "Senderliste",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            // RECHTS schließt das Overlay (zurück zum Player); FF/REW blättert.
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionRight -> { onDismiss(); true }
                                    Key.MediaFastForward -> { jumpBy(+OVERLAY_PAGE_JUMP); true }
                                    Key.MediaRewind -> { jumpBy(-OVERLAY_PAGE_JUMP); true }
                                    else -> false
                                }
                            }
                    ) {
                        // Kein URL-Key: reale Playlists enthalten denselben Stream mehrfach.
                        itemsIndexed(channels) { index, channel ->
                            ChannelRow(
                                channel = channel,
                                isFavorite = channel.url in favorites,
                                epg = mainViewModel.epgFor(channel),
                                modifier = if (index == focusTarget) {
                                    Modifier.focusRequester(rowFocusRequester)
                                } else Modifier,
                                onClick = {
                                    mainViewModel.selectChannel(channel)
                                    onDismiss()
                                },
                                onLongClick = {},
                                onFocusChange = { focused -> if (focused) focusedIndex = index }
                            )
                        }
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
