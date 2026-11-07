plugins {
    alias(libs.plugins.android.application) apply false
    // puts the Kotlin Gradle plugin of the pinned version on the classpath: AGP 9's built-in Kotlin would otherwise use its own older default
    alias(libs.plugins.kotlin.jvm) apply false
}
