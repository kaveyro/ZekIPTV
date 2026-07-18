package com.zekikoese

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zekikoese.ui.tvFocusFrame

/** Eintrag der Kategorie-Spalte; key = null steht für "Alle". */
data class CategoryEntry(val key: String?, val label: String, val count: Int? = null)

/**
 * Vertikale Kategorie-Spalte (TiviMate-Muster): HOCH/RUNTER wandert durch die Kategorien,
 * der Filter greift schon beim Fokussieren (kein OK nötig). OK bzw. RECHTS wechselt in den
 * Inhalt rechts daneben.
 *
 * Bewusst OHNE focusRestorer: In Kombination mit verticalScroll schlägt dessen
 * Fokus-Wiederherstellung beim Wieder-Betreten der Spalte fehl und der D-Pad-Fokus geht
 * komplett verloren (keine Reaktion mehr, bis man zur Rail und zurück wechselt). Der
 * räumliche Fokus-Einstieg (Compose-Standard) landet ohnehin auf dem nächstgelegenen Eintrag.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CategoryColumn(
    entries: List<CategoryEntry>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 236.dp,
    guardLeft: Boolean = false
) {
    // Die Auswahl NICHT direkt im Fokus-Event schreiben: Der Gruppenwechsel stößt eine große
    // Recomposition an (Senderliste refiltert) — mitten in der laufenden Fokus-Transaktion
    // kann das den Fokuswechsel abbrechen. Stattdessen den fokussierten Eintrag nur merken
    // und die Auswahl nachgelagert anwenden.
    var focusTick by remember { mutableStateOf(0) }
    var focusedEntry by remember { mutableStateOf<CategoryEntry?>(null) }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) focusedEntry?.let { onSelect(it.key) }
    }

    // Beim Betreten der Spalte (LINKS aus der Liste / RECHTS von der Rail) immer auf der
    // AKTIVEN Kategorie landen — nicht auf dem räumlich nächsten Eintrag. Sonst "springt"
    // die Auswahl nach dem Scrollen in der Senderliste auf eine Kategorie weiter unten.
    val selectedRequester = remember { FocusRequester() }
    val hasSelectedEntry = entries.any { it.key == selectedKey }

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .focusProperties {
                enter = { if (hasSelectedEntry) selectedRequester else FocusRequester.Default }
            }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        entries.forEach { entry ->
            val selected = selectedKey == entry.key
            CategoryRow(
                entry = entry,
                selected = selected,
                guardLeft = guardLeft,
                focusRequester = if (selected) selectedRequester else null,
                onFocused = {
                    focusedEntry = entry
                    focusTick++
                }
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CategoryRow(
    entry: CategoryEntry,
    selected: Boolean,
    guardLeft: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // Im Player-Overlay darf LINKS den Fokus nicht an die PlayerView verlieren.
            .focusProperties { if (guardLeft) left = FocusRequester.Cancel }
            .tvFocusFrame(
                // OK springt in den Inhalt rechts — gefiltert ist beim Fokussieren bereits.
                onClick = { focusManager.moveFocus(FocusDirection.Right) },
                shape = RoundedCornerShape(10.dp),
                restColor = if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
                onFocusChange = { focused -> if (focused) onFocused() }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = entry.label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f)
        )
        if (entry.count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.count.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
