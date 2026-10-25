package com.flit.wallpaper.network

import com.flit.wallpaper.flights.AuthException
import com.flit.wallpaper.flights.BoundingBox
import com.flit.wallpaper.flights.FetchResult
import com.flit.wallpaper.flights.FlightDataProvider
import com.flit.wallpaper.flights.RateLimitedException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * OpenSky Network REST provider (`GET /api/states/all` with a bounding box).
 *
 * Works anonymously by default. If the user supplies their own OpenSky API
 * client (OAuth2 client-credentials), requests are authenticated, which raises
 * the daily credit allowance. Credentials are entered on the device and never
 * compiled into the APK.
 */
class OpenSkyProvider(
    private val clientId: String?,
    private val clientSecret: String?,
) : FlightDataProvider {

    override val name = "OpenSky Network"
    override val isSimulated = false

    val isAuthenticated: Boolean get() = !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()

    /** OpenSky charges /states/all by bounding-box area in square degrees. */
    override fun creditCost(box: BoundingBox): Int = when {
        box.areaSqDeg <= 25.0 -> 1
        box.areaSqDeg <= 100.0 -> 2
        box.areaSqDeg <= 400.0 -> 3
        else -> 4
    }

    override fun fetch(box: BoundingBox): FetchResult {
        val url = URL(
            String.format(
                Locale.US,
                "%s/states/all?lamin=%.4f&lomin=%.4f&lamax=%.4f&lomax=%.4f",
                API_BASE, box.latMin, box.lonMin, box.latMax, box.lonMax,
            )
        )
        val token = if (isAuthenticated) bearerToken() else null
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            val remaining = conn.getHeaderField("X-Rate-Limit-Remaining")?.trim()?.toIntOrNull()
            when (code) {
                HttpURLConnection.HTTP_OK -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    return FetchResult(OpenSkyParser.parse(body), remaining)
                }
                429 -> {
                    val retry = conn.getHeaderField("X-Rate-Limit-Retry-After-Seconds")?.trim()?.toLongOrNull()
                    throw RateLimitedException(retry)
                }
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> {
                    synchronized(TokenCache) { TokenCache.clear() }
                    throw AuthException("OpenSky rejected the request (HTTP $code)")
                }
                else -> throw IOException("OpenSky HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun bearerToken(): String {
        val id = clientId!!.trim()
        synchronized(TokenCache) {
            val now = System.currentTimeMillis()
            if (TokenCache.clientId == id && TokenCache.token != null && now < TokenCache.expiresAtMs) {
                return TokenCache.token!!
            }
            val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val form = "grant_type=client_credentials" +
                    "&client_id=" + URLEncoder.encode(id, "UTF-8") +
                    "&client_secret=" + URLEncoder.encode(clientSecret!!.trim(), "UTF-8")
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw AuthException("OpenSky sign-in failed (HTTP $code). Check client id/secret.")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val token = json.optString("access_token", "")
                if (token.isEmpty()) throw AuthException("OpenSky sign-in returned no token")
                val ttlSec = json.optLong("expires_in", 1800L).coerceAtLeast(120L)
                TokenCache.clientId = id
                TokenCache.token = token
                TokenCache.expiresAtMs = now + (ttlSec - 60) * 1000
                return token
            } finally {
                conn.disconnect()
            }
        }
    }

    private object TokenCache {
        var clientId: String? = null
        var token: String? = null
        var expiresAtMs: Long = 0
        fun clear() {
            token = null
            expiresAtMs = 0
        }
    }

    companion object {
        const val API_BASE = "https://opensky-network.org/api"
        const val TOKEN_URL =
            "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token"
        const val USER_AGENT = "Flit/0.1 (Android live wallpaper)"
        private const val TIMEOUT_MS = 15_000

        /** Published daily allowances; used only as a local safety budget. */
        const val ANONYMOUS_DAILY_CREDITS = 400
        const val AUTHENTICATED_DAILY_CREDITS = 4000
    }
}
