plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.test") version "9.4.1" apply false
    // Baseline Profiles: vorab kompilierter Startpfad -> schnellerer Kaltstart (Fire TV)
    id("androidx.baselineprofile") version "1.5.0" apply false
    // AGP 9 bringt Kotlin selbst mit (built-in Kotlin); hier wird nur die Kotlin-Version gesetzt.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
