package com.flit.wallpaper.projection

import com.flit.wallpaper.flights.BoundingBox
import kotlin.math.cos

/**
 * Local equirectangular projection centred on (lat0, lon0).
 *
 * Over the ~50–300 km a wallpaper shows, distortion is well under 1%, and the
 * maths is two multiplications per point, which suits a per-frame renderer.
 * East and north are in kilometres.
 */
class LocalProjection(val lat0: Double, val lon0: Double) {

    private val kmPerDegLon = KM_PER_DEG_LON_EQUATOR * cos(Math.toRadians(lat0)).coerceAtLeast(MIN_COS)

    fun eastKm(lon: Double): Double = wrapDeg(lon - lon0) * kmPerDegLon
    fun northKm(lat: Double): Double = (lat - lat0) * KM_PER_DEG_LAT

    /** Box spanning [halfNorthKm] north/south and [halfEastKm] east/west of the centre. */
    fun boundingBox(halfNorthKm: Double, halfEastKm: Double): BoundingBox {
        val dLat = halfNorthKm / KM_PER_DEG_LAT
        val dLon = halfEastKm / kmPerDegLon
        return BoundingBox(
            latMin = (lat0 - dLat).coerceIn(-89.0, 89.0),
            lonMin = (lon0 - dLon).coerceAtLeast(-180.0),
            latMax = (lat0 + dLat).coerceIn(-89.0, 89.0),
            lonMax = (lon0 + dLon).coerceAtMost(180.0),
        )
    }

    fun sameCentre(other: LocalProjection?): Boolean =
        other != null && other.lat0 == lat0 && other.lon0 == lon0

    companion object {
        const val KM_PER_DEG_LAT = 110.574
        const val KM_PER_DEG_LON_EQUATOR = 111.320
        private const val MIN_COS = 0.05

        /** Wraps a longitude difference into [-180, 180). */
        fun wrapDeg(d: Double): Double {
            var x = (d + 180.0) % 360.0
            if (x < 0) x += 360.0
            return x - 180.0
        }
    }
}
