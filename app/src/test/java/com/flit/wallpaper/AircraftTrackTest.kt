package com.flit.wallpaper

import com.flit.wallpaper.flights.Aircraft
import com.flit.wallpaper.flights.FlightScene
import com.flit.wallpaper.projection.LocalProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class AircraftTrackTest {

    private val proj = LocalProjection(0.0, 0.0)
    private val t0 = 1_730_000_000L

    private fun plane(id: String, lat: Double, lon: Double, timeSec: Long, track: Double = 90.0, speed: Double = 250.0) =
        Aircraft(id, "X", lat, lon, 10000.0, track, speed, timeSec)

    @Test fun deadReckonsAlongTrack() {
        val scene = FlightScene().apply { configure(proj, 120) }
        scene.apply(listOf(plane("a", 0.0, 0.0, t0)), t0 * 1000)
        val out = DoubleArray(2)
        scene.tracks[0].position(t0 * 1000 + 60_000, scene.staleAfterMs, out)
        // 250 m/s eastbound for 60 s = 15 km east.
        assertEquals(15.0, out[0], 0.01)
        assertEquals(0.0, out[1], 0.01)
    }

    @Test fun newObservationBlendsInsteadOfJumping() {
        val scene = FlightScene().apply { configure(proj, 120) }
        scene.apply(listOf(plane("a", 0.0, 0.0, t0)), t0 * 1000)
        val nowMs = t0 * 1000 + 120_000
        val before = DoubleArray(2)
        scene.tracks[0].position(nowMs, scene.staleAfterMs, before)
        // New fix 2 km north of where we predicted.
        val newLat = 2.0 / LocalProjection.KM_PER_DEG_LAT
        scene.apply(listOf(plane("a", newLat, 30.0 / 111.32, t0 + 120)), nowMs)
        val after = DoubleArray(2)
        scene.tracks[0].position(nowMs, scene.staleAfterMs, after)
        assertTrue(hypot(after[0] - before[0], after[1] - before[1]) < 0.01)
        // ...and converges to the new fix a few seconds later.
        val later = DoubleArray(2)
        scene.tracks[0].position(nowMs + 10_000, scene.staleAfterMs, later)
        assertEquals(2.0, later[1], 0.05)
    }

    @Test fun noDuplicatesAndStaleAircraftFadeAndAreRemoved() {
        val scene = FlightScene().apply { configure(proj, 120) }
        val now = t0 * 1000
        scene.apply(listOf(plane("a", 0.0, 0.0, t0), plane("a", 0.0, 0.0, t0)), now)
        assertEquals(1, scene.tracks.size)
        val t = scene.tracks[0]
        assertEquals(1f, scene.alphaFor(t, now + scene.staleAfterMs), 0f)
        assertTrue(scene.alphaFor(t, now + scene.staleAfterMs + 15_000) < 1f)
        scene.prune(now + scene.staleAfterMs + FlightScene.FADE_MS + 1)
        assertTrue(scene.isEmpty)
    }

    @Test fun olderObservationDoesNotOverwriteNewer() {
        val scene = FlightScene().apply { configure(proj, 120) }
        scene.apply(listOf(plane("a", 1.0, 0.0, t0 + 100)), t0 * 1000)
        scene.apply(listOf(plane("a", 0.0, 0.0, t0)), t0 * 1000)
        assertEquals(1.0, scene.tracks[0].observation!!.latitude, 0.0)
    }
}
