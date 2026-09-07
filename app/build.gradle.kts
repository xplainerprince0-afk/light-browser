plugins {
    alias(libs.plugins.android.application)
    // NOTE: no org.jetbrains.kotlin.android — AGP 9 has built-in Kotlin support.
    // Only the Compose compiler plugin is needed (version tracks Kotlin).
    alias(libs.plugins.compose)
}

android {
    namespace = "com.lightbrowser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lightbrowser"
        minSdk = 23
        targetSdk = 37
        versionCode = 14
        versionName = "3.0-expressive"
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
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugarJdkLibs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.compose.material3.navigationSuite)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewModelCompose)
    implementation(libs.coil.kt.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // SAF file access (scoped-storage compliant file manager + music import)
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Kept: manifest theme + file_paths depend on Material/AppCompat resources
    implementation("com.google.android.material:material:1.11.0")
    implementation(libs.androidx.appcompat)
}
