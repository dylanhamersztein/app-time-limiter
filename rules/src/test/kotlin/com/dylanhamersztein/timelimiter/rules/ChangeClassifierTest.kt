package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class ChangeClassifierTest {
    private val zone = ZoneId.of("Europe/London")
    private val base = LimitConfig(
        packageName = "com.example",
        dailyBudget = 30.minutes,
        sessionCap = 10.minutes,
        sessionGap = 5.minutes,
        cooldown = 15.minutes,
        warningThreshold = 5.minutes,
    )

    @Test
    fun `lowering the daily budget is a tightening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(dailyBudget = 20.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `raising the daily budget is a loosening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(dailyBudget = 45.minutes)))
            .isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `removing the daily budget is a loosening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(dailyBudget = null)))
            .isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `adding a daily budget that did not exist is a tightening`() {
        val sessionOnly = LimitConfig("com.example", sessionCap = 10.minutes)
        assertThat(ChangeClassifier.classify(sessionOnly, sessionOnly.copy(dailyBudget = 30.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `raising the session cap is a loosening and lowering it is a tightening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(sessionCap = 20.minutes)))
            .isEqualTo(ChangeKind.LOOSENING)
        assertThat(ChangeClassifier.classify(base, base.copy(sessionCap = 5.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `removing the session cap is a loosening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(sessionCap = null)))
            .isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `lowering the cooldown is a loosening and raising it is a tightening`() {
        assertThat(ChangeClassifier.classify(base, base.copy(cooldown = 5.minutes)))
            .isEqualTo(ChangeKind.LOOSENING)
        assertThat(ChangeClassifier.classify(base, base.copy(cooldown = 30.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `raising the session gap is a loosening`() {
        // A longer gap means a session ends later, so more use fits in one session.
        assertThat(ChangeClassifier.classify(base, base.copy(sessionGap = 10.minutes)))
            .isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `changing only the warning threshold is a tightening so it applies immediately`() {
        assertThat(ChangeClassifier.classify(base, base.copy(warningThreshold = 10.minutes)))
            .isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `no change at all is a tightening`() {
        assertThat(ChangeClassifier.classify(base, base)).isEqualTo(ChangeKind.TIGHTENING)
    }

    @Test
    fun `a mixed edit counts as a loosening`() {
        val mixed = base.copy(dailyBudget = 20.minutes, sessionCap = 30.minutes)
        assertThat(ChangeClassifier.classify(base, mixed)).isEqualTo(ChangeKind.LOOSENING)
    }

    @Test
    fun `a pending change takes effect on the next local day`() {
        val now = Instant.parse("2026-08-13T22:00:00Z") // 23:00 London
        assertThat(PendingChangeResolver.effectiveDateFor(now, zone))
            .isEqualTo(LocalDate.of(2026, 8, 14))
    }

    @Test
    fun `changes are due on and after their effective date`() {
        val change = PendingChange("com.example", PendingKind.UPDATE, base, LocalDate.of(2026, 8, 14))
        assertThat(PendingChangeResolver.due(listOf(change), LocalDate.of(2026, 8, 13))).isEmpty()
        assertThat(PendingChangeResolver.due(listOf(change), LocalDate.of(2026, 8, 14)))
            .containsExactly(change)
        assertThat(PendingChangeResolver.due(listOf(change), LocalDate.of(2026, 8, 20)))
            .containsExactly(change)
    }
}
