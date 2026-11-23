package com.ryanvidal.barfriend

import android.app.Application
import com.ryanvidal.barfriend.core.platform.PlatformContext
import com.ryanvidal.barfriend.di.AppGraph
import com.ryanvidal.barfriend.di.createAppGraph

class BarFriendApplication : Application() {
    val appGraph: AppGraph by lazy {
        createAppGraph(PlatformContext(this))
    }
}
