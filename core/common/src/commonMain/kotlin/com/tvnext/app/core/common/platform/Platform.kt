package com.tvnext.app.core.common.platform

/**
 * True on Android (including Android TV). Used to enable TV-oriented UX
 * (e.g. initial focus placement) without affecting the Desktop behavior.
 */
expect fun isAndroidPlatform(): Boolean
