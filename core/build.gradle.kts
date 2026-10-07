// onehux-sso-android/core/build.gradle.kts
// PURPOSE: the OAuth/PKCE client logic and resource-server JWKS verification — plain Kotlin/
// JVM, no Android dependency, so a Kotlin/JVM backend could use the resource-server half
// (verifyOneHuxAccessToken) without pulling in anything Android-specific. The :android module
// wraps this with Custom Tabs + Keystore-backed storage for real Android apps.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
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
}

tasks.test {
    useJUnitPlatform()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}

