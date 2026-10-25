package com.flit.wallpaper.flights

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.flit.wallpaper.cache.FlightCache
import com.flit.wallpaper.network.OpenSkyProvider
import com.flit.wallpaper.network.RefreshPolicy
import com.flit.wallpaper.settings.DataMode
import com.flit.wallpaper.settings.FlitSettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Single owner of all flight-data fetching, shared by every wallpaper engine
 * (home, lock, preview) and the settings preview.
 *
 * Fetching happens only while at least one client is attached, and clients
 * attach only while they are visible. When nothing is visible (screen off,
 * app covering the wallpaper) there is no timer and no network activity.
 * Requests run on one low-priority thread, never on the render path.
 *
 * All public methods must be called on the main thread.
 */
object FlightRepository {

    interface Listener {
        fun onFlightsUpdated(aircraft: List<Aircraft>, simulated: Boolean)
    }

    enum class Status { LIVE, STALE, OFFLINE, DEMO, STATIC }

    /** Portrait screens show more sky vertically; query a taller box. */
    const val VERTICAL_FACTOR = 2.0

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flit-net").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val listeners = LinkedHashSet<Listener>()
    private var appContext: Context? = null
    private val policy = RefreshPolicy()
    private var inFlight = false
    private var generation = 0
    private var restored = false
    private var lastDemoMs = 0L

    private var latest: List<Aircraft> = emptyList()
    private var latestSimulated = false

    private val tick = Runnable {
        refreshIfDue()
        scheduleNext()
    }

    fun attach(context: Context, listener: Listener) {
        val ctx = context.applicationContext
        appContext = ctx
        restoreOnce(ctx)
        if (listeners.add(listener)) {
            val mode = FlitSettings(ctx).dataMode
            if (latest.isNotEmpty() && matchesMode(mode, latestSimulated)) {
                listener.onFlightsUpdated(latest, latestSimulated)
            }
        }
        refreshIfDue()
        scheduleNext()
    }

    fun detach(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) main.removeCallbacks(tick)
    }

    /** Call after the user edits settings. Drops in-flight results and refreshes. */
    fun settingsChanged(context: Context, locationOrModeChanged: Boolean) {
        val ctx = context.applicationContext
        appContext = ctx
        generation++
        inFlight = false
        policy.resetBackoff()
        lastDemoMs = 0
        if (locationOrModeChanged) {
            latest = emptyList()
            statusPrefs(ctx).edit()
                .putLong(K_LAST_SUCCESS, 0)
                .putInt(K_COUNT, 0)
                .remove(K_ERROR)
                .apply()
            policy.restore(0, policy.creditDay, policy.creditsUsed)
            io.execute { FlightCache(ctx).clear() }
        }
        refreshIfDue()
        scheduleNext()
    }

    private fun matchesMode(mode: DataMode, simulated: Boolean) = when (mode) {
        DataMode.LIVE -> !simulated
        DataMode.DEMO -> simulated
        DataMode.OFF -> false
    }

    private fun restoreOnce(ctx: Context) {
        if (restored) return
        restored = true
        val sp = statusPrefs(ctx)
        policy.restore(
            sp.getLong(K_LAST_SUCCESS, 0),
            sp.getLong(K_CREDIT_DAY, -1),
            sp.getInt(K_CREDITS_USED, 0),
        )
        val gen = generation
        io.execute {
            val snap = FlightCache(ctx).load() ?: return@execute
            main.post {
                if (gen != generation || latest.isNotEmpty()) return@post
                latest = snap.aircraft
                latestSimulated = snap.simulated
                notifyListeners(ctx)
            }
        }
    }

    private fun refreshIfDue() {
        val ctx = appContext ?: return
        if (listeners.isEmpty() || inFlight) return
        val settings = FlitSettings(ctx)
        val now = System.currentTimeMillis()
        when (settings.dataMode) {
            DataMode.OFF -> return
            DataMode.DEMO -> {
                if (now - lastDemoMs < DEMO_INTERVAL_MS) return
                lastDemoMs = now
                val box = boxFor(settings)
                latest = MockFlightProvider().fetch(box).aircraft
                latestSimulated = true
                notifyListeners(ctx)
            }
            DataMode.LIVE -> fetchLive(ctx, settings, now)
        }
    }

    private fun fetchLive(ctx: Context, settings: FlitSettings, now: Long) {
        val intervalMs = effectiveIntervalMs(ctx, settings)
        if (!policy.isDue(now, intervalMs)) return

        if (!isOnline(ctx)) {
            policy.onAttempt(now)
            policy.onFailure()
            saveStatus(ctx, error = "No network connection")
            return
        }

        val provider = OpenSkyProvider(settings.clientId, settings.clientSecret)
        val box = boxFor(settings)
        val cost = provider.creditCost(box)
        val limit = if (provider.isAuthenticated) {
            OpenSkyProvider.AUTHENTICATED_DAILY_CREDITS
        } else {
            OpenSkyProvider.ANONYMOUS_DAILY_CREDITS
        }
        if (!policy.hasBudget(now, cost, limit)) {
            policy.blockUntilNextDay(now)
            saveStatus(ctx, error = "Daily OpenSky allowance used; resumes after 00:00 UTC")
            return
        }

        policy.onAttempt(now)
        inFlight = true
        val gen = generation
        io.execute {
            try {
                val result = provider.fetch(box)
                FlightCache(ctx).save(
                    FlightCache.Snapshot(result.aircraft, System.currentTimeMillis(), simulated = false)
                )
                main.post {
                    if (gen != generation) return@post
                    inFlight = false
                    policy.onSuccess(System.currentTimeMillis(), cost, result.remainingCredits, limit)
                    latest = result.aircraft
                    latestSimulated = false
                    saveStatus(ctx, error = null, count = result.aircraft.size)
                    notifyListeners(ctx)
                    scheduleNext()
                }
            } catch (e: RateLimitedException) {
                fail(ctx, gen) {
                    policy.onRateLimited(System.currentTimeMillis(), e.retryAfterSec)
                    "OpenSky rate limit reached; pausing updates"
                }
            } catch (e: AuthException) {
                fail(ctx, gen) {
                    policy.onFailure()
                    e.message ?: "OpenSky sign-in failed"
                }
            } catch (e: Exception) {
                fail(ctx, gen) {
                    policy.onFailure()
                    "Could not reach OpenSky (${e.javaClass.simpleName})"
                }
            }
        }
    }

    private inline fun fail(ctx: Context, gen: Int, crossinline record: () -> String) {
        main.post {
            if (gen != generation) return@post
            inFlight = false
            saveStatus(ctx, error = record())
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        main.removeCallbacks(tick)
        val ctx = appContext ?: return
        if (listeners.isEmpty()) return
        val settings = FlitSettings(ctx)
        val now = System.currentTimeMillis()
        val delay = when (settings.dataMode) {
            DataMode.OFF -> return
            DataMode.DEMO -> (lastDemoMs + DEMO_INTERVAL_MS - now).coerceAtLeast(1_000L)
            DataMode.LIVE -> policy.delayUntilDue(now, effectiveIntervalMs(ctx, settings))
        }
        main.postDelayed(tick, delay)
    }

    private fun notifyListeners(ctx: Context) {
        val mode = FlitSettings(ctx).dataMode
        if (!matchesMode(mode, latestSimulated)) return
        val snapshot = latest
        for (l in listeners.toList()) l.onFlightsUpdated(snapshot, latestSimulated)
    }

    private fun boxFor(s: FlitSettings): BoundingBox {
        val r = s.radiusKm.toDouble()
        return s.projection().boundingBox(halfNorthKm = r * VERTICAL_FACTOR, halfEastKm = r)
    }

    /** Doubles the interval while Battery Saver is on. */
    fun effectiveIntervalMs(ctx: Context, s: FlitSettings): Long {
        val base = s.refreshSec * 1000L
        return if (isPowerSave(ctx)) base * 2 else base
    }

    fun isPowerSave(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    private fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---- Status (shared with the settings screen) ----

    private const val STATUS_PREFS = "flit_status"
    private const val K_LAST_SUCCESS = "last_success"
    private const val K_ERROR = "last_error"
    private const val K_COUNT = "aircraft_count"
    private const val K_CREDIT_DAY = "credit_day"
    private const val K_CREDITS_USED = "credits_used"
    private const val DEMO_INTERVAL_MS = 15_000L

    private fun statusPrefs(ctx: Context) =
        ctx.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)

    private fun saveStatus(ctx: Context, error: String?, count: Int? = null) {
        statusPrefs(ctx).edit().apply {
            putLong(K_LAST_SUCCESS, policy.lastSuccessMs)
            putLong(K_CREDIT_DAY, policy.creditDay)
            putInt(K_CREDITS_USED, policy.creditsUsed)
            if (error == null) remove(K_ERROR) else putString(K_ERROR, error)
            if (count != null) putInt(K_COUNT, count)
        }.apply()
    }

    /** Status and a one-line human description for the settings screen. */
    fun describeStatus(context: Context): Pair<Status, String> {
        val ctx = context.applicationContext
        val s = FlitSettings(ctx)
        val sp = statusPrefs(ctx)
        return when (s.dataMode) {
            DataMode.OFF -> Status.STATIC to "Static sky. Live data is off."
            DataMode.DEMO -> Status.DEMO to "Demo. Simulated aircraft, not real positions."
            DataMode.LIVE -> {
                val now = System.currentTimeMillis()
                val last = sp.getLong(K_LAST_SUCCESS, 0)
                val err = sp.getString(K_ERROR, null)
                val count = sp.getInt(K_COUNT, 0)
                val fresh = s.refreshSec * 2_000L + 60_000L
                when {
                    last > 0 && now - last <= fresh ->
                        Status.LIVE to "Live. $count aircraft, updated ${ago(now - last)}." +
                            (err?.let { "\n$it" } ?: "")
                    last > 0 ->
                        Status.STALE to "Stale. Last update ${ago(now - last)}." + (err?.let { "\n$it" } ?: "")
                    else ->
                        Status.OFFLINE to (err ?: "Waiting for the first update. Updates run only while the wallpaper is visible.")
                }
            }
        }
    }

    private fun ago(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}s ago"
            sec < 3600 -> "${sec / 60} min ago"
            else -> "${sec / 3600} h ago"
        }
    }
}
