pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sdk:ui api-exposes com.github.lightphone:light-keyboard (font);
        // the composite resolves it against the consumer's repositories.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
}

rootProject.name = "audiobooks"

include(":app")
include(":server")

// Audiobooks is a single-APK project since 2026-08-18: `:app` is the real
// LightOS tool (lighttool.toml + the light-sdk tool plugin, LightScreen UI);
// `:server` is the merged companion as an Android LIBRARY whose manifest
// contributes the SDK server components (LightSdkService, media provider,
// consent/permission activities) and whose ServerBootstrapProvider wires the
// SDK server + scan at app start. The tool binds to itself (lighttool.toml
// serverPackage = com.lightphone.audiobooks). Both consume the SDK as an
// included build.
includeBuild("../light-sdk") {
    dependencySubstitution {
        substitute(module("com.thelightphone:sdk-ui")).using(project(":sdk:ui"))
        substitute(module("com.thelightphone:sdk-client")).using(project(":sdk:client"))
        substitute(module("com.thelightphone:sdk-server")).using(project(":sdk:server"))
        substitute(module("com.thelightphone:sdk-shared")).using(project(":sdk:shared"))
    }
}
