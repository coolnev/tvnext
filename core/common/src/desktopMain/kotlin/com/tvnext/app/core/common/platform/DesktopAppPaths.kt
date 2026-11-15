package com.tvnext.app.core.common.platform

import java.io.File

object DesktopAppPaths {
    val dataDirectory: File get() = System.getProperty("tvnext.data.dir")?.takeIf { it.isNotBlank() }
        ?.let(::File) ?: File(System.getProperty("user.home"),".tvnext")
}
