package com.flit.wallpaper

import com.flit.wallpaper.network.OpenSkyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSkyParserTest {

    private val sample = """
    {"time":1730000000,"states":[
      ["800abc","AIC101  ","India",1729999995,1729999998,80.30,13.10,10668.0,false,240.5,45.0,0.0,null,10900.0,"1234",false,0],
      ["800def",null,"India",null,1729999990,80.10,12.90,null,false,null,null,null,null,3000.0,null,false,0],
      ["800aaa","GND1","India",1729999990,1729999990,80.0,13.0,0.0,true,5.0,90.0,0.0,null,0.0,null,false,0],
      ["800bbb","NOPOS","India",1729999990,1729999990,null,null,9000,false,200,10,0,null,9000,null,false,0],
      ["800ABC","AIC101","India",1729999999,1729999999,80.31,13.11,10668.0,false,240.5,45.0,0.0,null,10900.0,null,false,0],
      "garbage"
    ]}
    """.trimIndent()

    @Test fun parsesAirborneAircraftAndSkipsBadRows() {
        val list = OpenSkyParser.parse(sample)
        val ids = list.map { it.id }
        assertEquals(listOf("800abc", "800def"), ids)
    }

    @Test fun deduplicatesKeepingNewestPosition() {
        val a = OpenSkyParser.parse(sample).first { it.id == "800abc" }
        assertEquals(1729999999L, a.positionTimeSec)
        assertEquals(13.11, a.latitude, 1e-9)
        assertEquals("AIC101", a.callsign)
    }

    @Test fun missingFieldsBecomeNullWithFallbacks() {
        val a = OpenSkyParser.parse(sample).first { it.id == "800def" }
        assertNull(a.callsign)
        assertNull(a.speedMs)
        assertNull(a.trackDeg)
        assertEquals(3000.0, a.altitudeM!!, 1e-9) // geo altitude fallback
        assertEquals(1729999990L, a.positionTimeSec) // last_contact fallback
    }

    @Test fun emptyOrNullStatesIsEmptyList() {
        assertTrue(OpenSkyParser.parse("""{"time":1,"states":null}""").isEmpty())
        assertTrue(OpenSkyParser.parse("""{"time":1}""").isEmpty())
    }
}
