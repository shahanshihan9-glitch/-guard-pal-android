plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guardpal.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guardpal.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // One fixed key, so every new build installs as an update over the old one.
    signingConfigs {
        create("guard") {
            storeFile = file("guardpal.keystore")
            storePassword = "guardpal123"
            keyAlias = "guardpal"
            keyPassword = "guardpal123"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("guard") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("guard")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
