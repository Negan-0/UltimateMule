plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nostroteam.ultimatemule"
    compileSdk = 34 // 34 supporta perfettamente fino ad Android 15/16

    defaultConfig {
        applicationId = "com.nostroteam.ultimatemule"
        minSdk = 30 // Android 11+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-ALPHA"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
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

    // LibVLC (per le anteprime dei file .part incompleti di eMule/Torrent)
    implementation("org.videolan.android:libvlc-all:3.6.0-eap11")

    // Motore BitTorrent (Libtorrent4j + core nativo ARM64 per il tuo telefono Android 16)
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")

    // Motore Rete IRC / XDCC (tipo mIRC) - versione 2.2, presente su Maven Central
    implementation("org.pircbotx:pircbotx:2.2")
    implementation("org.slf4j:slf4j-android:1.7.36")
}
