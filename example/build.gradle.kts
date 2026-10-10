// onehux-sso-android/example/build.gradle.kts
// PURPOSE: the smallest real app that exercises :android end to end — sign in, show claims,
// call validAccessToken(), sign out — against the live platform on a device/emulator. Not
// published; exists purely so this SDK has been run as a real app would run it, not just
// unit-tested in isolation.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.onehux.sso.example"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.onehux.sso.example"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "ONEHUX_CLIENT_ID", "\"onehux_client_OsoyE3nfyC5ygeeg2DczVWrK48oh0IT7\"")
        manifestPlaceholders["onehuxRedirectScheme"] = "com.onehux.ssoexample"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":android"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("detekt.yml"))
}
