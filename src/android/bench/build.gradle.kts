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
    androidResources { noCompress += listOf("onnx", "gguf") }
    // Только arm64: у sherpa-onnx для x86 лежит свой libonnxruntime.so и конфликтует с onnxruntime-android.
    // Для arm64 рантайм у sherpa встроен в libsherpa-onnx-jni.so, конфликта нет. abiFilters выше этого не снимает:
    // слияние нативных библиотек идёт по всем ABI до фильтра.
    packaging { jniLibs { excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**") } }
}

dependencies {
    // Вся обработка и модели приходят через :pipeline, бенч только меряет её события
    implementation(project(":pipeline"))
    // Только для режима verify: сравнить чтение файла одним проходом и параллельно, см. android/README.md
    implementation(project(":speech"))
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
}
