// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.plusos.ai"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.plusos.ai"
        minSdk = 34
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.0"
    }
    buildFeatures { aidl = true }
    buildTypes {
        // Release code (not debuggable) with the development catalog of src/debug/res: for the dev repository only.
        // Unsigned here; signed with the project key when it is published.
        create("staging") { initWith(getByName("release")) }
    }
    sourceSets { getByName("staging") { res.srcDirs("src/debug/res") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    // LiteRT-LM (Apache-2.0): the runtime the LLM Runner proved on the Pixel 9 GPU (2026-10-17).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
