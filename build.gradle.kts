// Archivo de compilación raíz: solo fija las versiones de los plugins.
// AGP 8.13.0 es la misma versión que usa App-Matematicas (ya está en la caché de Gradle).
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    // KSP genera el código de Room (la base de datos) en tiempo de compilación.
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}

// El proyecto vive en OneDrive y la sincronización bloquea los archivos intermedios de Gradle
// (no los puede borrar entre compilaciones). Igual que en App-Matematicas, mandamos las
// carpetas build/ a %LOCALAPPDATA%\cadapeso-build, fuera de OneDrive.
val localAppData: String? = System.getenv("LOCALAPPDATA")
if (localAppData != null) {
    val raizSalida = File(localAppData, "cadapeso-build")
    allprojects {
        layout.buildDirectory.set(File(raizSalida, if (this == rootProject) "root" else name))
    }
}
