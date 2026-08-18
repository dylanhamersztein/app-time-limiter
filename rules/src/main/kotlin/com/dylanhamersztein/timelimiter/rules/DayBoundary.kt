package com.dylanhamersztein.timelimiter.rules

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The budget day is the local calendar date and resets at local midnight (FR-11).
 */
object DayBoundary {
    fun localDate(now: Instant, zone: ZoneId): LocalDate =
        now.atZone(zone).toLocalDate()

    /** The next local midnight strictly after [now]. */
    fun nextResetAt(now: Instant, zone: ZoneId): Instant =
        localDate(now, zone).plusDays(1).atStartOfDay(zone).toInstant()
}
