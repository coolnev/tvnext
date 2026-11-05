package com.tvnext.app.core.iptv.data

import com.tvnext.app.core.iptv.xmltv.EpgProgramme
import kotlin.test.Test
import kotlin.test.assertEquals

class EpgArchiveHistoryTest {
    private val now = 8L * 86_400_000L
    private fun entry(start: Long, title: String) = EpgProgramme("channel", start, start + 3_600_000L, title)

    @Test fun midnightImportKeepsYesterday() {
        val yesterday = entry(now - 8 * 3_600_000L, "Вчерашняя передача")
        val today = entry(now + 3_600_000L, "Сегодня")
        val actual = mergeEpgArchiveHistory(listOf(yesterday), listOf(today), now, now - 7 * 86_400_000L)
        assertEquals(listOf(yesterday, today), actual)
    }

    @Test fun correctedProgrammeFromProviderWins() {
        val before = entry(now - 3_600_000L, "Старое название")
        val update = before.copy(title = "Исправленное название")
        assertEquals(listOf(update), mergeEpgArchiveHistory(listOf(before), listOf(update), now, now - 86_400_000L))
    }

    @Test fun expiredArchiveIsNotRetained() {
        val old = entry(now - 3 * 86_400_000L, "Недоступная запись")
        assertEquals(emptyList(), mergeEpgArchiveHistory(listOf(old), emptyList(), now, now - 86_400_000L))
    }

    @Test fun noInventedArchive() {
        assertEquals(emptyList(), mergeEpgArchiveHistory(emptyList(), emptyList(), now, now - 7 * 86_400_000L))
    }
}
