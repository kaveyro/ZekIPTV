import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

// Signierdaten liegen gitignoriert in keystore/keystore.properties (nicht committen!).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.zekikoese"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zekikoese"
        minSdk = 24
        targetSdk = 37
        versionCode = 13
        versionName = "1.9.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Native FFmpeg-Bibliothek nur für ARM (Fire TV / Android TV / Handys) und x86_64
        // (Emulator) — x86 (32 bit) spart Platz, dafür gibt es keine relevanten Geräte mehr.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storeName = keystoreProperties.getProperty("storeFile")
            if (storeName != null) {
                storeFile = rootProject.file("keystore/$storeName")
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: kleineres, schnelleres APK — wichtig für die schwache Fire-TV-Hardware.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true // VERSION_NAME für die Update-Prüfung
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Nur das Core-Icon-Set (material3 bringt es nicht mehr transitiv mit); bewusst kein
    // material-icons-extended — spart mehrere MB.
    implementation(libs.androidx.compose.material.icons.core)

    // Media3 / ExoPlayer inkl. HLS für .m3u8-Streams
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    // MediaSession: Medientasten der Fernbedienung / Systemsteuerung der Wiedergabe
    implementation(libs.androidx.media3.session)
    // FFmpeg-Audio-Decoder (Jellyfin-Build, GPL-3.0): AC3/E-AC3/DTS/MP2, wenn das Gerät sie
    // nicht selbst dekodieren kann — viele IPTV-Sender senden Dolby-Ton.
    implementation(libs.jellyfin.media3.ffmpeg.decoder)

    // Installiert das mitgelieferte Baseline Profile auch bei Sideload (ohne Play Store).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    // Persistenz (URL, Kanäle, Favoriten)
    implementation(libs.androidx.datastore.preferences)

    // Periodischer Hintergrund-Refresh von Playlist/EPG
    implementation(libs.androidx.work.runtime.ktx)

    // Kanal-Logos
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    // XmlPullParser-Implementierung für JVM-Unit-Tests des XMLTV-Parsers
    // (auf dem Gerät liefert die Android-Plattform kXML).
    testImplementation(libs.kxml2)
    testImplementation(libs.xmlpull)
    // Echte org.json-Implementierung für JVM-Unit-Tests (android.jar enthält nur Stubs).
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
