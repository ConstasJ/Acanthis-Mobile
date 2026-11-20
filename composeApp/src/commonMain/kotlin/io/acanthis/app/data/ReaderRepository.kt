package io.acanthis.app.data

import io.acanthis.app.core.database.ChapterCacheEntity
import io.acanthis.app.core.database.NovelCacheEntity
import io.acanthis.app.core.network.AcanthisApi
import io.acanthis.app.core.network.NovelDto
import io.acanthis.app.core.network.rebaseMediaUrlWithBase
import io.acanthis.app.core.network.stripTrailingV1Prefix
import io.acanthis.app.core.platform.currentTimeMillis
import io.acanthis.app.core.preferences.ServerConfig
import io.acanthis.app.core.preferences.ServerConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

data class NovelDetailLoadState(
    val novel: NovelDto?,
    val isRefreshing: Boolean,
    val error: Throwable?,
)

data class CacheStats(
    val chapterCount: Int,
    val chapterBytes: Long,
)

class ReaderRepository(
    private val api: AcanthisApi,
    private val localStore: ReaderLocalStore,
    private val configProvider: suspend () -> ServerConfig,
    private val json: Json = DefaultReaderJson,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    constructor(
        api: AcanthisApi,
        localStore: ReaderLocalStore,
        serverConfigStore: ServerConfigStore,
        json: Json = DefaultReaderJson,
        clock: () -> Long = ::currentTimeMillis,
    ) : this(
        api = api,
        localStore = localStore,
        configProvider = { serverConfigStore.currentConfig() },
        json = json,
        clock = clock,
    )

    private val prefetchMutex = Mutex()
    private val inFlightPrefetches = mutableSetOf<Pair<String, String>>()

    fun observeNovelDetail(
        platform: String,
        novelId: String,
        forceRefresh: Boolean = false,
    ): Flow<NovelDetailLoadState> = flow {
        val config = configProvider()
        val baseUrl = stripTrailingV1Prefix(config.apiBaseUrl)

        val cachedNovel = localStore.getNovelCache(platform, novelId)
            ?.let { entity ->
                runCatching {
                    json.decodeFromString(NovelDto.serializer(), entity.novelJson)
                }.getOrNull()
            }
            ?.let { decoded ->
                if (baseUrl.isNotEmpty()) decoded.rebaseCovers(baseUrl) else decoded
            }

        emit(
            NovelDetailLoadState(
                novel = cachedNovel,
                isRefreshing = true,
                error = null,
            ),
        )

        if (baseUrl.isEmpty()) {
            emit(
                NovelDetailLoadState(
                    novel = cachedNovel,
                    isRefreshing = false,
                    error = if (cachedNovel == null) {
                        IllegalStateException("尚未配置 Acanthis 服务器地址")
                    } else {
                        null
                    },
                ),
            )
            return@flow
        }

        // Stale-While-Revalidate: always revalidate against server (including forceRefresh)
        @Suppress("UNUSED_EXPRESSION")
        forceRefresh

        try {
            val remote = api.fetchNovelDetail(
                baseUrl = baseUrl,
                authToken = config.authToken.takeIf { it.isNotBlank() },
                platform = platform,
                novelId = novelId,
            )
            val rebasedRemote = remote.rebaseCovers(baseUrl)
            localStore.upsertNovelCache(
                NovelCacheEntity(
                    platform = platform,
                    novelId = novelId,
                    novelJson = json.encodeToString(NovelDto.serializer(), rebasedRemote),
                    updatedAt = clock(),
                ),
            )
            emit(
                NovelDetailLoadState(
                    novel = rebasedRemote,
                    isRefreshing = false,
                    error = null,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(
                NovelDetailLoadState(
                    novel = cachedNovel,
                    isRefreshing = false,
                    error = if (cachedNovel == null) e else null,
                ),
            )
        }
    }

    suspend fun getChapterHtml(
        platform: String,
        novelId: String,
        chapterId: String,
        title: String,
    ): String {
        val cached = localStore.getChapterCache(platform, chapterId)
        if (cached != null) {
            localStore.updateChapterAccessedAt(platform, chapterId, clock())
            return cached.contentHtml
        }

        val config = configProvider()
        val baseUrl = stripTrailingV1Prefix(config.apiBaseUrl)
        check(baseUrl.isNotEmpty()) { "尚未配置 Acanthis 服务器地址" }

        val html = api.fetchChapterContent(
            baseUrl = baseUrl,
            authToken = config.authToken.takeIf { it.isNotBlank() },
            platform = platform,
            chapterId = chapterId,
            novelId = novelId.takeIf { it.isNotBlank() },
        )

        localStore.upsertChapterCache(
            ChapterCacheEntity(
                platform = platform,
                chapterId = chapterId,
                novelId = novelId,
                title = title,
                contentHtml = html,
                byteSize = html.encodeToByteArray().size.toLong(),
                accessedAt = clock(),
                source = ChapterCacheEntity.SOURCE_AUTO,
            ),
        )
        return html
    }

    suspend fun prefetchNextChapter(
        platform: String,
        novelId: String,
        chapterId: String,
        title: String,
    ) {
        val key = platform to chapterId
        val acquired = prefetchMutex.withLock {
            if (key in inFlightPrefetches) {
                false
            } else {
                inFlightPrefetches.add(key)
                true
            }
        }
        if (!acquired) {
            return
        }

        try {
            if (localStore.hasChapterCache(platform, chapterId)) {
                return
            }

            val config = configProvider()
            val baseUrl = stripTrailingV1Prefix(config.apiBaseUrl)
            if (baseUrl.isEmpty()) {
                return
            }

            val html = api.fetchChapterContent(
                baseUrl = baseUrl,
                authToken = config.authToken.takeIf { it.isNotBlank() },
                platform = platform,
                chapterId = chapterId,
                novelId = novelId.takeIf { it.isNotBlank() },
            )
            localStore.upsertChapterCache(
                ChapterCacheEntity(
                    platform = platform,
                    chapterId = chapterId,
                    novelId = novelId,
                    title = title,
                    contentHtml = html,
                    byteSize = html.encodeToByteArray().size.toLong(),
                    accessedAt = clock(),
                    source = ChapterCacheEntity.SOURCE_AUTO,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Silent failure: background prefetch must never interrupt reading
        } finally {
            prefetchMutex.withLock {
                inFlightPrefetches.remove(key)
            }
        }
    }

    fun observeCachedChapterIds(platform: String, novelId: String): Flow<Set<String>> {
        return localStore.observeCachedChapterIds(platform, novelId).map { it.toSet() }
    }

    fun observeCacheStats(): Flow<CacheStats> {
        return localStore.observeChapterCacheStats().map { row ->
            CacheStats(
                chapterCount = row.chapterCount,
                chapterBytes = row.totalBytes,
            )
        }
    }

    suspend fun clearReadingCaches() {
        localStore.clearNovelCaches()
        localStore.clearChapterCaches()
    }

    private fun NovelDto.rebaseCovers(baseUrl: String): NovelDto {
        return copy(
            coverUrl = coverUrl?.let { rebaseMediaUrlWithBase(it, baseUrl) },
            volumes = volumes.map { volume ->
                volume.copy(
                    coverUrl = volume.coverUrl?.let { rebaseMediaUrlWithBase(it, baseUrl) },
                )
            },
        )
    }

    companion object {
        private val DefaultReaderJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
