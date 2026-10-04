package com.zekikoese

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.zekikoese.ui.LocalIsTv
import com.zekikoese.ui.tvFocusFrame
import kotlinx.coroutines.android.awaitFrame
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val HOUR_MS = 60 * 60_000L
private const val GUIDE_PAST_MS = 3 * HOUR_MS     // Rückblick (Catch-up direkt aus dem Raster)
private const val GUIDE_FUTURE_MS = 12 * HOUR_MS  // Vorschau

/** Maße des Rasters: TV großzügig für 3 m Abstand, Handy kompakt. */
private data class GuideDims(val channelWidth: Dp, val perMinute: Dp, val rowHeight: Dp)

/**
 * TV-Guide: Sender (Zeilen) × Zeitachse. Statt jede Zeile einzeln zu scrollen, verschiebt ein
 * gemeinsames Zeitfenster ([windowStart]) alle Zeilen gleichzeitig. Alle Sendungen des geladenen
 * Bereichs sind komponiert (nur weggeclippt), damit die D-Pad-Fokussuche auch die Sendung
 * außerhalb des Fensters findet; beim Fokussieren rückt das Fenster nach.
 * OK auf eine laufende Sendung schaltet um, auf eine vergangene (mit Archiv) startet Catch-up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideScreen(mainViewModel: MainViewModel) {
    val isTv = LocalIsTv.current
    val channels by mainViewModel.visibleChannels
    // Stabile Keys (Fokus/Position bleiben beim Kategoriewechsel erhalten).
    val channelKeys = remember(channels) { stableChannelKeys(channels) }
    val groups by mainViewModel.groups
    val groupCounts by mainViewModel.groupCounts
    val allCount by mainViewModel.unhiddenChannelCount
    val selectedGroup by mainViewModel.selectedGroup
    val epgAvailable by mainViewModel.epgAvailable
    val lastWatchedUrl by mainViewModel.lastWatchedUrl
    val tick by mainViewModel.epgTick // Minuten-Ticker: "Jetzt"-Linie und laufende Sendung aktuell halten
    val now = remember(tick) { System.currentTimeMillis() }

    val dims = if (isTv) GuideDims(240.dp, 6.dp, 72.dp) else GuideDims(116.dp, 4.dp, 64.dp)
    val rangeStart = remember { floorToHalfHour(System.currentTimeMillis()) - GUIDE_PAST_MS }
    val rangeEnd = remember { rangeStart + GUIDE_PAST_MS + GUIDE_FUTURE_MS }
    var windowStart by remember { mutableLongStateOf(floorToHalfHour(System.currentTimeMillis()) - HALF_HOUR_MS) }
    var showCategories by remember { mutableStateOf(false) }
    var programmeDialog by remember { mutableStateOf<Pair<Channel, EpgProgramme>?>(null) }

    val categoryEntries = buildList {
        add(CategoryEntry(null, "Alle", allCount))
        groups.forEach { add(CategoryEntry(it, it, groupCounts[it])) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = if (isTv) 32.dp else 16.dp, top = 24.dp, bottom = 16.dp)
    ) {
        ScreenTitle("TV-Guide")

        if (channels.isEmpty() && groups.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.DateRange,
                title = "Keine Sender",
                subtitle = "Lade zuerst eine Playlist — dann erscheint hier das Programm."
            )
            return@Column
        }
        if (!epgAvailable) {
            EmptyState(
                icon = Icons.Filled.DateRange,
                title = "Kein Programmführer geladen",
                subtitle = "Füge in den Einstellungen eine EPG-Quelle hinzu (Xtream-Anbieter liefern sie automatisch).",
                action = {
                    TvButton(text = "Zu den Einstellungen", onClick = { mainViewModel.navigate(NavDestination.SETTINGS) })
                }
            )
            return@Column
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val gridWidth = (maxWidth - dims.channelWidth).coerceAtLeast(dims.perMinute * 30)
            val spanMs = ((gridWidth / dims.perMinute) * 60_000).toLong()
            val maxWindow = (rangeEnd - spanMs).coerceAtLeast(rangeStart)
            fun moveWindow(target: Long) {
                windowStart = target.coerceIn(rangeStart, maxWindow)
            }

            val totalWidth = dims.perMinute * ((rangeEnd - rangeStart) / 60_000f)
            val perMinutePx = with(LocalDensity.current) { dims.perMinute.toPx() }
            val offsetMinutes by animateFloatAsState(
                targetValue = (windowStart.coerceIn(rangeStart, maxWindow) - rangeStart) / 60_000f,
                label = "guideWindow"
            )
            // Verschiebung erst in der Layout-Phase lesen — kein Recompose aller Zeilen pro Frame.
            val shift: Density.() -> IntOffset = { IntOffset(-(offsetMinutes * perMinutePx).roundToInt(), 0) }

            val listState = rememberLazyListState()
            val guideFocus = remember { FocusRequester() }
            val pendingFocus by mainViewModel.pendingGuideFocus
            val focusRow = channels.indexOfFirst { it.url == lastWatchedUrl }.coerceAtLeast(0)

            // Beim Betreten: zur laufenden Sendung des zuletzt gesehenen Senders (TV: dort fokussieren).
            LaunchedEffect(pendingFocus, channels.size) {
                if (!pendingFocus || channels.isEmpty()) return@LaunchedEffect
                moveWindow(floorToHalfHour(System.currentTimeMillis()) - HALF_HOUR_MS)
                listState.scrollToItem((focusRow - 1).coerceAtLeast(0))
                if (isTv) {
                    repeat(10) {
                        awaitFrame()
                        if (runCatching { guideFocus.requestFocus() }.isSuccess) {
                            mainViewModel.pendingGuideFocus.value = false
                            return@LaunchedEffect
                        }
                    }
                }
                mainViewModel.pendingGuideFocus.value = false
            }

            Column(modifier = Modifier.fillMaxSize()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    TvButton(
                        text = categoryEntries.firstOrNull { it.key == selectedGroup }?.label ?: "Alle",
                        onClick = { showCategories = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        icon = Icons.AutoMirrored.Filled.List
                    )
                    TvButton(
                        text = "Früher",
                        onClick = { moveWindow(windowStart - HOUR_MS) },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                    TvButton(
                        text = "Jetzt",
                        onClick = { moveWindow(floorToHalfHour(System.currentTimeMillis()) - HALF_HOUR_MS) }
                    )
                    TvButton(
                        text = "Später",
                        onClick = { moveWindow(windowStart + HOUR_MS) },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Zeitachse
                Row(modifier = Modifier.fillMaxWidth().height(28.dp)) {
                    // Tag des sichtbaren Fensters in der freien Ecke über der Senderspalte.
                    Text(
                        text = dayLabel(windowStart, now),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.width(dims.channelWidth).padding(start = 6.dp)
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Box(
                            modifier = Modifier
                                .wrapContentWidth(Alignment.Start, unbounded = true)
                                .requiredWidth(totalWidth)
                                .fillMaxHeight()
                                .offset(shift)
                        ) {
                            val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                            var t = rangeStart
                            while (t < rangeEnd) {
                                val x = dims.perMinute * ((t - rangeStart) / 60_000f)
                                Box(
                                    modifier = Modifier
                                        .offset(x = x)
                                        .width(1.dp)
                                        .height(8.dp)
                                        .align(Alignment.BottomStart)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                )
                                Text(
                                    text = timeFormat.format(Date(t)),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.offset(x = x + 4.dp)
                                )
                                t += HALF_HOUR_MS
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            // Touch: horizontal wischen verschiebt die Zeitachse (vertikal scrollt die Liste).
                            if (isTv) Modifier else Modifier.pointerInput(spanMs) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    moveWindow(windowStart - (dragAmount / perMinutePx * 60_000).toLong())
                                }
                            }
                        )
                ) {
                    itemsIndexed(channels, key = { index, _ -> channelKeys[index] }) { index, channel ->
                        val programmes = remember(channel, tick, rangeStart) {
                            programmesInRange(mainViewModel.programmesFor(channel), rangeStart, rangeEnd)
                        }
                        GuideRow(
                            channel = channel,
                            number = mainViewModel.channelNumberOf(channel),
                            programmes = programmes,
                            now = now,
                            rangeStart = rangeStart,
                            rangeEnd = rangeEnd,
                            windowStart = windowStart,
                            dims = dims,
                            totalWidth = totalWidth,
                            shift = shift,
                            catchupFrom = mainViewModel.catchupFrom(channel),
                            isCurrentChannel = channel.url == lastWatchedUrl,
                            focusRequester = if (index == focusRow) guideFocus else null,
                            onChannelClick = { mainViewModel.selectChannel(channel, channels) },
                            onProgrammeClick = { programme ->
                                if (now >= programme.startMs && now < programme.stopMs) {
                                    // Läuft gerade: direkt umschalten.
                                    mainViewModel.selectChannel(channel, channels)
                                } else {
                                    programmeDialog = channel to programme
                                }
                            },
                            onProgrammeFocused = { programme ->
                                moveWindow(
                                    guideWindowFor(
                                        windowStart, spanMs, programme.startMs, programme.stopMs, rangeStart, rangeEnd
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCategories) {
        CategoryPickerDialog(
            entries = categoryEntries,
            selectedKey = selectedGroup,
            onSelect = { mainViewModel.selectGroup(it) },
            onDismiss = { showCategories = false }
        )
    }

    programmeDialog?.let { (channel, programme) ->
        val catchup = mainViewModel.catchupFrom(channel)
        val replayable = catchup != null && programme.stopMs <= now && programme.startMs >= catchup
        ActionsDialog(
            title = programme.title,
            subtitle = "${channel.name} · ${programmeTimeLabel(programme, now)}",
            actions = buildList {
                if (replayable) {
                    add(DialogAction("Aus dem Archiv abspielen") { mainViewModel.playCatchup(channel, programme) })
                }
                add(DialogAction("Sender jetzt einschalten") { mainViewModel.selectChannel(channel, channels) })
                add(DialogAction("Tagesprogramm des Senders") { mainViewModel.openEpgFor(channel) })
            },
            onDismiss = { programmeDialog = null }
        )
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    number: Int?,
    programmes: List<EpgProgramme>,
    now: Long,
    rangeStart: Long,
    rangeEnd: Long,
    windowStart: Long,
    dims: GuideDims,
    totalWidth: Dp,
    shift: Density.() -> IntOffset,
    catchupFrom: Long?,
    isCurrentChannel: Boolean,
    focusRequester: FocusRequester?,
    onChannelClick: () -> Unit,
    onProgrammeClick: (EpgProgramme) -> Unit,
    onProgrammeFocused: (EpgProgramme) -> Unit
) {
    val isTv = LocalIsTv.current
    // Fokusziel der Zeile: laufende Sendung, sonst der Platzhalter bzw. die Senderzelle.
    val nowProgramme = programmes.firstOrNull { now >= it.startMs && now < it.stopMs }

    Row(modifier = Modifier.fillMaxWidth().height(dims.rowHeight).padding(vertical = 2.dp)) {
        // Senderzelle: OK schaltet live um.
        Row(
            modifier = Modifier
                .width(dims.channelWidth)
                .fillMaxHeight()
                .padding(end = 4.dp)
                .then(
                    if (focusRequester != null && nowProgramme == null && programmes.isNotEmpty()) {
                        Modifier.focusRequester(focusRequester)
                    } else Modifier
                )
                .tvFocusFrame(
                    onClick = onChannelClick,
                    shape = RoundedCornerShape(8.dp),
                    isSelected = isCurrentChannel
                )
                .padding(horizontal = if (isTv) 10.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val logoSize = if (isTv) 40.dp else 28.dp
            // Handy: ohne Logo kein Platzhalter — die schmale Spalte braucht den Platz für den Namen.
            if (isTv || channel.logo != null) {
                ImageOrInitial(
                    model = channel.logo,
                    name = channel.name,
                    textStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.size(logoSize).clip(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(if (isTv) 10.dp else 6.dp))
            }
            Column {
                if (number != null && isTv) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = channel.name,
                    style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                    color = if (isCurrentChannel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (isTv) 1 else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Sendungen: gemeinsam verschobene, geclippte Zeitleiste.
        Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            Box(
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .requiredWidth(totalWidth)
                    .fillMaxHeight()
                    .offset(shift)
            ) {
                if (programmes.isEmpty()) {
                    // Ohne Programmdaten eine durchgehende Zelle — hält die D-Pad-Navigation flüssig.
                    GuideCell(
                        x = 0.dp,
                        width = totalWidth,
                        stickyStart = dims.perMinute * ((windowStart - rangeStart) / 60_000f),
                        restColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        focusRequester = focusRequester,
                        onClick = onChannelClick,
                        onFocused = {}
                    ) {
                        Text(
                            text = "Keine Programmdaten",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                programmes.forEach { programme ->
                    val start = maxOf(programme.startMs, rangeStart)
                    val stop = minOf(programme.stopMs, rangeEnd)
                    val isNow = programme == nowProgramme
                    val isPast = programme.stopMs <= now
                    val replayable = catchupFrom != null && isPast && programme.startMs >= catchupFrom
                    val x = dims.perMinute * ((start - rangeStart) / 60_000f)
                    val width = (dims.perMinute * ((stop - start) / 60_000f)).coerceAtLeast(4.dp)
                    // Titel am linken Fensterrand "kleben" lassen, wenn die Sendung links angeschnitten ist.
                    val sticky = (dims.perMinute * ((windowStart - start) / 60_000f))
                        .coerceIn(0.dp, (width - 56.dp).coerceAtLeast(0.dp))
                    GuideCell(
                        x = x,
                        width = width,
                        stickyStart = sticky,
                        restColor = when {
                            isNow -> MaterialTheme.colorScheme.secondaryContainer
                            isPast -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        focusRequester = if (isNow) focusRequester else null,
                        onClick = { onProgrammeClick(programme) },
                        onFocused = { onProgrammeFocused(programme) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (replayable) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_fast_rewind),
                                    contentDescription = "Aus dem Archiv abspielbar",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                text = programme.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isPast && !replayable) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = programmeTimeLabel(programme, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
                // "Jetzt"-Linie
                if (now in rangeStart until rangeEnd) {
                    Box(
                        modifier = Modifier
                            .offset(x = dims.perMinute * ((now - rangeStart) / 60_000f))
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

/** Fokussierbare Sendungszelle; [stickyStart] rückt den Inhalt an den sichtbaren linken Rand. */
@Composable
private fun GuideCell(
    x: Dp,
    width: Dp,
    stickyStart: Dp,
    restColor: Color,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .offset(x = x)
            .width(width)
            .fillMaxHeight()
            .padding(end = 3.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusFrame(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1f, // im Raster nicht vergrößern — sonst überdeckt die Zelle die Nachbarn
                restColor = restColor,
                onFocusChange = { focused -> if (focused) onFocused() }
            )
            .padding(start = 10.dp + stickyStart, end = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column { content() }
    }
}

/** "18:00 – 19:30", bei anderen Tagen mit Wochentag ("Fr 18:00 – 19:30"). */
internal fun programmeTimeLabel(programme: EpgProgramme, now: Long): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    val range = "${time.format(Date(programme.startMs))} – ${time.format(Date(programme.stopMs))}"
    return if (sameDay(programme.startMs, now)) range
    else SimpleDateFormat("EE", Locale.GERMAN).format(Date(programme.startMs)) + " " + range
}

/** Tag des sichtbaren Fensters: "Heute", "Morgen" oder "Fr, 03.10.". */
private fun dayLabel(windowStart: Long, now: Long): String {
    val tomorrow = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
    return when {
        sameDay(windowStart, now) -> "Heute"
        sameDay(windowStart, tomorrow) -> "Morgen"
        sameDay(windowStart, yesterday) -> "Gestern"
        else -> SimpleDateFormat("EE, dd.MM.", Locale.GERMAN).format(Date(windowStart))
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}
