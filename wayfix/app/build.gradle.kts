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

val geminiKeyRaw = providers.gradleProperty("GEMINI_API_KEY").orNull
    ?: localProps.getProperty("GEMINI_API_KEY").orEmpty()

// Mantiene BuildConfig válido aunque la clave haya sido pegada con comillas o saltos de línea.
val geminiKey = geminiKeyRaw.trim()
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
        // Gama media-alta compatible desde Android 8.0
        minSdk = 26
        targetSdk = 35
        // IMPORTANTE: versionCode siempre mayor para que al tocar el APK se actualice solo
        // sin desinstalar. Android detecta mismo package + firma + versionCode mayor = update.
        versionCode = 7
        versionName = "0.5.1"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        // No se descarga ningún modelo local, todo es en la nube, por eso no pesa.
        buildConfigField("boolean", "AUTO_UPDATE_ENABLED", "true")
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

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Importante para que la actualización con un toque funcione:
            // No cambiamos applicationId ni usamos splits que rompan la firma
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Asegura que el APK sea instalable como actualización
    // Mismo package, misma firma debug/release, versionCode incrementado
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
