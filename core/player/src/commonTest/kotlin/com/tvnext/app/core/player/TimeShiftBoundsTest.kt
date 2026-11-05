package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeShiftBoundsTest {
    @Test fun crossesProgrammeBoundaryWithoutClampingToProgramme() {
        // Target precedes the currently loaded programme, but is inside provider retention.
        assertEquals(40_000L, boundedArchiveTarget(40_000, 100_000, 90_000))
    }
    @Test fun oldestAvailableTimeIsTheOnlyBackwardLimit() {
        assertEquals(10_000L, boundedArchiveTarget(-30_000, 100_000, 90_000))
    }
    @Test fun futurePositionReturnsToLiveEdge() {
        assertEquals(100_000L, boundedArchiveTarget(130_000, 100_000, 90_000))
    }
    @Test fun unknownDepthDoesNotInventADayLimit() {
        assertEquals(1_000L, boundedArchiveTarget(1_000, 100_000, null))
    }
}
