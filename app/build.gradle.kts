plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lightbrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lightbrowser"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // keep dex small
        multiDexEnabled = false
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // FIX for "Package appears to be invalid": previous release was unsigned (app-release-unsigned.apk)
            // Use debug signing for CI release so APK is v1/v2 signed and installable. Replace with your own keystore for store release.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    // size optimizations
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
