package io.acanthis.app.core.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val code: Int,
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class ApiErrorBody(
    val code: Int = 0,
    val message: String? = null,
    val error: String? = null,
)

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: String,
)

@Serializable
data class NovelSourceDescriptor(
    val id: String,
    val name: String,
    val capabilities: List<String> = emptyList(),
)

class ApiException(
    val statusCode: Int,
    val apiCode: Int,
    override val message: String,
    val retryAfterSeconds: Int? = null,
) : Exception(message)

@Serializable
data class ExploreFilterOptionDto(
    val value: String,
    val label: String,
)

@Serializable
data class ExploreFilterGroupDto(
    val key: String,
    val label: String,
    val defaultValue: String = "",
    val options: List<ExploreFilterOptionDto> = emptyList(),
)

@Serializable
data class ExploreChannelOptionDto(
    val id: String,
    val label: String,
    val requiresChallenge: Boolean = false,
)

@Serializable
data class ExploreRankingOptionDto(
    val id: String,
    val label: String,
    val supportsAll: Boolean = true,
    val supportsFull: Boolean = true,
)

@Serializable
data class ExploreSchemaDto(
    val platform: String,
    val supportsHome: Boolean = true,
    val channels: List<ExploreChannelOptionDto> = emptyList(),
    val rankings: List<ExploreRankingOptionDto> = emptyList(),
    val catalogRequiresChallenge: Boolean = false,
    val catalogFilters: List<ExploreFilterGroupDto> = emptyList(),
)

@Serializable
data class ExploreNovelItemDto(
    val id: String,
    val platform: String,
    val title: String,
    val coverUrl: String? = null,
    val author: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val category: String? = null,
    val genres: List<String> = emptyList(),
    val latestChapter: String? = null,
    val latestChapterId: String? = null,
    val wordCount: String? = null,
    val updatedAt: String? = null,
    val rank: Int? = null,
)

@Serializable
data class ExploreSectionMoreTargetDto(
    val type: String,
    val params: Map<String, String> = emptyMap(),
)

@Serializable
data class ExploreSectionDto(
    val id: String,
    val title: String,
    val layout: String,
    val items: List<ExploreNovelItemDto> = emptyList(),
    val more: ExploreSectionMoreTargetDto? = null,
)

@Serializable
data class ExploreHomeDataDto(
    val platform: String,
    val channel: String,
    val title: String = "",
    val sections: List<ExploreSectionDto> = emptyList(),
)

@Serializable
data class ExplorePageDto(
    val platform: String,
    val items: List<ExploreNovelItemDto> = emptyList(),
    val page: Int = 1,
    val totalPages: Int? = null,
    val hasNextPage: Boolean = false,
)

@Serializable
data class NovelSearchResultDto(
    val id: String,
    val platform: String,
    val title: String,
    val coverUrl: String? = null,
)

@Serializable
data class NovelInfoDto(
    val id: String,
    val platform: String = "",
    val title: String,
    val coverUrl: String? = null,
    val author: String = "",
    val summary: String = "",
    val status: String = "unknown",
    val genres: List<String> = emptyList(),
)

fun NovelInfoDto.toExploreNovelItem(fallbackPlatform: String): ExploreNovelItemDto =
    ExploreNovelItemDto(
        id = id,
        platform = platform.ifBlank { fallbackPlatform },
        title = title,
        coverUrl = coverUrl,
        author = author.takeIf { it.isNotBlank() },
        summary = summary.takeIf { it.isNotBlank() },
        status = status.takeIf { it.isNotBlank() },
        genres = genres,
    )

@Serializable
data class PaginatedNovelsResponseDto(
    val items: List<NovelInfoDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
)

@Serializable
data class CachedGenreDto(
    val name: String,
    val count: Int = 0,
)

@Serializable
data class ChapterDto(
    val id: String,
    val platform: String? = null,
    val title: String,
    val novelId: String? = null,
    val volumeId: String? = null,
    val contentHash: String? = null,
)

@Serializable
data class VolumeDto(
    val id: String,
    val platform: String? = null,
    val title: String,
    val coverUrl: String? = null,
    val novelId: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
data class NovelDto(
    val id: String,
    val platform: String,
    val title: String,
    val coverUrl: String? = null,
    val author: String = "",
    val summary: String = "",
    val status: String = "unknown",
    val genres: List<String> = emptyList(),
    val volumes: List<VolumeDto> = emptyList(),
)

@Serializable
data class ChapterContentResponseDto(
    val content: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BookshelfCategoryDto(
    val id: String,
    val name: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deletedAt: Long? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BookshelfItemDto(
    val platform: String,
    val novelId: String,
    val title: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val author: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val coverUrl: String = "",
    val addedAt: Long,
    val updatedAt: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deletedAt: Long? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val categoryIds: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val genres: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val status: String = "unknown",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ReadingProgressDto(
    val platform: String,
    val novelId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val novelTitle: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val coverUrl: String = "",
    val volumeId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val volumeTitle: String = "",
    val chapterId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val chapterTitle: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val paragraphIndex: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val totalParagraphs: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val deviceLabel: String = "",
    val updatedAt: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val historyHiddenAt: Long? = null,
)

@Serializable
data class SyncStatePullResponseDto(
    val bookshelf: List<BookshelfItemDto> = emptyList(),
    val progress: List<ReadingProgressDto> = emptyList(),
    val categories: List<BookshelfCategoryDto> = emptyList(),
    val cursor: Long = 0L,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SyncStatePushPayloadDto(
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bookshelf: List<BookshelfItemDto>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val progress: List<ReadingProgressDto>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val categories: List<BookshelfCategoryDto>? = null,
)

@Serializable
data class SyncStatePushResponseDto(
    val bookshelf: List<BookshelfItemDto> = emptyList(),
    val progress: List<ReadingProgressDto> = emptyList(),
    val categories: List<BookshelfCategoryDto> = emptyList(),
    val serverTime: Long = 0L,
)
