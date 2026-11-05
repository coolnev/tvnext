package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoAspectModeTest {
    @Test
    fun everyTokenIsUniqueAndEveryFixedRatioIsPositive() {
        assertEquals(VideoAspectMode.entries.size, VideoAspectMode.entries.map { it.token }.toSet().size)
        VideoAspectMode.entries.forEach { mode ->
            assertEquals(mode, VideoAspectMode.fromToken(mode.token))
            if (mode.numerator > 0) assertTrue(mode.fixedRatio!! > 0f)
        }
        assertNull(VideoAspectMode.fromToken("invalid"))
    }

    @Test
    fun channelInheritsGlobalUnlessExplicitlyOverridden() {
        assertEquals(VideoAspectMode.RATIO_4_3, VideoAspectMode.resolved("inherit", "4:3"))
        assertEquals(VideoAspectMode.RATIO_16_9, VideoAspectMode.resolved("16:9", "4:3"))
        assertEquals(VideoAspectMode.AUTO, VideoAspectMode.resolved(null, "invalid"))
        assertEquals(VideoAspectMode.AUTO, VideoAspectMode.global("inherit"))
        assertEquals(VideoAspectMode.STRETCH, VideoAspectMode.channel("fill"))
    }

    @Test
    fun fixedRatiosFitInsideTheirContainer() {
        assertEquals(960 to 720, videoViewportSize(1280, 720, 4f / 3f))
        assertEquals(1280 to 720, videoViewportSize(1280, 720, 16f / 9f))
        assertEquals(405 to 720, videoViewportSize(1280, 720, 9f / 16f))
        assertEquals(1280 to 720, videoViewportSize(1280, 720, null))
        assertEquals(0 to 720, videoViewportSize(0, 720, 4f / 3f))
        assertEquals(1280 to 720, videoViewportSize(1280, 720, Float.NaN))
    }
}
