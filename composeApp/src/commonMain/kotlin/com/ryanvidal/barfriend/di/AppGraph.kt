package com.ryanvidal.barfriend.di

import com.ryanvidal.barfriend.core.data.AppPreferences
import com.ryanvidal.barfriend.core.data.AuthRepository
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.data.SessionRepository
import com.ryanvidal.barfriend.core.database.BarFriendDatabase
import com.ryanvidal.barfriend.core.network.BarAssistantApi
import com.ryanvidal.barfriend.core.network.ImageHttpClient
import com.ryanvidal.barfriend.core.platform.PlatformContext
import com.ryanvidal.barfriend.core.platform.PlatformPaths
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

@DependencyGraph(AppScope::class)
interface AppGraph {
    val sessionRepository: SessionRepository
    val appPreferences: AppPreferences
    val authRepository: AuthRepository
    val cocktailRepository: CocktailRepository
    val barAssistantApi: BarAssistantApi
    val imageHttpClient: ImageHttpClient
    val platformPaths: PlatformPaths
    val database: BarFriendDatabase

    @DependencyGraph.Factory
    interface Factory {
        fun create(@Provides platformContext: PlatformContext): AppGraph
    }
}

fun createAppGraph(platformContext: PlatformContext): AppGraph =
    createGraphFactory<AppGraph.Factory>().create(platformContext)
