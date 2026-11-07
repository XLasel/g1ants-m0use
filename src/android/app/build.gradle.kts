plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "ru.stenographer.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.stenographer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Модели собраны только под arm64, это все современные телефоны
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            // Для сдачи на хакатоне подписываем отладочным ключом: такой APK ставится на любой телефон без настроек.
            signingConfig = signingConfigs.getByName("debug")
            // R8 выключен: он может удалить классы, которые вызываются только из нативного кода моделей
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true }
    // Модели не сжимаем: так они быстрее копируются из APK при первом запуске
    androidResources { noCompress += listOf("onnx", "gguf") }
    // У sherpa-onnx для других архитектур свой libonnxruntime.so, он конфликтует с onnxruntime-android (см. bench)
    packaging { jniLibs { excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Вся обработка: чтение, голоса, распознавание, конспект. Типы данных приходят из :core через неё
    implementation(project(":pipeline"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.material3:material3:1.5.0-alpha17")
    implementation("androidx.compose.ui:ui:1.11.0-beta02")
    implementation("androidx.compose.foundation:foundation:1.11.0-beta02")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.0-beta02")
    debugImplementation("androidx.compose.ui:ui-tooling:1.11.0-beta02")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
