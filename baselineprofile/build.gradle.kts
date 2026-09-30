// Erzeugt das Baseline Profile der App (vorab kompilierter Startpfad) und misst den Kaltstart.
//   Profil erzeugen:  ./gradlew :app:generateReleaseBaselineProfile   (Gerät/Emulator ab API 33)
//   Start messen:     ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.zekikoese.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Messungen auf dem Emulator zulassen (Werte dort nur als Anhaltspunkt).
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
