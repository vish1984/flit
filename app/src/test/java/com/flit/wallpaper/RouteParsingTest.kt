package com.flit.wallpaper

import com.flit.wallpaper.flights.Airport
import com.flit.wallpaper.flights.Callsigns
import com.flit.wallpaper.network.AdsbdbRouteProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteParsingTest {

    private val sample = """
    {"response":{"flightroute":{
      "callsign":"AIC101","callsign_icao":"AIC101","callsign_iata":"AI101",
      "airline":{"name":"Air India","icao":"AIC","iata":"AI","country":"India","callsign":"AIRINDIA"},
      "origin":{"country_name":"India","iata_code":"DEL","icao_code":"VIDP","municipality":"New Delhi","name":"Indira Gandhi International Airport"},
      "destination":{"country_name":"United States","iata_code":"JFK","icao_code":"KJFK","municipality":"New York","name":"John F Kennedy International Airport"}
    }}}
    """.trimIndent()

    @Test fun parsesFlightRoute() {
        val r = AdsbdbRouteProvider.parse(sample)!!
        assertEquals("AI101", r.flightNumber)
        assertEquals("Air India", r.airlineName)
        assertEquals("DEL → JFK", r.shortRoute())
        assertEquals("New Delhi (DEL)", r.origin!!.display())
        assertEquals("New York", r.destination!!.city)
    }

    @Test fun fallsBackToIcaoCodesAndCallsign() {
        val json = """{"response":{"flightroute":{"callsign":"XYZ12","callsign_iata":null,
            "origin":{"iata_code":"","icao_code":"VOMM","municipality":null},
            "destination":{"iata_code":null,"icao_code":"VABB","municipality":"Mumbai"}}}}"""
        val r = AdsbdbRouteProvider.parse(json)!!
        assertEquals("XYZ12", r.flightNumber)
        assertNull(r.airlineName)
        assertEquals("VOMM → VABB", r.shortRoute())
        assertEquals("VOMM", r.origin!!.display())
    }

    @Test fun unknownOrMalformedIsNull() {
        assertNull(AdsbdbRouteProvider.parse("""{"response":"unknown callsign"}"""))
        assertNull(AdsbdbRouteProvider.parse("""{"response":{}}"""))
        assertNull(AdsbdbRouteProvider.parse("not json"))
    }

    @Test fun callsignRules() {
        assertEquals("AIC101", Callsigns.normalize(" aic101  "))
        assertNull(Callsigns.normalize("   "))
        assertTrue(Callsigns.looksLikeAirline("AIC101"))
        assertTrue(Callsigns.looksLikeAirline("BAW12K"))
        assertFalse(Callsigns.looksLikeAirline("N123AB"))
        assertFalse(Callsigns.looksLikeAirline("VTABC"))
    }

    @Test fun airportDisplayVariants() {
        assertEquals("Mumbai", Airport(null, "Mumbai", null).display())
        assertEquals("Chhatrapati Shivaji", Airport(null, null, "Chhatrapati Shivaji").display())
    }
}
