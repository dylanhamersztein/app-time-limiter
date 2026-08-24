package com.dylanhamersztein.timelimiter.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TrackedAppEntity::class,
        LimitEntity::class,
        PendingChangeEntity::class,
        DailyUsageEntity::class,
        SessionEntity::class,
        WarningSentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class TimeLimiterDatabase : RoomDatabase() {
    abstract fun limitDao(): LimitDao
    abstract fun usageDao(): UsageDao
    abstract fun sessionDao(): SessionDao
}
