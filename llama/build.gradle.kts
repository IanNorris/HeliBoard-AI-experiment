// SPDX-License-Identifier: GPL-3.0-only
// Android library module wrapping llama.cpp for on-device text completion.
// This module uses CMake for its native build; the :app module keeps ndkBuild for the AOSP
// dictionary code. Android Gradle Plugin allows one externalNativeBuild system PER MODULE, so the
// two coexist without conflict.
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "helium314.keyboard.llama"
    compileSdk = 36
    ndkVersion = "28.0.13004108"

    defaultConfig {
        // llama-mmap uses posix_madvise, available from API 23; the completion feature is gated well
        // above this anyway.
        minSdk = 23
        ndk {
            abiFilters.clear()
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti", "-O3", "-DNDEBUG")
                cFlags += listOf("-O3", "-DNDEBUG")
                arguments += listOf(
                    "-DANDROID_STL=c++_static",   // self-contained JNI lib; avoids libc++_shared duplication with :app
                    "-DGGML_NATIVE=OFF",          // required for cross-compile (no host -march detection)
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DBUILD_SHARED_LIBS=OFF",     // static-link ggml into the single JNI .so
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    // AGP forces CMAKE_BUILD_TYPE=Debug for the app's debug variant, which otherwise
                    // compiles ggml/llama.cpp at -O0 (10x+ slower). The _DEBUG config flags are emitted
                    // LAST by CMake, so overriding them guarantees the native code stays optimized.
                    "-DCMAKE_C_FLAGS_DEBUG=-O3 -DNDEBUG -g",
                    "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -DNDEBUG -g",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // mirror :app's custom build types so cross-module variant matching succeeds
    buildTypes {
        create("nouserlib") { matchingFallbacks += listOf("release") }
        create("runTests") { matchingFallbacks += listOf("debug") }
        create("debugNoMinify") { matchingFallbacks += listOf("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies { }
