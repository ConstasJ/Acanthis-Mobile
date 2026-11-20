package io.acanthis.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class AcanthisApiClient(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    suspend fun checkHealth(baseUrl: String): HealthResponse {
        val normalizedBase = stripTrailingV1Prefix(baseUrl)
        require(normalizedBase.isNotEmpty()) { "服务器地址不能为空" }

        val response = httpClient.get("$normalizedBase/v1/health")
        ensureSuccess(response)
        return response.body()
    }

    suspend fun fetchSources(
        baseUrl: String,
        authToken: String?,
    ): List<NovelSourceDescriptor> {
        val normalizedBase = stripTrailingV1Prefix(baseUrl)
        require(normalizedBase.isNotEmpty()) { "服务器地址不能为空" }

        val response = httpClient.get("$normalizedBase/v1/sources") {
            val token = authToken?.trim().orEmpty()
            if (token.isNotEmpty()) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        ensureSuccess(response)
        val envelope: ApiEnvelope<List<NovelSourceDescriptor>> = response.body()
        if (envelope.code != 0 || envelope.data == null) {
            throw ApiException(
                statusCode = response.status.value,
                apiCode = envelope.code,
                message = envelope.message.ifBlank { "获取书源列表失败" },
            )
        }
        return envelope.data
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (response.status.isSuccess()) {
            return
        }
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
                )
            }
        }
        throw ApiException(
            statusCode = response.status.value,
            apiCode = 0,
            message = "HTTP ${response.status.value} ${response.status.description}".trim(),
        )
    }
}
