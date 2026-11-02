// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 The +PLUS for GrapheneOS project
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.plusos.app"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        // +PLUS takes the GrapheneOS Updater's package name: SELinux gives that name the updater_app domain,
        // the only app domain allowed to call update_engine (spec section 3.1).
        applicationId = "app.seamlessupdate.client"
        minSdk = 34
        targetSdk = 34
        versionCode = 18
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // avbroot, afsr and the signing helper run as child processes from the native library directory.
    packaging { jniLibs { useLegacyPackaging = true } }

    // The three system XMLs +PLUS writes over the GrapheneOS Updater's (assets/system/...).
    sourceSets["main"].assets.srcDir("../etc")
    // The code-loading overlay for "your own app" add-ons (built by tools/build-dcl-overlay.sh from overlay-dcl/).
    sourceSets["main"].assets.srcDir("src/main/overlay-assets")

    buildTypes {
        // Release code (not debuggable) with the development repository and the source-build release channel of
        // src/debug/res: for the dev phone running our own builds. Unsigned here; signed with the project key on the box.
        create("staging") { initWith(getByName("release")) }
        // Release code with official GrapheneOS releases (pinned keys) and the development repository: images for
        // evaluators' phones until the public repository exists.
        create("evaluation") { initWith(getByName("release")) }
    }
    sourceSets["staging"].res.srcDir("src/debug/res")
}

dependencies {
    implementation(project(":core"))
    // Only android.os.UpdateEngine*: a system API, allowed at runtime (hidden-API flags "sdk,system-api").
    compileOnly(files("libs/system-stubs.jar"))
    implementation(libs.zxing.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.androidx.activity)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
