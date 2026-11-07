plugins { id("com.android.library") }

android {
    namespace = "ru.stenographer.summary"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // На устройстве используется org.json из Android, в JVM-тестах — реальная реализация.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
