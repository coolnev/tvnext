package com.tvnext.app.feature.settings.ui

import com.tvnext.app.core.tmdb.KinopoiskConnectionResult
import com.tvnext.app.core.ui.i18n.uiText

fun KinopoiskConnectionResult.uiMessage():String=when(kind) {
    KinopoiskConnectionResult.Kind.SUCCESS->uiText("Кинопоиск: соединение установлено")
    KinopoiskConnectionResult.Kind.KEY_MISSING->uiText("Кинопоиск: ключ не задан")
    KinopoiskConnectionResult.Kind.INVALID_KEY->uiText("Кинопоиск: ключ отклонён (HTTP {0})",httpStatus ?: "?")
    KinopoiskConnectionResult.Kind.LIMIT_REACHED->uiText("Кинопоиск: лимит запросов исчерпан (HTTP {0})",httpStatus ?: "?")
    KinopoiskConnectionResult.Kind.NO_CONNECTION->uiText("Кинопоиск: нет ответа от сервера — проверьте подключение")
    KinopoiskConnectionResult.Kind.SERVER_ERROR->uiText("Кинопоиск: ошибка сервера (HTTP {0})",httpStatus ?: "?")
    KinopoiskConnectionResult.Kind.INVALID_RESPONSE->uiText("Кинопоиск: некорректный ответ сервера")
}
