plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ultimatemule.app"
    compileSdk = 34 

    defaultConfig {
        applicationId = "com.ultimatemule.app"
        minSdk = 30 
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-ALPHA"
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isDebuggable = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

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
    // 1. Librerie Ufficiali Google & Kotlin Coroutines (Per Socket TCP/UDP di eMule e IRC/XDCC)
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // 2. OkHttp Ufficiale (Con GZIP nativo per scaricare server.met e nodes.dat)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 3. LibVLC Ufficiale (Per le anteprime video dei file .part incompleti)
    implementation("org.videolan.android:libvlc-all:3.5.1")

    // 4. Motore BitTorrent Ufficiale su Maven Central (Verificato nei log #2 e #4!)
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-31")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-31")
}
