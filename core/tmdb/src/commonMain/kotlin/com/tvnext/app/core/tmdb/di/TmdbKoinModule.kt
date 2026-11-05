package com.tvnext.app.core.tmdb.di

import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.tmdb.SqlDelightTmdbRepository
import com.tvnext.app.core.tmdb.SqlDelightTmdbSettingsStore
import com.tvnext.app.core.tmdb.TmdbRepository
import com.tvnext.app.core.tmdb.TmdbSettingsStore
import com.tvnext.app.core.tmdb.KinopoiskKeyStore
import com.tvnext.app.core.tmdb.SqlDelightKinopoiskKeyStore
import com.tvnext.app.core.tmdb.KinopoiskProgrammeLookup
import com.tvnext.app.core.tmdb.ProgrammeMetadataRepository
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module

val tmdbKoinModule: Module = module {
    single<TmdbSettingsStore> { SqlDelightTmdbSettingsStore(get()) }
    single<TmdbRepository> { SqlDelightTmdbRepository(get<HttpClient>(), get<TvNextDatabase>(), get()) }
    single<KinopoiskKeyStore> { SqlDelightKinopoiskKeyStore(get()) }
    single { KinopoiskProgrammeLookup(get<HttpClient>(), get()) }
    single { ProgrammeMetadataRepository(get(), get(), get()) }
}
