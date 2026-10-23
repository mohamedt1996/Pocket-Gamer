// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.pocketarcade.madeira"
    compileSdk = 35
    ndkVersion = "27.3.13750724"
    defaultConfig {
        applicationId = "org.pocketarcade.madeira"
        manifestPlaceholders["appLabel"] = "Pocket Arcade"
        minSdk = 28
        targetSdk = 28 // Prototype only: review modern Android policies before distribution.
        versionCode = 3
        versionName = "0.3.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
    buildFeatures { compose = true; buildConfig = true }
    // These payloads are already gzip-compressed. Recompressing them wastes heap and CPU.
    androidResources { noCompress += "tgz" }
    packaging { resources { excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module") } }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.jakewharton.timber:timber:5.0.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json in unit tests (Android's is a stub there).
    testImplementation("org.json:json:20240303")
}
