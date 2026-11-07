pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx публикуется как AAR в релизах GitHub, его кладёт scripts/prepare_speech.sh.
        flatDir { dirs(rootDir.resolve("speech/libs")) }
    }
}

rootProject.name = "stenographer"
include(":speech", ":diarization", ":bench")
include(":summary", ":summary-android")
