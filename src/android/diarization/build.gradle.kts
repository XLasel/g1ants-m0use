import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Версия плагина задана в settings.gradle.kts этого модуля и действует только при отдельной сборке.
// В общем проекте берётся Kotlin, который уже подключён вместе с AGP.
plugins {
    kotlin("jvm")
}

// Та же версия, что в Python-прототипе.
val onnxRuntime = "1.22.0"

// Для тестов на компе. Сборки 1.19+ под Windows падают при загрузке DLL: им нужен msvcp140.dll от 14.40,
// а JDK кладёт в процесс свой, более старый. На Linux и Mac можно -PortTestVersion=1.22.0.
val onnxRuntimeTest = (findProperty("ortTestVersion") as String?) ?: "1.18.0"

dependencies {
    // Общие модели: Word, Segment, Utterance. api — чтобы зависящим от диаризации они были видны сразу.
    api(project(":core"))
    // Модуль не тянет рантайм сам: на телефоне его даёт onnxruntime-android, в тестах — обычный onnxruntime.
    // API у обоих одинаковый (пакет ai.onnxruntime).
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$onnxRuntime")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxRuntimeTest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Эталон и модели лежат рядом с репозиторием, как у voicerec-poc: ../../../../reference и ../../../../voicerec-original.
// Другие пути: ../gradlew test -PreferenceDir=... -PmodelsDir=...
val outside = rootDir.resolve("../../../..").normalize()
tasks.test {
    systemProperty("reference.dir", findProperty("referenceDir") ?: outside.resolve("reference").path)
    systemProperty("models.dir", findProperty("modelsDir") ?: outside.resolve("voicerec-original/models_onnx").path)
    maxHeapSize = "3g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
