// De dónde baja Gradle los plugins (AGP, Kotlin, KSP) y las librerías.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Todas las librerías se declaran acá, ningún módulo agrega repositorios propios.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()       // AndroidX, Compose, MediaPipe (Gemma 2 en el teléfono)
        mavenCentral() // Kotlin, Guava, Protobuf, JUnit
    }
}

rootProject.name = "CadaPeso"
include(":app")
