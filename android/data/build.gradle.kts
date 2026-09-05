import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

// Java 17 bytecode, same as :core-mesh, so the Android modules can consume it.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // The mesh engine owns packet identity, TTL and signing; this module owns
    // local persistence and talking to the server.
    api(project(":core-mesh"))

    // `api`, not `implementation`: HttpSyncApi and AssignmentActions expose
    // Json and OkHttpClient directly in their public signatures (defaultJson,
    // defaultClient()), so :app — which constructs these classes itself —
    // needs both on its own compile classpath, not just this module's.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // OkHttp rather than Retrofit: one dependency instead of three, and the
    // sync path needs explicit control over timeouts and status-code handling
    // that Retrofit's exception model would hide behind HttpException. It is a
    // plain JVM library, so the client and its tests run in this module without
    // Android — which is why they can be tested at all.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // A real HTTP server in the test JVM. Asserting against a hand-written fake
    // would only prove the fake agrees with itself.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
