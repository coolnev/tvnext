package com.tvnext.app.feature.channels.domain

data class Channel(
    val id: String,
    val name: String,
    val number: Int,
    val category: String,
    val isFavorite: Boolean = false,
)
