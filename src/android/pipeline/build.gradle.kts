// Вся обработка одного файла: чтение → диаризация → нарезка → распознавание → реплики → конспект.
// Отдаёт поток событий PipelineEvent из :core, интерфейс зависит только от них.
plugins {
    id("com.android.library")
}

android {
    namespace = "ru.stenographer.pipeline"
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
    api(project(":core"))
    implementation(project(":speech"))
    implementation(project(":diarization"))
    implementation(project(":summary-android"))
    // Рантайм для Nemotron. У sherpa-onnx в speech рантайм для arm64 встроен статически.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
