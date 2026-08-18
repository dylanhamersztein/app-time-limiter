package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class LimitConfigTest {
    @Test
    fun `accepts a daily budget only`() {
        val config = LimitConfig(packageName = "com.example", dailyBudget = 30.minutes)
        assertThat(config.dailyBudget).isEqualTo(30.minutes)
        assertThat(config.sessionCap).isNull()
    }

    @Test
    fun `accepts a session cap only`() {
        val config = LimitConfig(packageName = "com.example", sessionCap = 10.minutes)
        assertThat(config.sessionCap).isEqualTo(10.minutes)
        assertThat(config.dailyBudget).isNull()
    }

    @Test
    fun `rejects a config with neither cap`() {
        val error = runCatching { LimitConfig(packageName = "com.example") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects non-positive caps`() {
        assertThat(
            runCatching { LimitConfig("com.example", dailyBudget = 0.minutes) }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { LimitConfig("com.example", sessionCap = (-1).minutes) }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `defaults session gap to five minutes and cooldown to fifteen`() {
        val config = LimitConfig("com.example", sessionCap = 10.minutes)
        assertThat(config.sessionGap).isEqualTo(5.minutes)
        assertThat(config.cooldown).isEqualTo(15.minutes)
        assertThat(config.warningThreshold).isEqualTo(5.minutes)
    }

    @Test
    fun `rejects a blank package name`() {
        assertThat(
            runCatching { LimitConfig("  ", sessionCap = 10.minutes) }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects nonsensical pacing knobs`() {
        assertThat(
            runCatching {
                LimitConfig("com.example", sessionCap = 10.minutes, sessionGap = 0.minutes)
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching {
                LimitConfig("com.example", sessionCap = 10.minutes, cooldown = (-1).minutes)
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching {
                LimitConfig("com.example", sessionCap = 10.minutes, warningThreshold = (-1).minutes)
            }.exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
}
