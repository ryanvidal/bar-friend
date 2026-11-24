package com.ryanvidal.barfriend.di

import com.ryanvidal.barfriend.core.platform.PlatformContext

internal actual fun PlatformContext.toCoilPlatformContext(): coil3.PlatformContext =
    coil3.PlatformContext.INSTANCE
