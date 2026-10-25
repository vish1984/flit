package com.flit.wallpaper.network

import kotlin.math.min

/**
 * Pure scheduling rules for network refreshes, kept free of Android types so
 * they are unit-testable.
 *
 *  - Never refresh more often than the configured interval.
 *  - Back off exponentially on failures (interval × 2^n, capped at 30 min).
 *  - Honour the provider's Retry-After when rate limited.
 *  - Keep a local per-UTC-day credit budget below the provider allowance.
 */
class RefreshPolicy {

    var lastAttemptMs = 0L
        private set
    var lastSuccessMs = 0L
        private set
    var consecutiveFailures = 0
        private set
    private var blockedUntilMs = 0L

    var creditDay = -1L
        private set
    var creditsUsed = 0
        private set

    fun isDue(nowMs: Long, intervalMs: Long): Boolean {
        if (nowMs < blockedUntilMs) return false
        if (lastAttemptMs == 0L) return true
        return nowMs - lastAttemptMs >= backoffMs(intervalMs)
    }

    /** Milliseconds until the next refresh could be due (>= 1 s). */
    fun delayUntilDue(nowMs: Long, intervalMs: Long): Long {
        val byBackoff = if (lastAttemptMs == 0L) 0 else lastAttemptMs + backoffMs(intervalMs) - nowMs
        val byBlock = blockedUntilMs - nowMs
        return maxOf(byBackoff, byBlock, 1_000L)
    }

    fun backoffMs(intervalMs: Long): Long {
        if (consecutiveFailures == 0) return intervalMs
        val factor = 1L shl min(consecutiveFailures, 5)
        return min(intervalMs * factor, MAX_BACKOFF_MS)
    }

    fun hasBudget(nowMs: Long, cost: Int, dailyLimit: Int): Boolean {
        rollDay(nowMs)
        return creditsUsed + cost <= dailyLimit - BUDGET_RESERVE
    }

    fun onAttempt(nowMs: Long) {
        lastAttemptMs = nowMs
    }

    fun onSuccess(nowMs: Long, cost: Int, remainingReported: Int?, dailyLimit: Int) {
        rollDay(nowMs)
        lastSuccessMs = nowMs
        consecutiveFailures = 0
        creditsUsed += cost
        if (remainingReported != null) {
            // Trust the provider's count when it gives one.
            creditsUsed = maxOf(creditsUsed, dailyLimit - remainingReported)
        }
    }

    fun onFailure() {
        consecutiveFailures++
    }

    fun onRateLimited(nowMs: Long, retryAfterSec: Long?) {
        consecutiveFailures++
        val wait = (retryAfterSec ?: 0L).coerceIn(0L, 24 * 3600L) * 1000
        blockedUntilMs = maxOf(blockedUntilMs, nowMs + maxOf(wait, MIN_RATE_LIMIT_WAIT_MS))
    }

    /** Blocks until the next UTC day when the local budget is exhausted. */
    fun blockUntilNextDay(nowMs: Long) {
        blockedUntilMs = maxOf(blockedUntilMs, (nowMs / DAY_MS + 1) * DAY_MS)
    }

    fun blockedUntil(): Long = blockedUntilMs

    fun restore(lastSuccess: Long, day: Long, used: Int) {
        lastSuccessMs = lastSuccess
        creditDay = day
        creditsUsed = used
    }

    /** Forget backoff state, e.g. after the user changes settings. */
    fun resetBackoff() {
        consecutiveFailures = 0
        lastAttemptMs = 0
    }

    private fun rollDay(nowMs: Long) {
        val day = nowMs / DAY_MS
        if (day != creditDay) {
            creditDay = day
            creditsUsed = 0
            if (blockedUntilMs <= nowMs) blockedUntilMs = 0
        }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
        const val MIN_RATE_LIMIT_WAIT_MS = 5 * 60_000L
        const val BUDGET_RESERVE = 10
    }
}
