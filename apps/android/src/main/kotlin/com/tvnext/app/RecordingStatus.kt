package com.tvnext.app

import com.tvnext.app.core.ui.i18n.uiText

internal fun SavedRecording.statusLabel(): String = when (status) {
    "recording" -> uiText("Идёт запись")
    "complete" -> uiText("Завершена")
    "stopped", "cancelled" -> uiText("Остановлена")
    "failed", "error" -> uiText("Ошибка")
    "partial" -> if (error.isBlank()) uiText("Остановлена") else uiText("Записана частично")
    else -> uiText("Неизвестно")
}
