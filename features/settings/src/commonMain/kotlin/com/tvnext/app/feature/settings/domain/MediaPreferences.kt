package com.tvnext.app.feature.settings.domain

enum class MediaPosterSize(val width: Int, val height: Int) { SMALL(128,232), MEDIUM(160,280), LARGE(200,344) }
enum class MediaFilmSort { PROVIDER, NAME_ASC, NAME_DESC }
enum class MediaCategorySort { PROVIDER, NAME }
enum class MediaResumeMode { CONTINUE, ASK, START_OVER }
data class MediaPreferences(
    val posterSize: MediaPosterSize = MediaPosterSize.MEDIUM,
    val filmSort: MediaFilmSort = MediaFilmSort.PROVIDER,
    val categorySort: MediaCategorySort = MediaCategorySort.NAME,
    val rememberPosition: Boolean = false,
    val resumeMode: MediaResumeMode = MediaResumeMode.CONTINUE,
)
data class MediaBrowserPosition(val sourceId: String?=null,val category: String?=null,
    val itemId: String?=null,val favorites: Boolean=false,val inFilms: Boolean=false)
