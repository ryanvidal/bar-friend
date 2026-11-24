package com.ryanvidal.barfriend.di

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.ryanvidal.barfriend.core.data.ImageCacheCleaner
import com.ryanvidal.barfriend.core.network.ImageHttpClient
import com.ryanvidal.barfriend.core.platform.PlatformContext
import com.ryanvidal.barfriend.core.platform.PlatformPaths
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

@ContributesTo(AppScope::class)
@BindingContainer
object ImageLoaderBindings {
    @SingleIn(AppScope::class)
    @Provides
    fun provideImageLoader(
        platformContext: PlatformContext,
        platformPaths: PlatformPaths,
        imageHttpClient: ImageHttpClient,
    ): ImageLoader {
        val coilContext = platformContext.toCoilPlatformContext()
        return ImageLoader.Builder(coilContext)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { imageHttpClient.client }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(coilContext, percent = 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(platformPaths.cachePath("coil3_image_cache").toPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    @SingleIn(AppScope::class)
    @Provides
    fun provideImageCacheCleaner(imageLoader: ImageLoader): ImageCacheCleaner =
        ImageCacheCleaner {
            imageLoader.memoryCache?.clear()
            withContext(Dispatchers.IO) {
                imageLoader.diskCache?.clear()
            }
        }
}
