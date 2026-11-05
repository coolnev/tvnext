package com.tvnext.app.feature.channels.ui

import com.tvnext.app.core.iptv.domain.Channel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidArchiveIndicatorTest {
    private val channel = Channel("id", "Channel", 1, "News", "https://example.com/live")

    @Test fun channelWithoutArchiveHasNoClock() {
        assertFalse(androidHasArchive(channel))
    }
    @Test fun disabledProviderFlagsHaveNoClock() {
        assertFalse(androidHasArchive(channel.copy(catchup = "none", catchupType = "0", timeshift = "false", catchupDays = 0)))
    }
    @Test fun archiveDoesNotRequireCurrentProgramme() {
        assertTrue(androidHasArchive(channel.copy(catchup = "append")))
        assertTrue(androidHasArchive(channel.copy(catchupDays = 7)))
        assertTrue(androidHasArchive(channel.copy(catchupSource = "https://example.com/archive?utc={utc}")))
    }
}
