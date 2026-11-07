// Модуль можно собирать отдельно, без Android SDK: cd diarization && ../gradlew test
// В общем проекте он подключается из ../settings.gradle.kts как :diarization.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "diarization"
