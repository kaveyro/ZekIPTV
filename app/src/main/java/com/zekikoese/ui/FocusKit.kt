package com.zekikoese.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.zekikoese.ui.theme.AccentGlow

/**
 * Einheitlicher D-Pad-Fokusrahmen für alle fokussierbaren Elemente:
 * animierte Skalierung + Glow-Rahmen + Akzent-Tönung des Hintergrunds.
 * Die Tönung (statt Vollfarbe) lässt alle Textfarben unverändert.
 *
 * Skalierung: volle Breite (Zeilen) ~1.015f, Poster/Karten ~1.05f.
 *
 * [focusRoom]: Außenabstand VOR der Skalierung. Lazy-Container (Grid/Row/Column) schneiden
 * an ihren Rändern ab, was über die Element-Grenzen hinausragt — mit focusRoom wächst die
 * Vergrößerung in den eigenen Rand hinein statt über die Zelle hinaus (kein Clipping in der
 * ersten/letzten Reihe). Faustregel: halbe Überstandshöhe, also ~Elementhöhe × (scale-1) / 2.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tvFocusFrame(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    focusedScale: Float = 1.015f,
    restColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    onFocusChange: ((Boolean) -> Unit)? = null,
    focusRoom: Dp = 0.dp
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(140),
        label = "tvFocusScale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) AccentGlow else Color.Transparent,
        animationSpec = tween(140),
        label = "tvFocusBorder"
    )
    val background by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f).compositeOver(restColor)
        } else {
            restColor
        },
        animationSpec = tween(140),
        label = "tvFocusBackground"
    )

    return this
        // Fokussierte (skalierte) Elemente über die Nachbarn zeichnen — kein Grid-Clipping.
        .zIndex(if (focused) 1f else 0f)
        // Vor der Skalierung: Rand, in den die Vergrößerung hineinwachsen kann.
        .padding(focusRoom)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .onFocusChanged {
            focused = it.isFocused
            onFocusChange?.invoke(it.isFocused)
        }
        // combinedClickable macht das Element D-Pad-fokussierbar; Center = öffnen, Lang = Menü.
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .background(background)
        .border(width = 2.5.dp, color = borderColor, shape = shape)
}
