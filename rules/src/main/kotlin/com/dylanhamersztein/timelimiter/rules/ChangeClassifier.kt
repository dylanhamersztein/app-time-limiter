package com.dylanhamersztein.timelimiter.rules

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration

enum class ChangeKind { TIGHTENING, LOOSENING }

enum class PendingKind { UPDATE, REMOVE }

/**
 * A queued loosening, applied at the next daily reset (FR-23).
 * [payload] is null when [kind] is [PendingKind.REMOVE].
 */
data class PendingChange(
    val packageName: String,
    val kind: PendingKind,
    val payload: LimitConfig?,
    val effectiveDate: LocalDate,
)

object ChangeClassifier {

    /**
     * An edit is a LOOSENING if any single field loosens, and is then queued whole
     * rather than split: splitting would let a token tightening carry a real
     * loosening through as a partial write that lands today.
     *
     * [LimitConfig.warningThreshold] is neutral — changing when you are warned makes
     * a limit neither stricter nor slacker — so a warning-only edit classifies as a
     * TIGHTENING and applies immediately.
     */
    fun classify(old: LimitConfig, new: LimitConfig): ChangeKind {
        val loosens = capLoosens(old.dailyBudget, new.dailyBudget) ||
            capLoosens(old.sessionCap, new.sessionCap) ||
            new.cooldown < old.cooldown ||
            new.sessionGap > old.sessionGap
        return if (loosens) ChangeKind.LOOSENING else ChangeKind.TIGHTENING
    }

    /** Removing a cap, or raising it, loosens. Adding one where there was none tightens. */
    private fun capLoosens(old: Duration?, new: Duration?): Boolean = when {
        old == null -> false
        new == null -> true
        else -> new > old
    }
}

object PendingChangeResolver {

    /** A loosening queued now takes effect at the next daily reset (FR-23). */
    fun effectiveDateFor(now: Instant, zone: ZoneId): LocalDate =
        DayBoundary.localDate(now, zone).plusDays(1)

    /** Changes stay due once their date has arrived, so a missed reset still applies them. */
    fun due(changes: List<PendingChange>, today: LocalDate): List<PendingChange> =
        changes.filter { !today.isBefore(it.effectiveDate) }
}
