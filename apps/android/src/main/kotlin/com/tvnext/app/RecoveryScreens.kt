package com.tvnext.app

/** Single left navigation lives in AppShell/Home. Feature dialogs are destinations, not another menu. */
enum class RecoveryScreen {
    NONE, CHANNEL_EDITOR, RECORDINGS, PROFILES, RECORDING_SETTINGS, VIDEO_ASPECT, BACKUP
}

enum class RecoverySection { PROFILES, RECORDINGS, RECORDING_SETTINGS }
