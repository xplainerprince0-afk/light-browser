plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    api(project(":terminal-emulator"))
    // Pinned by upstream Termux (annotation-only, ~30KB). User-approved.
    implementation(libs.androidx.annotation)
}
