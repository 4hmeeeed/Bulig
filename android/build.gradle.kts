// No `plugins` block here, deliberately.
//
// Anything declared at the root — even with `apply false` — lands on the
// buildscript classpath that every subproject inherits, and that breaks this
// build in two different ways at once:
//
//  1. Kotlin's plugin markers (jvm, android, plugin.compose) all resolve to the
//     same kotlin-gradle-plugin artifact. With one of them on the root
//     classpath, a subproject asking for another fails with "the plugin is
//     already on the classpath".
//
//  2. Declaring the Android Gradle Plugin here would force every build to
//     resolve it from Google's Maven, including builds of :core-mesh and :data
//     on machines that have no Android SDK and no access to that repository.
//     That is the property docs/06-ble-protocol.md 6.9 relies on: the relay
//     logic must be testable anywhere.
//
// So each module declares and versions the plugins it actually uses. The one
// rule to keep: the Kotlin version must match across all of them, because
// KotlinAndroidTarget in :app resolves com.android.build.gradle.api.BaseVariant
// out of the Android Gradle Plugin sitting in the same classloader, and a split
// or mismatched resolution turns that into a NoClassDefFoundError at sync time.

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
