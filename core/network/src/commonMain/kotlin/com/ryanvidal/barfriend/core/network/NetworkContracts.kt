package com.ryanvidal.barfriend.core.network

interface NetworkSessionProvider {
    suspend fun currentServerUrl(): String?
    suspend fun currentToken(): String?
    suspend fun currentBarId(): Int?
    suspend fun onUnauthorized()
}

class BarAssistantApiException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object ServerUrlNormalizer {
    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return if (withScheme.endsWith("/api", ignoreCase = true)) {
            withScheme.dropLast(4)
        } else {
            withScheme
        }
    }

    fun apiBaseUrl(serverUrl: String): String = "${normalize(serverUrl)}/api"
}
