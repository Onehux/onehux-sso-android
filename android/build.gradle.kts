// onehux-sso-android/android/build.gradle.kts
// PURPOSE: the real mobile SDK surface — Chrome Custom Tabs sign-in, redirect capture, and
// Keystore-backed token storage, wrapping :core's framework-agnostic OAuth client.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    `maven-publish`
}

android {
    namespace = "com.onehux.sso.android"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // Every consuming app must set this (see README): the scheme of its own redirect_uri,
        // e.g. manifestPlaceholders["onehuxRedirectScheme"] = "com.onehux.pos" for a
        // "com.onehux.pos://callback" redirect_uri.
        //
        // Deliberately NOT given a default value here. AGP resolves a library's own manifest
        // placeholders using the library's OWN defaultConfig.manifestPlaceholders at the
        // library's own processDebugManifest step, before a consuming app's manifest merge
        // ever runs — so a default set here gets permanently baked into every consuming app's
        // merged manifest, and that app's own override of the same key in ITS OWN
        // defaultConfig silently has no effect (confirmed against this repo's own :example
        // module: AGP's manifest-merger-blame report showed the placeholder already resolved
        // to this library's default inside [:android]'s own merged manifest, well before
        // :example's manifest merge ran — a real, if underdocumented, AGP library-authoring
        // gotcha). Leaving it unset here means the `${onehuxRedirectScheme}` token survives
        // unresolved into this library's AAR, so it's resolved exactly once: at the final
        // consuming app's own manifest merge, using that app's own value — the only place a
        // real app-specific redirect scheme can correctly come from.
        //
        // If this module ever needs its own instrumented (androidTest) APK to build
        // standalone, give the default there instead — androidTestImplementation-scoped
        // config (or a dedicated debug-only sourceSet), never the main defaultConfig, so it
        // never leaks into a published AAR consumers build against.
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Declared explicitly, in valid Kotlin DSL syntax, so JitPack never needs to auto-inject
    // its own version of this block — JitPack's Android-library auto-publish support assumes
    // Groovy's build.gradle (single-quoted strings), and its injected
    // `publishing { singleVariant('release') }` is a syntax error in a .kts file ('release' is
    // parsed as an illegal multi-character Char literal, not a String). Confirmed against a
    // real JitPack build log for this exact repo before this fix existed.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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

    detektPlugins(libs.detekt.formatting)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}

// JitPack passes -Pgroup/-Pversion on its own build invocation (README: install via
// com.github.Onehux:onehux-sso-android:<tag>) — this publication just needs to exist with a
// real artifactId; JitPack's own tooling overrides group/version to match the requested
// coordinate regardless of what's set here.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.Onehux"
                artifactId = "onehux-sso-android-android"
            }
        }
    }
}

