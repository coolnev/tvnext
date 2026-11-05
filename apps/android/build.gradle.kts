import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tvnext.app"
    compileSdk = 36

    signingConfigs {
        create("releaseSign") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.tvnext.app"
        minSdk = 24
        targetSdk = 36
        // Restore branch, deliberately NOT labelled as the original 0.5.21.
        versionCode = 529
        versionName = "0.5.29-recovery"
    }

    buildTypes {
        debug {
            // Safe parallel installation, never overwrite the reference 0.5.21.
            applicationIdSuffix = ".recovery"
            versionNameSuffix = "-debug"
        }
        release {
            // Restoration is not 0.5.21 yet. Never overwrite the GOLD APK.
            // applicationIdSuffix ".recovery" -> com.tvnext.app.recovery,
            // so the recovery build installs NEXT TO the original 0.5.21.
            applicationIdSuffix = ".recovery"
            versionNameSuffix = "-preview"
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("releaseSign")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.decompose)
    implementation(libs.decompose.android)
    implementation(libs.decompose.extensions.compose)
    implementation(libs.essenty.lifecycle.coroutines)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:app"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:tmdb"))
    implementation(project(":core:iptv"))
    implementation(project(":core:player"))
    implementation(project(":core:licensing"))
    implementation(project(":features:home"))
    implementation(project(":features:channels"))
    implementation(project(":features:settings"))
    implementation(project(":features:media"))
    implementation(project(":features:tvg"))
    implementation(project(":features:search"))
}
