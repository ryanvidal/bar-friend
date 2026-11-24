package com.ryanvidal.barfriend.di

import com.ryanvidal.barfriend.core.platform.PlatformContext

internal expect fun PlatformContext.toCoilPlatformContext(): coil3.PlatformContext
