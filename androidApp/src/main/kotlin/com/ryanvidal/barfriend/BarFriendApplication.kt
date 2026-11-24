package com.ryanvidal.barfriend

import android.app.Application
import coil3.SingletonImageLoader
import com.ryanvidal.barfriend.core.platform.PlatformContext
import com.ryanvidal.barfriend.di.AppGraph
import com.ryanvidal.barfriend.di.createAppGraph

class BarFriendApplication : Application() {
    val appGraph: AppGraph by lazy {
        createAppGraph(PlatformContext(this))
    }

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { appGraph.imageLoader }
    }
}
