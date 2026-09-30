package com.zekikoese.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Erkennung des Gerätetyps: TV (Fire TV / Android TV, D-Pad-Bedienung) vs. Handy/Tablet
 * (Touch-Bedienung). Die Nutzereinstellung "Bedienoberfläche" kann das Ergebnis übersteuern
 * (aufgelöst in MainActivity).
 */
fun Context.isTvDevice(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature("amazon.hardware.fire_tv")
}

/**
 * True = TV-Oberfläche (D-Pad, 10-Fuß-UI), false = Smartphone-Oberfläche (Touch).
 * Default true: Ohne Provider verhält sich alles wie bisher auf dem TV (fail-safe).
 */
val LocalIsTv = staticCompositionLocalOf { true }

/** True, solange die Activity im Bild-in-Bild-Modus läuft (nur Smartphone). */
val LocalInPictureInPicture = compositionLocalOf { false }
