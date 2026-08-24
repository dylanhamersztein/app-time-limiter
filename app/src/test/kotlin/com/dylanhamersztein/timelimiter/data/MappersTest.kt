package com.dylanhamersztein.timelimiter.data

import com.dylanhamersztein.timelimiter.rules.LimitConfig
import com.dylanhamersztein.timelimiter.rules.PendingChange
import com.dylanhamersztein.timelimiter.rules.PendingKind
import com.dylanhamersztein.timelimiter.rules.SessionState
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class MappersTest {

    @Test
    fun `a limit config survives a round trip through its entity`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes, sessionCap = 10.minutes)

        assertThat(config.toEntity().toDomain()).isEqualTo(config)
    }

    @Test
    fun `a session-only limit round trips with a null daily budget`() {
        val config = LimitConfig("com.example", sessionCap = 10.minutes)

        assertThat(config.toEntity().toDomain().dailyBudget).isNull()
    }

    @Test
    fun `a removal pending change round trips with a null payload`() {
        val change = PendingChange("com.example", PendingKind.REMOVE, null, LocalDate.of(2026, 8, 14))

        assertThat(change.toEntity().toDomain()).isEqualTo(change)
    }

    @Test
    fun `an update pending change round trips`() {
        val payload = LimitConfig("com.example", dailyBudget = 45.minutes)
        val change = PendingChange("com.example", PendingKind.UPDATE, payload, LocalDate.of(2026, 8, 14))

        assertThat(change.toEntity().toDomain()).isEqualTo(change)
    }

    @Test
    fun `a session in the foreground round trips`() {
        val session = SessionState(
            packageName = "com.example",
            startedAt = Instant.ofEpochMilli(1_000_000),
            accumulated = 90.seconds,
            enteredForegroundAt = Instant.ofEpochMilli(1_200_000),
            leftForegroundAt = null,
        )

        assertThat(session.toEntity().toDomain()).isEqualTo(session)
    }

    @Test
    fun `a capped session keeps its cap flag across a round trip`() {
        val session = SessionState(
            packageName = "com.example",
            startedAt = Instant.ofEpochMilli(1_000_000),
            accumulated = 10.minutes,
            enteredForegroundAt = null,
            leftForegroundAt = Instant.ofEpochMilli(1_600_000),
            endedByCap = true,
        )

        assertThat(session.toEntity().toDomain()).isEqualTo(session)
    }

    @Test
    fun `a duration is stored to whole-minute precision, so seconds are truncated`() {
        val config = LimitConfig("com.example", dailyBudget = 90.seconds)

        assertThat(config.toEntity().dailyBudgetMinutes).isEqualTo(1)
        assertThat(config.toEntity().toDomain().dailyBudget).isEqualTo(1.minutes)
    }
}
