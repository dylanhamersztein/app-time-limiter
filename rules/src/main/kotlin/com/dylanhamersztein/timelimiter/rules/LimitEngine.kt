package com.dylanhamersztein.timelimiter.rules

import java.time.Instant
import java.time.ZoneId
import kotlin.time.toJavaDuration

/**
 * The only place that decides whether an app may be used (FR-14).
 *
 * Pure: no clock field, no Android, no I/O. Everything it needs arrives as an
 * argument, which is what makes the whole rule set testable on the JVM.
 *
 * Blocks are checked longest-lived first — daily budget, then session cap, then
 * cooldown — so when several conditions hold the user is shown the one that lifts
 * last. A warning is only ever returned when nothing blocks.
 */
object LimitEngine {

    fun decide(
        config: LimitConfig,
        usage: UsageSnapshot,
        now: Instant,
        zone: ZoneId,
    ): Decision {
        val pkg = config.packageName
        val budget = config.dailyBudget

        // 1. Daily budget — the longest-lived block, so it is checked first.
        if (budget != null && usage.usedToday >= budget) {
            return Decision.Block(
                pkg,
                BlockReason.DAILY_BUDGET_EXHAUSTED,
                DayBoundary.nextResetAt(now, zone),
            )
        }

        val cap = config.sessionCap
        // Only one app is in the foreground at a time, so the session handed over may
        // well belong to a different app; another app's stint says nothing about this one.
        val session = usage.session?.takeIf { it.packageName == config.packageName }

        // 2. The session is running and has hit its cap: the user is about to be forced
        // out, so the earliest the block can lift is a full gap plus cooldown from now.
        if (cap != null && session != null && session.enteredForegroundAt != null &&
            SessionMachine.lengthAt(session, now) >= cap
        ) {
            val liftsAt = now
                .plus(config.sessionGap.toJavaDuration())
                .plus(config.cooldown.toJavaDuration())
            return Decision.Block(pkg, BlockReason.SESSION_CAP_REACHED, liftsAt)
        }

        // 3. A capped session that has left the foreground keeps the app shut until its
        // cooldown runs out.
        if (session != null) {
            val cooldownEnd =
                SessionMachine.cooldownEndsAt(session, config.sessionGap, config.cooldown)
            if (cooldownEnd != null && now.isBefore(cooldownEnd)) {
                return Decision.Block(pkg, BlockReason.IN_COOLDOWN, cooldownEnd)
            }
        }

        // 4. Warning — daily budgets only (FR-19).
        if (budget != null) {
            val remaining = budget - usage.usedToday
            if (remaining <= config.warningThreshold) {
                return Decision.Warn(pkg, remaining)
            }
        }

        return Decision.Allow
    }
}
