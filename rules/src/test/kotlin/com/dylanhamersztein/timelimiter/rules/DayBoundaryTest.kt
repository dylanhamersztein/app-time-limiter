package com.dylanhamersztein.timelimiter.rules

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

class DayBoundaryTest {
    private val london = ZoneId.of("Europe/London")

    @Test
    fun `buckets an instant into its local date`() {
        // 2026-08-13T23:30Z is 2026-08-14T00:30 in London (BST, UTC+1)
        val instant = Instant.parse("2026-08-13T23:30:00Z")
        assertThat(DayBoundary.localDate(instant, london)).isEqualTo(LocalDate.of(2026, 8, 14))
    }

    @Test
    fun `next reset is the following local midnight`() {
        val instant = Instant.parse("2026-08-13T10:00:00Z") // 11:00 London
        assertThat(DayBoundary.nextResetAt(instant, london))
            .isEqualTo(Instant.parse("2026-08-13T23:00:00Z")) // 2026-08-14T00:00 London
    }

    @Test
    fun `next reset from exactly midnight is a full day later`() {
        val midnight = Instant.parse("2026-08-13T23:00:00Z") // 2026-08-14T00:00 London
        assertThat(DayBoundary.nextResetAt(midnight, london))
            .isEqualTo(Instant.parse("2026-08-14T23:00:00Z"))
    }

    @Test
    fun `handles a timezone with a different offset`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val instant = Instant.parse("2026-08-13T16:00:00Z") // 2026-08-14T01:00 Tokyo
        assertThat(DayBoundary.localDate(instant, tokyo)).isEqualTo(LocalDate.of(2026, 8, 14))
    }

    @Test
    fun `reset follows local midnight across a DST transition`() {
        // UK clocks go back at 02:00 BST on 2026-10-25, making that local day 25 hours long.
        val startOfLongDay = Instant.parse("2026-10-24T23:00:00Z") // 2026-10-25T00:00 London (BST)
        assertThat(DayBoundary.nextResetAt(startOfLongDay, london))
            .isEqualTo(Instant.parse("2026-10-26T00:00:00Z")) // 2026-10-26T00:00 London (GMT)
    }
}
