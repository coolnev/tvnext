package com.tvnext.app.core.database.di

import android.content.Context
import com.tvnext.app.core.database.DatabaseFactory
import org.koin.core.module.Module
import org.koin.dsl.module

val databaseAndroidKoinModule: Module = module {
    single { DatabaseFactory(get<Context>()) }
}
