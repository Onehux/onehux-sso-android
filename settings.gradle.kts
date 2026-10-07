// onehux-sso-android/settings.gradle.kts
// PURPOSE: declares the two modules — a plain-JVM core (OAuth/PKCE logic + resource-server
// JWKS verification, usable from any Kotlin/JVM project, not just Android) and the Android
// library (Custom Tabs, Activity Result redirect capture, Keystore-backed token storage) that
// wraps it for real Android apps. Split this way so a Kotlin/JVM backend could depend on just
// :core for token verification without pulling in any Android dependency — mirrors how
// @onehux/sso's resource-server entrypoint needs no framework coupling.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "onehux-sso-android"

include(":core")
include(":android")
