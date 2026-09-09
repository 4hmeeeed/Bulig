pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bulig"

// core-mesh is a plain Kotlin/JVM module with no Android dependencies, so it
// builds and tests on any machine — including CI with no Android SDK. See
// docs/06-ble-protocol.md 6.9 for why the relay logic lives outside :app.
include(":core-mesh")

// Local persistence, the sync client, and the delivery state machine. Also pure
// Kotlin: Room and Retrofit bindings live in a thin Android adapter layer, so
// the logic that decides WHAT to sync stays testable without a device.
include(":data")

// :app needs the Android Gradle Plugin, which resolves from Google's Maven
// repository. Including it unconditionally would break `gradle test` on any
// machine without the Android SDK — including CI — so the pure-Kotlin modules
// stay buildable everywhere and :app joins in only where it can actually build.
//
// Android Studio supplies local.properties automatically on first open.
val androidSdkAvailable =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").exists()

if (androidSdkAvailable) {
    include(":app")
} else {
    logger.lifecycle(
        "Android SDK not found — skipping :app. " +
            ":core-mesh and :data still build and test normally."
    )
}


