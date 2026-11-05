package com.tvnext.app.core.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Process-lifetime state for the Channels and Media watching lists.
 *
 * F11 and double-click fullscreen recreate the window and therefore the entire
 * Compose tree, which would wipe any `remember`ed scroll position inside a
 * screen and make the list jump back to the top. Keeping the list state here
 * (a process-wide singleton, like the Koin singletons the app already relies
 * on) lets the scroll position and the expanded category survive those window
 * round-trips, so the currently playing item stays visible.
 *
 * The expanded-category defaults mirror the feature constants:
 *  - channels: ALL_CATEGORIES == "__all"
 *  - media:    ALL_CATEGORY_KEY == ""
 */
object TvListState {
    /** Channels: scroll state of the watching list (side panel / immersive drawer). */
    val channelsListState = LazyListState()

    /** Channels: expanded category of the watching list. */
    val channelsExpandedCategory: MutableState<String?> = mutableStateOf("__all")
    
    /** Channels Android TV: сохранение позиции фокуса для каждой категории. */
    val channelsFocusPosition: MutableMap<String?, Int> = mutableMapOf()

    /** Media: scroll state of the library list (side panel / immersive drawer). */
    val mediaListState = LazyListState()

    /** Media: expanded category of the library list. */
    val mediaExpandedCategory: MutableState<String?> = mutableStateOf("")
}
