plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    // Version-less fallbacks (rootProject.ext): inside this workspace the root
    // build.gradle.kts doesn't declare them — the included ../light-sdk build
    // does via its convention; in Light's Tool Library builder (which stages
    // this tool/ into the baked-in SDK repo) the SDK's root declares them.
    compileSdk = rootProject.ext["compileSdk"] as Int

    signingConfigs {
        // Workspace dev signing (same key as the SDK tools/emulator). Inside
        // an SDK checkout (Light's Tool Library builder stages this tool/
        // module into the baked-in SDK repo) the keys live at ../sdk/keys.
        create("lightsdkDev") {
            storeFile = file(
                listOf("../../light-sdk/sdk/keys/lightsdk-dev.jks", "../sdk/keys/lightsdk-dev.jks")
                    .map(::file).first { it.exists() }
            )
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
        }
    }

    defaultConfig {
        minSdk = rootProject.ext["minSdk"] as Int
        targetSdk = rootProject.ext["targetSdk"] as Int

        // Consumed by the plugin's generated manifest (SDK_VERSION metadata).
        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("release") {
            // R8 dead-code elimination + resource shrinking: the SDK's consumer
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

// zxing-cpp (the SDK's camera QR/barcode decoder, via sdk:ui) requires
// compileSdk 37 metadata; audiobooks never scans codes, so drop the group for
// the whole configuration — this removes the AarMetadata conflict outright
// (no task-disable hack) and keeps ~20 MB of native libs out of the APK
// together with the mlkit/camera excludes below.
configurations.configureEach {
    exclude(group = "com.github.markusfisch")
}

// Inside an SDK checkout the SDK modules are sibling projects; in this
// workspace the included ../light-sdk build substitutes the module artifacts.
val inSdkRepo = file("../sdk").exists()

dependencies {
    // SDK modules come from the included ../light-sdk build (see settings.gradle.kts).
    // sdk:ui (via sdk:client) bundles an ML Kit QR scanner + CameraX for the
    // SDK's authenticator example. Audiobooks never touches it — exclude the
    // groups so their ~20 MB of native libs don't ship (R8 removes the code).
    if (inSdkRepo) {
        implementation(project(":sdk:client")) {
            exclude(group = "com.google.mlkit")
            exclude(group = "androidx.camera")
        }
    } else {
        implementation("com.thelightphone:sdk-client") {
            exclude(group = "com.google.mlkit")
            exclude(group = "androidx.camera")
        }
    }

    // Everything runs in-process (Rung-1 fold, 2026-10): the scan, stores,
    // and playback file access live in this module behind the tool's
    // SealedLightContext — no LightSdkService, no embedded server, no
    // :server library.
    implementation(libs.kotlinx.coroutines)

    // Unit tests for the pure-logic helpers (kotlin-test only — the
    // workspace's only allowed test framework; junit is its backend).
    testImplementation(libs.kotlin.test)
}
