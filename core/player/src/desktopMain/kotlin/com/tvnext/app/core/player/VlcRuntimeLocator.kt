package com.tvnext.app.core.player

import java.io.File

/**
 * Locates the libVLC native runtime (`libvlc.dll` + `plugins/`) used by the
 * desktop libVLC backend.
 *
 * Sources, in priority order:
 *  1. `vlc.install.dir` / `jna.library.path` system properties (dev / explicit);
 *  2. the `TVNEXT_VLC_DIR` environment variable;
 *  3. a `vlc` directory bundled next to the application jar (production
 *     deployment — both `<appDir>/vlc` and `<distRoot>/vlc` for the jpackage
 *     layout `<distRoot>/app/`);
 *  4. an installed VLC (`Program Files\VideoLAN\VLC`, both 32/64-bit roots);
 *  5. the working directory (`user.dir/vlc`, `user.dir`);
 *  6. a per-user bundled cache `%LOCALAPPDATA%\TVNext\vlc`.
 *
 * Before a [MediaPlayerFactory] is created, [prepare] must be called so vlcj /
 * JNA can find the native library. This keeps the app runnable on a clean
 * machine without requiring the user to install VLC manually (bundled mode).
 */
internal object VlcRuntimeLocator {

    /**
     * Returns the directory containing a valid libVLC runtime, or `null` if no
     * usable runtime was found.
     */
    fun locate(): File? = candidates().firstOrNull { isValidVlcDir(it) }

    /**
     * Makes the located libVLC runtime visible to vlcj/JNA by setting
     * `vlc.install.dir` and appending to `jna.library.path`. No-op when no
     * runtime is found (the caller then reports an error through the
     * [PlaybackError] contract instead of crashing).
     */
    fun prepare() {
        val dir = locate() ?: return
        System.setProperty("vlc.install.dir", dir.absolutePath)
        val existing = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (existing.isNullOrBlank()) {
                dir.absolutePath
            } else {
                "$existing${File.pathSeparator}${dir.absolutePath}"
            },
        )
    }

    private fun candidates(): List<File> = buildList {
        System.getProperty("vlc.install.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { add(File(it)) }
        System.getProperty("jna.library.path")
            ?.split(File.pathSeparatorChar, ';')
            ?.filter { it.isNotBlank() }
            ?.forEach { add(File(it)) }
        System.getenv("TVNEXT_VLC_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let { add(File(it)) }
        // Bundled next to the application: handles both "run from a plain jar
        // with a vlc/ folder beside it" and the jpackage layout where the jar
        // lives in <distRoot>/app and vlc/ sits in <distRoot>.
        codeSourceDir()?.let { base ->
            var current: File? = base
            repeat(3) {
                val dir = current ?: return@let
                add(File(dir, "vlc"))
                add(dir)
                current = dir.parentFile
            }
        }
        // Installed VLC is a trusted, installer-controlled source.
        listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            .forEach { pf -> add(File(File(pf, "VideoLAN"), "VLC")) }
        // User-writable locations are only fallbacks: a hostile vlc/ folder in
        // the working directory (or in the per-user cache) must never take
        // precedence over the bundled or installed runtime.
        System.getProperty("user.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { userDir ->
                add(File(userDir, "vlc"))
                add(File(userDir))
            }
        System.getenv("LOCALAPPDATA")?.let { add(File(File(it, "TVNext"), "vlc")) }
    }

    private fun isValidVlcDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val libraryName = when {
            isWindows() -> "libvlc.dll"
            isMacOs() -> "libvlc.dylib"
            else -> "libvlc.so"
        }
        if (!File(dir, libraryName).isFile) return false
        // Codecs, demuxers and network modules live in plugins/ — without it
        // libVLC cannot open HLS/HEVC/4K streams.
        return File(dir, "plugins").isDirectory
    }

    /**
     * Directory containing the TVNext jar/classes, so the bundled `vlc/`
     * folder can be located regardless of the working directory.
     */
    private fun codeSourceDir(): File? = try {
        val location = DesktopPlaybackController::class.java
            .protectionDomain
            ?.codeSource
            ?.location
            ?.toURI()
            ?.let(::File)
        location?.let { if (it.isFile) it.parentFile else it }
    } catch (e: Exception) {
        null
    }

    private fun isWindows(): Boolean = System.getProperty("os.name")
        ?.lowercase()?.contains("windows") == true

    private fun isMacOs(): Boolean = System.getProperty("os.name")
        ?.lowercase()?.contains("mac") == true
}
