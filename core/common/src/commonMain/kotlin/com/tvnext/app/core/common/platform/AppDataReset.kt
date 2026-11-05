package com.tvnext.app.core.common.platform

/**
 * Wipes ALL locally stored application data (database, image cache, settings,
 * EPG) and restarts the process so the next launch starts clean.
 *
 * Implementations are platform-specific (desktop / android) and registered in
 * DI; the same instance is also used at startup — before the database is
 * opened — via [handlePendingReset].
 */
interface AppDataReset {
    /**
     * Called at app startup, BEFORE the database and caches are opened. If a
     * previous [requestResetAndRestart] left a pending marker, the data
     * directory is deleted so the fresh launch starts empty.
     */
    fun handlePendingReset()

    /**
     * Writes the reset marker and restarts the app. On the next launch
     * [handlePendingReset] performs the actual wipe: the data must NOT be
     * deleted while the database is still open (on Windows the SQLite file is
     * locked by the running process).
     */
    fun requestResetAndRestart()
}
