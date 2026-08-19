plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lightphone.audiobooks.server"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // SDK modules come from the included ../light-sdk build (see settings.gradle.kts).
    // sdk:server = LightSdkServer + LightSdkService (the binder). Since the
    // 2026-08-18 single-module merge this library ships INSIDE the tool APK,
    // which hosts the service and binds to itself (lighttool.toml serverPackage).
    implementation(libs.sdk.server) {
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
    }
    implementation(libs.compose.activity)  // ComponentActivity for the consent/permission activities
    implementation(libs.kotlinx.coroutines)
    testImplementation(libs.junit)
}
