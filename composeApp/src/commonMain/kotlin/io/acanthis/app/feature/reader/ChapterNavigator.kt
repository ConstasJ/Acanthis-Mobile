package io.acanthis.app.feature.reader

import io.acanthis.app.core.network.NovelDto

data class FlatChapterRef(
    val volumeId: String,
    val volumeTitle: String,
    val chapterId: String,
    val chapterTitle: String,
)

object ChapterNavigator {
    private const val PREFETCH_THRESHOLD = 0.75

    fun flatten(novel: NovelDto): List<FlatChapterRef> {
        return novel.volumes.flatMap { volume ->
            volume.chapters.map { chapter ->
                FlatChapterRef(
                    volumeId = volume.id,
                    volumeTitle = volume.title,
                    chapterId = chapter.id,
                    chapterTitle = chapter.title,
                )
            }
        }
    }

    fun previousOf(
        chapters: List<FlatChapterRef>,
        currentId: String,
    ): FlatChapterRef? {
        val index = chapters.indexOfFirst { it.chapterId == currentId }
        return if (index > 0) chapters[index - 1] else null
    }

    fun nextOf(
        chapters: List<FlatChapterRef>,
        currentId: String,
    ): FlatChapterRef? {
        val index = chapters.indexOfFirst { it.chapterId == currentId }
        return if (index in 0 until chapters.lastIndex) chapters[index + 1] else null
    }

    fun shouldPrefetchNext(
        lastVisibleIndex: Int,
        totalBlocks: Int,
    ): Boolean {
        if (totalBlocks <= 0 || lastVisibleIndex < 0) {
            return false
        }
        return (lastVisibleIndex + 1).toDouble() / totalBlocks.toDouble() >= PREFETCH_THRESHOLD
    }
}
