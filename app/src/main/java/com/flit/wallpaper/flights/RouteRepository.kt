package com.flit.wallpaper.flights

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.flit.wallpaper.network.AdsbdbRouteProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Looks up and caches routes for callsigns currently overhead.
 *
 *  - Lookups are queued by [FlightRepository] after each position update, so
 *    they only happen while Flit is visible.
 *  - One lookup at a time, spaced [SPACING_MS] apart, on its own thread so
 *    positions are never delayed by route lookups.
 *  - Hits are cached for a day and misses for 6 hours, in memory and on disk.
 *    A plane seen once costs one lookup per day at most.
 *
 * Public methods are main-thread only.
 */
object RouteRepository {

    interface Listener {
        fun onRoutesUpdated()
    }

    private class Entry(val route: RouteInfo?, val fetchedAtMs: Long)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flit-routes").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val provider = AdsbdbRouteProvider()

    private val cache = HashMap<String, Entry>()
    private val queue = LinkedHashSet<String>()
    private val listeners = LinkedHashSet<Listener>()
    private var draining = false
    private var pausedUntilMs = 0L
    private var loaded = false
    private var dirty = false
    private var appContext: Context? = null

    fun addListener(context: Context, l: Listener) {
        appContext = context.applicationContext
        loadOnce()
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Cached route for a callsign, or null if unknown or not yet looked up. */
    fun routeFor(callsign: String?): RouteInfo? {
        val cs = Callsigns.normalize(callsign) ?: return null
        DemoRoutes.forCallsign(cs)?.let { return it }
        return cache[cs]?.route
    }

    /** Queues lookups for any callsigns not cached or with expired entries. */
    fun request(context: Context, aircraft: List<Aircraft>) {
        appContext = context.applicationContext
        loadOnce()
        val now = System.currentTimeMillis()
        for (a in aircraft) {
            val cs = Callsigns.normalize(a.callsign) ?: continue
            if (!Callsigns.looksLikeAirline(cs) || DemoRoutes.forCallsign(cs) != null) continue
            val e = cache[cs]
            if (e != null && now - e.fetchedAtMs < ttl(e)) continue
            if (queue.size < MAX_QUEUE) queue.add(cs)
        }
        drain()
    }

    private fun ttl(e: Entry) = if (e.route != null) HIT_TTL_MS else MISS_TTL_MS

    private fun drain() {
        if (draining || queue.isEmpty()) return
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now < pausedUntilMs) {
            main.postDelayed({ drain() }, pausedUntilMs - now)
            return
        }
        val cs = queue.first().also { queue.remove(it) }
        draining = true
        worker.execute {
            var result: AdsbdbRouteProvider.Result? = null
            var rateLimited = false
            try {
                result = provider.lookup(cs)
            } catch (_: AdsbdbRouteProvider.RateLimited) {
                rateLimited = true
            } catch (_: Exception) {
                // Network error: leave uncached so it is retried on a later update.
            }
            val outcome = result
            val limited = rateLimited
            main.post {
                draining = false
                val t = System.currentTimeMillis()
                when {
                    limited -> {
                        pausedUntilMs = t + RATE_LIMIT_PAUSE_MS
                        queue.add(cs)
                    }
                    outcome is AdsbdbRouteProvider.Result.Found -> {
                        cache[cs] = Entry(outcome.route, t)
                        dirty = true
                        notifyListeners()
                    }
                    outcome is AdsbdbRouteProvider.Result.Unknown -> {
                        cache[cs] = Entry(null, t)
                        dirty = true
                    }
                }
                if (queue.isEmpty() || listeners.isEmpty()) {
                    // Stop when nothing is visible; leftovers are re-queued next time.
                    if (listeners.isEmpty()) queue.clear()
                    persist(ctx)
                } else {
                    main.postDelayed({ drain() }, SPACING_MS)
                }
            }
        }
    }

    private fun notifyListeners() {
        for (l in listeners.toList()) l.onRoutesUpdated()
    }

    // ---- disk cache ----

    private fun file(ctx: Context) = File(ctx.filesDir, "routes_cache.json")

    private fun loadOnce() {
        if (loaded) return
        val ctx = appContext ?: return
        loaded = true
        worker.execute {
            val loadedMap = HashMap<String, Entry>()
            try {
                val f = file(ctx)
                if (f.exists()) {
                    val root = JSONObject(f.readText())
                    val now = System.currentTimeMillis()
                    for (key in root.keys()) {
                        val o = root.optJSONObject(key) ?: continue
                        val t = o.optLong("t")
                        val route = o.optJSONObject("r")?.let(::routeFromJson)
                        val e = Entry(route, t)
                        if (now - t < ttl(e)) loadedMap[key] = e
                    }
                }
            } catch (_: Exception) {
            }
            main.post {
                for ((k, v) in loadedMap) if (!cache.containsKey(k)) cache[k] = v
                if (loadedMap.isNotEmpty()) notifyListeners()
            }
        }
    }

    private fun persist(ctx: Context) {
        if (!dirty) return
        dirty = false
        val now = System.currentTimeMillis()
        // Newest entries first, capped.
        val snapshot = cache.entries
            .filter { now - it.value.fetchedAtMs < ttl(it.value) }
            .sortedByDescending { it.value.fetchedAtMs }
            .take(MAX_DISK_ENTRIES)
            .map { it.key to it.value }
        worker.execute {
            try {
                val root = JSONObject()
                for ((k, e) in snapshot) {
                    val o = JSONObject().put("t", e.fetchedAtMs)
                    e.route?.let { o.put("r", routeToJson(it)) }
                    root.put(k, o)
                }
                file(ctx).writeText(root.toString())
            } catch (_: Exception) {
            }
        }
    }

    private fun routeToJson(r: RouteInfo) = JSONObject().apply {
        put("fn", r.flightNumber)
        r.airlineName?.let { put("al", it) }
        r.origin?.let { put("o", airportToJson(it)) }
        r.destination?.let { put("d", airportToJson(it)) }
    }

    private fun airportToJson(a: Airport) = JSONObject().apply {
        a.code?.let { put("c", it) }
        a.city?.let { put("m", it) }
        a.name?.let { put("n", it) }
    }

    private fun routeFromJson(o: JSONObject): RouteInfo? {
        val fn = o.optString("fn", "").ifEmpty { return null }
        return RouteInfo(
            flightNumber = fn,
            airlineName = o.optString("al", "").ifEmpty { null },
            origin = o.optJSONObject("o")?.let(::airportFromJson),
            destination = o.optJSONObject("d")?.let(::airportFromJson),
        )
    }

    private fun airportFromJson(o: JSONObject) = Airport(
        code = o.optString("c", "").ifEmpty { null },
        city = o.optString("m", "").ifEmpty { null },
        name = o.optString("n", "").ifEmpty { null },
    )

    private const val SPACING_MS = 1_500L
    private const val RATE_LIMIT_PAUSE_MS = 5 * 60_000L
    private const val HIT_TTL_MS = 24 * 3_600_000L
    private const val MISS_TTL_MS = 6 * 3_600_000L
    private const val MAX_QUEUE = 80
    private const val MAX_DISK_ENTRIES = 600
}

/** Fixed routes for Demo mode so the details UI can be tried offline. Clearly fictional airline. */
object DemoRoutes {
    private val routes = mapOf(
        "FLT101" to RouteInfo("FL101", "Demo Air", Airport("DEL", "Delhi", null), Airport("BOM", "Mumbai", null)),
        "FLT202" to RouteInfo("FL202", "Demo Air", Airport("BLR", "Bengaluru", null), Airport("DXB", "Dubai", null)),
        "FLT303" to RouteInfo("FL303", "Demo Air", Airport("MAA", "Chennai", null), Airport("HYD", "Hyderabad", null)),
        "FLT404" to RouteInfo("FL404", "Demo Air", Airport("SIN", "Singapore", null), Airport("LHR", "London", null)),
        "FLT505" to RouteInfo("FL505", "Demo Air", Airport("CCU", "Kolkata", null), Airport("MAA", "Chennai", null)),
        "FLT606" to RouteInfo("FL606", "Demo Air", Airport("PHX", "Phoenix", null), Airport("JFK", "New York", null)),
        "FLT707" to RouteInfo("FL707", "Demo Air", Airport("SFO", "San Francisco", null), Airport("NRT", "Tokyo", null)),
    )

    fun forCallsign(cs: String): RouteInfo? = routes[cs]
}
