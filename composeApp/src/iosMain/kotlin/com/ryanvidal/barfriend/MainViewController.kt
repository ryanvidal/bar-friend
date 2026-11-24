package com.ryanvidal.barfriend

import androidx.compose.ui.window.ComposeUIViewController
import coil3.SingletonImageLoader
import com.ryanvidal.barfriend.core.platform.PlatformContext
import com.ryanvidal.barfriend.di.AppGraph
import com.ryanvidal.barfriend.di.createAppGraph
import platform.UIKit.UIViewController

private val sharedAppGraph: AppGraph by lazy {
    createAppGraph(PlatformContext()).also { graph ->
        SingletonImageLoader.setSafe { graph.imageLoader }
    }
}

@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController {
    BarFriendApp(appGraph = sharedAppGraph)
}
