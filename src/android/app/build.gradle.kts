plugins {
    id("com.android.application")
}

android {
    namespace = "ru.stenographer.speechbench"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.stenographer.speechbench"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            // Debug-ключ подходит только для замеров. Для сдачи нужен отдельный ключ команды вне git.
            signingConfig = signingConfigs.getByName("debug")
            // R8 выключен: он может удалить классы, которые вызываются только из нативного кода.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Настройка действует в приложении, а не в библиотеке: без неё модели сжимаются в APK и дольше копируются.
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation(project(":speech"))
    implementation(project(":diarization"))
    // Рантайм для Nemotron. У sherpa-onnx в speech рантайм встроен статически, своего libonnxruntime.so у него нет.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
}
