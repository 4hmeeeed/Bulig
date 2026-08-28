rootProject.name = "bulig"

// core-mesh is a plain Kotlin/JVM module with no Android dependencies, so it
// builds and tests on any machine — including CI with no Android SDK. See
// docs/06-ble-protocol.md 6.9 for why the relay logic lives outside :app.
include(":core-mesh")

// The Android modules (:app, :data) are added once the SDK is available.
