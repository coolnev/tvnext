package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlin.test.*

class WindowedDescriptionProgrammeTest {
    private val playing = EpgProgramme("playing", 1, 2, "Играющий фильм")
    private val browsing = EpgProgramme("browsing", 3, 4, "Хитрый койот")
    @Test fun anotherChannelsProgrammeIsNotShownWhileEpgIsLoading() {
        assertNull(windowedDescriptionProgramme("playing", "browsing", ChannelTimelineState(), null,
            ChannelEpg(channelId = "browsing"), ChannelEpg(current = playing, channelId = "playing")))
    }
    @Test fun oldArchiveCursorCannotLeakIntoAnotherChannelsTimeline() {
        assertEquals(browsing, windowedDescriptionProgramme("playing", "browsing",
            ChannelTimelineState("browsing", false, listOf(browsing)), playing,
            ChannelEpg(current = browsing, channelId = "browsing"), ChannelEpg(current = playing, channelId = "playing")))
    }
    @Test fun matchingArchiveCursorStillShowsTheSelectedProgramme() {
        assertEquals(browsing, windowedDescriptionProgramme("playing", "browsing",
            ChannelTimelineState("browsing", false, listOf(browsing)), browsing,
            ChannelEpg(), ChannelEpg(current = playing, channelId = "playing")))
    }
    @Test fun playingProgrammeFollowsPlaybackInsteadOfAnOldBrowseCursor() {
        val advanced = playing.copy(start = 2, stop = 3, title = "Следующая передача")
        assertEquals(advanced, windowedDescriptionProgramme("playing", "playing", ChannelTimelineState(), null,
            ChannelEpg(current = playing, channelId = "playing"), ChannelEpg(current = advanced, channelId = "playing")))
    }
}
