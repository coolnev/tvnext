package com.tvnext.app.core.network.di

import io.ktor.client.HttpClient
import com.tvnext.app.core.network.createHttpClient
import org.koin.core.module.Module
import org.koin.dsl.module

val networkKoinModule: Module = module {
    single<HttpClient> { createHttpClient() }
}
