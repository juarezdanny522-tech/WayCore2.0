import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


val localProps = Properties()
val localFile = rootProject.file("local.properties")
if (localFile.exists()) {
    localFile.inputStream().use { input ->
        localProps.load(input)
    }
}

val qwenUrlRaw = providers.gradleProperty("QWEN_BASE_URL").orNull
    ?: localProps.getProperty("QWEN_BASE_URL").orEmpty()

// Mantiene BuildConfig válido aunque la URL haya sido pegada con comillas o saltos de línea.
val qwenUrl = qwenUrlRaw.trim()
    .removeSurrounding("\"")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", "")
    .replace("\n", "")

android {
    namespace = "com.wayhat.waycore"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wayhat.waycore"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.0"
        buildConfigField("String", "QWEN_BASE_URL", "\"$qwenUrl\"")
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
}
