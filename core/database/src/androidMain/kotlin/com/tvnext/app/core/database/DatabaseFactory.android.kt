package com.tvnext.app.core.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DatabaseFactory(
    private val context: Context,
) {
    actual fun createDriver(): SqlDriver {
        val driver = AndroidSqliteDriver(
            schema = TvNextDatabase.Schema,
            context = context,
            name = "tvnext.db",
        )
        // WAL: readers never block the single EPG-import writer (and vice
        // versa). Without it, a big streaming import (vivamax ~1.4M rows) makes
        // concurrent readers/writers fail with SQLITE_BUSY.
        runCatching { driver.execute(null, "PRAGMA journal_mode=WAL;", 0) { } }
            .onFailure { e -> android.util.Log.w("TVNextDatabase", "WAL pragma failed: ${e.message}") }
        // Make competing writes WAIT (up to 60 s) instead of failing with
        // SQLITE_BUSY. Best effort: never break the startup on a driver/version
        // that rejects the pragmas.
        runCatching { driver.execute(null, "PRAGMA busy_timeout=60000;", 0) { } }
            .onFailure { e -> android.util.Log.w("TVNextDatabase", "busy_timeout pragma failed: ${e.message}") }
        // NORMAL is safe under WAL (only FULL protects against an OS crash,
        // not an app crash, and WAL already guarantees consistency on both).
        // It skips the fsync after every transaction commit, which matters a
        // lot here: a large EPG import commits every 500 channel rows / 10 000
        // programme rows — with the default FULL mode that is one fsync per
        // batch, i.e. real, sustained flash I/O and CPU wake-ups on a TV box
        // for the whole multi-minute import. NORMAL removes most of that cost.
        runCatching { driver.execute(null, "PRAGMA synchronous=NORMAL;", 0) { } }
            .onFailure { e -> android.util.Log.w("TVNextDatabase", "synchronous pragma failed: ${e.message}") }
        // Do not checkpoint/truncate a possibly large legacy WAL during startup.
        // EPG now owns a separate database; startup reads stay independent.
        registerRawSqlDriver(driver)
        return driver
    }
}
