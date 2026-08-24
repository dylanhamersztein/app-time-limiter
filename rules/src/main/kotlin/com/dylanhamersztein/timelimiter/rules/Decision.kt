package com.dylanhamersztein.timelimiter.rules

import java.time.Instant
import kotlin.time.Duration

/** What the ledger knows about one app right now. */
data class UsageSnapshot(
    val packageName: String,
    val usedToday: Duration,
    val session: SessionState?,
)

enum class BlockReason {
    DAILY_BUDGET_EXHAUSTED,
    SESSION_CAP_REACHED,
    IN_COOLDOWN,
}

sealed interface Decision {
    data object Allow : Decision

    data class Warn(val packageName: String, val remaining: Duration) : Decision

    data class Block(
        val packageName: String,
        val reason: BlockReason,
        val liftsAt: Instant,
    ) : Decision
}
