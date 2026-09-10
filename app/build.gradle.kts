plugins {
    alias(libs.plugins.android.application)
    // NOTE: no org.jetbrains.kotlin.android — AGP 9 has built-in Kotlin support.
    // Only the Compose compiler plugin is needed (version tracks Kotlin).
    alias(libs.plugins.compose)
}

android {
    namespace = "com.rg.webloom"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rg.webloom"
        minSdk = 23
        targetSdk = 37
        versionCode = 23
        versionName = "4.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("webloom") {
            storeFile = file("webloom.jks")
            storePassword = "aXKs3ir0i3ZHuuEwySIAiTXG"
            keyAlias = "webloom"
            keyPassword = "aXKs3ir0i3ZHuuEwySIAiTXG"
            // New Webloom key (2026-09) — keep for all future updates of com.rg.webloom.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("webloom")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("webloom")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    // PTY terminal (vendored Termux terminal-view, Apache-2.0 — see DEPENDENCY_TREE.md)
    implementation(project(":terminal-view"))
    // SAF file access (scoped-storage compliant file manager + music import)
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Kept: manifest theme + file_paths depend on Material/AppCompat resources
    implementation("com.google.android.material:material:1.11.0")
}
