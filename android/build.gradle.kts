plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    // kotlin("android") and kotlin("plugin.compose") are declared here too, even
    // though only :app uses them, and even though neither needs Google's Maven
    // (JetBrains publishes the Kotlin Gradle Plugin to Maven Central directly).
    // All of Kotlin's plugin markers — jvm, android, plugin.compose — resolve to
    // the same kotlin-gradle-plugin artifact; requesting it from two separate
    // classpath resolutions (root and :app) in the same build is what produces
    // Gradle's "plugin already on the classpath" error. One version, declared
    // once, applied without a version in the subproject that needs it, avoids
    // that entirely.
    //
    // The Android Gradle Plugin (com.android.application) stays declared only
    // in app/build.gradle.kts: it genuinely needs Google's Maven, and declaring
    // it here — even with apply false — would force that resolution on machines
    // building only :core-mesh and :data, which have no Android SDK.
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.compose") version "2.0.21" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
