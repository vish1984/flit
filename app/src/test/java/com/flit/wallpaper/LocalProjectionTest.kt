package com.flit.wallpaper

import com.flit.wallpaper.projection.LocalProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProjectionTest {

    @Test fun centreMapsToOrigin() {
        val p = LocalProjection(13.0827, 80.2707)
        assertEquals(0.0, p.eastKm(80.2707), 1e-9)
        assertEquals(0.0, p.northKm(13.0827), 1e-9)
    }

    @Test fun northAndEastHaveCorrectSignAndScale() {
        val p = LocalProjection(0.0, 0.0)
        assertEquals(110.574, p.northKm(1.0), 1e-6)
        assertEquals(111.3, p.eastKm(1.0), 0.2)
        assertTrue(p.eastKm(-0.5) < 0)
        assertTrue(p.northKm(-0.5) < 0)
    }

    @Test fun longitudeShrinksWithLatitude() {
        val p = LocalProjection(60.0, 0.0)
        assertEquals(55.7, p.eastKm(1.0), 0.3)
    }

    @Test fun wrapsAcrossAntimeridian() {
        val p = LocalProjection(0.0, 179.9)
        // -179.9 is 0.2 degrees east of 179.9, not 359.8 degrees west.
        assertEquals(0.2 * 111.32, p.eastKm(-179.9), 0.5)
    }

    @Test fun boundingBoxCoversRequestedDistance() {
        val p = LocalProjection(13.0, 80.0)
        val b = p.boundingBox(halfNorthKm = 120.0, halfEastKm = 60.0)
        assertEquals(120.0, p.northKm(b.latMax), 0.01)
        assertEquals(-120.0, p.northKm(b.latMin), 0.01)
        assertEquals(60.0, p.eastKm(b.lonMax), 0.01)
        assertTrue(b.areaSqDeg < 25.0) // one OpenSky credit
    }
}
