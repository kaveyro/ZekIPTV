package com.zekikoese

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zekikoese.ui.tvFocusFrame

private data class RailEntry(val dest: NavDestination, val label: String, val iconRes: Int)

/**
 * Linke Navigations-Rail: eingeklappt nur Icons, expandiert bei D-Pad-Fokus.
 * LINKS vom Inhalt landet hier, RECHTS kehrt zum Inhalt zurück; navigiert wird
 * ausschließlich per OK-Klick (nie durch bloßes Fokussieren).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NavRail(
    current: NavDestination,
    xtreamAvailable: Boolean,
    onNavigate: (NavDestination) -> Unit,
    focusRequester: FocusRequester? = null, // Ziel für „Zurück auf Home“ (führt zum aktuellen Eintrag)
    onFocusChange: (Boolean) -> Unit = {}
) {
    val entries = buildList {
        add(RailEntry(NavDestination.SEARCH, "Suche", R.drawable.ic_nav_search))
        add(RailEntry(NavDestination.HOME, "Home", R.drawable.ic_nav_home))
        add(RailEntry(NavDestination.LIVE, "Live-TV", R.drawable.ic_nav_live))
        add(RailEntry(NavDestination.GUIDE, "TV-Guide", R.drawable.ic_nav_guide))
        if (xtreamAvailable) {
            add(RailEntry(NavDestination.MOVIES, "Filme", R.drawable.ic_nav_movies))
            add(RailEntry(NavDestination.SERIES, "Serien", R.drawable.ic_nav_series))
        }
        add(RailEntry(NavDestination.SETTINGS, "Einstellungen", R.drawable.ic_nav_settings))
    }

    var railFocused by remember { mutableStateOf(false) }
    // Ein FocusRequester je Eintrag: Betreten der Rail landet immer auf dem aktuellen Bereich.
    val itemFocus = remember { mutableMapOf<NavDestination, FocusRequester>() }
    fun focusOf(dest: NavDestination) = itemFocus.getOrPut(dest) { FocusRequester() }
    val width by animateDpAsState(
        targetValue = if (railFocused) 232.dp else 72.dp,
        animationSpec = tween(180),
        label = "railWidth"
    )

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .background(MaterialTheme.colorScheme.surface)
            // Beim Betreten der Rail (LINKS aus dem Inhalt oder „Zurück“ auf Home) landet der
            // Fokus auf dem Eintrag des aktuellen Bereichs.
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { onEnter = { focusOf(current).requestFocus() } }
            .focusGroup()
            .onFocusChanged {
                railFocused = it.hasFocus
                onFocusChange(it.hasFocus)
            }
            .padding(horizontal = 8.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, bottom = 24.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            if (railFocused) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "ZekIPTV",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }

        entries.forEach { entry ->
            RailItem(
                entry = entry,
                selected = current == entry.dest,
                expanded = railFocused,
                modifier = Modifier.focusRequester(focusOf(entry.dest)),
                onClick = { onNavigate(entry.dest) }
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RailItem(
    entry: RailEntry,
    selected: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (expanded) 4.dp else 2.dp)
            .focusProperties { left = FocusRequester.Cancel }
            .tvFocusFrame(
                onClick = onClick,
                shape = CircleShape,
                restColor = Color.Transparent,
                isSelected = selected
            )
            .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
    ) {
        Icon(
            painter = painterResource(entry.iconRes),
            contentDescription = entry.label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        if (expanded) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
    }
}
