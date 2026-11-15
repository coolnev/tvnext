package com.tvnext.app.core.player

import com.tvnext.app.core.common.AppVersion
import com.tvnext.app.core.common.util.currentTimeMillis

data class StreamInfoFact(val label:String,val value:String)

/** A photographable snapshot: opening/refreshing it does not restart or probe the stream. */
data class PlaybackStreamInfo(
    val title:String,
    val sourceUrl:String,
    val capturedAtMs:Long=currentTimeMillis(),
    val facts:List<StreamInfoFact> = emptyList(),
    val lastRequestUrl:String?=null,
    val lastError:String?=null,
)

fun basicPlaybackStreamInfo(state:PlayerState):PlaybackStreamInfo {
    val d=state.diagnostics
    return PlaybackStreamInfo(state.item?.title.orEmpty(),state.item?.url.orEmpty(),facts=listOf(
        StreamInfoFact("Приложение","TVNext ${AppVersion.NAME}"),
        StreamInfoFact("Состояние",when(state.state) {
            PlaybackState.LOADING->"Загрузка";PlaybackState.BUFFERING->"Буферизация"
            PlaybackState.READY->"Готов"
            PlaybackState.PLAYING->"Воспроизведение";PlaybackState.PAUSED->"Пауза"
            PlaybackState.ENDED->"Завершено";PlaybackState.ERROR->"Ошибка";PlaybackState.IDLE->"Остановлено"
        }),
        StreamInfoFact("Режим",when(state.item?.kind) {
            PlaybackContentKind.LIVE->"Эфир";PlaybackContentKind.ARCHIVE->"Архив";PlaybackContentKind.VOD->"Медиатека";null->"—"
        }),
        StreamInfoFact("Видеокодек",d?.codec ?: "—"),
        StreamInfoFact("Видеодекодер",d?.decoder ?: "—"),
        StreamInfoFact("Декодирование",when(d?.actualMode) {
            PlayerDiagnostics.MODE_HARDWARE->"Аппаратный";PlayerDiagnostics.MODE_SOFTWARE->"Программный";else->"Не определено"
        }),
        StreamInfoFact("Разрешение",d?.resolution ?: "—"),
        StreamInfoFact("Частота кадров",d?.fps ?: "—"),
        StreamInfoFact("Аудио",d?.audio ?: "—"),
        StreamInfoFact("Вывод видео",d?.videoOutput ?: "—"),
    ),lastError=state.error)
}
