package io.acanthis.app.core.media

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.acanthis.app.core.platform.platformCacheDirectory
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

interface CoilCacheController {
    fun diskCacheSizeBytes(): Long
    suspend fun clearDiskCache()
}

class DefaultCoilCacheController(
    private val httpClient: HttpClient,
) : CoilCacheController {
    private var imageLoader: ImageLoader? = null

    fun createImageLoader(context: PlatformContext): ImageLoader {
        val loader = ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory("${platformCacheDirectory()}/$DISK_CACHE_DIR_NAME".toPath())
                    .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
        imageLoader = loader
        return loader
    }

    override fun diskCacheSizeBytes(): Long {
        return runCatching { imageLoader?.diskCache?.size ?: 0L }.getOrDefault(0L)
    }

    override suspend fun clearDiskCache() {
        withContext(Dispatchers.IO) {
            runCatching {
                imageLoader?.memoryCache?.clear()
                imageLoader?.diskCache?.clear()
            }
        }
    }

    companion object {
        const val DISK_CACHE_DIR_NAME = "coil_image_cache"
        const val DISK_CACHE_MAX_BYTES = 256L * 1024L * 1024L
    }
}
