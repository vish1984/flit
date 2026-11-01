package com.flit.wallpaper.flights

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.flit.wallpaper.network.AdsbdbRouteProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Looks up and caches flight metadata for aircraft currently overhead:
 * routes (by callsign) and aircraft types (by ICAO 24-bit address).
 *
 *  - Lookups are queued by [FlightRepository] after each position update, so
 *    they only happen while Flit is visible.
 *  - Routes and types share one queue: one request at a time, spaced
 *    [SPACING_MS] apart, on its own thread. Routes go first.
 *  - Routes are cached for a day (misses 6 h). Aircraft types change almost
 *    never, so they are cached for 30 days (misses 7 days). All of it is kept
 *    in memory and on disk.
 *
 * Public methods are main-thread only.
 */
object RouteRepository {

    interface Listener {
        fun onRoutesUpdated()
    }

    private class Entry<T>(val value: T?, val fetchedAtMs: Long)

    private sealed class Job {
        data class Route(val callsign: String) : Job()
        data class Type(val icao24: String) : Job()
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flit-routes").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val provider = AdsbdbRouteProvider()

    private val routes = HashMap<String, Entry<RouteInfo>>()
    private val types = HashMap<String, Entry<AircraftTypeInfo>>()
    private val routeQueue = LinkedHashSet<Job>()
    private val typeQueue = LinkedHashSet<Job>()
    private val listeners = LinkedHashSet<Listener>()
    private var draining = false
    private var pausedUntilMs = 0L
    private var loaded = false
    private var dirty = false
    private var appContext: Context? = null

    /** Increments whenever any cached metadata changes; renderers use it to refresh per-track caches. */
    var version = 0
        private set

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
        return routes[cs]?.value
    }

    /** Cached aircraft type for an ICAO address, or null if unknown or not yet looked up. */
    fun typeFor(icao24: String): AircraftTypeInfo? {
        DemoRoutes.typeFor(icao24)?.let { return it }
        return types[icao24]?.value
    }

    /** Queues lookups for anything not cached or expired. */
    fun request(context: Context, aircraft: List<Aircraft>) {
        appContext = context.applicationContext
        loadOnce()
        val now = System.currentTimeMillis()
        for (a in aircraft) {
            val cs = Callsigns.normalize(a.callsign)
            if (cs != null && Callsigns.looksLikeAirline(cs) && DemoRoutes.forCallsign(cs) == null) {
                val e = routes[cs]
                val fresh = e != null && now - e.fetchedAtMs < (if (e.value != null) ROUTE_TTL_MS else ROUTE_MISS_TTL_MS)
                if (!fresh && routeQueue.size < MAX_QUEUE) routeQueue.add(Job.Route(cs))
            }
            if (DemoRoutes.typeFor(a.id) == null && HEX.matches(a.id)) {
                val e = types[a.id]
                val fresh = e != null && now - e.fetchedAtMs < (if (e.value != null) TYPE_TTL_MS else TYPE_MISS_TTL_MS)
                if (!fresh && typeQueue.size < MAX_QUEUE) typeQueue.add(Job.Type(a.id))
            }
        }
        drain()
    }

    private fun nextJob(): Job? {
        val q = if (routeQueue.isNotEmpty()) routeQueue else typeQueue
        val job = q.firstOrNull() ?: return null
        q.remove(job)
        return job
    }

    private fun requeue(job: Job) {
        when (job) {
            is Job.Route -> routeQueue.add(job)
            is Job.Type -> typeQueue.add(job)
        }
    }

    private fun drain() {
        if (draining) return
        if (routeQueue.isEmpty() && typeQueue.isEmpty()) return
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        if (now < pausedUntilMs) {
            main.postDelayed({ drain() }, pausedUntilMs - now)
            return
        }
        val job = nextJob() ?: return
        draining = true
        worker.execute {
            var found: Any? = null
            var answered = false
            var rateLimited = false
            try {
                when (job) {
                    is Job.Route -> {
                        val r = provider.lookup(job.callsign)
                        found = (r as? AdsbdbRouteProvider.Result.Found)?.route
                    }
                    is Job.Type -> found = provider.lookupAircraft(job.icao24)
                }
                answered = true
            } catch (_: AdsbdbRouteProvider.RateLimited) {
                rateLimited = true
            } catch (_: Exception) {
                // Network error: leave uncached so it is retried on a later update.
            }
            val value = found
            val ok = answered
            val limited = rateLimited
            main.post {
                draining = false
                val t = System.currentTimeMillis()
                if (limited) {
                    pausedUntilMs = t + RATE_LIMIT_PAUSE_MS
                    requeue(job)
                } else if (ok) {
                    when (job) {
                        is Job.Route -> routes[job.callsign] = Entry(value as RouteInfo?, t)
                        is Job.Type -> types[job.icao24] = Entry(value as AircraftTypeInfo?, t)
                    }
                    dirty = true
                    if (value != null) {
                        version++
                        notifyListeners()
                    }
                }
                val idle = routeQueue.isEmpty() && typeQueue.isEmpty()
                if (idle || listeners.isEmpty()) {
                    // Stop when nothing is visible; leftovers are re-queued next time.
                    if (listeners.isEmpty()) {
                        routeQueue.clear()
                        typeQueue.clear()
                    }
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

    private fun routeFile(ctx: Context) = File(ctx.filesDir, "routes_cache.json")
    private fun typeFile(ctx: Context) = File(ctx.filesDir, "types_cache.json")

    private fun loadOnce() {
        if (loaded) return
        val ctx = appContext ?: return
        loaded = true
        worker.execute {
            val now = System.currentTimeMillis()
            val r = readMap(routeFile(ctx)) { o -> o.optJSONObject("r")?.let(::routeFromJson) }
                .filterValues { now - it.fetchedAtMs < (if (it.value != null) ROUTE_TTL_MS else ROUTE_MISS_TTL_MS) }
            val ty = readMap(typeFile(ctx)) { o -> o.optJSONObject("a")?.let(::typeFromJson) }
                .filterValues { now - it.fetchedAtMs < (if (it.value != null) TYPE_TTL_MS else TYPE_MISS_TTL_MS) }
            main.post {
                for ((k, v) in r) if (!routes.containsKey(k)) routes[k] = v
                for ((k, v) in ty) if (!types.containsKey(k)) types[k] = v
                if (r.isNotEmpty() || ty.isNotEmpty()) {
                    version++
                    notifyListeners()
                }
            }
        }
    }

    private fun <T> readMap(f: File, parse: (JSONObject) -> T?): Map<String, Entry<T>> {
        val out = HashMap<String, Entry<T>>()
        try {
            if (!f.exists()) return out
            val root = JSONObject(f.readText())
            for (key in root.keys()) {
                val o = root.optJSONObject(key) ?: continue
                out[key] = Entry(parse(o), o.optLong("t"))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun persist(ctx: Context) {
        if (!dirty) return
        dirty = false
        val r = routes.entries.sortedByDescending { it.value.fetchedAtMs }.take(MAX_DISK_ENTRIES).map { it.key to it.value }
        val ty = types.entries.sortedByDescending { it.value.fetchedAtMs }.take(MAX_DISK_ENTRIES).map { it.key to it.value }
        worker.execute {
            try {
                val root = JSONObject()
                for ((k, e) in r) {
                    val o = JSONObject().put("t", e.fetchedAtMs)
                    e.value?.let { o.put("r", routeToJson(it)) }
                    root.put(k, o)
                }
                routeFile(ctx).writeText(root.toString())
                val troot = JSONObject()
                for ((k, e) in ty) {
                    val o = JSONObject().put("t", e.fetchedAtMs)
                    e.value?.let { o.put("a", typeToJson(it)) }
                    troot.put(k, o)
                }
                typeFile(ctx).writeText(troot.toString())
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

    private fun typeToJson(a: AircraftTypeInfo) = JSONObject().apply {
        a.icaoType?.let { put("i", it) }
        a.manufacturer?.let { put("m", it) }
        a.model?.let { put("t", it) }
        a.registration?.let { put("r", it) }
    }

    private fun typeFromJson(o: JSONObject) = AircraftTypeInfo(
        icaoType = o.optString("i", "").ifEmpty { null },
        manufacturer = o.optString("m", "").ifEmpty { null },
        model = o.optString("t", "").ifEmpty { null },
        registration = o.optString("r", "").ifEmpty { null },
    )

    private val HEX = Regex("^[0-9a-f]{6}$")
    private const val SPACING_MS = 1_500L
    private const val RATE_LIMIT_PAUSE_MS = 5 * 60_000L
    private const val ROUTE_TTL_MS = 24 * 3_600_000L
    private const val ROUTE_MISS_TTL_MS = 6 * 3_600_000L
    private const val TYPE_TTL_MS = 30 * 24 * 3_600_000L
    private const val TYPE_MISS_TTL_MS = 7 * 24 * 3_600_000L
    private const val MAX_QUEUE = 80
    private const val MAX_DISK_ENTRIES = 1_000
}

/** Fixed routes and types for Demo mode so the details UI can be tried offline. Clearly fictional airline. */
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

    private val types = mapOf(
        "demo01" to AircraftTypeInfo("A20N", "Airbus", "A320neo", null),
        "demo02" to AircraftTypeInfo("B38M", "Boeing", "737 MAX 8", null),
        "demo03" to AircraftTypeInfo("AT76", "ATR", "72-600", null),
        "demo04" to AircraftTypeInfo("B77W", "Boeing", "777-300ER", null),
        "demo05" to AircraftTypeInfo("DH8D", "De Havilland", "Dash 8-400", null),
        "demo06" to AircraftTypeInfo("A359", "Airbus", "A350-900", null),
        "demo07" to AircraftTypeInfo("B789", "Boeing", "787-9", null),
        "demo08" to AircraftTypeInfo("EC35", "Airbus Helicopters", "H135", null),
        "demo09" to AircraftTypeInfo("C172", "Cessna", "172 Skyhawk", null),
    )

    fun forCallsign(cs: String): RouteInfo? = routes[cs]
    fun typeFor(id: String): AircraftTypeInfo? = types[id]
}
