plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lightphone.audiobooks.server"
    compileSdk = 36

    signingConfigs {
        // Workspace dev signing (same key as the SDK tools/emulator).
        create("lightsdkDev") {
            storeFile = file("../../light-sdk/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.lightphone.audiobooks.server"
        minSdk = 34
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.8"
    }

    buildTypes {
        getByName("release") {
            // R8 dead-code elimination + resource shrinking (0.5.5) — see :app.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // SDK modules come from the included ../light-sdk build (see settings.gradle.kts).
    implementation(libs.sdk.server) {  // LightSdkServer + LightSdkService (the binder)
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
    }
    implementation(libs.sdk.client) {  // LightAudioPlayer
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
    }
    implementation(libs.sdk.ui) {      // Light design system for the status screen
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
    }
    implementation(libs.compose.activity)
    implementation(libs.kotlinx.coroutines)
    testImplementation(libs.junit)
}
