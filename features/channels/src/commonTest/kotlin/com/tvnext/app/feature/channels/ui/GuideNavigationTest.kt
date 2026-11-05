package com.tvnext.app.feature.channels.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuideNavigationTest {
    @Test fun noItemsNeverCrash() {
        assertNull(guideAdjacentIndex(0, -1, 1))
        assertNull(guideAdjacentIndex(0, 0, -1))
    }

    @Test fun dpadDownMovesOneRowAtATime() {
        assertEquals(1, guideAdjacentIndex(12, 0, 1))
        assertEquals(2, guideAdjacentIndex(12, 1, 1))
        assertEquals(3, guideAdjacentIndex(12, 2, 1))
    }

    @Test fun edgesStayWithinList() {
        assertEquals(0, guideAdjacentIndex(12, 0, -1))
        assertEquals(11, guideAdjacentIndex(12, 11, 1))
        assertEquals(0, guideAdjacentIndex(1, 0, 1))
    }

    @Test fun unselectedColumnStartsAtBoundary() {
        assertEquals(0, guideAdjacentIndex(3, -1, 1))
        assertEquals(2, guideAdjacentIndex(3, -1, -1))
    }
}

class GuideCentreWindowTest {
    @Test fun firstSelectedRowStaysInMiddleSlot() {
        assertEquals(-3..3, guideCentredIndexWindow(100, 0, 7))
    }

    @Test fun middleSelectedRowStaysInMiddleSlot() {
        assertEquals(47..53, guideCentredIndexWindow(100, 50, 7))
    }

    @Test fun lastSelectedRowStaysInMiddleSlot() {
        assertEquals(96..102, guideCentredIndexWindow(100, 99, 7))
    }

    @Test fun noProgrammesOrDatesNeverRequestRows() {
        assertEquals(0..-1, guideCentredIndexWindow(0, -1, 7))
    }

    @Test fun evenViewportCapacityUsesOddNumberOfFullRows() {
        assertEquals(8..12, guideCentredIndexWindow(21, 10, 6))
    }
}


class GuideClampedEdgeAndAlignmentTest {
    @Test fun firstChannelStartsAtTopWithoutEmptySlots() {
        assertEquals(0..6, guideClampedChannelWindow(100, 0, 7))
        assertEquals(0, guideChannelAnchorSlot(100, 0, 7))
    }

    @Test fun channelsNearTopDoNotHaveGapsAboveThem() {
        assertEquals(0..6, guideClampedChannelWindow(100, 2, 7))
        assertEquals(2, guideChannelAnchorSlot(100, 2, 7))
    }

    @Test fun middleChannelRemainsCentred() {
        assertEquals(47..53, guideClampedChannelWindow(100, 50, 7))
        assertEquals(3, guideChannelAnchorSlot(100, 50, 7))
    }

    @Test fun lastChannelUsesBottomOfVisibleRows() {
        assertEquals(93..99, guideClampedChannelWindow(100, 99, 7))
        assertEquals(6, guideChannelAnchorSlot(100, 99, 7))
    }

    @Test fun programmeAndDayHighlightMatchesChannelRowAtTop() {
        assertEquals(0..6, guideAlignedWindow(32, 0, 7, 0))
        assertEquals(2..8, guideAlignedWindow(32, 2, 7, 0))
    }

    @Test fun programmeAndDayHighlightMatchesChannelRowInMiddle() {
        assertEquals(-3..3, guideAlignedWindow(7, 0, 7, 3))
        assertEquals(-1..5, guideAlignedWindow(7, 2, 7, 3))
    }

    @Test fun emptyOrSmallListsNeverGenerateInvalidIndices() {
        assertEquals(0..-1, guideClampedChannelWindow(0, -1, 7))
        assertEquals(0..-1, guideAlignedWindow(0, -1, 7, 3))
        assertEquals(0..6, guideClampedChannelWindow(2, 0, 7))
    }
}
