package io.acanthis.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

interface AcanthisApi {
    suspend fun checkHealth(baseUrl: String): HealthResponse

    suspend fun fetchSources(
        baseUrl: String,
        authToken: String?,
    ): List<NovelSourceDescriptor>

    suspend fun fetchExploreSchema(
        baseUrl: String,
        authToken: String?,
        platform: String,
    ): ExploreSchemaDto

    suspend fun fetchExploreHome(
        baseUrl: String,
        authToken: String?,
        platform: String,
        channel: String = "home",
    ): ExploreHomeDataDto

    suspend fun fetchRankings(
        baseUrl: String,
        authToken: String?,
        platform: String,
        rank: String,
        full: Boolean,
        page: Int = 1,
    ): ExplorePageDto

    suspend fun fetchCatalog(
        baseUrl: String,
        authToken: String?,
        platform: String,
        filters: Map<String, String>,
        page: Int = 1,
    ): ExplorePageDto

    suspend fun searchNovels(
        baseUrl: String,
        authToken: String?,
        platform: String,
        keyword: String,
    ): List<NovelSearchResultDto>

    suspend fun fetchNovelDetail(
        baseUrl: String,
        authToken: String?,
        platform: String,
        novelId: String,
    ): NovelDto

    suspend fun fetchChapterContent(
        baseUrl: String,
        authToken: String?,
        platform: String,
        chapterId: String,
        novelId: String? = null,
    ): String

    suspend fun pullSyncState(
        baseUrl: String,
        authToken: String?,
        since: Long? = null,
    ): SyncStatePullResponseDto

    suspend fun pushSyncState(
        baseUrl: String,
        authToken: String?,
        payload: SyncStatePushPayloadDto,
    ): SyncStatePushResponseDto
}

class AcanthisApiClient(
    private val httpClient: HttpClient,
    private val json: Json,
) : AcanthisApi {
    override suspend fun checkHealth(baseUrl: String): HealthResponse {
        val normalizedBase = requireBaseUrl(baseUrl)
        val response = httpClient.get("$normalizedBase/v1/health") {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
        }
        ensureSuccess(response)
        return response.body()
    }

    override suspend fun fetchSources(
        baseUrl: String,
        authToken: String?,
    ): List<NovelSourceDescriptor> {
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/sources",
            authToken = authToken,
            fallbackErrorMessage = "获取书源列表失败",
        )
    }

    override suspend fun fetchExploreSchema(
        baseUrl: String,
        authToken: String?,
        platform: String,
    ): ExploreSchemaDto {
        val encodedPlatform = requireEncodedPathPart(platform, "平台标识不能为空")
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/$encodedPlatform/explore/schema",
            authToken = authToken,
            fallbackErrorMessage = "获取发现页配置失败",
        )
    }

    override suspend fun fetchExploreHome(
        baseUrl: String,
        authToken: String?,
        platform: String,
        channel: String,
    ): ExploreHomeDataDto {
        val encodedPlatform = requireEncodedPathPart(platform, "平台标识不能为空")
        val cleanChannel = channel.trim()
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/$encodedPlatform/home",
            authToken = authToken,
            fallbackErrorMessage = "获取发现首页失败",
        ) {
            if (cleanChannel.isNotEmpty()) {
                parameter("channel", cleanChannel)
            }
        }
    }

    override suspend fun fetchRankings(
        baseUrl: String,
        authToken: String?,
        platform: String,
        rank: String,
        full: Boolean,
        page: Int,
    ): ExplorePageDto {
        val encodedPlatform = requireEncodedPathPart(platform, "平台标识不能为空")
        val cleanRank = rank.trim()
        require(cleanRank.isNotEmpty()) { "榜单标识不能为空" }
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/$encodedPlatform/rankings",
            authToken = authToken,
            fallbackErrorMessage = "获取榜单失败",
        ) {
            parameter("rank", cleanRank)
            parameter("full", full.toString())
            parameter("page", page.coerceAtLeast(1).toString())
        }
    }

    override suspend fun fetchCatalog(
        baseUrl: String,
        authToken: String?,
        platform: String,
        filters: Map<String, String>,
        page: Int,
    ): ExplorePageDto {
        val encodedPlatform = requireEncodedPathPart(platform, "平台标识不能为空")
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/$encodedPlatform/catalog",
            authToken = authToken,
            fallbackErrorMessage = "获取分类书库失败",
        ) {
            filters.forEach { (rawKey, rawValue) ->
                val key = rawKey.trim()
                val value = rawValue.trim()
                if (key.isNotEmpty() && value.isNotEmpty() && key != "page") {
                    parameter(key, value)
                }
            }
            parameter("page", page.coerceAtLeast(1).toString())
        }
    }

    override suspend fun searchNovels(
        baseUrl: String,
        authToken: String?,
        platform: String,
        keyword: String,
    ): List<NovelSearchResultDto> {
        val encodedPlatform = requireEncodedPathPart(platform, "平台标识不能为空")
        val cleanKeyword = keyword.trim()
        require(cleanKeyword.isNotEmpty()) { "搜索关键词不能为空" }
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/$encodedPlatform/search",
            authToken = authToken,
            fallbackErrorMessage = "搜索小说失败",
        ) {
            parameter("keyword", cleanKeyword)
        }
    }

    override suspend fun fetchNovelDetail(
        baseUrl: String,
        authToken: String?,
        platform: String,
        novelId: String,
    ): NovelDto {
        val encodedPlatform = requireEncodedPathPart(platform, "平台标识不能为空")
        val encodedNovelId = requireEncodedPathPart(novelId, "小说 ID 不能为空")
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/$encodedPlatform/novel/$encodedNovelId",
            authToken = authToken,
            fallbackErrorMessage = "获取小说详情失败",
        )
    }

    override suspend fun fetchChapterContent(
        baseUrl: String,
        authToken: String?,
        platform: String,
        chapterId: String,
        novelId: String?,
    ): String {
        val encodedPlatform = requireEncodedPathPart(platform, "平台标识不能为空")
        val encodedChapterId = requireEncodedPathPart(chapterId, "章节 ID 不能为空")
        val cleanNovelId = novelId?.trim().orEmpty()
        val data: ChapterContentResponseDto = requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/$encodedPlatform/chapter/$encodedChapterId",
            authToken = authToken,
            fallbackErrorMessage = "获取章节正文失败",
        ) {
            if (cleanNovelId.isNotEmpty()) {
                parameter("novelId", cleanNovelId)
            }
        }
        return data.content
    }

    override suspend fun pullSyncState(
        baseUrl: String,
        authToken: String?,
        since: Long?,
    ): SyncStatePullResponseDto {
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/sync/state",
            authToken = authToken,
            fallbackErrorMessage = "拉取云同步状态失败",
        ) {
            if (since != null && since > 0L) {
                parameter("since", since.toString())
            }
        }
    }

    override suspend fun pushSyncState(
        baseUrl: String,
        authToken: String?,
        payload: SyncStatePushPayloadDto,
    ): SyncStatePushResponseDto {
        return requestEnvelope(
            baseUrl = baseUrl,
            path = "/v1/sync/state",
            authToken = authToken,
            method = HttpMethod.Post,
            fallbackErrorMessage = "推送云同步状态失败",
        ) {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
    }

    private suspend inline fun <reified T> requestEnvelope(
        baseUrl: String,
        path: String,
        authToken: String?,
        method: HttpMethod = HttpMethod.Get,
        fallbackErrorMessage: String = "请求失败",
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        val normalizedBase = requireBaseUrl(baseUrl)
        val response = httpClient.request("$normalizedBase$path") {
            this.method = method
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            applyBearerAuth(authToken)
            configure()
        }
        ensureSuccess(response)
        val envelope: ApiEnvelope<T> = response.body()
        if (envelope.code != 0 || envelope.data == null) {
            throw ApiException(
                statusCode = response.status.value,
                apiCode = envelope.code,
                message = envelope.message.ifBlank { fallbackErrorMessage },
                retryAfterSeconds = parseRetryAfterSeconds(response),
            )
        }
        return envelope.data
    }

    private fun requireBaseUrl(baseUrl: String): String {
        val normalizedBase = stripTrailingV1Prefix(baseUrl)
        require(normalizedBase.isNotEmpty()) { "服务器地址不能为空" }
        return normalizedBase
    }

    private fun requireEncodedPathPart(value: String, errorMessage: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { errorMessage }
        return trimmed.encodeURLPathPart()
    }

    private fun HttpRequestBuilder.applyBearerAuth(authToken: String?) {
        val token = authToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }

    private fun parseRetryAfterSeconds(response: HttpResponse): Int? {
        return response.headers[HttpHeaders.RetryAfter]
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (response.status.isSuccess()) {
            return
        }
        val retryAfterSeconds = parseRetryAfterSeconds(response)
        val bodyText = runCatching { response.bodyAsText() }.getOrDefault("").trim()
        if (bodyText.isNotEmpty()) {
            val parsedError = runCatching {
                json.decodeFromString<ApiErrorBody>(bodyText)
            }.getOrNull()
            if (parsedError != null) {
                val msg = parsedError.message?.takeIf { it.isNotBlank() }
                    ?: parsedError.error?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.status.value}"
                throw ApiException(
                    statusCode = response.status.value,
                    apiCode = parsedError.code,
                    message = msg,
                    retryAfterSeconds = retryAfterSeconds,
                )
            }
        }
        throw ApiException(
            statusCode = response.status.value,
            apiCode = 0,
            message = "HTTP ${response.status.value} ${response.status.description}".trim(),
            retryAfterSeconds = retryAfterSeconds,
        )
    }
}
