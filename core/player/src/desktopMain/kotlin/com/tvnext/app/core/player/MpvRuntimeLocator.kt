package com.tvnext.app.core.player

import java.io.File

/**
 * Locates libmpv-2.dll so the JNA binding can load it regardless of how the
 * application was started (double-click on the jar, IDE, installed layout).
 *
 * Search order:
 *  1. `TVNEXT_MPV_DIR` environment variable (or `-Dtvnext.mpv.dir=...`);
 *  2. the directory of the running jar / classpath location;
 *  3. the process working directory, then its parent;
 *  4. `%LOCALAPPDATA%\TVNext` and `~/.tvnext` / `user.home/TVNext`;
 *  5. walking UP from the working directory towards the drive root
 *     (covers launches from a sub-folder of the project, e.g. `apps/desktop`);
 *  6. the system PATH (JNA falls back to it automatically).
 *
 * The found directory is added to `jna.library.path` so `Native.load("mpv", …)`
 * succeeds without any launcher flags. If nothing is found, a readable hint is
 * printed to stderr so the failure is diagnosable from the launch console.
 */
internal object MpvRuntimeLocator {

    private const val DLL = "libmpv-2.dll"

    /** Adds the directory containing libmpv-2.dll to jna.library.path. */
    fun prepare(): Boolean {
        val dir = locate()
        if (dir == null) {
            System.err.println(
                "TVNext: libmpv-2.dll NOT found. Put it next to the app, in the " +
                    "working directory, or set TVNEXT_MPV_DIR / -Dtvnext.mpv.dir.",
            )
            return false
        }
        val current = System.getProperty("jna.library.path")
        if (current != null && current.isNotBlank()) {
            if (current.split(File.pathSeparatorChar).any { it.equals(dir, ignoreCase = true) }) {
                return true
            }
        }
        val merged = if (current.isNullOrBlank()) dir else "$dir${File.pathSeparator}$current"
        System.setProperty("jna.library.path", merged)
        println("TVNext: libmpv-2.dll found at $dir")
        return true
    }

    fun locate(): String? {
        val candidates = buildList {
            System.getenv("TVNEXT_MPV_DIR")?.takeIf { it.isNotBlank() }?.let { add(it) }
            System.getProperty("tvnext.mpv.dir")?.takeIf { it.isNotBlank() }?.let { add(it) }
            jarDirectory()?.let { add(it) }
            // The process working directory itself, then its parent.
            File(".").absoluteFile.absolutePath?.let { add(it) }
            File(".").absoluteFile.parentFile?.absolutePath?.let { add(it) }
            // Common install locations.
            System.getenv("LOCALAPPDATA")?.let { add(File(it, "TVNext").absolutePath) }
            System.getProperty("user.home")?.let { add(File(it, "TVNext").absolutePath) }
            // Walk up from the cwd towards the drive root: covers launching
            // from a project sub-folder (IDE working dirs, etc.).
            var dir: File? = File(".").absoluteFile
            var hops = 0
            while (dir != null && hops < 8) {
                add(dir.absolutePath)
                dir = dir.parentFile
                hops++
            }
        }
        return candidates
            .map { File(it, DLL) }
            .firstOrNull { it.isFile }
            ?.parentFile
            ?.absolutePath
    }

    /** Directory containing the running jar (works for the fat jar and classes dirs). */
    private fun jarDirectory(): String? = try {
        val location = MpvRuntimeLocator::class.java.protectionDomain?.codeSource?.location
        val file = location?.toURI()?.let { File(it) } ?: return null
        if (file.isDirectory) file.absolutePath else file.parentFile?.absolutePath
    } catch (_: Throwable) {
        null
    }
}
