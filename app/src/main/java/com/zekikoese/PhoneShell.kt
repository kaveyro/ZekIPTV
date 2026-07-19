package com.zekikoese

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class PhoneNavEntry(val dest: NavDestination, val label: String, val iconRes: Int)

/**
 * Smartphone-Shell: material3-Scaffold mit unterer Navigationsleiste statt der TV-Rail.
 * Nutzt dieselben Ziele/Icons und dasselbe MainViewModel.navigate() wie die NavRail —
 * nur die Darstellung unterscheidet sich.
 */
@Composable
fun PhoneMainScreen(
    mainViewModel: MainViewModel,
    content: @Composable () -> Unit
) {
    val destination by mainViewModel.currentDestination
    val xtreamAvailable by mainViewModel.xtreamAvailable

    val entries = buildList {
        add(PhoneNavEntry(NavDestination.SEARCH, "Suche", R.drawable.ic_nav_search))
        add(PhoneNavEntry(NavDestination.HOME, "Home", R.drawable.ic_nav_home))
        add(PhoneNavEntry(NavDestination.LIVE, "Live", R.drawable.ic_nav_live))
        if (xtreamAvailable) {
            add(PhoneNavEntry(NavDestination.MOVIES, "Filme", R.drawable.ic_nav_movies))
            add(PhoneNavEntry(NavDestination.SERIES, "Serien", R.drawable.ic_nav_series))
        }
        add(PhoneNavEntry(NavDestination.SETTINGS, "Mehr", R.drawable.ic_nav_settings))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                entries.forEach { entry ->
                    NavigationBarItem(
                        selected = destination == entry.dest,
                        onClick = { mainViewModel.navigate(entry.dest) },
                        icon = {
                            Icon(
                                painter = painterResource(entry.iconRes),
                                contentDescription = entry.label
                            )
                        },
                        label = { Text(entry.label, maxLines = 1) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            content()
        }
    }
}

/**
 * Kategorie-Auswahl für Touch: kompakte Kopfzeile ("Kategorie: … ▾"), die ein
 * ModalBottomSheet mit allen Kategorien (inkl. Anzahl) öffnet. Ersetzt auf dem Handy
 * die fokus-gesteuerte CategoryColumn — bei 50–200 Gruppen ist eine antippbare,
 * scrollende Liste das passende Muster.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneCategoryPicker(
    entries: List<CategoryEntry>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }
    val selected = entries.firstOrNull { it.key == selectedKey } ?: entries.firstOrNull()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { showSheet = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Kategorie:",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = selected?.label ?: "Alle",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "▾",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(entries) { entry ->
                    val isSelected = entry.key == selectedKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(entry.key)
                                showSheet = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (if (isSelected) "✓ " else "") + entry.label,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (entry.count != null) {
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = entry.count.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
