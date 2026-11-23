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
            val defaultScheme = if (isLocalHost(extractHost(trimmed))) "http://" else "https://"
            "$defaultScheme$trimmed"
        }
        return if (withScheme.endsWith("/api", ignoreCase = true)) {
            withScheme.dropLast(4)
        } else {
            withScheme
        }
    }

    fun apiBaseUrl(serverUrl: String): String = "${normalize(serverUrl)}/api"

    private fun extractHost(authorityAndPath: String): String {
        val authority = authorityAndPath.substringBefore('/')
        return if (authority.startsWith("[")) {
            authority.substringAfter('[').substringBefore(']')
        } else {
            authority.substringBefore(':')
        }
    }

    private fun isLocalHost(host: String): Boolean {
        val lower = host.trim().lowercase()
        if (lower == "localhost" || lower == "::1" || lower.endsWith(".local")) {
            return true
        }
        val octets = lower.split('.')
        if (octets.size != 4) return false
        val nums = octets.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val first = nums[0]
        val second = nums[1]
        return first == 127 ||
            first == 10 ||
            (first == 192 && second == 168) ||
            (first == 172 && second in 16..31)
    }
}
