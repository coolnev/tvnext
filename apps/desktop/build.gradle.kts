import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.decompose)
    implementation(libs.essenty.lifecycle.coroutines)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(project(":core:common", configuration = "desktopRuntimeElements"))
    implementation(project(":core:ui", configuration = "desktopRuntimeElements"))
    implementation(project(":core:app", configuration = "desktopRuntimeElements"))
    implementation(project(":core:database", configuration = "desktopRuntimeElements"))
    implementation(project(":core:network", configuration = "desktopRuntimeElements"))
    implementation(project(":core:tmdb", configuration = "desktopRuntimeElements"))
    implementation(project(":core:iptv", configuration = "desktopRuntimeElements"))
    implementation(project(":core:player", configuration = "desktopRuntimeElements"))
    implementation(project(":core:licensing", configuration = "desktopRuntimeElements"))
    implementation(project(":features:home", configuration = "desktopRuntimeElements"))
    implementation(project(":features:channels", configuration = "desktopRuntimeElements"))
    implementation(project(":features:settings", configuration = "desktopRuntimeElements"))
}

compose.desktop {
    application {
        mainClass = "com.tvnext.app.MainKt"
        nativeDistributions {
            // Windows installer (setup.exe) via jpackage --type exe.
            targetFormats(TargetFormat.Exe)
            packageName = "TVNext"
            packageVersion = "1.0.13"
            vendor = "TVNext"
            description = "TVNext — IPTV player (live TV, archives, VOD, EPG)"
            licenseFile.set(project.file("LICENSE.txt"))
            // The auto-detected jlink module set was too minimal: the app
            // crashed at startup with NoClassDefFoundError (java.sql.DriverManager)
            // and JNA (libmpv) needs jdk.unsupported. Pin the full set here.
            // jdk.httpserver powers the HLS VOD prefetch proxy (HlsPrefetchServer).
            modules(
                "java.base", "java.datatransfer", "java.prefs", "java.xml",
                "java.desktop", "java.logging", "jdk.crypto.ec",
                "java.sql", "java.naming", "java.management", "jdk.unsupported",
                "jdk.httpserver",
            )
            // libmpv is bundled by the buildWindowsInstaller task (the plugin's
            // appResourcesRootDir is not packaged into the APK image in this
            // Compose version), so no appResourcesRootDir here.
            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "TVNext"
                shortcut = true
            }
        }
        // Dev-only: point the libVLC backend at a local VLC runtime.
        //   .\gradlew :apps:desktop:run -PvlcDir=C:\path\to\vlc
        // Auto-detected fallbacks: TVNEXT_VLC_DIR env var, then the standard
        // dev location. Without any of these the app falls back to the bundled
        // runtime discovery (VlcRuntimeLocator: vlc/ next to the app,
        // %LOCALAPPDATA%\TVNext\vlc, installed VLC).
        val vlcDir = providers.gradleProperty("vlcDir").orNull
            ?: System.getenv("TVNEXT_VLC_DIR")?.takeIf { it.isNotBlank() }
            ?: "C:\\Temp\\kilo\\vlc\\vlc-3.0.23".takeIf { File(it, "libvlc.dll").isFile }
        if (vlcDir != null) {
            jvmArgs += "-Dvlc.install.dir=$vlcDir"
            jvmArgs += "-Djna.library.path=$vlcDir"
        }
        val playerDebug = providers.gradleProperty("playerDebug").orNull
        if (playerDebug != null) {
            jvmArgs += "-Dtvnext.player.debug=true"
        }
    }
}

// Diagnostics: prints the full runtime classpath, one entry per line. Used by
// the packaging verification (run the app from a dist folder with libVLC
// bundled next to the jars). Not part of the app.
tasks.register("printRuntimeClasspath") {
    doLast {
        configurations.runtimeClasspath.get().forEach { println(it.absolutePath) }
    }
}

/**
 * Builds the Windows setup.exe installer with the mpv runtime bundled and the
 * user data wiped on uninstall.
 *
 * TVNext stores ALL user data (SQLDelight database, logo cache) in
 * `%USERPROFILE%\.tvnext` — outside the install folder, so a plain
 * jpackage-based uninstall leaves it behind (a reinstall "remembers" old
 * playlists/history). jpackage cannot run custom uninstall actions, so this
 * task builds the installer directly with WiX 3.11 (build/wix311):
 *
 *  1. builds the app image (createDistributable);
 *  2. copies libmpv-2.dll + d3dcompiler_43.dll next to the jars;
 *  3. harvests the image with heat.exe into WiX components;
 *  4. compiles + links an MSI (WixUI_InstallDir: license + directory chooser,
 *     per-user install, start-menu + desktop shortcuts) whose RemoveFolderEx
 *     component deletes `%USERPROFILE%\.tvnext` on uninstall;
 *  5. wraps the MSI in a Burn bootstrapper (setup.exe).
 *
 * Requirements: WiX Toolset binaries are vendored under build/wix311 (they
 * are checked out by :downloadWix). No extra JDK with jpackage is needed.
 */
tasks.register("buildWindowsInstaller") {
    group = "distribution"
    description = "Builds the Windows setup.exe installer with the bundled mpv runtime; uninstall wipes the user data."
    dependsOn("createDistributable")
    doLast {
        val wixDir = rootProject.layout.projectDirectory.dir("build/wix311").asFile
        val wixSources = layout.projectDirectory.dir("wix").asFile
        val imageDir = layout.buildDirectory.dir("compose/binaries/main/app/TVNext").get().asFile
        val workDir = layout.buildDirectory.dir("wix").get().asFile
        val exeOut = layout.buildDirectory.dir("compose/binaries/main/exe").get().asFile

        fun runProc(label: String, cmd: List<String>, cwd: File) {
            val process = ProcessBuilder(cmd)
                .directory(cwd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit != 0) {
                throw GradleException(
                    "[$label] failed with exit code $exit.\nCommand: ${cmd.joinToString(" ")}\n$output",
                )
            }
            if (output.isNotBlank()) println("[$label]\n$output")
        }

        // 1. Bundle libmpv + d3dcompiler next to the jars (the first place
        //    MpvRuntimeLocator looks in a packaged app).
        layout.projectDirectory.dir("app-resources").asFile
            .copyRecursively(File(imageDir, "app"), overwrite = true)
        workDir.mkdirs()
        exeOut.mkdirs()

        val heat = File(wixDir, "heat.exe")
        val candle = File(wixDir, "candle.exe")
        val light = File(wixDir, "light.exe")
        val filesWxs = File(workDir, "files.wxs")
        val mainWixobj = File(workDir, "main.wixobj")
        val filesWixobj = File(workDir, "files.wixobj")
        val bundleWixobj = File(workDir, "bundle.wixobj")
        val msi = File(workDir, "TVNext-1.0.13.msi")
        val setupExe = File(exeOut, "TVNext-1.0.13.exe")

        // 2. Harvest the app image into WiX components (INSTALLFOLDER root).
        runProc(
            "heat",
            listOf(
                heat.absolutePath, "dir", imageDir.absolutePath,
                "-cg", "AppFiles", "-gg", "-scom", "-sreg", "-srd", "-ke", "-sf",
                "-template", "fragment", "-dr", "INSTALLFOLDER",
                "-out", filesWxs.absolutePath,
            ),
            workDir,
        )

        // 3. Compile the product + harvested files (WixUtilExtension for
        //    RemoveFolderEx, WixUIExtension for WixUI_InstallDir).
        runProc(
            "candle (product)",
            listOf(
                candle.absolutePath,
                "-ext", "WixUIExtension",
                File(wixSources, "main.wxs").absolutePath,
                filesWxs.absolutePath,
                "-out", workDir.absolutePath + File.separator,
            ),
            workDir,
        )

        // 4. Link the MSI. -b points light at the app image (harvested
        //    "SourceDir\..." paths) and at apps/desktop (relative paths to
        //    icon.ico / LICENSE.rtf inside main.wxs). -sval skips the ICE
        //    validation: ICE38/ICE91 complain about file KeyPaths in per-user
        //    directories, which is exactly how a per-user install works.
        runProc(
            "light (msi)",
            listOf(
                light.absolutePath,
                "-ext", "WixUIExtension",
                "-sval",
                "-b", imageDir.absolutePath,
                "-b", wixSources.absolutePath,
                mainWixobj.absolutePath, filesWixobj.absolutePath,
                "-out", msi.absolutePath,
            ),
            workDir,
        )

        // 5. Compile + link the Burn bootstrapper (setup.exe).
        runProc(
            "candle (bundle)",
            listOf(
                candle.absolutePath, "-ext", "WixBalExtension",
                File(wixSources, "bundle.wxs").absolutePath,
                "-out", bundleWixobj.absolutePath,
            ),
            workDir,
        )
        runProc(
            "light (exe)",
            listOf(
                light.absolutePath, "-ext", "WixBalExtension",
                "-b", wixSources.absolutePath,
                bundleWixobj.absolutePath,
                "-o", setupExe.absolutePath,
            ),
            workDir,
        )

        println("The installer is written to $setupExe")
    }
}

// Packaging: builds a single runnable fat jar with all dependencies so the
// desktop app can be shipped as a portable build (libVLC runtime is placed
// next to it in a `vlc/` folder — VlcRuntimeLocator discovers it there).
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds a single runnable jar with all dependencies."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.tvnext.app.MainKt"
        attributes["Implementation-Title"] = "TVNext"
        attributes["Implementation-Version"] = "portable"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.isFile && it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/versions/**/module-info.class",
            "module-info.class",
            "META-INF/INDEX.LIST",
        )
    }
}
