package com.zekikoese.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zeichnet den Startpfad und die Hauptbereiche auf (Startup-Profil für DEX-Layout-Optimierung
 * inklusive). Läuft ohne Playlist — gemessen wird vor allem der Kaltstart.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Home")), 10_000)
        // Hauptbereiche der Handy-Navigation einmal besuchen (Compose-Code dieser Screens).
        for (label in listOf("Live", "Mehr", "Suche", "Home")) {
            device.findObject(By.text(label))?.let {
                it.click()
                device.waitForIdle()
            }
        }
    }
}

internal const val PACKAGE = "com.zekikoese"
