package com.tvnext.app.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.Flow

interface AppMetaRepository {
    suspend fun getValue(key: String): String?
    suspend fun setValue(key: String, value: String)
    fun observeAll(): Flow<List<App_meta>>
}

class SqlDelightAppMetaRepository(
    database: TvNextDatabase,
) : AppMetaRepository {

    private val queries = database.appMetaQueries

    override suspend fun getValue(key: String): String? =
        queries.selectByKey(key).executeAsOneOrNull()?.value_

    override suspend fun setValue(key: String, value: String) {
        queries.upsert(key = key, value_ = value)
    }

    override fun observeAll(): Flow<List<App_meta>> =
        queries.selectAll().asFlow().mapToList(EmptyCoroutineContext)
}
