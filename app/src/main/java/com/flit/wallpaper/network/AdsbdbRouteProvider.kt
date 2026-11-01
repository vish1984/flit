package com.flit.wallpaper.network

import com.flit.wallpaper.flights.AircraftTypeInfo
import com.flit.wallpaper.flights.Airport
import com.flit.wallpaper.flights.RouteInfo
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lookups against adsbdb.com (free, no API key):
 *  - callsign → scheduled route, airline and airports
 *  - ICAO 24-bit address → aircraft type
 * Only the public callsign or transponder address is sent.
 */
class AdsbdbRouteProvider {

    sealed class Result {
        data class Found(val route: RouteInfo) : Result()
        /** The database has no route for this callsign. Safe to cache. */
        object Unknown : Result()
    }

    class RateLimited : Exception("adsbdb rate limit")

    fun lookup(callsign: String): Result {
        val body = get(API_BASE + URLEncoder.encode(callsign, "UTF-8")) ?: return Result.Unknown
        return parse(body)?.let { Result.Found(it) } ?: Result.Unknown
    }

    /** Aircraft type for an ICAO 24-bit address, or null if the database doesn't know it. */
    fun lookupAircraft(icao24: String): AircraftTypeInfo? {
        val body = get(AIRCRAFT_BASE + URLEncoder.encode(icao24, "UTF-8")) ?: return null
        return parseAircraft(body)
    }

    /** Returns the body on 200, null on 404; throws on rate limit or other errors. */
    private fun get(address: String): String? {
        val url = URL(address)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", OpenSkyProvider.USER_AGENT)
        }
        try {
            return when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> conn.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                429 -> throw RateLimited()
                else -> throw IOException("adsbdb HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val API_BASE = "https://api.adsbdb.com/v0/callsign/"
        const val AIRCRAFT_BASE = "https://api.adsbdb.com/v0/aircraft/"
        private const val TIMEOUT_MS = 10_000

        /**
         * Parses `{"response":{"flightroute":{...}}}`. Any unexpected shape
         * returns null (treated as "route unknown"), never throws on content.
         */
        fun parse(json: String): RouteInfo? {
            val root = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val response = root.opt("response") as? JSONObject ?: return null
            val fr = response.optJSONObject("flightroute") ?: return null
            val icao = fr.str("callsign_icao") ?: fr.str("callsign") ?: return null
            val airline = fr.optJSONObject("airline")
            val origin = fr.optJSONObject("origin")?.let(::airport)
            val destination = fr.optJSONObject("destination")?.let(::airport)
            if (origin == null && destination == null && airline == null) return null
            return RouteInfo(
                flightNumber = fr.str("callsign_iata") ?: icao,
                airlineName = airline?.str("name"),
                origin = origin,
                destination = destination,
            )
        }

        /** Parses `{"response":{"aircraft":{...}}}`; anything else returns null. */
        fun parseAircraft(json: String): AircraftTypeInfo? {
            val root = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val response = root.opt("response") as? JSONObject ?: return null
            val a = response.optJSONObject("aircraft") ?: return null
            val info = AircraftTypeInfo(
                icaoType = a.str("icao_type")?.uppercase(),
                manufacturer = a.str("manufacturer"),
                model = a.str("type"),
                registration = a.str("registration"),
            )
            return if (info.icaoType == null && info.model == null && info.manufacturer == null) null else info
        }

        private fun airport(o: JSONObject): Airport? {
            val a = Airport(
                code = o.str("iata_code") ?: o.str("icao_code"),
                city = o.str("municipality"),
                name = o.str("name"),
            )
            return if (a.code == null && a.city == null && a.name == null) null else a
        }

        private fun JSONObject.str(key: String): String? {
            if (isNull(key)) return null
            return optString(key, "").trim().ifEmpty { null }
        }
    }
}
