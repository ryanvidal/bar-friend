package com.ryanvidal.barfriend.core.platform

import androidx.compose.runtime.Composable
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

expect class PlatformContext

interface SecureTokenStore {
    suspend fun getToken(): String?
    suspend fun saveToken(token: String)
    suspend fun clearToken()
}

interface PlatformPaths {
    fun databasePath(name: String): String
    fun dataStorePath(name: String): String
    fun cachePath(name: String): String
}

expect fun createSecureTokenStore(context: PlatformContext): SecureTokenStore

expect fun createPlatformPaths(context: PlatformContext): PlatformPaths

@Composable
expect fun KeepScreenOnEffect(enabled: Boolean)

@ContributesTo(AppScope::class)
@BindingContainer
object PlatformBindings {
    @SingleIn(AppScope::class)
    @Provides
    fun provideSecureTokenStore(context: PlatformContext): SecureTokenStore =
        createSecureTokenStore(context)

    @SingleIn(AppScope::class)
    @Provides
    fun providePlatformPaths(context: PlatformContext): PlatformPaths =
        createPlatformPaths(context)
}
