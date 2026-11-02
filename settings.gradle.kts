pluginManagement {
    repositories {
        google()
        // Maven Central 本家はこの回線から 429 を返すことがあるため、
        // Google が運用している Maven Central のミラーを先に引く
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        mavenCentral()
    }
}
rootProject.name = "GeoLogger"
include(":app")
