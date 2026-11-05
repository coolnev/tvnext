package com.tvnext.app.core.database

actual fun executeRawSql(sql: String) {
    // Android TV keeps the programme indexes in place during an import (the
    // UI constantly resolves "now playing" against them), so this path is
    // never taken there. Implemented anyway for symmetry.
    RawSqlDriverHolder.driver?.execute(null, sql, 0, null)
}

actual fun checkpointWal() {
    RawSqlDriverHolder.driver?.execute(null, "PRAGMA wal_checkpoint(TRUNCATE);", 0, null)
}
