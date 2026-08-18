package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import org.junit.Test

class TestClockTest {
    private val start = Instant.parse("2026-08-13T10:00:00Z")

    @Test
    fun `time stands still until it is advanced`() {
        val clock = TestClock(start)
        assertThat(clock.instant()).isEqualTo(start)
        assertThat(clock.instant()).isEqualTo(start)

        clock.advanceBy(30.minutes)
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-08-13T10:30:00Z"))
    }

    @Test
    fun `can be jumped to an arbitrary instant`() {
        val clock = TestClock(start)
        val target = Instant.parse("2026-12-25T08:00:00Z")

        clock.setTo(target)

        assertThat(clock.instant()).isEqualTo(target)
    }

    @Test
    fun `rezoning keeps the current instant`() {
        val clock = TestClock(start, ZoneId.of("Europe/London"))
        clock.advanceBy(90.minutes)

        val tokyo = clock.withZone(ZoneId.of("Asia/Tokyo"))

        assertThat(tokyo.zone).isEqualTo(ZoneId.of("Asia/Tokyo"))
        assertThat(tokyo.instant()).isEqualTo(clock.instant())
    }
}
