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
        versionCode = 8
        versionName = "1.7-blackfix"
        // keep dex small
        multiDexEnabled = false
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("lightbrowser") {
            storeFile = file("lightbrowser.jks")
            storePassword = "lightbrowser123"
            keyAlias = "lightbrowser"
            keyPassword = "lightbrowser123"
            // keep same key for upgrades – do not regenerate. For Play Store use your own upload key.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("lightbrowser")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lightbrowser")
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.8.0")
}
