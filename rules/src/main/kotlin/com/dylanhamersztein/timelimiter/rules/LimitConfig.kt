package com.dylanhamersztein.timelimiter.rules

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The limits configured for one tracked app (FR-4, FR-5, FR-6).
 *
 * At least one of [dailyBudget] and [sessionCap] must be set; a tracked app with
 * neither is meaningless, and removing the last cap is modelled as untracking
 * the app instead (FR-6a).
 */
data class LimitConfig(
    val packageName: String,
    val dailyBudget: Duration? = null,
    val sessionCap: Duration? = null,
    val sessionGap: Duration = 5.minutes,
    val cooldown: Duration = 15.minutes,
    val warningThreshold: Duration = 5.minutes,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(dailyBudget != null || sessionCap != null) {
            "a limit must set at least one of dailyBudget or sessionCap"
        }
        require(dailyBudget == null || dailyBudget.isPositive()) {
            "dailyBudget must be positive"
        }
        require(sessionCap == null || sessionCap.isPositive()) {
            "sessionCap must be positive"
        }
        require(sessionGap.isPositive()) { "sessionGap must be positive" }
        require(!cooldown.isNegative()) { "cooldown must not be negative" }
        require(!warningThreshold.isNegative()) { "warningThreshold must not be negative" }
    }
}
