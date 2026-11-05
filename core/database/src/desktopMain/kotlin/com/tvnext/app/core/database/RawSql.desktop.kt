package com.tvnext.app.core.database

actual fun executeRawSql(sql: String) {
    RawSqlDriverHolder.driver?.execute(null, sql, 0, null)
}

actual fun checkpointWal() {
    RawSqlDriverHolder.driver?.execute(null, "PRAGMA wal_checkpoint(TRUNCATE);", 0, null)
}
