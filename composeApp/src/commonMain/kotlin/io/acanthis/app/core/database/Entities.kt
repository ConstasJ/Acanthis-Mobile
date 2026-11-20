package io.acanthis.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookshelf_items",
    primaryKeys = ["platform", "novelId"],
)
data class BookshelfEntity(
    val platform: String,
    val novelId: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val addedAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Entity(
    tableName = "reading_progress",
    primaryKeys = ["platform", "novelId"],
)
data class ReadingProgressEntity(
    val platform: String,
    val novelId: String,
    val novelTitle: String,
    val coverUrl: String,
    val volumeId: String,
    val volumeTitle: String,
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val totalParagraphs: Int,
    val deviceLabel: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "dirty_sync_queue",
)
data class DirtySyncEntity(
    @PrimaryKey
    val key: String,
    val kind: String,
    val platform: String,
    val novelId: String,
    val updatedAt: Long,
) {
    companion object {
        const val KIND_BOOKSHELF = "bookshelf"
        const val KIND_PROGRESS = "progress"

        fun makeKey(kind: String, platform: String, novelId: String): String =
            "$kind:$platform:$novelId"
    }
}

@Entity(
    tableName = "novel_cache",
    primaryKeys = ["platform", "novelId"],
)
data class NovelCacheEntity(
    val platform: String,
    val novelId: String,
    val novelJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "chapter_cache",
    primaryKeys = ["platform", "chapterId"],
    indices = [Index(value = ["platform", "novelId"])],
)
data class ChapterCacheEntity(
    val platform: String,
    val chapterId: String,
    val novelId: String,
    val title: String,
    val contentHtml: String,
    val byteSize: Long,
    val accessedAt: Long,
    val source: String = SOURCE_AUTO,
) {
    companion object {
        const val SOURCE_AUTO = "auto"
        const val SOURCE_MANUAL = "manual"
    }
}
