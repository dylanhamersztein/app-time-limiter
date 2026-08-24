package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class SessionMachineTest {
    private val gap = 5.minutes
    private val cooldown = 15.minutes
    private val t0 = Instant.parse("2026-08-13T10:00:00Z")

    @Test
    fun `entering foreground with no prior session starts a new one`() {
        val state = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        assertThat(state.packageName).isEqualTo("com.example")
        assertThat(state.startedAt).isEqualTo(t0)
        assertThat(state.accumulated).isEqualTo(0.minutes)
        assertThat(state.enteredForegroundAt).isEqualTo(t0)
    }

    @Test
    fun `length grows while in the foreground`() {
        val state = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        assertThat(SessionMachine.lengthAt(state, t0.plusSeconds(180))).isEqualTo(3.minutes)
    }

    @Test
    fun `leaving the foreground freezes the length`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(180))
        assertThat(SessionMachine.lengthAt(left, t0.plusSeconds(600))).isEqualTo(3.minutes)
    }

    @Test
    fun `returning within the gap resumes the same session`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(180))
        val resumed = SessionMachine.onEnterForeground(left, "com.example", t0.plusSeconds(300), gap)
        assertThat(resumed.startedAt).isEqualTo(t0)
        assertThat(resumed.accumulated).isEqualTo(3.minutes)
        assertThat(SessionMachine.lengthAt(resumed, t0.plusSeconds(360))).isEqualTo(4.minutes)
    }

    @Test
    fun `returning after the gap starts a fresh session`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(180))
        val fresh = SessionMachine.onEnterForeground(left, "com.example", t0.plusSeconds(600), gap)
        assertThat(fresh.startedAt).isEqualTo(t0.plusSeconds(600))
        assertThat(fresh.accumulated).isEqualTo(0.minutes)
    }

    @Test
    fun `a different package always starts a fresh session`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(60))
        val other = SessionMachine.onEnterForeground(left, "com.other", t0.plusSeconds(90), gap)
        assertThat(other.packageName).isEqualTo("com.other")
        assertThat(other.accumulated).isEqualTo(0.minutes)
    }

    @Test
    fun `expiry is exactly one gap after leaving the foreground`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0)
        assertThat(SessionMachine.hasExpired(left, t0.plusSeconds(299), gap)).isFalse()
        assertThat(SessionMachine.hasExpired(left, t0.plusSeconds(300), gap)).isTrue()
    }

    @Test
    fun `a session still in the foreground has not expired`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        assertThat(SessionMachine.hasExpired(entered, t0.plusSeconds(99999), gap)).isFalse()
    }

    @Test
    fun `cooldown runs from the session end, which is one gap after leaving`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val capped = SessionMachine.markCapped(entered)
        val left = SessionMachine.onLeaveForeground(capped, t0.plusSeconds(600))
        assertThat(SessionMachine.cooldownEndsAt(left, gap, cooldown))
            .isEqualTo(t0.plusSeconds(600 + 300 + 900))
    }

    @Test
    fun `a session that was not capped has no cooldown`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(entered, t0.plusSeconds(60))
        assertThat(SessionMachine.cooldownEndsAt(left, gap, cooldown)).isNull()
    }

    @Test
    fun `a capped session stays capped when it is resumed within the gap`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(SessionMachine.markCapped(entered), t0.plusSeconds(60))
        val resumed = SessionMachine.onEnterForeground(left, "com.example", t0.plusSeconds(120), gap)
        assertThat(resumed.endedByCap).isTrue()
    }

    @Test
    fun `a fresh session after a capped one is not capped`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val left = SessionMachine.onLeaveForeground(SessionMachine.markCapped(entered), t0.plusSeconds(60))
        val fresh = SessionMachine.onEnterForeground(left, "com.example", t0.plusSeconds(600), gap)
        assertThat(fresh.endedByCap).isFalse()
        assertThat(SessionMachine.cooldownEndsAt(fresh, gap, cooldown)).isNull()
    }

    @Test
    fun `a capped session still in the foreground has no cooldown end yet`() {
        val capped = SessionMachine.markCapped(
            SessionMachine.onEnterForeground(null, "com.example", t0, gap),
        )
        assertThat(SessionMachine.cooldownEndsAt(capped, gap, cooldown)).isNull()
    }

    @Test
    fun `re-entering the foreground without leaving does not restart the session`() {
        val entered = SessionMachine.onEnterForeground(null, "com.example", t0, gap)
        val again = SessionMachine.onEnterForeground(entered, "com.example", t0.plusSeconds(180), gap)
        assertThat(again.startedAt).isEqualTo(t0)
        assertThat(SessionMachine.lengthAt(again, t0.plusSeconds(240))).isEqualTo(4.minutes)
    }
}
