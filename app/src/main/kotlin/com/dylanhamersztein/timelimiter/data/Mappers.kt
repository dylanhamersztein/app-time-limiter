package com.dylanhamersztein.timelimiter.data

import com.dylanhamersztein.timelimiter.rules.LimitConfig
import com.dylanhamersztein.timelimiter.rules.PendingChange
import com.dylanhamersztein.timelimiter.rules.PendingKind
import com.dylanhamersztein.timelimiter.rules.SessionState
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun LimitEntity.toDomain(): LimitConfig = LimitConfig(
    packageName = packageName,
    dailyBudget = dailyBudgetMinutes?.minutes,
    sessionCap = sessionCapMinutes?.minutes,
    sessionGap = sessionGapMinutes.minutes,
    cooldown = cooldownMinutes.minutes,
    warningThreshold = warningThresholdMinutes.minutes,
)

fun LimitConfig.toEntity(): LimitEntity = LimitEntity(
    packageName = packageName,
    dailyBudgetMinutes = dailyBudget?.wholeMinutesInt(),
    sessionCapMinutes = sessionCap?.wholeMinutesInt(),
    sessionGapMinutes = sessionGap.wholeMinutesInt(),
    cooldownMinutes = cooldown.wholeMinutesInt(),
    warningThresholdMinutes = warningThreshold.wholeMinutesInt(),
)

fun PendingChangeEntity.toDomain(): PendingChange {
    val pendingKind = PendingKind.valueOf(kind)
    return PendingChange(
        packageName = packageName,
        kind = pendingKind,
        // A REMOVE carries no limits, so every payload column is null for it. An
        // UPDATE always wrote them, which is what makes the requireNotNull safe.
        payload = if (pendingKind == PendingKind.REMOVE) null else LimitConfig(
            packageName = packageName,
            dailyBudget = dailyBudgetMinutes?.minutes,
            sessionCap = sessionCapMinutes?.minutes,
            sessionGap = requireNotNull(sessionGapMinutes).minutes,
            cooldown = requireNotNull(cooldownMinutes).minutes,
            warningThreshold = requireNotNull(warningThresholdMinutes).minutes,
        ),
        effectiveDate = LocalDate.parse(effectiveDateIso),
    )
}

fun PendingChange.toEntity(): PendingChangeEntity = PendingChangeEntity(
    packageName = packageName,
    kind = kind.name,
    dailyBudgetMinutes = payload?.dailyBudget?.wholeMinutesInt(),
    sessionCapMinutes = payload?.sessionCap?.wholeMinutesInt(),
    sessionGapMinutes = payload?.sessionGap?.wholeMinutesInt(),
    cooldownMinutes = payload?.cooldown?.wholeMinutesInt(),
    warningThresholdMinutes = payload?.warningThreshold?.wholeMinutesInt(),
    effectiveDateIso = effectiveDate.toString(),
)

fun SessionEntity.toDomain(): SessionState = SessionState(
    packageName = packageName,
    startedAt = Instant.ofEpochMilli(startedAtEpochMillis),
    accumulated = accumulatedSeconds.seconds,
    enteredForegroundAt = enteredForegroundAtEpochMillis?.let(Instant::ofEpochMilli),
    leftForegroundAt = leftForegroundAtEpochMillis?.let(Instant::ofEpochMilli),
    endedByCap = endedByCap,
)

fun SessionState.toEntity(): SessionEntity = SessionEntity(
    packageName = packageName,
    startedAtEpochMillis = startedAt.toEpochMilli(),
    accumulatedSeconds = accumulated.inWholeSeconds,
    enteredForegroundAtEpochMillis = enteredForegroundAt?.toEpochMilli(),
    leftForegroundAtEpochMillis = leftForegroundAt?.toEpochMilli(),
    endedByCap = endedByCap,
)

/** Room stores every duration as whole minutes. */
private fun Duration.wholeMinutesInt(): Int = inWholeMinutes.toInt()
