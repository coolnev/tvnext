package com.tvnext.app.core.network

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient
