package com.dylanhamersztein.timelimiter.rules

import java.time.Duration as JavaDuration
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * One continuous stretch of use of a single app.
 *
 * [accumulated] is foreground time banked before the current stint. While the app
 * is in the foreground [enteredForegroundAt] is set; while it is backgrounded but
 * the session is still alive, [leftForegroundAt] is set. Exactly one of the two is
 * non-null at any time.
 */
data class SessionState(
    val packageName: String,
    val startedAt: Instant,
    val accumulated: Duration,
    val enteredForegroundAt: Instant?,
    val leftForegroundAt: Instant?,
    val endedByCap: Boolean = false,
)

object SessionMachine {

    fun onEnterForeground(
        prev: SessionState?,
        packageName: String,
        now: Instant,
        gap: Duration,
    ): SessionState {
        val continues = prev != null &&
            prev.packageName == packageName &&
            !hasExpired(prev, now, gap)

        return if (continues) {
            // A repeated foreground event for an app that never left keeps its original
            // entry instant, so the stint already under way is not silently discarded.
            prev.copy(
                enteredForegroundAt = prev.enteredForegroundAt ?: now,
                leftForegroundAt = null,
            )
        } else {
            SessionState(
                packageName = packageName,
                startedAt = now,
                accumulated = ZERO,
                enteredForegroundAt = now,
                leftForegroundAt = null,
            )
        }
    }

    fun onLeaveForeground(prev: SessionState, now: Instant): SessionState {
        val entered = prev.enteredForegroundAt ?: return prev
        return prev.copy(
            accumulated = prev.accumulated + stintSince(entered, now),
            enteredForegroundAt = null,
            leftForegroundAt = now,
        )
    }

    fun lengthAt(state: SessionState, now: Instant): Duration {
        val entered = state.enteredForegroundAt ?: return state.accumulated
        return state.accumulated + stintSince(entered, now)
    }

    /** A session ends once the app has been out of the foreground for a full [gap]. */
    fun hasExpired(state: SessionState, now: Instant, gap: Duration): Boolean {
        val left = state.leftForegroundAt ?: return false
        return !now.isBefore(left.plus(gap.toJavaDuration()))
    }

    fun markCapped(state: SessionState): SessionState = state.copy(endedByCap = true)

    /** Non-null only for a capped session that has left the foreground (FR-14.3). */
    fun cooldownEndsAt(state: SessionState, gap: Duration, cooldown: Duration): Instant? {
        if (!state.endedByCap) return null
        val left = state.leftForegroundAt ?: return null
        return left.plus(gap.toJavaDuration()).plus(cooldown.toJavaDuration())
    }

    private fun stintSince(entered: Instant, now: Instant): Duration =
        JavaDuration.between(entered, now).toKotlinDuration()
}
