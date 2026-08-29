plugins {
    // Versions are declared here rather than in the root build file. Declaring
    // them at the root forces Gradle to resolve the Android Gradle Plugin from
    // Google's Maven even with `apply false`, which breaks :core-mesh and :data
    // on machines without the Android SDK.
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.0.21"
    kotlin("plugin.compose") version "2.0.21"
}

android {
    namespace = "ph.bulig.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ph.bulig.app"
        // Android 8.0. Covers the overwhelming majority of devices in use while
        // keeping foreground-service behaviour sane — older releases handle the
        // long-running BLE relay service too inconsistently to support.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-capstone"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // The relay engine and the data layer. Both are pure Kotlin and carry their
    // own test suites, so nothing that matters is first tested on a device.
    implementation(project(":core-mesh"))
    implementation(project(":data"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation(kotlin("test"))
}
