// onehux-sso-android/build.gradle.kts (root)
// No plugin `apply false` declarations here: declaring AGP's apply(false) at root — even
// unused by the root project — loads Kotlin Gradle plugin classes onto the root classpath as a
// side effect of AGP 9's built-in-Kotlin support, which breaks a sibling plain-JVM
// kotlin("jvm")/alias(libs.plugins.kotlin.jvm) module with "plugin already on the classpath
// with an unknown version" (found and fixed the same way in onehux-pos-android). detekt
// triggers the identical "org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension" collision
// when apply(false)'d at root, for the same underlying reason — confirmed by testing, not
// assumed. Every plugin version is applied directly per-module via the version catalog instead.
