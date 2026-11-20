package io.acanthis.app.feature.reader

import io.acanthis.app.core.network.ChapterDto
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.core.network.VolumeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterNavigatorTest {

    @Test
    fun skipsEmptyVolumesAndResolvesPrevNextAcrossVolumeBoundary() {
        val novel = NovelDto(
            id = "n1",
            platform = "linovelib",
            title = "测试轻小说",
            volumes = listOf(
                VolumeDto(
                    id = "v0",
                    title = "空卷零",
                    chapters = emptyList(),
                ),
                VolumeDto(
                    id = "v1",
                    title = "第一卷",
                    chapters = listOf(
                        ChapterDto(id = "c1", title = "序章", volumeId = "v1"),
                        ChapterDto(id = "c2", title = "第一卷终章", volumeId = "v1"),
                    ),
                ),
                VolumeDto(
                    id = "v2",
                    title = "空卷二",
                    chapters = emptyList(),
                ),
                VolumeDto(
                    id = "v3",
                    title = "第二卷",
                    chapters = listOf(
                        ChapterDto(id = "c3", title = "第二卷序章", volumeId = "v3"),
                        ChapterDto(id = "c4", title = "尾声", volumeId = "v3"),
                    ),
                ),
                VolumeDto(
                    id = "v4",
                    title = "空卷四",
                    chapters = emptyList(),
                ),
            ),
        )

        val flat = ChapterNavigator.flatten(novel)
        assertEquals(listOf("c1", "c2", "c3", "c4"), flat.map { it.chapterId })
        assertEquals("v1", flat[0].volumeId)
        assertEquals("第一卷", flat[0].volumeTitle)
        assertEquals("v3", flat[2].volumeId)
        assertEquals("第二卷", flat[2].volumeTitle)

        // First chapter boundary
        assertNull(ChapterNavigator.previousOf(flat, "c1"))
        assertEquals("c2", ChapterNavigator.nextOf(flat, "c1")?.chapterId)

        // Crosses empty volume v2 from v1 -> v3 and v3 -> v1
        val nextAfterV1End = ChapterNavigator.nextOf(flat, "c2")
        assertEquals("c3", nextAfterV1End?.chapterId)
        assertEquals("v3", nextAfterV1End?.volumeId)

        val prevBeforeV3Start = ChapterNavigator.previousOf(flat, "c3")
        assertEquals("c2", prevBeforeV3Start?.chapterId)
        assertEquals("v1", prevBeforeV3Start?.volumeId)

        // Last chapter boundary
        assertEquals("c3", ChapterNavigator.previousOf(flat, "c4")?.chapterId)
        assertNull(ChapterNavigator.nextOf(flat, "c4"))

        // Unknown chapter ID and completely empty novel
        assertNull(ChapterNavigator.previousOf(flat, "unknown"))
        assertNull(ChapterNavigator.nextOf(flat, "unknown"))

        val emptyNovel = novel.copy(
            volumes = listOf(VolumeDto(id = "empty", title = "空卷", chapters = emptyList())),
        )
        val emptyFlat = ChapterNavigator.flatten(emptyNovel)
        assertEquals(emptyList(), emptyFlat)
        assertNull(ChapterNavigator.previousOf(emptyFlat, "c1"))
        assertNull(ChapterNavigator.nextOf(emptyFlat, "c1"))
    }

    @Test
    fun shouldPrefetchNextTriggersAtSeventyFivePercent() {
        assertFalse(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 0, totalBlocks = 0))
        assertFalse(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 5, totalBlocks = 0))
        assertFalse(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = -1, totalBlocks = 10))
        assertFalse(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 0, totalBlocks = -5))

        // 100 blocks: 74th block (index 73 -> 74/100 = 0.74) is below 75%; 75th block (index 74 -> 0.75) triggers.
        assertFalse(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 0, totalBlocks = 100))
        assertFalse(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 50, totalBlocks = 100))
        assertFalse(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 73, totalBlocks = 100))
        assertTrue(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 74, totalBlocks = 100))
        assertTrue(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 75, totalBlocks = 100))
        assertTrue(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 99, totalBlocks = 100))

        // 4 blocks: index 1 (2/4 = 0.50) -> false, index 2 (3/4 = 0.75) -> true
        assertFalse(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 1, totalBlocks = 4))
        assertTrue(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 2, totalBlocks = 4))

        // Single-block chapter (e.g. illustration-only chapter) triggers prefetch immediately
        assertTrue(ChapterNavigator.shouldPrefetchNext(lastVisibleIndex = 0, totalBlocks = 1))
    }
}
