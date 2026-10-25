package com.flit.wallpaper.flights

import com.flit.wallpaper.projection.LocalProjection
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Smoothed motion of one aircraft between observations.
 *
 * Between updates the aircraft is dead-reckoned along its reported track and
 * speed, so it glides rather than jumps. When a new observation arrives, the
 * difference between where we were drawing it and where the new fix says it is
 * becomes a correction that decays to zero over [BLEND_MS]. Extrapolation is
 * capped so a lost aircraft never flies off indefinitely.
 */
class AircraftTrack(val id: String) {

    var observation: Aircraft? = null
        private set
    var lastSeenMs: Long = 0
        private set

    private var baseE = 0.0
    private var baseN = 0.0
    private var baseTimeMs = 0L
    private var velE = 0.0 // km per ms
    private var velN = 0.0
    private var corrE = 0.0
    private var corrN = 0.0
    private var corrStartMs = 0L

    val trackDeg: Double? get() = observation?.trackDeg
    val hasMotion: Boolean get() = velE != 0.0 || velN != 0.0

    fun update(a: Aircraft, proj: LocalProjection, nowMs: Long, maxExtrapolationMs: Long) {
        val tmp = DoubleArray(2)
        val had = observation != null
        if (had) position(nowMs, maxExtrapolationMs, tmp)
        val oldE = tmp[0]
        val oldN = tmp[1]

        observation = a
        lastSeenMs = nowMs
        setBase(a, proj)

        if (had) {
            deadReckon(nowMs, maxExtrapolationMs, tmp)
            val dE = oldE - tmp[0]
            val dN = oldN - tmp[1]
            if (hypot(dE, dN) <= MAX_BLEND_KM) {
                corrE = dE
                corrN = dN
            } else {
                corrE = 0.0
                corrN = 0.0
            }
            corrStartMs = nowMs
        }
    }

    /** Recomputes position for a new projection centre. Drops any in-flight blend. */
    fun reproject(proj: LocalProjection) {
        observation?.let { setBase(it, proj) }
        corrE = 0.0
        corrN = 0.0
    }

    /** Writes the smoothed (east, north) in km into [out]. */
    fun position(nowMs: Long, maxExtrapolationMs: Long, out: DoubleArray) {
        deadReckon(nowMs, maxExtrapolationMs, out)
        if (corrE != 0.0 || corrN != 0.0) {
            val elapsed = (nowMs - corrStartMs).coerceAtLeast(0)
            if (elapsed >= BLEND_MS * 6) {
                corrE = 0.0
                corrN = 0.0
            } else {
                val k = exp(-elapsed / BLEND_TAU_MS)
                out[0] += corrE * k
                out[1] += corrN * k
            }
        }
    }

    /** Milliseconds since the position was observed by the provider. */
    fun observationAgeMs(nowMs: Long): Long {
        val obs = observation ?: return Long.MAX_VALUE
        return nowMs - obs.positionTimeSec * 1000
    }

    private fun setBase(a: Aircraft, proj: LocalProjection) {
        baseE = proj.eastKm(a.longitude)
        baseN = proj.northKm(a.latitude)
        baseTimeMs = a.positionTimeSec * 1000
        val speed = a.speedMs
        val track = a.trackDeg
        if (speed != null && track != null && speed > 0) {
            val rad = Math.toRadians(track)
            val kmPerMs = speed / 1_000_000.0
            velE = sin(rad) * kmPerMs
            velN = cos(rad) * kmPerMs
        } else {
            velE = 0.0
            velN = 0.0
        }
    }

    private fun deadReckon(nowMs: Long, maxExtrapolationMs: Long, out: DoubleArray) {
        val dt = (nowMs - baseTimeMs).coerceIn(0, maxExtrapolationMs).toDouble()
        out[0] = baseE + velE * dt
        out[1] = baseN + velN * dt
    }

    companion object {
        const val BLEND_MS = 2_500L
        private const val BLEND_TAU_MS = BLEND_MS / 3.0
        /** Corrections larger than this are treated as a new track, not blended. */
        const val MAX_BLEND_KM = 15.0
    }
}
