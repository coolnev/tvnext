package com.tvnext.app.core.tmdb

import com.tvnext.app.core.iptv.domain.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TmdbTitleNormalizerTest {
    @Test
    fun stripsYearAndEpisodeMarkers() {
        val item = MediaItem(
            id = "1", playlistId = "p", name = "Интерстеллар (2014) S01E02",
            number = 1, category = "Фильмы", streamUrl = "http://example",
        )
        val result = TmdbTitleNormalizer.normalize(item)
        assertEquals("Интерстеллар", result.query)
        assertEquals(2014, result.year)
        assertTrue(result.isTv)
    }

    @Test
    fun keepsCyrillicTitleAndDetectsSeriesFromSeason() {
        val item = MediaItem(
            id = "2", playlistId = "p", name = "След / S02",
            number = 2, category = "Сериалы", streamUrl = "http://example", season = "2",
        )
        val result = TmdbTitleNormalizer.normalize(item)
        assertEquals("След", result.query)
        assertTrue(result.isTv)
    }

    @Test
    fun normalizesTechnicalTagsWithoutOverFuzzyMatching() {
        val item = MediaItem(
            id = "3", playlistId = "p", name = "Dune 2021 4K WEB-DL",
            number = 3, category = "", streamUrl = "http://example",
        )
        val result = TmdbTitleNormalizer.normalize(item)
        assertEquals("Dune", result.query)
        assertEquals(2021, result.year)
        assertTrue(!result.isTv)
    }
}
