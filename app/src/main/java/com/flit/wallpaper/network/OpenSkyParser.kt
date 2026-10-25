package com.flit.wallpaper.network

import com.flit.wallpaper.flights.Aircraft
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the OpenSky `/states/all` response.
 *
 * Each state vector is a positional array:
 *  0 icao24, 1 callsign, 2 origin_country, 3 time_position, 4 last_contact,
 *  5 longitude, 6 latitude, 7 baro_altitude, 8 on_ground, 9 velocity,
 *  10 true_track, 11 vertical_rate, 12 sensors, 13 geo_altitude, 14 squawk,
 *  15 spi, 16 position_source (17 category, extended only).
 *
 * Aircraft on the ground or without a position are dropped; any other missing
 * field becomes null. Malformed rows are skipped, never fatal.
 */
object OpenSkyParser {

    fun parse(json: String): List<Aircraft> {
        val root = JSONObject(json)
        val responseTime = root.optLong("time", 0L)
        val states = root.optJSONArray("states") ?: return emptyList()
        val byId = LinkedHashMap<String, Aircraft>(states.length())
        for (i in 0 until states.length()) {
            val row = states.optJSONArray(i) ?: continue
            val a = parseRow(row, responseTime) ?: continue
            val existing = byId[a.id]
            if (existing == null || existing.positionTimeSec < a.positionTimeSec) {
                byId[a.id] = a
            }
        }
        return ArrayList(byId.values)
    }

    private fun parseRow(row: JSONArray, responseTime: Long): Aircraft? {
        val id = row.str(0)?.lowercase() ?: return null
        val lon = row.num(5) ?: return null
        val lat = row.num(6) ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (!row.isNull(8) && row.optBoolean(8, false)) return null

        val time = row.long(3) ?: row.long(4) ?: responseTime
        if (time <= 0) return null

        return Aircraft(
            id = id,
            callsign = row.str(1),
            latitude = lat,
            longitude = lon,
            altitudeM = row.num(7) ?: row.num(13),
            trackDeg = row.num(10)?.takeIf { it in 0.0..360.0 },
            speedMs = row.num(9)?.takeIf { it >= 0.0 },
            positionTimeSec = time,
        )
    }

    private fun JSONArray.num(i: Int): Double? {
        if (isNull(i)) return null
        val d = optDouble(i)
        return if (d.isNaN() || d.isInfinite()) null else d
    }

    private fun JSONArray.long(i: Int): Long? = num(i)?.toLong()?.takeIf { it > 0 }

    private fun JSONArray.str(i: Int): String? {
        if (isNull(i)) return null
        return optString(i, "").trim().ifEmpty { null }
    }
}
