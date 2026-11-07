plugins {
    alias(libs.plugins.android.application)
}

// Blue Box Home (M6-H1): a separate, light launcher APK. Views only, no Compose, no DI, no third-party libraries:
// the target box (Realtek RTD1325) is render-bound, see docs/plan.md.
android {
    namespace = "app.bluebox.home"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.bluebox.home"
        minSdk = 26 // TvContract.WatchNextPrograms appeared in API 26
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // -PhomeIdSuffix=.two builds a second copy: lets a smoke test check rows of a foreign package
            applicationIdSuffix = ".debug" + (providers.gradleProperty("homeIdSuffix").orNull ?: "")
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
}
