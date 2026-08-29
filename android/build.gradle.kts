plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    // The Android Gradle Plugin and Compose compiler are declared in
    // app/build.gradle.kts instead. Declaring them here would force Gradle to
    // resolve them from Google's Maven even with `apply false`, breaking the
    // pure-Kotlin modules on machines without the Android SDK.
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
