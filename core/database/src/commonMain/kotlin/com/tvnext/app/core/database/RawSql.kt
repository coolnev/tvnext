package com.tvnext.app.core.database

/**
 * Executes a single raw SQL statement against the app database (index
 * management for the fast EPG bulk-import path). SQLDelight cannot generate
 * DDL statements that duplicate schema-declared indexes, so the platform layer
 * registers the live [app.cash.sqldelight.db.SqlDriver] here.
 */
expect fun executeRawSql(sql: String)

/** Best-effort WAL checkpoint used after large EPG batch replacement. */
expect fun checkpointWal()

/** Registers the live driver (called once by the platform database factory). */
fun registerRawSqlDriver(driver: app.cash.sqldelight.db.SqlDriver) {
    RawSqlDriverHolder.driver = driver
}

internal object RawSqlDriverHolder {
    @Volatile
    var driver: app.cash.sqldelight.db.SqlDriver? = null
}
