plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ultimatemule.app"
    compileSdk = 34 // Pienamente compatibile con Android 16

    defaultConfig {
        applicationId = "com.ultimatemule.app"
        minSdk = 30 // Android 11+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-ALPHA"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Evita conflitti tra le librerie IRC, Torrent e OkHttp durante la creazione dell'APK
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    // Librerie base Android e Kotlin Coroutines (per download multipli)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp (con supporto GZIP nativo per liste server.met e nodes.dat)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // LibVLC Versione Stabile (per le anteprime dei file .part incompleti)
    implementation("org.videolan.android:libvlc-all:3.5.1")

    // Motore BitTorrent (Libtorrent4j + core nativo ARM64 per Android 16)
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")

    // Motore Rete IRC / XDCC (Versione 2.2 Ufficiale Maven Central)
    implementation("org.pircbotx:pircbotx:2.2") {
        exclude(group = "com.google.guava") // Evita conflitti con Android
    }
    implementation("com.google.guava:guava:32.1.3-android")
    implementation("org.slf4j:slf4j-android:1.7.36")
}
