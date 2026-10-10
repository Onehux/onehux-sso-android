// onehux-sso-android/core/build.gradle.kts
// PURPOSE: the OAuth/PKCE client logic and resource-server JWKS verification — plain Kotlin/
// JVM, no Android dependency, so a Kotlin/JVM backend could use the resource-server half
// (verifyOneHuxAccessToken) without pulling in anything Android-specific. The :android module
// wraps this with Custom Tabs + Keystore-backed storage for real Android apps.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // api, not implementation: OkHttpClient is a public constructor parameter of
    // OneHuxOAuthClient, so it's genuinely part of this module's public API surface — a
    // consumer (like :android) needs it on its own compile classpath too.
    api(libs.okhttp.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.nimbus.jose.jwt)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    detektPlugins(libs.detekt.formatting)
}

tasks.test {
    useJUnitPlatform()
}

// :android depends on this module via `api(project(":core"))`. Without an explicit
// publication here, JitPack never builds/publishes :core as its own artifact at all — it only
// auto-publishes the module(s) a consumer's requested coordinate names directly — so :android's
// generated POM ends up with an unresolvable dependency (`com.github.Onehux:core:<tag>` returns
// 401 from JitPack, confirmed against a real build of onehux-pos-android pulling this SDK in).
// Deliberately NOT setting group/artifactId here: JitPack passes `-Pgroup`/`-Pversion` to every
// subproject in the build (not just the one a consumer names directly), so the default
// `project.group`/`project.name` ("core") already match the exact coordinate :android's own
// generated POM dependency expects — overriding either here would just reintroduce the mismatch.
publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}

