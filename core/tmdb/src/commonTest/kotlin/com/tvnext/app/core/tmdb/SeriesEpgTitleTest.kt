package com.tvnext.app.core.tmdb

import kotlin.test.*

class SeriesEpgTitleTest {
    @Test fun episodeNumbersAfterProviderSeparatorsAreNotPartOfTheSeriesName() {
        for(separator in listOf("|","¦","│","┆","┊")) {
            for(episode in listOf("1.","2", "1-2", "1-я и 2-я серии")) {
                val raw="Т/с Оперативная память $separator $episode"
                assertEquals("Оперативная память",cleanProgrammeTitle(raw),raw)
            }
        }
        for(suffix in listOf(". 1-я и 2-я серии", ". Сезон 2. Серии 1-4", ". 1-4 с.", ". Сезон 2"))
            assertEquals("Оперативная память",cleanProgrammeTitle("Т/с Оперативная память$suffix"),suffix)
        assertEquals("Побег 2",cleanProgrammeTitle("Т/с Побег 2 ¦ 1."))
        assertEquals("Формула 1",cleanProgrammeTitle("Формула 1"))
    }
    @Test fun cyrillicLatinAndMixedSeriesMarkersProduceTheSameQueryAndKind() {
        for (prefix in listOf("Т/с", "T/S", "T/С", "Т/C", "T.C.", "т / s", "Телесериал", "Сериал")) {
            for (tail in listOf(" — Оперативная память", " «Оперативная память»", ":Оперативная память", "–Оперативная память")) {
                val title = "  $prefix$tail  "
                assertEquals(ProgrammeKind.SERIES, ProgrammeMetadataRequest(title).kind, title)
                assertEquals("Оперативная память", cleanProgrammeTitle(title), title)
            }
        }
        val episode = "Премьера! T/S — Оперативная память, серия 3 (2025) 16+"
        assertEquals(ProgrammeKind.SERIES, ProgrammeMetadataRequest(episode).kind)
        assertEquals("Оперативная память", cleanProgrammeTitle(episode))
    }

    @Test fun prefixMatchingPreservesRealTitlesSequelsAndBroadcastSafety() {
        for (title in listOf("T/Samsung", "T.S.Eliot", "Т/Самолёт", "Сериализация", "Тесла 2")) {
            assertEquals(title, cleanProgrammeTitle(title))
            assertFalse(ProgrammeMetadataRequest(title).kind == ProgrammeKind.SERIES, title)
        }
        assertEquals("Метод 2", cleanProgrammeTitle("T/S — Метод 2"))
        assertEquals(ProgrammeKind.BROADCAST, ProgrammeMetadataRequest("T/S — Время", "Новости").kind)
    }
}
