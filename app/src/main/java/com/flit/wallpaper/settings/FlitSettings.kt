package com.flit.wallpaper.settings

import android.content.Context
import android.content.SharedPreferences
import com.flit.wallpaper.projection.LocalProjection

enum class DataMode { LIVE, DEMO, OFF }
enum class Appearance { DARK_SKY, NEAR_BLACK }

/** A predefined place the user can pick instead of typing coordinates. */
data class City(val name: String, val lat: Double, val lon: Double)

/** Thin, typed wrapper over local SharedPreferences. Nothing leaves the device. */
class FlitSettings(context: Context) {

    val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var dataMode: DataMode
        get() = enumOr(prefs.getString(K_MODE, null), DataMode.LIVE)
        set(v) = prefs.edit().putString(K_MODE, v.name).apply()

    var appearance: Appearance
        get() = enumOr(prefs.getString(K_APPEARANCE, null), Appearance.DARK_SKY)
        set(v) = prefs.edit().putString(K_APPEARANCE, v.name).apply()

    var placeName: String
        get() = prefs.getString(K_PLACE, null) ?: DEFAULT_CITY.name
        set(v) = prefs.edit().putString(K_PLACE, v).apply()

    var latitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(K_LAT, DEFAULT_CITY.lat.toRawBits()))
        set(v) = prefs.edit().putLong(K_LAT, v.toRawBits()).apply()

    var longitude: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(K_LON, DEFAULT_CITY.lon.toRawBits()))
        set(v) = prefs.edit().putLong(K_LON, v.toRawBits()).apply()

    var radiusKm: Int
        get() = prefs.getInt(K_RADIUS, DEFAULT_RADIUS_KM).coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM)
        set(v) = prefs.edit().putInt(K_RADIUS, v.coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM)).apply()

    var refreshSec: Int
        get() = prefs.getInt(K_REFRESH, DEFAULT_REFRESH_SEC).coerceIn(MIN_REFRESH_SEC, MAX_REFRESH_SEC)
        set(v) = prefs.edit().putInt(K_REFRESH, v.coerceIn(MIN_REFRESH_SEC, MAX_REFRESH_SEC)).apply()

    var showLabels: Boolean
        get() = prefs.getBoolean(K_LABELS, true)
        set(v) = prefs.edit().putBoolean(K_LABELS, v).apply()

    var clientId: String
        get() = prefs.getString(K_CLIENT_ID, "") ?: ""
        set(v) = prefs.edit().putString(K_CLIENT_ID, v.trim()).apply()

    var clientSecret: String
        get() = prefs.getString(K_CLIENT_SECRET, "") ?: ""
        set(v) = prefs.edit().putString(K_CLIENT_SECRET, v.trim()).apply()

    fun projection(): LocalProjection = LocalProjection(latitude, longitude)

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: fallback

    companion object {
        const val PREFS = "flit_settings"
        private const val K_MODE = "data_mode"
        private const val K_APPEARANCE = "appearance"
        private const val K_PLACE = "place_name"
        private const val K_LAT = "lat_bits"
        private const val K_LON = "lon_bits"
        private const val K_RADIUS = "radius_km"
        private const val K_REFRESH = "refresh_sec"
        private const val K_LABELS = "show_labels"
        private const val K_CLIENT_ID = "opensky_client_id"
        private const val K_CLIENT_SECRET = "opensky_client_secret"

        const val MIN_RADIUS_KM = 20
        const val MAX_RADIUS_KM = 150
        const val DEFAULT_RADIUS_KM = 60

        /** 60 s floor keeps an always-visible wallpaper near OpenSky's anonymous allowance. */
        const val MIN_REFRESH_SEC = 60
        const val MAX_REFRESH_SEC = 600
        const val DEFAULT_REFRESH_SEC = 120

        val CITIES = listOf(
            City("Chennai", 13.0827, 80.2707),
            City("Bengaluru", 12.9716, 77.5946),
            City("Mumbai", 19.0760, 72.8777),
            City("Delhi", 28.6139, 77.2090),
            City("Hyderabad", 17.3850, 78.4867),
            City("Kolkata", 22.5726, 88.3639),
            City("Dubai", 25.2048, 55.2708),
            City("Singapore", 1.3521, 103.8198),
            City("London", 51.5074, -0.1278),
            City("Frankfurt", 50.1109, 8.6821),
            City("New York", 40.7128, -74.0060),
            City("San Francisco", 37.7749, -122.4194),
            City("Phoenix", 33.4484, -112.0740),
            City("Tokyo", 35.6762, 139.6503),
            City("Sydney", -33.8688, 151.2093),
        )
        val DEFAULT_CITY = CITIES.first()
    }
}
