package com.flit.wallpaper.flights

import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic simulated aircraft for development and the "Demo" mode.
 * Always reported as simulated so the UI never presents it as live data.
 *
 * Each aircraft flies a straight line through the box and wraps around,
 * so positions are a pure function of wall-clock time.
 */
class MockFlightProvider(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : FlightDataProvider {

    override val name = "Demo (simulated)"
    override val isSimulated = true

    private data class Route(
        val id: String,
        val callsign: String,
        val trackDeg: Double,
        val speedMs: Double,
        val altitudeM: Double,
        val phase: Double,
        val lateral: Double,
    )

    private val routes = listOf(
        Route("demo01", "FLT101", 62.0, 235.0, 11200.0, 0.10, -0.35),
        Route("demo02", "FLT202", 241.0, 220.0, 10400.0, 0.55, 0.20),
        Route("demo03", "FLT303", 12.0, 180.0, 6100.0, 0.30, 0.55),
        Route("demo04", "FLT404", 165.0, 245.0, 11900.0, 0.80, -0.10),
        Route("demo05", "FLT505", 300.0, 140.0, 3200.0, 0.20, 0.05),
        Route("demo06", "FLT606", 95.0, 230.0, 10900.0, 0.65, 0.70),
        Route("demo07", "FLT707", 205.0, 200.0, 8800.0, 0.45, -0.60),
    )

    override fun fetch(box: BoundingBox): FetchResult {
        val nowMs = clock()
        val lat0 = (box.latMin + box.latMax) / 2
        val lon0 = (box.lonMin + box.lonMax) / 2
        val halfNorthKm = (box.latMax - box.latMin) / 2 * KM_PER_DEG_LAT
        val kmPerDegLon = 111.320 * cos(Math.toRadians(lat0)).coerceAtLeast(0.05)
        val halfEastKm = (box.lonMax - box.lonMin) / 2 * kmPerDegLon
        val spanKm = 2 * minOf(halfNorthKm, halfEastKm)

        val list = routes.map { r ->
            val rad = Math.toRadians(r.trackDeg)
            val dirE = sin(rad)
            val dirN = cos(rad)
            // Distance travelled along the route, wrapped into [-span/2, span/2].
            val travelledKm = r.speedMs * nowMs / 1_000_000.0 + r.phase * spanKm
            val along = ((travelledKm % spanKm) + spanKm) % spanKm - spanKm / 2
            val across = r.lateral * spanKm / 2
            // Perpendicular unit vector: (dirN, -dirE)
            val e = dirE * along + dirN * across
            val n = dirN * along - dirE * across
            Aircraft(
                id = r.id,
                callsign = r.callsign,
                latitude = lat0 + n / KM_PER_DEG_LAT,
                longitude = lon0 + e / kmPerDegLon,
                altitudeM = r.altitudeM,
                trackDeg = r.trackDeg,
                speedMs = r.speedMs,
                positionTimeSec = nowMs / 1000,
            )
        }
        return FetchResult(list)
    }

    private companion object {
        const val KM_PER_DEG_LAT = 110.574
    }
}
