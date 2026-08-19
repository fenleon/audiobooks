plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
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
        minSdk = 34
        targetSdk = 36

        // Consumed by the plugin's generated manifest (SDK_VERSION metadata).
        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("release") {
            // R8 dead-code elimination + resource shrinking (0.5.5): the app is
            // ~67 MB mostly of unreachable library code; the SDK's consumer
            // rules keep the generated registry + entry points.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
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
    // sdk:client pulls sdk:ui, which bundles an ML Kit QR scanner + CameraX for the
    // SDK's authenticator example. Audiobooks never touches it — exclude the groups
    // so their ~20 MB of native libs don't ship (R8 removes the scanner code).
    implementation(libs.sdk.client) {
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
    }
    implementation(libs.kotlinx.coroutines)
    // The former :server companion, merged into the tool APK (single-module
    // build, 2026-08-18): its manifest contributes the SDK server components
    // (LightSdkService, media provider, consent/permission activities), its
    // ServerBootstrapProvider wires the SDK server + scan at app start, and
    // the tool binds to itself (lighttool.toml serverPackage = own id).
    implementation(project(":server"))
}
