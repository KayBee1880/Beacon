plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.beacon"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.beacon"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Milestone 13 (D-062/D-064): SQLCipher's SupportFactory wraps Room's normal SQLite
    // access with page-level AES-256 encryption. The current, actively maintained
    // net.zetetic:sqlcipher-android was tried first and rejected on real build evidence,
    // not preference: 4.19.0 requires compileSdk 37 (a much larger, unrelated toolchain
    // upgrade this project has no other reason to make), and 4.17.0 (before that
    // requirement existed) transitively pulls an androidx.sqlite build compiled with
    // Kotlin 2.1, incompatible with this project's pinned Kotlin 1.9.24 compiler. This
    // older, deprecated-but-stable artifact and its old androidx.sqlite companion predate
    // that entire toolchain generation, avoiding both problems. Real tradeoff, not free:
    // deprecated, and per Zetetic's own docs, missing the 16KB native-page-size support
    // Google Play now requires for new/updated apps, fine for a project with no release
    // anywhere on the horizon, but must be revisited before one exists (D-064).
    implementation("net.zetetic:android-database-sqlcipher:4.5.3")
    implementation("androidx.sqlite:sqlite:2.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Milestone 11 (D-055): plain JVM unit tests, src/test/, no emulator or device
    // needed, this project's first automated tests of any kind.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
