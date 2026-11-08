import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    android {
        namespace = "com.tvnext.app.core.player"
        compileSdk = 36
        minSdk = 24
        androidResources { enable = true }
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
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(project(":core:common"))
        }
        androidMain.dependencies {
            implementation(project(":core:iptv"))
            implementation(compose.ui)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.exoplayer.dash)
            implementation(libs.media3.ui)
            implementation(libs.media3.session)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("desktopMain").dependencies {
            implementation(compose.ui)
            // libVLC (vlcj 4.x) is the production Windows playback backend since
            // Sprint 4. libVLC ships its own FFmpeg decoders, so HEVC/4K do not
            // depend on the Windows Media Foundation codec store.
            implementation("uk.co.caprica:vlcj:4.12.1")
        }
    }
}

// Diagnostics: probes drive the production libVLC backend (VlcProbe,
// VlcRenderProbe) and report per-stream metrics. Not part of the app.
val probeRuntime: Configuration by configurations.creating
dependencies {
    // Skiko native runtime so Compose ImageBitmap works outside the desktop app
    // shell. Probe-only; the app gets this via compose.desktop.currentOs.
    probeRuntime(compose.desktop.currentOs)
}
val desktopMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")

// libVLC PoC probe: same stream matrix through libVLC instead of JavaFX.
// Usage: .\gradlew :core:player:probeRunVlc --args="--duration=12000 <url> [<url>...]"
tasks.register<JavaExec>("probeRunVlc") {
    group = "diagnostics"
    description = "Runs the libVLC PoC probe (VlcProbe)."
    mainClass.set("com.tvnext.app.core.player.VlcProbeKt")
    classpath = desktopMainCompilation.output.allOutputs + configurations["desktopRuntimeClasspath"] + probeRuntime
    jvmArgs(
        "-Dstdout.encoding=UTF-8",
        "-Dfile.encoding=UTF-8",
        "-Djna.library.path=C:\\Temp\\kilo\\vlc\\vlc-3.0.23",
        "-Dvlc.install.dir=C:\\Temp\\kilo\\vlc\\vlc-3.0.23",
    )
}

// libVLC hardware probe: plays streams through a real HWND video output
// (--avcodec-hw=d3d11va --vout=direct3d11) and proves which decoder is used.
// Usage: .\gradlew :core:player:probeRunVlcHw --args="--duration=12000 --hw=d3d11va --vout=direct3d11 <url> [<url>...]"
tasks.register<JavaExec>("probeRunVlcHw") {
    group = "diagnostics"
    description = "Runs the libVLC hardware-acceleration probe (VlcHwProbe)."
    mainClass.set("com.tvnext.app.core.player.VlcHwProbeKt")
    classpath = desktopMainCompilation.output.allOutputs + configurations["desktopRuntimeClasspath"] + probeRuntime
    jvmArgs(
        "-Dstdout.encoding=UTF-8",
        "-Dfile.encoding=UTF-8",
        "-Djna.library.path=C:\\Temp\\kilo\\vlc\\vlc-3.0.23",
        "-Dvlc.install.dir=C:\\Temp\\kilo\\vlc\\vlc-3.0.23",
    )
}

tasks.register<JavaExec>("probeRunComposeHwSurface") {
    group = "diagnostics"
    description = "End-to-end probe: libVLC HWND video + Compose overlay in one window."
    mainClass.set("com.tvnext.app.core.player.ComposeHwSurfaceProbeKt")
    classpath = desktopMainCompilation.output.allOutputs + configurations["desktopRuntimeClasspath"] + probeRuntime
    jvmArgs(
        "-Dstdout.encoding=UTF-8",
        "-Dfile.encoding=UTF-8",
        "-Dcompose.interop.blending=true",
        "-Dskiko.renderApi=OPENGL",
        "-Djna.library.path=C:\\Temp\\kilo\\vlc\\vlc-3.0.23",
        "-Dvlc.install.dir=C:\\Temp\\kilo\\vlc\\vlc-3.0.23",
    )
}

// libVLC render probe: drives the production DesktopPlaybackController (libVLC
// backend) and reports whether real video frames reach the Compose ImageBitmap
// pipeline. Usage: .\gradlew :core:player:probeRunVlcRender --args="--duration=12000 <url> [<url>...]"
tasks.register<JavaExec>("probeRunVlcRender") {
    group = "diagnostics"
    description = "Runs the libVLC render probe through DesktopPlaybackController."
    mainClass.set("com.tvnext.app.core.player.VlcRenderProbeKt")
    classpath = desktopMainCompilation.output.allOutputs + configurations["desktopRuntimeClasspath"] + probeRuntime
    jvmArgs(
        "-Dstdout.encoding=UTF-8",
        "-Dfile.encoding=UTF-8",
        "-Djna.library.path=C:\\Temp\\kilo\\vlc\\vlc-3.0.23",
        "-Dvlc.install.dir=C:\\Temp\\kilo\\vlc\\vlc-3.0.23",
    )
}

// HLS prefetch probe: measures direct-CDN vs HlsPrefetchServer download speed
// for a VOD playlist. Usage:
//   .\gradlew :core:player:probeRunHlsPrefetch --args="<playlistUrl> <segmentCount>"
tasks.register<JavaExec>("probeRunHlsPrefetch") {
    group = "diagnostics"
    description = "Measures direct-CDN vs HlsPrefetchServer VOD download speed."
    mainClass.set("com.tvnext.app.core.player.HlsPrefetchProbeKt")
    classpath = desktopMainCompilation.output.allOutputs + configurations["desktopRuntimeClasspath"]
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}

