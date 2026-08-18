package com.dylanhamersztein.timelimiter.rules

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A [Clock] whose time only moves when a test tells it to. */
class TestClock(
    private var current: Instant,
    private val zone: ZoneId = ZoneId.of("Europe/London"),
) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TestClock(current, zone)
    override fun instant(): Instant = current

    fun advanceBy(duration: Duration) {
        current = current.plus(duration.toJavaDuration())
    }

    fun setTo(instant: Instant) {
        current = instant
    }
}
