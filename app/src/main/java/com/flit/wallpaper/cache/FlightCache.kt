package com.flit.wallpaper.cache

import android.content.Context
import com.flit.wallpaper.flights.Aircraft
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last-known aircraft snapshot on disk, so the wallpaper has something honest
 * to show after a restart or while offline. Old positions fade out in the
 * scene on their own; the cache never makes stale data look fresh.
 */
class FlightCache(context: Context) {

    private val file = File(context.applicationContext.filesDir, "flights_cache.json")

    data class Snapshot(val aircraft: List<Aircraft>, val fetchedAtMs: Long, val simulated: Boolean)

    /** Call from a background thread. */
    fun save(snapshot: Snapshot) {
        try {
            val arr = JSONArray()
            for (a in snapshot.aircraft) {
                arr.put(JSONObject().apply {
                    put("id", a.id)
                    put("cs", a.callsign ?: JSONObject.NULL)
                    put("lat", a.latitude)
                    put("lon", a.longitude)
                    put("alt", a.altitudeM ?: JSONObject.NULL)
                    put("trk", a.trackDeg ?: JSONObject.NULL)
                    put("spd", a.speedMs ?: JSONObject.NULL)
                    put("t", a.positionTimeSec)
                })
            }
            val root = JSONObject()
                .put("v", 1)
                .put("fetchedAt", snapshot.fetchedAtMs)
                .put("simulated", snapshot.simulated)
                .put("aircraft", arr)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: Exception) {
            // Cache is best-effort.
        }
    }

    fun load(): Snapshot? {
        return try {
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("aircraft") ?: return null
            val list = ArrayList<Aircraft>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    Aircraft(
                        id = o.optString("id"),
                        callsign = if (o.isNull("cs")) null else o.optString("cs"),
                        latitude = o.optDouble("lat"),
                        longitude = o.optDouble("lon"),
                        altitudeM = if (o.isNull("alt")) null else o.optDouble("alt"),
                        trackDeg = if (o.isNull("trk")) null else o.optDouble("trk"),
                        speedMs = if (o.isNull("spd")) null else o.optDouble("spd"),
                        positionTimeSec = o.optLong("t"),
                    )
                )
            }
            Snapshot(list, root.optLong("fetchedAt"), root.optBoolean("simulated"))
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        file.delete()
    }
}
