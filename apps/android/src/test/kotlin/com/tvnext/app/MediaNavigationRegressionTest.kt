package com.tvnext.app

import com.tvnext.app.core.app.appHasVideoDestination
import com.tvnext.app.core.app.tab.AppTab
import com.tvnext.app.core.player.*
import org.junit.Assert.*
import org.junit.Test

class MediaNavigationRegressionTest {
    private val channel=PlaybackItem("channel","Канал","https://fixture.invalid/live")
    private val film=PlaybackItem("film","Фильм","https://fixture.invalid/film",PlaybackContentKind.VOD)
    @Test fun openingCatalogueDoesNotPromiseAViewportForTheHomeLiveChannel() {
        assertTrue(appHasVideoDestination(AppTab.HOME,false,null,channel))
        assertFalse(appHasVideoDestination(AppTab.MEDIA_LIBRARY,false,null,channel))
        assertFalse(appHasVideoDestination(AppTab.MEDIA_LIBRARY,false,channel.id,channel))
    }
    @Test fun selectedFilmGetsVideoOnlyWhenThePlayerActuallyOpenedThatFilm() {
        assertFalse(appHasVideoDestination(AppTab.MEDIA_LIBRARY,false,null,film))
        assertFalse(appHasVideoDestination(AppTab.MEDIA_LIBRARY,false,"other-film",film))
        assertTrue(appHasVideoDestination(AppTab.MEDIA_LIBRARY,false,film.id,film))
        assertFalse(appHasVideoDestination(AppTab.MEDIA_LIBRARY,true,film.id,film))
    }
    @Test fun guideAndChannelsKeepTheirViewportWhileSettingsAndSearchHideIt() {
        assertTrue(appHasVideoDestination(AppTab.TV_GUIDE,false,null,channel))
        assertTrue(appHasVideoDestination(AppTab.CHANNELS,false,null,channel))
        assertFalse(appHasVideoDestination(AppTab.SETTINGS,false,null,channel))
        assertFalse(appHasVideoDestination(AppTab.SEARCH,false,null,channel))
        assertFalse(appHasVideoDestination(AppTab.HOME,true,null,channel))
    }
}
