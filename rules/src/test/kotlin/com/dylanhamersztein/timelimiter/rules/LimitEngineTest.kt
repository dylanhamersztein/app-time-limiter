package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class LimitEngineTest {
    private val zone = ZoneId.of("Europe/London")
    private val t0 = Instant.parse("2026-08-13T10:00:00Z")
    private val nextMidnight = Instant.parse("2026-08-13T23:00:00Z")

    private fun snapshot(
        used: kotlin.time.Duration = 0.minutes,
        session: SessionState? = null,
    ) = UsageSnapshot("com.example", used, session)

    @Test
    fun `allows an app well inside its daily budget`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes)
        assertThat(LimitEngine.decide(config, snapshot(used = 10.minutes), t0, zone))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `blocks when the daily budget is exactly spent`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes)
        val decision = LimitEngine.decide(config, snapshot(used = 30.minutes), t0, zone)
        assertThat(decision).isEqualTo(
            Decision.Block("com.example", BlockReason.DAILY_BUDGET_EXHAUSTED, nextMidnight)
        )
    }

    @Test
    fun `daily block lifts at the next local midnight`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes)
        val decision = LimitEngine.decide(config, snapshot(used = 45.minutes), t0, zone)
        assertThat((decision as Decision.Block).liftsAt).isEqualTo(nextMidnight)
    }

    @Test
    fun `warns once remaining budget reaches the threshold`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes, warningThreshold = 5.minutes)
        assertThat(LimitEngine.decide(config, snapshot(used = 25.minutes), t0, zone))
            .isEqualTo(Decision.Warn("com.example", 5.minutes))
    }

    @Test
    fun `does not warn while remaining budget is above the threshold`() {
        val config = LimitConfig("com.example", dailyBudget = 30.minutes, warningThreshold = 5.minutes)
        assertThat(LimitEngine.decide(config, snapshot(used = 24.minutes), t0, zone))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `a session-only limit never warns and never blocks on daily usage`() {
        val config = LimitConfig("com.example", sessionCap = 10.minutes)
        assertThat(LimitEngine.decide(config, snapshot(used = 600.minutes), t0, zone))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `blocks when the session cap is reached`() {
        val config = LimitConfig(
            "com.example", sessionCap = 10.minutes, sessionGap = 5.minutes, cooldown = 15.minutes
        )
        val session = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val now = t0.plusSeconds(600)
        val decision = LimitEngine.decide(config, snapshot(session = session), now, zone)
        assertThat(decision).isEqualTo(
            Decision.Block(
                "com.example",
                BlockReason.SESSION_CAP_REACHED,
                now.plusSeconds(300 + 900),
            )
        )
    }

    @Test
    fun `blocks while inside the cooldown of a capped session`() {
        val config = LimitConfig(
            "com.example", sessionCap = 10.minutes, sessionGap = 5.minutes, cooldown = 15.minutes
        )
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val left = SessionMachine.onLeaveForeground(SessionMachine.markCapped(entered), t0.plusSeconds(600))
        val cooldownEnd = t0.plusSeconds(600 + 300 + 900)
        val decision = LimitEngine.decide(config, snapshot(session = left), t0.plusSeconds(1000), zone)
        assertThat(decision).isEqualTo(
            Decision.Block("com.example", BlockReason.IN_COOLDOWN, cooldownEnd)
        )
    }

    @Test
    fun `allows again once the cooldown has passed`() {
        val config = LimitConfig(
            "com.example", sessionCap = 10.minutes, sessionGap = 5.minutes, cooldown = 15.minutes
        )
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val left = SessionMachine.onLeaveForeground(SessionMachine.markCapped(entered), t0.plusSeconds(600))
        assertThat(LimitEngine.decide(config, snapshot(session = left), t0.plusSeconds(1801), zone))
            .isEqualTo(Decision.Allow)
    }

    @Test
    fun `daily exhaustion outranks a session cap when both hold`() {
        val config = LimitConfig(
            "com.example", dailyBudget = 30.minutes, sessionCap = 10.minutes
        )
        val session = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val decision = LimitEngine.decide(
            config, snapshot(used = 30.minutes, session = session), t0.plusSeconds(600), zone
        )
        assertThat((decision as Decision.Block).reason).isEqualTo(BlockReason.DAILY_BUDGET_EXHAUSTED)
    }

    @Test
    fun `daily exhaustion blocks mid-session with no grace`() {
        // FR-15: three minutes into a session, the daily budget runs dry.
        val config = LimitConfig("com.example", dailyBudget = 30.minutes, sessionCap = 60.minutes)
        val session = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val decision = LimitEngine.decide(
            config, snapshot(used = 30.minutes, session = session), t0.plusSeconds(180), zone
        )
        assertThat(decision).isInstanceOf(Decision.Block::class.java)
    }

    @Test
    fun `a session cap block outranks a pending warning`() {
        val config = LimitConfig(
            "com.example",
            dailyBudget = 30.minutes,
            sessionCap = 10.minutes,
            warningThreshold = 5.minutes,
        )
        val session = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val decision = LimitEngine.decide(
            config, snapshot(used = 25.minutes, session = session), t0.plusSeconds(600), zone
        )
        assertThat((decision as Decision.Block).reason).isEqualTo(BlockReason.SESSION_CAP_REACHED)
    }

    @Test
    fun `a cooldown block outranks a pending warning`() {
        val config = LimitConfig(
            "com.example",
            dailyBudget = 30.minutes,
            sessionCap = 10.minutes,
            warningThreshold = 5.minutes,
        )
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val left = SessionMachine.onLeaveForeground(SessionMachine.markCapped(entered), t0.plusSeconds(600))
        val decision = LimitEngine.decide(
            config, snapshot(used = 25.minutes, session = left), t0.plusSeconds(1000), zone
        )
        assertThat((decision as Decision.Block).reason).isEqualTo(BlockReason.IN_COOLDOWN)
    }

    @Test
    fun `another app's session never blocks this one`() {
        val config = LimitConfig(
            "com.example", sessionCap = 10.minutes, sessionGap = 5.minutes, cooldown = 15.minutes
        )
        val other = SessionMachine.onEnterForeground(null, "com.other", t0, 5.minutes)
        val decision = LimitEngine.decide(
            config,
            UsageSnapshot("com.example", 0.minutes, other),
            t0.plusSeconds(600),
            zone,
        )
        assertThat(decision).isEqualTo(Decision.Allow)
    }

    @Test
    fun `reopening a capped app within the gap is still blocked`() {
        val config = LimitConfig(
            "com.example", sessionCap = 10.minutes, sessionGap = 5.minutes, cooldown = 15.minutes
        )
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, 5.minutes)
        val left = SessionMachine.onLeaveForeground(SessionMachine.markCapped(entered), t0.plusSeconds(600))
        val reopened = SessionMachine.onEnterForeground(left, "com.example", t0.plusSeconds(700), 5.minutes)
        val decision = LimitEngine.decide(config, snapshot(session = reopened), t0.plusSeconds(700), zone)
        assertThat((decision as Decision.Block).reason).isEqualTo(BlockReason.SESSION_CAP_REACHED)
    }
}
