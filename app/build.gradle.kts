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
    // Base Android
    implementation("androidx.core:core-ktx:1.12.0")
    
    // OkHttp (GZIP e Liste Server)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // LibVLC (Anteprime video/audio su file .part)
    implementation("org.videolan.android:libvlc-all:3.6.0-eap11")

    // Motore BitTorrent (libtorrent4j)
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")

    // Motore IRC (PircBotX)
    implementation("org.pircbotx:pircbotx:2.3.1")
}
