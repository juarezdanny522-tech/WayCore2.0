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
        // Bump para que al tocar APK se actualice solo (mismo package + firma + versionCode mayor)
        // v0.7.3 arregla INVALID_ARGUMENT Unsupported file format -> cambia GGUF por .litertlm oficial Qwen3 0.6B
        // y arregla 'archivo malicioso' + GitHub API rate limit con URL directa
        versionCode = 11
        versionName = "0.7.3"
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiKey\"")
        buildConfigField("boolean", "AUTO_UPDATE_ENABLED", "true")

        // El motor de IA local trae librerías nativas por arquitectura. Sin este filtro el
        // APK arrastraría también la variante de emulador x86. Para probar en emulador,
        // añadir "x86_64" a la lista o compilar con -PABI=x86_64.
        ndk {
            val wanted = providers.gradleProperty("ABI").orNull
                ?: System.getenv("WAYCORE_ABI")
                ?: "arm64-v8a,armeabi-v7a"
            abiFilters += wanted.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    buildTypes {
        getByName("debug") {
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) {
                signingConfigs.create("wayhat") {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
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
        // El AAR de LiteRT-LM se publica con un Kotlin más nuevo que el del proyecto. Sin esta
        // bandera el compilador aborta leyendo metadatos ajenos (y ni siquiera llega a revisar
        // si nuestro código está bien); con ella se ignora la diferencia de versión y el resto
        // de la API se usa normal. Si alguna vez falla, la alternativa es subir
        // org.jetbrains.kotlin.android / plugin.compose a la versión con la que viene built.
        freeCompilerArgs += "-Xskip-metadata-version-check"
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
        // litert y litertlm empaquetan el mismo .so de OpenCL; sin esto la Fusión de
        // librerías nativas falla con "2 files found with path".
        jniLibs.pickFirsts += "**/libLiteRtClGlAccelerator.so"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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

    // Motor de inferencia local (llama.cpp por dentro) para .litertlm de Qwen.
    // Antes usábamos GGUF que daba "Unsupported file format". Ahora usamos modelos oficiales
    // litert-community/Qwen3-0.6B que son formato .litertlm 100% compatible.
    // 0.13.1 es la última estable de 2026-06, más nueva que 0.17.1 que era alpha.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")

    testImplementation("junit:junit:4.13.2")
    // En las pruebas de JVM org.json no viene del framework de Android.
    testImplementation("org.json:json:20240303")
    // org.json no se agrega como librería: el framework de Android ya la provee.
    // androidx.work tampoco: WayCore usa AlarmManager, no WorkManager.
}
