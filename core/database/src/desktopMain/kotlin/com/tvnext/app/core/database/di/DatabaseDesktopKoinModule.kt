package com.tvnext.app.core.database.di

import com.tvnext.app.core.database.DatabaseFactory
import org.koin.core.module.Module
import org.koin.dsl.module

val databaseDesktopKoinModule: Module = module {
    single { DatabaseFactory() }
}
