package com.tvnext.app.core.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackErrorTest {

    @Test
    fun networkMapsToNetworkMessage() {
        assertEquals(
            "Не удалось подключиться к потоку. Проверьте сеть.",
            PlaybackError.Network.toUserMessage(),
        )
    }

    @Test
    fun unsupportedFormatMapsToUnsupportedMessage() {
        assertEquals(
            "Формат потока не поддерживается.",
            PlaybackError.UnsupportedFormat.toUserMessage(),
        )
    }

    @Test
    fun decoderMapsToDecoderMessage() {
        assertEquals(
            "Не удалось декодировать видеопоток.",
            PlaybackError.Decoder.toUserMessage(),
        )
    }

    @Test
    fun timeoutMapsToTimeoutMessage() {
        assertEquals(
            "Таймаут загрузки потока. Попробуйте ещё раз.",
            PlaybackError.Timeout.toUserMessage(),
        )
    }

    @Test
    fun unknownMapsToUnknownMessage() {
        assertEquals(
            "Не удалось воспроизвести канал.",
            PlaybackError.Unknown.toUserMessage(),
        )
    }
}
