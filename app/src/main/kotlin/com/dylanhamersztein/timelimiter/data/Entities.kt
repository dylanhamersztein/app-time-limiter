package com.dylanhamersztein.timelimiter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * Storage conventions for this schema: durations are whole minutes (`Int`), instants
 * are epoch millis (`Long`), and local dates are ISO-8601 strings so they sort and
 * compare correctly in SQL. Mappers.kt converts each to its domain type.
 */

@Entity(tableName = "tracked_apps")
data class TrackedAppEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val addedAtEpochMillis: Long,
)

@Entity(tableName = "limits")
data class LimitEntity(
    @PrimaryKey val packageName: String,
    val dailyBudgetMinutes: Int?,
    val sessionCapMinutes: Int?,
    val sessionGapMinutes: Int,
    val cooldownMinutes: Int,
    val warningThresholdMinutes: Int,
)

@Entity(tableName = "pending_changes")
data class PendingChangeEntity(
    @PrimaryKey val packageName: String,
    val kind: String, // "UPDATE" | "REMOVE"
    val dailyBudgetMinutes: Int?,
    val sessionCapMinutes: Int?,
    val sessionGapMinutes: Int?,
    val cooldownMinutes: Int?,
    val warningThresholdMinutes: Int?,
    val effectiveDateIso: String, // ISO-8601 local date
)

@Entity(tableName = "daily_usage", primaryKeys = ["packageName", "localDateIso"])
data class DailyUsageEntity(
    val packageName: String,
    val localDateIso: String,
    val foregroundSeconds: Long,
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val packageName: String,
    val startedAtEpochMillis: Long,
    val accumulatedSeconds: Long,
    val enteredForegroundAtEpochMillis: Long?,
    val leftForegroundAtEpochMillis: Long?,
    val endedByCap: Boolean,
)

@Entity(tableName = "warnings_sent", primaryKeys = ["packageName", "localDateIso"])
data class WarningSentEntity(
    val packageName: String,
    val localDateIso: String,
)
