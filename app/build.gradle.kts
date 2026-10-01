import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Datos de la firma del APK. Los crea compilar-apk.ps1 la primera vez y NO se suben a git:
// si se pierde el .jks no se pueden instalar actualizaciones encima de la app ya instalada.
val propsFirma = Properties().apply {
    val archivo = rootProject.file("keystore.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}

android {
    namespace = "com.gabriel.cadapeso"
    compileSdk = 36
    buildToolsVersion = "35.0.0" // la versión que ya está instalada en el SDK

    defaultConfig {
        applicationId = "com.gabriel.cadapeso"
        minSdk = 26        // Android 8.0: trae java.time (fechas) sin librerías extra
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // MediaPipe trae el motor de Gemma compilado para 4 arquitecturas (~108 MB).
        // Los teléfonos reales usan ARM; x86 solo sirve para emuladores, así que lo dejamos afuera.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            if (!propsFirma.isEmpty) {
                storeFile = rootProject.file(propsFirma.getProperty("storeFile"))
                storePassword = propsFirma.getProperty("storePassword")
                keyAlias = propsFirma.getProperty("keyAlias")
                keyPassword = propsFirma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Sin R8: MediaPipe usa JNI/reflexión y minificar podría romperlo sin que lo notemos.
            isMinifyEnabled = false
            signingConfig = if (!propsFirma.isEmpty) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        // Comprime las librerías nativas dentro del APK: el archivo pesa mucho menos
        // (útil para pasarlo por WhatsApp/Drive); Android las descomprime al instalar.
        jniLibs { useLegacyPackaging = true }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric necesita los recursos y el manifiesto para simular Android en la PC.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Jetpack Compose (la interfaz). El BOM fija versiones compatibles entre sí.
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // Room: base de datos SQLite local (los movimientos nunca salen del teléfono).
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // WorkManager: recordatorios (regla de las 24/48 h, día de cobro, resumen semanal).
    implementation("androidx.work:work-runtime-ktx:2.10.5")

    // MediaPipe LLM Inference: ejecuta Gemma 2 (archivo .task) dentro del teléfono, sin internet.
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // Tests: los del motor corren en la JVM pura; los de pantallas usan Robolectric, que simula
    // Android en la PC (no hace falta emulador ni teléfono).
    testImplementation("junit:junit:4.13.2")
    testImplementation(composeBom)
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.work:work-testing:2.10.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
