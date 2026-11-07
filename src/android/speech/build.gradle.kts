plugins {
    id("com.android.library")
}

android {
    namespace = "ru.stenographer.speech"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Вариант со статически встроенным ONNX Runtime: в нём нет своего libonnxruntime.so,
    // поэтому он не должен конфликтовать с onnxruntime-android у диаризации. Проверить в одном APK.
    implementation(":sherpa-onnx-static-link-onnxruntime:2.0.1@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
