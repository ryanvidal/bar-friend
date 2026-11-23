package com.ryanvidal.barfriend.core.model

enum class AppThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
}

data class ServerInfo(
    val version: String,
    val latestVersion: String?,
    val isPasswordLoginEnabled: Boolean,
    val isAiEnabled: Boolean,
    val isAiImageEnabled: Boolean,
)

data class Bar(
    val id: Int,
    val name: String,
    val subtitle: String?,
    val description: String?,
    val slug: String,
    val isActive: Boolean = false,
)

data class UserProfile(
    val id: Int,
    val name: String,
    val email: String,
)
