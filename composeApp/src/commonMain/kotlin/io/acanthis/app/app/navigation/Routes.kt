package io.acanthis.app.app.navigation

import kotlinx.serialization.Serializable

@Serializable
data object BookshelfRoute

@Serializable
data object ExploreRoute

@Serializable
data object SettingsRoute

@Serializable
data class NovelDetailRoute(
    val platform: String,
    val novelId: String,
)

@Serializable
data class ReaderRoute(
    val platform: String,
    val novelId: String,
    val chapterId: String,
)
