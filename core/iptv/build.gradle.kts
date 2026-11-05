import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.tvnext.app.core.iptv"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.encoding)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(project(":core:common"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation("androidx.sqlite:sqlite-framework:2.6.2")
        }
        getByName("desktopTest").dependencies {
            // In-memory SQLite for repository/store tests (history, EPG cache).
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":core:database"))
        }
    }
}

// Pass `-DepgXmlPath=<path>` through to the test JVM so the opt-in
// RealEpgImportReproTest can benchmark a real XMLTV document offline.
tasks.withType<Test>().configureEach {
    providers.systemProperty("epgXmlPath").orNull?.let { path ->
        systemProperty("epgXmlPath", path)
    }
    providers.systemProperty("epgParseOnly").orNull?.let { value ->
        systemProperty("epgParseOnly", value)
    }
}
