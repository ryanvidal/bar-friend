package com.ryanvidal.barfriend.core.network

sealed interface ServerCompatibility {
    data class Supported(val version: String) : ServerCompatibility
    data class Unsupported(val version: String, val minimumRequired: String = MINIMUM_VERSION) : ServerCompatibility
    data class Unknown(val rawVersion: String) : ServerCompatibility

    companion object {
        const val MINIMUM_VERSION = "6.0.0"
    }
}

object ServerVersionValidator {
    private const val MINIMUM_MAJOR = 6
    private val SEMVER_REGEX = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?.*$""", RegexOption.IGNORE_CASE)

    fun validate(rawVersion: String): ServerCompatibility {
        val trimmed = rawVersion.trim()
        if (trimmed.equals("develop", ignoreCase = true)) {
            return ServerCompatibility.Supported(trimmed)
        }
        val match = SEMVER_REGEX.matchEntire(trimmed)
            ?: return ServerCompatibility.Unknown(trimmed)
        val major = match.groupValues[1].toIntOrNull()
            ?: return ServerCompatibility.Unknown(trimmed)
        return if (major >= MINIMUM_MAJOR) {
            ServerCompatibility.Supported(trimmed)
        } else {
            ServerCompatibility.Unsupported(trimmed)
        }
    }
}
