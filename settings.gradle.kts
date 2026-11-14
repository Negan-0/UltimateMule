pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Rimosse le restrizioni per permettere a Gradle di cercare su tutti i server disponibili
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // FONDAMENTALE per PircBotX e Torrent
    }
}

rootProject.name = "UltimateMule"
include(":app")
