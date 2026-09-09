plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 37
    // Pinned NDK: CI installs this exact revision (cached).
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 23
        // JNI: ndkBuild consumes src/main/jni/Android.mk (libtermux.so).
        externalNativeBuild {
            ndkBuild {
                arguments("-j8")
                cFlags(
                    "-std=c11", "-Wall", "-Wextra", "-Werror", "-Os",
                    "-fno-stack-protector", "-Wl,--gc-sections"
                )
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // Pinned by upstream Termux (annotation-only, ~30KB). User-approved.
    implementation(libs.androidx.annotation)
}
