package com.tvnext.app.core.iptv.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tvnext.app.core.database.TvNextDatabase

/** Fresh in-memory SQLDelight database for repository/store tests. */
internal fun createTestDatabase(): TvNextDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TvNextDatabase.Schema.create(driver)
    return TvNextDatabase(driver)
}
