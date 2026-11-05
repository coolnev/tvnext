package com.tvnext.app

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.tvnext.app.core.common.platform.AppAutostart
import java.io.File

/**
 * Windows implementation of [AppAutostart]: adds/removes a value under
 * `HKEY_CURRENT_USER\...\Run`. HKCU (not HKLM) matches the per-user,
 * no-admin-rights install this app already uses (see `main.wxs`:
 * `InstallScope="perUser"`) — writing HKLM would need elevation this app
 * never asks for.
 *
 * Uses the JNA dependency already bundled for libmpv, so no new native
 * dependency is introduced.
 */
class DesktopAppAutostart : AppAutostart {

    override fun isEnabled(): Boolean = runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
    }.getOrDefault(false)

    override fun setEnabled(enabled: Boolean) {
        runCatching {
            if (enabled) {
                val exe = findExecutable() ?: return
                // Quoted: the install path can contain spaces
                // ("C:\Users\<name>\AppData\Local\TVNext\TVNext.exe").
                Advapi32Util.registrySetStringValue(
                    WinReg.HKEY_CURRENT_USER,
                    RUN_KEY,
                    VALUE_NAME,
                    "\"${exe.absolutePath}\"",
                )
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
            }
        }
    }

    /**
     * Same jpackage-layout lookup as [DesktopAppDataReset.findExecutable]:
     * the jar lives in `<install>/app`, the `.exe` in `<install>`. In a dev
     * run (no packaged exe) this returns null and the toggle is a silent
     * no-op — there is nothing meaningful to register for autostart.
     */
    private fun findExecutable(): File? {
        val jar = System.getProperty("java.class.path")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.map { File(it) }
            ?.firstOrNull { it.isFile && it.name.endsWith(".jar") }
        var dir = jar?.parentFile
        repeat(3) {
            if (dir == null) return null
            File(dir, "TVNext.exe").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private companion object {
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE_NAME = "TVNext"
    }
}
