package io.acanthis.app.core.network

import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val code: Int,
    val message: String,
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
) : Exception(message)
