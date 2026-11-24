package com.ryanvidal.barfriend

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.ryanvidal.barfriend.core.platform.PlatformContext
import com.ryanvidal.barfriend.di.AppGraph
import com.ryanvidal.barfriend.di.createAppGraph

class BarFriendApplication : Application(), SingletonImageLoader.Factory {
    val appGraph: AppGraph by lazy {
        createAppGraph(PlatformContext(this))
    }

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        appGraph.imageLoader
}
