package com.tvnext.app.feature.channels.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidChannelFocusTest {
    @Test fun reorderPreservesIdentityRatherThanOldIndex() {
        assertEquals("b", resolveAndroidChannelFocus(listOf("c", "a", "b"), "b", "a"))
    }
    @Test fun deletedFocusedChannelFallsBackToSelection() {
        assertEquals("a", resolveAndroidChannelFocus(listOf("c", "a"), "b", "a"))
    }
    @Test fun differentCategoryFallsBackToFirstVisibleChannel() {
        assertEquals("c", resolveAndroidChannelFocus(listOf("c", "d"), "b", "a"))
    }
    @Test fun explicitCategoryPickRequestsTopWithoutChangingSelection() {
        assertEquals("c", resolveAndroidChannelFocus(listOf("c", "a"), "a", "a", true))
    }
    @Test fun emptySearchHasNoFocusTarget() {
        assertNull(resolveAndroidChannelFocus(emptyList(), "b", "a"))
    }
}
