package com.tvnext.app.core.app

import com.tvnext.app.core.app.tab.AppTab
import com.tvnext.app.core.player.PlaybackContentKind
import com.tvnext.app.core.player.PlaybackItem

/** The catalogue has no viewport for a live channel carried over from Home. */
fun appHasVideoDestination(tab:AppTab,overlay:Boolean,selectedFilm:String?,playing:PlaybackItem?):Boolean =
    !overlay && when(tab) {
        AppTab.HOME,AppTab.CHANNELS,AppTab.TV_GUIDE->true
        AppTab.MEDIA_LIBRARY->playing?.kind==PlaybackContentKind.VOD && playing.id==selectedFilm
        else->false
    }
