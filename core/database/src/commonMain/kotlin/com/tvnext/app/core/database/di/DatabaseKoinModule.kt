package com.tvnext.app.core.database.di

import app.cash.sqldelight.db.SqlDriver
import com.tvnext.app.core.database.AppMetaRepository
import com.tvnext.app.core.database.DatabaseFactory
import com.tvnext.app.core.database.SqlDelightAppMetaRepository
import com.tvnext.app.core.database.TvNextDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

val databaseKoinModule: Module = module {
    single<SqlDriver> { get<DatabaseFactory>().createDriver() }
    single { TvNextDatabase(get()) }
    single<AppMetaRepository> { SqlDelightAppMetaRepository(get()) }
}
