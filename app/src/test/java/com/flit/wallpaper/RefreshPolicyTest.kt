package com.flit.wallpaper

import com.flit.wallpaper.network.RefreshPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {

    private val interval = 120_000L
    private val day = RefreshPolicy.DAY_MS
    private val start = 20_000 * day + 3_600_000

    @Test fun respectsInterval() {
        val p = RefreshPolicy()
        assertTrue(p.isDue(start, interval))
        p.onAttempt(start)
        p.onSuccess(start, 1, null, 400)
        assertFalse(p.isDue(start + 60_000, interval))
        assertTrue(p.isDue(start + interval, interval))
    }

    @Test fun backsOffExponentiallyAndCaps() {
        val p = RefreshPolicy()
        p.onAttempt(start); p.onFailure()
        assertEquals(interval * 2, p.backoffMs(interval))
        repeat(10) { p.onFailure() }
        assertEquals(RefreshPolicy.MAX_BACKOFF_MS, p.backoffMs(interval))
    }

    @Test fun honoursRetryAfter() {
        val p = RefreshPolicy()
        p.onAttempt(start)
        p.onRateLimited(start, 3600)
        assertFalse(p.isDue(start + 3_599_000, interval))
        assertTrue(p.isDue(start + 3_600_000, interval))
    }

    @Test fun dailyBudgetResetsAtUtcMidnight() {
        val p = RefreshPolicy()
        repeat(389) { p.onSuccess(start, 1, null, 400) }
        assertTrue(p.hasBudget(start, 1, 400))
        p.onSuccess(start, 1, null, 400)
        assertFalse(p.hasBudget(start, 1, 400))
        assertTrue(p.hasBudget(start + day, 1, 400))
    }

    @Test fun trustsProviderRemainingCount() {
        val p = RefreshPolicy()
        p.onSuccess(start, 1, remainingReported = 5, dailyLimit = 400)
        assertFalse(p.hasBudget(start, 1, 400))
    }
}
