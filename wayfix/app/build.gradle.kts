plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wayhat.waycore"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wayhat.waycore"
        // 28 porque la librería LiteRT-LM (motor del modelo local) no soporta
        // versiones anteriores de Android.
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.7.0"
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
    // Motor de inferencia local (Qwen2.5-1.5B). Trae sus librerías nativas
    // (CPU XNNPACK + GPU) dentro del AAR; no se necesita NDK en el build.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")
}
