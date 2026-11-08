package com.tvnext.app.feature.settings.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.tvnext.app.core.database.SqlDelightAppMetaRepository
import com.tvnext.app.core.database.TvNextDatabase
import com.tvnext.app.core.player.ClockPosition
import com.tvnext.app.core.ui.theme.AccentColor
import com.tvnext.app.feature.settings.domain.AppSettings
import com.tvnext.app.feature.settings.domain.ThemePreference
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Interface/theme settings persistence: theme, accent color and on-screen clock
 * position survive a save → reload round-trip (app restart).
 */
class SettingsRepositoryTest {

    private fun createRepo(): SettingsRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TvNextDatabase.Schema.create(driver)
        return SqlDelightSettingsRepository(SqlDelightAppMetaRepository(TvNextDatabase(driver)))
    }

    @Test
    fun clockPositionPersistsAcrossRestart() = runBlocking {
        val repo = createRepo()
        repo.save(AppSettings(clockPosition = ClockPosition.TOP_RIGHT))

        // New repository instance over the same storage = app restart.
        val loaded = repo.load()

        assertEquals(ClockPosition.TOP_RIGHT, loaded.clockPosition)
    }

    @Test
    fun accentColorPersistsAcrossRestart() = runBlocking {
        val repo = createRepo()
        repo.save(AppSettings(accentColor = AccentColor.PURPLE))

        val loaded = repo.load()

        assertEquals(AccentColor.PURPLE, loaded.accentColor)
    }

    @Test
    fun themePersistsAcrossRestart() = runBlocking {
        val repo = createRepo()
        repo.save(AppSettings(theme = ThemePreference.SYSTEM))

        val loaded = repo.load()

        assertEquals(ThemePreference.SYSTEM, loaded.theme)
    }

    @Test
    fun fullSettingsRoundTrip() = runBlocking {
        val repo = createRepo()
        val saved = AppSettings(
            language = com.tvnext.app.core.common.UiLanguage.CHINESE,
            theme = ThemePreference.LIGHT,
            accentColor = AccentColor.ORANGE,
            clockPosition = ClockPosition.TOP_LEFT,
            startFullscreen = true,
            showChannelNumbers = false,
            openLastChannel = true,
            epgUrl = "http://epg.example/x.xml",
        )

        repo.save(saved)
        assertEquals(saved, repo.load())
    }
}
