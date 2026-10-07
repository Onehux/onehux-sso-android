// onehux-sso-android/android/build.gradle.kts
// PURPOSE: the real mobile SDK surface — Chrome Custom Tabs sign-in, redirect capture, and
// Keystore-backed token storage, wrapping :core's framework-agnostic OAuth client.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.onehux.sso.android"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // Every consuming app must set this (see README): the scheme of its own redirect_uri,
        // e.g. manifestPlaceholders["onehuxRedirectScheme"] = "com.onehux.pos" for a
        // "com.onehux.pos://callback" redirect_uri. Defaulting it here only so this library
        // itself (and its own instrumented tests, once added) has a valid manifest to merge —
        // a real consuming app overrides it in its own defaultConfig/flavor.
        manifestPlaceholders["onehuxRedirectScheme"] = "com.onehux.sso.android.unconfigured"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core")) // OneHuxConfig/OneHuxTokenResponse/exceptions are part of this module's own public API

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}

