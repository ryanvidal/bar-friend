package com.ryanvidal.barfriend.core.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.ryanvidal.barfriend.core.platform.PlatformContext

actual fun createDatabaseBuilder(
    context: PlatformContext,
    dbPath: String,
): RoomDatabase.Builder<BarFriendDatabase> = Room.databaseBuilder<BarFriendDatabase>(
    name = dbPath,
)
