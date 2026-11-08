package com.tvnext.app

import java.io.IOException

internal enum class RecordingFailureReason { LIMIT, STORAGE_FULL, STORAGE_UNAVAILABLE, STORAGE_WRITE, NETWORK, SOURCE }

/** Safe reason codes keep source URLs and platform exception messages out of the saved library. */
internal class RecordingFailure(val reason: RecordingFailureReason, cause: Throwable? = null) :
    IOException("Recording failure: ${reason.name}", cause)

internal fun recordingFailureMessage(failure: Exception): String = when ((failure as? RecordingFailure)?.reason) {
    RecordingFailureReason.LIMIT -> "Достигнут лимит записей TVNext"
    RecordingFailureReason.STORAGE_FULL -> "Недостаточно свободного места для записи"
    RecordingFailureReason.STORAGE_UNAVAILABLE -> "Накопитель для записи недоступен"
    RecordingFailureReason.STORAGE_WRITE -> "Не удалось записать файл на накопитель"
    RecordingFailureReason.NETWORK -> "Сбой сети или чтения потока"
    RecordingFailureReason.SOURCE -> "Источник не отдаёт поддерживаемый поток"
    null -> if (failure is IOException) "Сбой сети или чтения потока" else "Не удалось полностью записать поток"
}
