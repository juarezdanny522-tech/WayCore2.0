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

// Firma propia opcional. Si existe wayfix/keystore.properties, el build release usa esa
// keystore; si no, se firma con la clave de depuración para que el APK sea instalable.
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
val hasKeystore = keystorePropsFile.exists() && run {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    !keystoreProps.getProperty("storeFile").isNullOrBlank()
}

android {
    namespace = "com.wayhat.waycore"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wayhat.waycore"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
    }

    if (hasKeystore) {
        signingConfigs {
            create("wayhat") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("wayhat")
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
    }

    lint {
        // Los avisos de lint no deben impedir un build del sombrero; se revisan aparte.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json no se agrega como librería: el framework de Android ya la provee.
    // androidx.work tampoco: WayCore usa AlarmManager, no WorkManager.
}
