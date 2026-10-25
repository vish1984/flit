package com.flit.wallpaper.flights

/**
 * One observation of one aircraft, as reported by a [FlightDataProvider].
 * Optional fields are null when the provider did not report them.
 */
data class Aircraft(
    /** Stable unique id (ICAO 24-bit transponder address for OpenSky). */
    val id: String,
    val callsign: String?,
    val latitude: Double,
    val longitude: Double,
    /** Metres above sea level. */
    val altitudeM: Double?,
    /** Degrees clockwise from true north. */
    val trackDeg: Double?,
    /** Ground speed in metres per second. */
    val speedMs: Double?,
    /** Epoch seconds at which the position was observed. */
    val positionTimeSec: Long,
)

/** Geographic rectangle used for provider queries. */
data class BoundingBox(
    val latMin: Double,
    val lonMin: Double,
    val latMax: Double,
    val lonMax: Double,
) {
    val areaSqDeg: Double get() = (latMax - latMin) * (lonMax - lonMin)
}

/** Result of one successful fetch. */
data class FetchResult(
    val aircraft: List<Aircraft>,
    /** Provider-reported remaining request budget, if known. */
    val remainingCredits: Int? = null,
)

/** Thrown when the provider asks us to slow down. */
class RateLimitedException(val retryAfterSec: Long?) : Exception("Rate limited")

/** Thrown when supplied credentials were rejected. */
class AuthException(message: String) : Exception(message)
