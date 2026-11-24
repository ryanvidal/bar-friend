package com.ryanvidal.barfriend.core.data

fun interface ImageCacheCleaner {
    suspend fun clear()
}
