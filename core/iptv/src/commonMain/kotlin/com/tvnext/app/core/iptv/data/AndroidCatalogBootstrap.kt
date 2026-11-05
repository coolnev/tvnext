package com.tvnext.app.core.iptv.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Distinguish an initial catalog import from a genuinely unconfigured library. */
object AndroidCatalogBootstrap {
    val pending = MutableStateFlow(false)
}
