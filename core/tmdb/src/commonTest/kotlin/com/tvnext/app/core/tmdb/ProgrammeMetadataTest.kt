package com.tvnext.app.core.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgrammeMetadataTest {
    @Test fun russianTitleAndYearAreMatched() {
        assertTrue(similarProgrammeTitle("Хитровка. Знак четырех", "Хитровка: Знак четырёх (2023)"))
        assertTrue(similarProgrammeTitle("Ёжик в тумане (1975)", "Ежик в тумане"))
    }

    @Test fun episodeMarkersDoNotBlockSeriesDescriptions() {
        assertTrue(similarProgrammeTitle("Тайны следствия", "Тайны следствия. Сезон 5, серия 3"))
        assertTrue(similarProgrammeTitle("Побег", "Побег S02E04"))
        assertTrue(!similarProgrammeTitle("Побег", "Побег 2"))
    }

    @Test fun unrelatedMoviesAreRejected() {
        assertTrue(!similarProgrammeTitle("Хитровка", "Хитровка. Знак четырех"))
        assertTrue(!similarProgrammeTitle("Хитровка 2", "Хитровка"))
        assertNull(parseKinopoiskSearch("""{"docs":[{"name":"Другой фильм","description":"Чужое описание"}]}""", "Хитровка"))
    }

    @Test fun kinopoiskFixturePrefersExactNameAndHttpsImage() {
        val json = """{"docs":[
          {"name":"Другой фильм","description":"Неверно"},
          {"name":"Хитровка. Знак четырех (2023)","description":"Описание",
           "poster":{"url":"https://example.org/poster.jpg"},
           "backdrop":{"url":"http://example.org/unsafe.jpg"}}
        ]}"""
        val result = parseKinopoiskSearch(json, "Хитровка. Знак четырех")
        assertEquals("Описание", result?.overview)
        assertEquals("https://example.org/poster.jpg", result?.posterUrl)
        assertNull(result?.frameUrl)
    }
    @Test fun kinopoiskSearchPosterWithoutDescriptionCanBeEnrichedById() {
        val search = """{"docs":[{"id":535341,"name":"Хитровка. Знак четырех", "poster":{"url":"https://example.org/p.jpg"},"description":null}]}"""
        assertEquals(535341, kinopoiskMatchedId(search, "Хитровка. Знак четырех"))
        val hit = parseKinopoiskSearch(search, "Хитровка. Знак четырех")
        assertNull(hit?.overview)
        assertEquals("https://example.org/p.jpg", hit?.posterUrl)
        val detail = parseKinopoiskDetails("""{"description":"Описание из полной карточки", "poster":{"url":"https://example.org/p.jpg"}}""")
        assertEquals("Описание из полной карточки", detail.overview)
    }

    @Test fun kinopoiskFullCardUsesShortDescriptionIfFullIsAbsent() {
        val full = parseKinopoiskDetails("""{"description":null,"shortDescription":"Краткий сюжет", "backdrop":{"url":"https://example.org/f.jpg"}}""")
        assertEquals("Краткий сюжет", full.overview)
        assertEquals("https://example.org/f.jpg", full.frameUrl)
    }

    @Test fun broadcastWrappersAreRemovedWithoutLosingTheSequelNumber() {
        assertEquals("Холоп 2", cleanProgrammeTitle("Х/ф «Холоп2» (2024) 16+"))
        assertEquals("Холоп 2", cleanProgrammeTitle("Премьера! Фильм \"Холоп 2\". (2024). 16+"))
        assertTrue(similarProgrammeTitle("Холоп-2", "Х/ф «Холоп 2» (2024) 16+"))
        assertTrue(!similarProgrammeTitle("Холоп", "Холоп 2"))
        assertEquals("Бегущий по лезвию 2049", cleanProgrammeTitle("Бегущий по лезвию 2049"))
        assertEquals("1917", cleanProgrammeTitle("1917"))
    }

    @Test fun kinopoiskAliasesAndReleaseYearSelectOneConsistentMovie() {
        val body = """{"docs":[
            {"id":1,"name":"Холоп","year":2019,"description":"Первый фильм"},
            {"id":2,"name":"Холоп 2","year":2023,"description":"Версия 2023"},
            {"id":3,"name":"Serf 2","names":[{"name":"Холоп 2"}],"year":2024,"description":"Нужная версия"}
        ]}"""
        assertEquals(3, kinopoiskMatchedId(body, "Х/ф Холоп2 (2024) 16+"))
        assertEquals("Нужная версия", parseKinopoiskSearch(body, "Х/ф Холоп2 (2024) 16+")?.overview)
    }

    @Test fun missingDescriptionSentencesAreNotTreatedAsEpgSynopses() {
        for (text in listOf("Описание передачи отсутствует", "Описание программы недоступно.", "Нет описания передачи", "No description available", "Описание\nпередачи\u00a0отсутствует", "<p>Описание программы недоступно.</p>", "Комедия"))
            assertTrue(!hasProgrammeSynopsis(text), text)
        assertTrue(hasProgrammeSynopsis("Герой возвращается домой и встречает старого друга."))
    }

    @Test fun realShortSynopsisWinsOverAPlaceholderInTheFullDescription() {
        val card = parseKinopoiskDetails("""{"description":"Описание передачи отсутствует","shortDescription":"Герои продолжают приключения во второй части фильма."}""")
        assertEquals("Герои продолжают приключения во второй части фильма.", card.overview)
    }

}

class ProgrammeSynopsisSelectionTest {
    @Test fun missingEpgVariantsCannotOverrideTheRemoteStory() {
        val plot = "Койот помогает герою спасти семью и раскрыть опасный заговор."
        for (stub in listOf("Описание нет", "Описания нет", "Нет описания фильма", "Нет информации о передаче",
            "Описание пока недоступно", "Описание не доступно", "Информация о фильме отсутствует",
            "Описание: Нет описания.", "<p>Описания нет.</p>", "ОПИСАНИЯ\nНЕТ", "Нет описания")) {
            assertTrue(!hasProgrammeSynopsis(stub), stub)
            assertEquals(plot, programmeSynopsis(stub, plot), stub)
        }
    }
    @Test fun fullRemotePlotExpandsABriefEpgEntry() {
        val brief = "Койот спасает семью."
        val full = "Хитрый койот помогает герою спасти семью и раскрывает опасный заговор."
        assertEquals(full, programmeSynopsis(brief, full))
        assertEquals(full, programmeSynopsis(full, brief))
    }
    @Test fun partialUpdatesAndErrorsCannotEraseARealSynopsis() {
        val epg = "Койот возвращается домой и встречает старого друга."
        assertEquals(epg, programmeSynopsis(epg, "Описание отсутствует"))
        assertEquals(epg, programmeSynopsis(epg, null))
        assertNull(programmeSynopsis("Описания нет", "Нет описания фильма"))
        assertTrue(hasProgrammeSynopsis("Описание героя отсутствует в досье, и сыщик начинает расследование."))
    }
}
