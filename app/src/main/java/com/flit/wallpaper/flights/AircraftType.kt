package com.flit.wallpaper.flights

/** Visual class of an aircraft; each has its own silhouette. */
enum class AircraftClass { JET, HEAVY, TURBOPROP, LIGHT, HELICOPTER }

/** Airframe details from an aircraft database, keyed by ICAO 24-bit address. */
data class AircraftTypeInfo(
    /** ICAO type designator, e.g. "B738". */
    val icaoType: String?,
    val manufacturer: String?,
    val model: String?,
    val registration: String?,
) {
    /** Human name, e.g. "Boeing 737-800", or null if nothing useful is known. */
    fun displayName(): String? {
        AircraftClassifier.friendlyName(icaoType)?.let { return it }
        val m = manufacturer?.takeIf { it.isNotBlank() }
        val t = model?.takeIf { it.isNotBlank() }
        return when {
            m != null && t != null -> if (t.startsWith(m, ignoreCase = true)) t else "$m $t"
            else -> t ?: icaoType
        }
    }
}

/**
 * Decides which silhouette to draw.
 *
 * Priority: ICAO type designator (most reliable) → manufacturer keywords →
 * ADS-B emitter category broadcast by the aircraft → speed/altitude heuristic.
 */
object AircraftClassifier {

    fun classify(info: AircraftTypeInfo?, category: Int?, speedMs: Double?, altitudeM: Double?): AircraftClass {
        val code = info?.icaoType?.trim()?.uppercase()
        if (!code.isNullOrEmpty()) {
            when (code) {
                in HELICOPTERS -> return AircraftClass.HELICOPTER
                in HEAVIES -> return AircraftClass.HEAVY
                in TURBOPROPS -> return AircraftClass.TURBOPROP
                in LIGHTS -> return AircraftClass.LIGHT
            }
        }
        val maker = info?.manufacturer?.lowercase()
        if (maker != null && HELI_MAKERS.any { maker.contains(it) }) return AircraftClass.HELICOPTER

        when (category) {
            8 -> return AircraftClass.HELICOPTER
            2, 9, 12 -> return AircraftClass.LIGHT
            6 -> return AircraftClass.HEAVY
            3, 4, 5, 7 -> return AircraftClass.JET
        }
        if (!code.isNullOrEmpty()) return AircraftClass.JET

        val speed = speedMs ?: return AircraftClass.JET
        val alt = altitudeM ?: 10_000.0
        return if (speed < 70.0 && alt < 3_000.0) AircraftClass.LIGHT else AircraftClass.JET
    }

    fun friendlyName(icaoType: String?): String? = icaoType?.trim()?.uppercase()?.let { NAMES[it] }

    private val HEAVIES = setOf(
        "A306", "A30B", "A310", "A332", "A333", "A338", "A339", "A342", "A343", "A345", "A346",
        "A359", "A35K", "A388", "B741", "B742", "B743", "B744", "B748", "B74S", "B762", "B763",
        "B764", "B772", "B773", "B77L", "B77W", "B778", "B779", "B788", "B789", "B78X",
        "MD11", "DC10", "IL96", "A124", "A225", "C5M", "C17", "IL76", "K35R", "KC10", "A3ST", "BLCF",
    )

    private val TURBOPROPS = setOf(
        "AT43", "AT44", "AT45", "AT46", "AT72", "AT73", "AT75", "AT76", "ATP",
        "DH8A", "DH8B", "DH8C", "DH8D", "DHC6", "DHC7", "SF34", "SB20", "E110", "E120",
        "JS31", "JS32", "JS41", "D228", "D328", "F27", "F50", "BE20", "BE30", "BE9L", "BE99",
        "B190", "B350", "C208", "C212", "CN35", "C295", "PC12", "PC6T", "TBM7", "TBM8", "TBM9",
        "P180", "L410", "AN24", "AN26", "AN28", "AN30", "AN32", "AN12", "C130", "C30J", "MA60",
        "Y12", "SW4", "A400", "P3", "AT8T", "AT5T", "AT6T", "M28", "C27J", "SH36", "DO28",
    )

    private val LIGHTS = setOf(
        "C150", "C152", "C162", "C170", "C172", "C177", "C180", "C182", "C185", "C206", "C207",
        "C210", "C310", "C340", "C414", "C421", "P28A", "P28B", "P28R", "P28T", "P32R", "PA18",
        "PA22", "PA24", "PA28", "PA30", "PA31", "PA32", "PA34", "PA44", "PA46", "SR20", "SR22",
        "S22T", "DA20", "DA40", "DA42", "DA62", "BE33", "BE35", "BE36", "BE55", "BE58", "BE76",
        "M20P", "M20T", "TB10", "TB20", "DR40", "AA5", "C42", "CRUZ", "TECN", "P92", "ZLIN",
        "GLID", "ULAC", "BALL", "AN2", "PC21", "EV97", "SIRA", "RV7", "RV8", "RV10", "RV12",
    )

    private val HELICOPTERS = setOf(
        "R22", "R44", "R66", "B06", "B06T", "B47G", "B407", "B412", "B427", "B429", "B430",
        "B505", "B212", "B205", "B222", "B230", "EC20", "EC25", "EC30", "EC35", "EC45", "EC55",
        "EC75", "AS32", "AS50", "AS55", "AS65", "AS3B", "H500", "H269", "A109", "A119", "A129",
        "A139", "A149", "A169", "A189", "S76", "S92", "S61", "S64", "H60", "UH1", "MI8", "MI24",
        "MI26", "KA32", "BK17", "EH10", "NH90", "H47", "H53", "H64", "EXPL", "G2CA", "ALO3",
        "ALH", "LCH", "H160", "H175", "AS30",
    )

    private val HELI_MAKERS = listOf(
        "helicopter", "robinson", "sikorsky", "agusta", "eurocopter", "md helicopters",
        "kamov", "schweizer", "enstrom", "guimbal",
    )

    private val NAMES = mapOf(
        "A19N" to "Airbus A319neo", "A20N" to "Airbus A320neo", "A21N" to "Airbus A321neo",
        "A318" to "Airbus A318", "A319" to "Airbus A319", "A320" to "Airbus A320", "A321" to "Airbus A321",
        "A332" to "Airbus A330-200", "A333" to "Airbus A330-300", "A339" to "Airbus A330-900",
        "A359" to "Airbus A350-900", "A35K" to "Airbus A350-1000", "A388" to "Airbus A380",
        "BCS1" to "Airbus A220-100", "BCS3" to "Airbus A220-300",
        "B737" to "Boeing 737-700", "B738" to "Boeing 737-800", "B739" to "Boeing 737-900",
        "B37M" to "Boeing 737 MAX 7", "B38M" to "Boeing 737 MAX 8", "B39M" to "Boeing 737 MAX 9",
        "B3XM" to "Boeing 737 MAX 10", "B752" to "Boeing 757-200", "B763" to "Boeing 767-300",
        "B744" to "Boeing 747-400", "B748" to "Boeing 747-8", "B772" to "Boeing 777-200",
        "B77L" to "Boeing 777-200LR", "B77W" to "Boeing 777-300ER", "B788" to "Boeing 787-8",
        "B789" to "Boeing 787-9", "B78X" to "Boeing 787-10", "E170" to "Embraer E170",
        "E75L" to "Embraer E175", "E190" to "Embraer E190", "E195" to "Embraer E195",
        "E290" to "Embraer E190-E2", "E295" to "Embraer E195-E2", "CRJ2" to "Bombardier CRJ200",
        "CRJ7" to "Bombardier CRJ700", "CRJ9" to "Bombardier CRJ900", "AT72" to "ATR 72",
        "AT76" to "ATR 72-600", "AT45" to "ATR 42-500", "AT46" to "ATR 42-600",
        "DH8D" to "De Havilland Dash 8-400", "C208" to "Cessna 208 Caravan",
        "B350" to "Beechcraft King Air 350", "PC12" to "Pilatus PC-12", "C172" to "Cessna 172",
        "MD11" to "McDonnell Douglas MD-11", "A306" to "Airbus A300-600",
    )
}
