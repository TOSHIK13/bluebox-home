plugins {
    alias(libs.plugins.android.application)
}

// Blue Box Home (M6-H1): a separate, light launcher APK. Views only, no Compose, no DI, no third-party libraries:
// the target box (Realtek RTD1325) is render-bound, see docs/plan.md.
// Version comes from the release tag: -PversionName=0.2.1 (-PversionCode=<major*10000+minor*100+patch>); update repo: -PupdateRepo=owner/name
val homeVersionName = providers.gradleProperty("versionName").orElse("0.1.0").get()
val homeVersionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
val homeUpdateRepo = providers.gradleProperty("updateRepo").orElse("TOSHIK13/bluebox-home").get()

android {
    namespace = "app.bluebox.home"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.bluebox.home"
        minSdk = 26 // TvContract.WatchNextPrograms appeared in API 26
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = homeVersionCode
        versionName = homeVersionName
        buildConfigField("String", "BASE_VERSION", "\"$homeVersionName\"")
        buildConfigField("String", "UPDATE_REPO", "\"$homeUpdateRepo\"")
    }

    buildFeatures { buildConfig = true }

    // Release signing comes from the environment (CI secrets) or local.properties-free env only; without it the release APK stays unsigned.
    val ksFile = System.getenv("HOME_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null) create("release") {
            storeFile = file(ksFile)
            storePassword = System.getenv("HOME_STORE_PASSWORD")
            keyAlias = System.getenv("HOME_KEY_ALIAS")
            keyPassword = System.getenv("HOME_KEY_PASSWORD")
        }
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
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
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
