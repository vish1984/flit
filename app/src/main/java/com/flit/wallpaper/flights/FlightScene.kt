package com.flit.wallpaper.flights

import com.flit.wallpaper.projection.LocalProjection

/**
 * The set of aircraft currently drawn. Merges successive snapshots by id
 * (so there are no duplicates), keeps motion smooth across updates, and fades
 * out aircraft whose data has gone stale.
 *
 * Not thread-safe: use from the main thread only.
 */
class FlightScene {

    private val byId = HashMap<String, AircraftTrack>()

    /** Iteration list, reused to avoid per-frame allocation. */
    val tracks = ArrayList<AircraftTrack>()

    var projection: LocalProjection? = null
        private set

    /** Data older than this starts fading out. */
    var staleAfterMs: Long = 300_000
        private set

    val isEmpty: Boolean get() = tracks.isEmpty()

    fun configure(proj: LocalProjection, refreshIntervalSec: Int) {
        staleAfterMs = refreshIntervalSec * 2_000L + 60_000L
        if (!proj.sameCentre(projection)) {
            projection = proj
            for (t in tracks) t.reproject(proj)
        }
    }

    fun apply(aircraft: List<Aircraft>, nowMs: Long) {
        val proj = projection ?: return
        for (a in aircraft) {
            val t = byId.getOrPut(a.id) { AircraftTrack(a.id).also { tracks.add(it) } }
            // Ignore an older observation arriving after a newer one (e.g. from cache).
            val prev = t.observation
            if (prev != null && prev.positionTimeSec > a.positionTimeSec) continue
            t.update(a, proj, nowMs, staleAfterMs)
        }
        prune(nowMs)
    }

    fun clear() {
        byId.clear()
        tracks.clear()
    }

    /** 1.0 while fresh, ramping to 0.0 over [FADE_MS] once stale. */
    fun alphaFor(t: AircraftTrack, nowMs: Long): Float {
        val age = t.observationAgeMs(nowMs)
        if (age <= staleAfterMs) return 1f
        return (1f - (age - staleAfterMs).toFloat() / FADE_MS).coerceIn(0f, 1f)
    }

    /** Removes fully faded aircraft. Returns true if anything was removed. */
    fun prune(nowMs: Long): Boolean {
        var removed = false
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.observationAgeMs(nowMs) > staleAfterMs + FADE_MS) {
                it.remove()
                byId.remove(t.id)
                removed = true
            }
        }
        return removed
    }

    companion object {
        const val FADE_MS = 30_000L
    }
}
