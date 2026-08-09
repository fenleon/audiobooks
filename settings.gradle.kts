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

// Audiobooks is a plain Android app (not a light-sdk "tool"): it needs a user
// manifest, a playback foreground service, and Context access, which the
// light-sdk tool plugin forbids. It still builds on the SDK's design system
// and client library, so the SDK is consumed here as an included build.
includeBuild("../light-sdk") {
    dependencySubstitution {
        substitute(module("com.thelightphone:sdk-ui")).using(project(":sdk:ui"))
        substitute(module("com.thelightphone:sdk-client")).using(project(":sdk:client"))
    }
}
