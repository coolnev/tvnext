package com.tvnext.app.core.common.result

sealed interface AppError {
    data class Network(val cause: Throwable? = null) : AppError
    data class Database(val cause: Throwable? = null) : AppError
    data class InvalidData(val message: String) : AppError
    data class License(val message: String) : AppError
    data class Playback(val message: String, val cause: Throwable? = null) : AppError
    data class InvalidPlaylist(val message: String) : AppError
    data class Parse(val message: String) : AppError
    data class EmptyPlaylist(val message: String) : AppError
    data object Unknown : AppError
}

fun AppError.toUserMessage(): String = when (this) {
    is AppError.Network -> "Ошибка сети. Проверьте подключение к интернету."
    is AppError.Database -> "Ошибка базы данных."
    is AppError.InvalidData -> message
    is AppError.License -> message
    is AppError.Playback -> message
    is AppError.InvalidPlaylist -> message
    is AppError.Parse -> message
    is AppError.EmptyPlaylist -> message
    AppError.Unknown -> "Неизвестная ошибка."
}
