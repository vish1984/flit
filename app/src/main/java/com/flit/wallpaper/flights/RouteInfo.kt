package com.flit.wallpaper.flights

/** An airport as shown to the user. Any field may be missing. */
data class Airport(
    /** IATA code if known (e.g. "BOM"), else ICAO (e.g. "VABB"). */
    val code: String?,
    val city: String?,
    val name: String?,
) {
    /** "Mumbai (BOM)", "BOM", or "Mumbai". */
    fun display(): String? = when {
        city != null && code != null -> "$city ($code)"
        else -> code ?: city ?: name
    }
}

/**
 * Scheduled route for a callsign. This comes from a crowd-sourced database,
 * so it describes what the flight normally flies, which is usually but not
 * always what it is flying today.
 */
data class RouteInfo(
    /** Commercial flight number, e.g. "AI101", falling back to the ICAO callsign. */
    val flightNumber: String,
    val airlineName: String?,
    val origin: Airport?,
    val destination: Airport?,
) {
    /** Compact "DEL → BOM", or null if either end is unknown. */
    fun shortRoute(): String? {
        val o = origin?.code ?: return null
        val d = destination?.code ?: return null
        return "$o → $d"
    }
}

object Callsigns {
    private val AIRLINE_PATTERN = Regex("^[A-Z]{3}[0-9][0-9A-Z]{0,4}$")

    fun normalize(raw: String?): String? = raw?.trim()?.uppercase()?.ifEmpty { null }

    /**
     * True for airline-style callsigns (3-letter ICAO airline code + flight
     * number). Private registrations like "N123AB" or "VTABC" have no route.
     */
    fun looksLikeAirline(cs: String): Boolean = AIRLINE_PATTERN.matches(cs)
}
