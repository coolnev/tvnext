package com.tvnext.app.core.tmdb

import com.tvnext.app.core.database.AppMetaRepository
import com.tvnext.app.core.database.App_meta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbSettingsStoreTest {
    private class FakeMeta : AppMetaRepository {
        private val values = mutableMapOf<String, String>()
        override suspend fun getValue(key: String): String? = values[key]
        override suspend fun setValue(key: String, value: String) { values[key] = value }
        override fun observeAll(): Flow<List<App_meta>> = flowOf(emptyList())
    }

    @Test
    fun persistsEnabledAndApiKey() = runBlocking {
        val store = SqlDelightTmdbSettingsStore(FakeMeta())
        // Out of the box TMDB is ON with the built-in key (no manual setup).
        assertTrue(store.isEnabled())
        assertEquals(SqlDelightTmdbSettingsStore.DEFAULT_API_KEY, store.getApiKey())

        store.setEnabled(true)
        store.setApiKey("  abc123  ")

        assertTrue(store.isEnabled())
        assertEquals("abc123", store.getApiKey())
    }

    @Test
    fun userCanDisableAndClearKey() = runBlocking {
        val store = SqlDelightTmdbSettingsStore(FakeMeta())
        store.setEnabled(false)
        store.setApiKey("")
        // Explicit "off" is respected; an empty key falls back to the default.
        assertFalse(store.isEnabled())
        assertEquals(SqlDelightTmdbSettingsStore.DEFAULT_API_KEY, store.getApiKey())
    }
}
