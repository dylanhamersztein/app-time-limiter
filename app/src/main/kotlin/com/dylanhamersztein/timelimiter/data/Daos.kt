package com.dylanhamersztein.timelimiter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LimitDao {
    @Query("SELECT * FROM tracked_apps ORDER BY label COLLATE NOCASE")
    fun observeTrackedApps(): Flow<List<TrackedAppEntity>>

    @Query("SELECT * FROM limits")
    fun observeLimits(): Flow<List<LimitEntity>>

    @Query("SELECT * FROM limits WHERE packageName = :packageName")
    suspend fun limitFor(packageName: String): LimitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrackedApp(app: TrackedAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLimit(limit: LimitEntity)

    @Query("DELETE FROM tracked_apps WHERE packageName = :packageName")
    suspend fun deleteTrackedApp(packageName: String)

    @Query("DELETE FROM limits WHERE packageName = :packageName")
    suspend fun deleteLimit(packageName: String)

    /** Untracking an app drops its limit too — FR-6a models removing the last cap this way. */
    @Transaction
    suspend fun untrack(packageName: String) {
        deleteLimit(packageName)
        deleteTrackedApp(packageName)
    }

    @Query("SELECT * FROM pending_changes")
    fun observePendingChanges(): Flow<List<PendingChangeEntity>>

    @Query("SELECT * FROM pending_changes")
    suspend fun allPendingChanges(): List<PendingChangeEntity>

    /**
     * There is exactly one pending change per package: a new edit replaces any
     * change already queued for that app (FR-24), which REPLACE gives for free.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingChange(change: PendingChangeEntity)

    @Query("DELETE FROM pending_changes WHERE packageName = :packageName")
    suspend fun deletePendingChange(packageName: String)
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM daily_usage WHERE localDateIso = :dateIso")
    fun observeUsageOn(dateIso: String): Flow<List<DailyUsageEntity>>

    @Query("SELECT * FROM daily_usage WHERE packageName = :packageName AND localDateIso = :dateIso")
    suspend fun usageFor(packageName: String, dateIso: String): DailyUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsage(usage: DailyUsageEntity)

    @Query("DELETE FROM daily_usage WHERE localDateIso < :dateIso")
    suspend fun deleteUsageBefore(dateIso: String)

    @Query("SELECT COUNT(*) FROM warnings_sent WHERE packageName = :packageName AND localDateIso = :dateIso")
    suspend fun warningCount(packageName: String, dateIso: String): Int

    /** IGNORE, not REPLACE: one warning per app per day, so a re-send is a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun recordWarning(warning: WarningSentEntity)

    @Query("DELETE FROM warnings_sent WHERE localDateIso < :dateIso")
    suspend fun deleteWarningsBefore(dateIso: String)
}

/**
 * Sessions are persisted so a cooldown survives a service restart or reboot (FR-28).
 * Only the most recent session per package is kept — history is out of scope.
 */
@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE packageName = :packageName")
    suspend fun sessionFor(packageName: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE packageName = :packageName")
    suspend fun deleteSession(packageName: String)
}
