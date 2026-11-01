package com.flit.wallpaper

import com.flit.wallpaper.flights.AircraftClass
import com.flit.wallpaper.flights.AircraftClassifier
import com.flit.wallpaper.flights.AircraftTrack
import com.flit.wallpaper.flights.AircraftTypeInfo
import com.flit.wallpaper.network.AdsbdbRouteProvider
import com.flit.wallpaper.network.OpenSkyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AircraftClassTest {

    private fun type(code: String?, maker: String? = null) = AircraftTypeInfo(code, maker, null, null)

    @Test fun typeCodesWin() {
        assertEquals(AircraftClass.HEAVY, AircraftClassifier.classify(type("B77W"), 4, 250.0, 11000.0))
        assertEquals(AircraftClass.TURBOPROP, AircraftClassifier.classify(type("AT76"), 3, 140.0, 5000.0))
        assertEquals(AircraftClass.HELICOPTER, AircraftClassifier.classify(type("EC35"), null, 60.0, 400.0))
        assertEquals(AircraftClass.LIGHT, AircraftClassifier.classify(type("C172"), null, 55.0, 1200.0))
        assertEquals(AircraftClass.JET, AircraftClassifier.classify(type("A20N"), null, 230.0, 11000.0))
    }

    @Test fun manufacturerAndCategoryFallbacks() {
        assertEquals(AircraftClass.HELICOPTER, AircraftClassifier.classify(type("XXXX", "Robinson Helicopter Co"), null, 50.0, 300.0))
        assertEquals(AircraftClass.HELICOPTER, AircraftClassifier.classify(null, 8, 50.0, 300.0))
        assertEquals(AircraftClass.HEAVY, AircraftClassifier.classify(null, 6, 250.0, 11000.0))
        assertEquals(AircraftClass.LIGHT, AircraftClassifier.classify(null, 2, 60.0, 900.0))
    }

    @Test fun speedHeuristicWhenNothingKnown() {
        assertEquals(AircraftClass.LIGHT, AircraftClassifier.classify(null, null, 50.0, 800.0))
        assertEquals(AircraftClass.JET, AircraftClassifier.classify(null, null, 220.0, 9000.0))
        assertEquals(AircraftClass.JET, AircraftClassifier.classify(null, null, null, null))
    }

    @Test fun friendlyNames() {
        assertEquals("Boeing 737-800", type("B738").displayName())
        assertEquals("Airbus Helicopters H135", AircraftTypeInfo("EC35", "Airbus Helicopters", "H135", null).displayName())
        assertEquals("Cessna 172", type("C172").displayName())
        assertNull(AircraftTypeInfo(null, null, null, null).displayName())
    }

    @Test fun parsesAdsbdbAircraft() {
        val json = """{"response":{"aircraft":{"type":"737NG 8AL/W","icao_type":"b738","manufacturer":"Boeing",
            "mode_s":"800ABC","registration":"VT-ABC","registered_owner":"Example"}}}"""
        val a = AdsbdbRouteProvider.parseAircraft(json)!!
        assertEquals("B738", a.icaoType)
        assertEquals("Boeing", a.manufacturer)
        assertEquals("Boeing 737-800", a.displayName())
        assertNull(AdsbdbRouteProvider.parseAircraft("""{"response":"unknown aircraft"}"""))
        assertNull(AdsbdbRouteProvider.parseAircraft("<html>"))
    }

    @Test fun parsesOpenSkyCategory() {
        val json = """{"time":1730000000,"states":[
          ["800abc","AIC1","India",1729999995,1729999998,80.3,13.1,500.0,false,60.0,90.0,0.0,null,520.0,null,false,0,8],
          ["800abd","AIC2","India",1729999995,1729999998,80.3,13.1,500.0,false,60.0,90.0,0.0,null,520.0,null,false,0,0],
          ["800abe","AIC3","India",1729999995,1729999998,80.3,13.1,500.0,false,60.0,90.0,0.0,null,520.0,null,false,0]
        ]}"""
        val byId = OpenSkyParser.parse(json).associateBy { it.id }
        assertEquals(8, byId["800abc"]!!.category)
        assertNull(byId["800abd"]!!.category)
        assertNull(byId["800abe"]!!.category)
    }

    @Test fun trailKeepsRecentPointsOldestFirst() {
        val t = AircraftTrack("x")
        val step = AircraftTrack.TRAIL_SAMPLE_MS
        for (i in 0 until AircraftTrack.TRAIL_POINTS + 5) t.sampleTrail(i * step, i.toDouble(), 0.0, 1000.0)
        assertEquals(AircraftTrack.TRAIL_POINTS, t.trailCount)
        assertEquals(5f, t.trailEAt(0), 0f)
        assertEquals((AircraftTrack.TRAIL_POINTS + 4).toFloat(), t.trailEAt(t.trailCount - 1), 0f)
        // Samples closer together than the interval are ignored.
        t.sampleTrail((AircraftTrack.TRAIL_POINTS + 4) * step + 10, 99.0, 0.0, 1000.0)
        assertEquals((AircraftTrack.TRAIL_POINTS + 4).toFloat(), t.trailEAt(t.trailCount - 1), 0f)
        // A long gap (wallpaper hidden) restarts the trail.
        t.sampleTrail(10_000_000L, 0.0, 0.0, 1000.0)
        assertEquals(1, t.trailCount)
    }
}
