plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.stan.libbylight"
    compileSdk = 36

    signingConfigs {
        // Workspace dev signing (same key as the SDK tools/emulator): sideloadable,
        // treated as Light-signed on the emulator. A private per-app release key
        // (see RELEASE.md) can replace this later.
        create("lightsdkDev") {
            storeFile = file("../../light-sdk/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.stan.libbylight"
        minSdk = 34
        targetSdk = 36
        versionCode = 11
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")

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
    implementation(libs.sdk.ui)
    implementation(libs.sdk.client)
    implementation(libs.compose.activity)
    implementation(libs.kotlinx.coroutines)
}
