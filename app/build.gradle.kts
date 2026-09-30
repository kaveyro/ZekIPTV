import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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
        targetSdk = 34
        versionCode = 10
        versionName = "1.7.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Kein material-icons-extended: der einzige genutzte Icon (Star) ist im Core-Set
    // enthalten (transitiv über material3) — spart mehrere MB.

    // Media3 / ExoPlayer inkl. HLS für .m3u8-Streams
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

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
