plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val resolvedVersionName = providers.gradleProperty("versionName")
    .orElse(providers.environmentVariable("APP_VERSION_NAME"))
    .orElse("0.1.0")
    .get()
val resolvedVersionCode = providers.gradleProperty("versionCode")
    .orElse(providers.environmentVariable("APP_VERSION_CODE"))
    .map { it.toInt() }
    .orElse(1)
    .get()

val enableAbiSplits = providers.gradleProperty("enableAbiSplits")
    .map { it.toBoolean() }
    .orElse(
        gradle.startParameter.taskNames.any { task ->
            task.contains("Release", ignoreCase = true)
        },
    )
    .get()

val releaseKeystorePath = providers.gradleProperty("signing.keystorePath")
    .orElse(providers.environmentVariable("ANDROID_KEYSTORE_PATH"))
    .orNull
val releaseKeystorePassword = providers.gradleProperty("signing.keystorePassword")
    .orElse(providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("signing.keyAlias")
    .orElse(providers.environmentVariable("ANDROID_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("signing.keyPassword")
    .orElse(providers.environmentVariable("ANDROID_KEY_PASSWORD"))
    .orNull
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "io.acanthis.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.acanthis.app"
        minSdk = 24
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    debugImplementation(libs.compose.ui.tooling)
}
