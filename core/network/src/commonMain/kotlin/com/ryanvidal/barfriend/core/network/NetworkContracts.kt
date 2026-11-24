package com.ryanvidal.barfriend.core.network

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath

interface NetworkSessionProvider {
    suspend fun currentServerUrl(): String?
    suspend fun currentToken(): String?
    suspend fun currentBarId(): Int?
    suspend fun onUnauthorized(failedToken: String)
}

class BarAssistantApiException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object ServerUrlNormalizer {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Server URL cannot be blank." }

        val candidate = buildCandidateUrl(trimmed)
        val builder = URLBuilder(candidate)
        require(builder.protocol == URLProtocol.HTTP || builder.protocol == URLProtocol.HTTPS) {
            "Unsupported server URL scheme: ${builder.protocol.name}."
        }
        require(builder.host.isNotBlank()) { "Server URL is missing a host." }

        builder.host = builder.host.lowercase()
        builder.parameters.clear()
        builder.fragment = ""
        builder.trailingQuery = false

        val segments = builder.encodedPath
            .split('/')
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (segments.lastOrNull()?.equals("api", ignoreCase = true) == true) {
            segments.removeAt(segments.lastIndex)
        }
        builder.encodedPath = if (segments.isEmpty()) "" else "/${segments.joinToString("/")}"

        return builder.buildString()
    }

    fun apiBaseUrl(serverUrl: String): String = "${normalize(serverUrl)}/api"

    fun extractHost(raw: String): String? {
        if (raw.isBlank()) return null
        val normalized = runCatching { normalize(raw) }.getOrNull() ?: return null
        return runCatching { Url(normalized).host }.getOrNull()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.takeIf { it.isNotBlank() }
    }

    fun isLocalHostUrl(raw: String): Boolean {
        val host = extractHost(raw) ?: return false
        return isLocalHost(host)
    }

    fun isCleartextPublicUrl(raw: String): Boolean {
        if (raw.isBlank()) return false
        val normalized = runCatching { normalize(raw) }.getOrNull() ?: return false
        return normalized.startsWith("http://", ignoreCase = true) && !isLocalHostUrl(normalized)
    }

    private fun buildCandidateUrl(trimmed: String): String {
        val schemeDelimiter = trimmed.indexOf("://")
        if (schemeDelimiter >= 0) {
            val scheme = trimmed.substring(0, schemeDelimiter).lowercase()
            require(scheme == "http" || scheme == "https") {
                "Unsupported server URL scheme: $scheme. Use http:// or https://."
            }
            return "$scheme://${trimmed.substring(schemeDelimiter + 3)}"
        }

        val authorityEnd = trimmed.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it == -1) trimmed.length else it }
        val authority = trimmed.substring(0, authorityEnd)
        val suffix = trimmed.substring(authorityEnd)
        require(authority.isNotEmpty()) { "Server URL is missing a host." }

        val atIndex = authority.lastIndexOf('@')
        val userInfoPrefix = if (atIndex >= 0) authority.substring(0, atIndex + 1) else ""
        val hostPort = if (atIndex >= 0) authority.substring(atIndex + 1) else authority
        require(hostPort.isNotEmpty()) { "Server URL is missing a host." }

        val (host, normalizedHostPort) = when {
            hostPort.startsWith('[') -> {
                val closeBracket = hostPort.indexOf(']')
                require(closeBracket > 1) { "Malformed IPv6 host in server URL." }
                val remainder = hostPort.substring(closeBracket + 1)
                if (remainder.isNotEmpty()) {
                    require(remainder.startsWith(':')) { "Malformed port after IPv6 host in server URL." }
                    val portStr = remainder.substring(1)
                    require(portStr.isNotEmpty() && portStr.all { it.isDigit() }) {
                        "Invalid port in server URL."
                    }
                }
                hostPort.substring(1, closeBracket) to hostPort
            }

            "::" in hostPort || hostPort.count { it == ':' } > 1 -> {
                hostPort to "[$hostPort]"
            }

            else -> {
                val colonIndex = hostPort.indexOf(':')
                if (colonIndex >= 0) {
                    val portStr = hostPort.substring(colonIndex + 1)
                    require(portStr.isNotEmpty() && portStr.all { it.isDigit() }) {
                        "Invalid port or scheme in server URL."
                    }
                    hostPort.substring(0, colonIndex) to hostPort
                } else {
                    hostPort to hostPort
                }
            }
        }

        val defaultScheme = if (isLocalHost(host)) "http://" else "https://"
        return "$defaultScheme$userInfoPrefix$normalizedHostPort$suffix"
    }

    private fun isLocalHost(host: String): Boolean {
        val cleaned = host.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase()
        if (cleaned.isEmpty()) return false
        if (
            cleaned == "localhost" ||
            cleaned.endsWith(".local") ||
            cleaned.endsWith(".lan") ||
            cleaned.endsWith(".home.arpa")
        ) {
            return true
        }

        if (':' in cleaned) {
            val ipv6 = cleaned.substringBefore('%')
            if (ipv6 == "::1" || ipv6 == "0:0:0:0:0:0:0:1") {
                return true
            }
            val firstGroup = ipv6.substringBefore(':')
            if (firstGroup.length == 4) {
                val hex = firstGroup.toIntOrNull(16)
                if (hex != null && (hex in 0xFC00..0xFDFF || hex in 0xFE80..0xFEBF)) {
                    return true
                }
            }
            return false
        }

        if ('.' !in cleaned) {
            return true
        }

        val octets = cleaned.split('.')
        if (octets.size != 4) return false
        val nums = octets.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val first = nums[0]
        val second = nums[1]
        return first == 127 ||
            first == 10 ||
            (first == 192 && second == 168) ||
            (first == 172 && second in 16..31) ||
            (first == 169 && second == 254) ||
            (first == 100 && second in 64..127)
    }
}
