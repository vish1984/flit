package com.flit.wallpaper.flights

/**
 * A source of aircraft positions. Implementations are called on a background
 * thread and may block; they must throw rather than return partial garbage.
 */
interface FlightDataProvider {
    /** Human-readable source name, used for attribution and status. */
    val name: String

    /** True if positions are simulated. The UI must label such data clearly. */
    val isSimulated: Boolean

    /** Request cost in provider credits for [box] (0 if not applicable). */
    fun creditCost(box: BoundingBox): Int = 0

    fun fetch(box: BoundingBox): FetchResult
}
