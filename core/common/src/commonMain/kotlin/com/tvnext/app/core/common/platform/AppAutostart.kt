package com.tvnext.app.core.common.platform

/**
 * Controls whether TVNext launches automatically when the user logs into the
 * OS. Windows-only for now (registered via [com.tvnext.app.feature.settings]
 * → «Запускать при загрузке Windows»); the Android build binds a no-op
 * implementation and the setting itself is hidden from the Android UI
 * (`isAndroidPlatform()` check in SettingsScreen), so [setEnabled] is never
 * actually invoked there.
 */
interface AppAutostart {
    /** True if the OS is currently configured to launch TVNext at login. */
    fun isEnabled(): Boolean

    /** Enables/disables OS autostart. Best-effort: never throws. */
    fun setEnabled(enabled: Boolean)
}

/** No-op binding for platforms without an autostart implementation (Android). */
class NoOpAppAutostart : AppAutostart {
    override fun isEnabled(): Boolean = false
    override fun setEnabled(enabled: Boolean) = Unit
}
