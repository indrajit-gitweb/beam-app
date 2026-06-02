plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace   = "com.beam.app"
    compileSdk  = 34

    defaultConfig {
        applicationId   = "com.beam.app"
        minSdk          = 26          // Android 8.0+
        targetSdk       = 34
        versionCode     = 1
        versionName     = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Bundle index.html in assets
    sourceSets["main"].assets.srcDirs("src/main/assets")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // LAN server — HTTP + WebSocket on Android without heavy frameworks
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // HTTP client for transfers
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}
