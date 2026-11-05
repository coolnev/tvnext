package com.tvnext.app.core.iptv.archive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Signals UI readers when the persisted archive depth has changed. */
object ArchiveDepthChanges {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision
    fun changed() { _revision.value = _revision.value + 1L }
}
