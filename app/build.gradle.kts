plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.vietsmart.key"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vietsmart.key"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // ── NDK ──────────────────────────────────────────────────────────
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_TOOLCHAIN=clang",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cppFlags("-std=c++17", "-O2", "-fvisibility=hidden")
            }
        }
    }

    // ── CMake build script ────────────────────────────────────────────────
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
}
