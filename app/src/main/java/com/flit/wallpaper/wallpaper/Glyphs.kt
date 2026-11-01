package com.flit.wallpaper.wallpaper

import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import com.flit.wallpaper.flights.AircraftClass

/**
 * Top-down aircraft silhouettes in unit space: nose at (0, -1), tail near
 * (0, +1), centred on the origin. Built once; drawn by scaling and rotating.
 * Overlapping contours are filled with the WINDING rule, so they merge.
 */
object Glyphs {

    /** Extra scale on top of the base plane size, so classes also differ in size. */
    fun sizeFactor(c: AircraftClass): Float = when (c) {
        AircraftClass.HEAVY -> 1.35f
        AircraftClass.JET -> 1.0f
        AircraftClass.TURBOPROP -> 0.9f
        AircraftClass.LIGHT -> 0.7f
        AircraftClass.HELICOPTER -> 0.85f
    }

    fun pathFor(c: AircraftClass): Path = when (c) {
        AircraftClass.JET -> JET
        AircraftClass.HEAVY -> HEAVY
        AircraftClass.TURBOPROP -> TURBOPROP
        AircraftClass.LIGHT -> LIGHT
        AircraftClass.HELICOPTER -> HELICOPTER_BODY
    }

    /** Narrow-body: swept wings, swept tail. */
    private val JET: Path = mirrored(
        0f, -1f,
        0.10f, -0.86f,
        0.12f, -0.30f,
        0.95f, 0.12f,
        0.95f, 0.28f,
        0.12f, 0.10f,
        0.09f, 0.62f,
        0.36f, 0.82f,
        0.36f, 0.94f,
        0f, 0.86f,
    )

    /** Wide-body: fatter fuselage, long swept wings, underwing engine pods. */
    private val HEAVY: Path = mirrored(
        0f, -1f,
        0.13f, -0.84f,
        0.15f, -0.32f,
        1.0f, 0.18f,
        1.0f, 0.30f,
        0.15f, 0.08f,
        0.11f, 0.64f,
        0.42f, 0.84f,
        0.42f, 0.95f,
        0f, 0.88f,
    ).apply {
        addOval(RectF(0.32f, -0.30f, 0.44f, -0.04f), Path.Direction.CW)
        addOval(RectF(-0.44f, -0.30f, -0.32f, -0.04f), Path.Direction.CW)
        addOval(RectF(0.60f, -0.08f, 0.71f, 0.15f), Path.Direction.CW)
        addOval(RectF(-0.71f, -0.08f, -0.60f, 0.15f), Path.Direction.CW)
    }

    /** Regional turboprop: straight high wing, T-tail, two nacelles with prop discs. */
    private val TURBOPROP: Path = mirrored(
        0f, -1f,
        0.09f, -0.88f,
        0.10f, -0.32f,
        0.98f, -0.28f,
        0.98f, -0.10f,
        0.10f, -0.08f,
        0.06f, 0.72f,
        0.34f, 0.78f,
        0.34f, 0.92f,
        0f, 0.92f,
    ).apply {
        addRect(RectF(0.27f, -0.56f, 0.37f, -0.08f), Path.Direction.CW)
        addRect(RectF(-0.37f, -0.56f, -0.27f, -0.08f), Path.Direction.CW)
        addRect(RectF(0.14f, -0.62f, 0.50f, -0.58f), Path.Direction.CW)
        addRect(RectF(-0.50f, -0.62f, -0.14f, -0.58f), Path.Direction.CW)
    }

    /** Light single: short body, straight wing, small tail, nose prop. */
    private val LIGHT: Path = mirrored(
        0f, -0.86f,
        0.10f, -0.80f,
        0.11f, -0.40f,
        0.95f, -0.38f,
        0.95f, -0.18f,
        0.11f, -0.16f,
        0.05f, 0.62f,
        0.36f, 0.66f,
        0.36f, 0.82f,
        0f, 0.82f,
    ).apply {
        addRect(RectF(-0.26f, -1.0f, 0.26f, -0.94f), Path.Direction.CW)
        addRect(RectF(-0.03f, -0.96f, 0.03f, -0.84f), Path.Direction.CW)
    }

    /** Helicopter body: cabin, tail boom, tail fin with tail rotor. Main rotor is drawn separately. */
    private val HELICOPTER_BODY: Path = Path().apply {
        addOval(RectF(-0.26f, -0.70f, 0.26f, 0.12f), Path.Direction.CW)
        addRect(RectF(-0.05f, 0.0f, 0.05f, 0.92f), Path.Direction.CW)
        addRect(RectF(-0.24f, 0.78f, 0.24f, 0.86f), Path.Direction.CW)
        addRect(RectF(0.05f, 0.70f, 0.09f, 0.98f), Path.Direction.CW)
    }

    /** Main rotor hub position in unit space. */
    const val ROTOR_CX = 0f
    const val ROTOR_CY = -0.30f
    const val ROTOR_RADIUS = 0.95f
    /** About 2 revolutions per second looks lively without flicker at 15 fps. */
    const val ROTOR_DEG_PER_MS = 0.12f

    /**
     * Altitude colour ramp for trails, Skylight-style:
     * ground orange → yellow → green → cyan → high-altitude blue-violet.
     */
    private val STOPS_M = floatArrayOf(0f, 2_000f, 5_000f, 8_000f, 11_500f)
    private val STOP_COLORS = intArrayOf(
        Color.rgb(255, 150, 70),
        Color.rgb(245, 210, 90),
        Color.rgb(110, 220, 140),
        Color.rgb(76, 200, 240),
        Color.rgb(150, 140, 255),
    )
    private const val RAMP_SIZE = 64
    private const val RAMP_MAX_M = 12_000f
    private val RAMP = IntArray(RAMP_SIZE) { i -> computeColor(i * RAMP_MAX_M / (RAMP_SIZE - 1)) }

    /** Allocation-free lookup of the trail colour for an altitude in metres. */
    fun altitudeColor(altM: Float): Int {
        val i = ((altM / RAMP_MAX_M) * (RAMP_SIZE - 1)).toInt().coerceIn(0, RAMP_SIZE - 1)
        return RAMP[i]
    }

    private fun computeColor(alt: Float): Int {
        if (alt <= STOPS_M[0]) return STOP_COLORS[0]
        for (i in 1 until STOPS_M.size) {
            if (alt <= STOPS_M[i]) {
                val t = (alt - STOPS_M[i - 1]) / (STOPS_M[i] - STOPS_M[i - 1])
                return lerp(STOP_COLORS[i - 1], STOP_COLORS[i], t)
            }
        }
        return STOP_COLORS.last()
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /** Builds a closed, left-right symmetric outline from the right-half points (x >= 0), nose first. */
    private fun mirrored(vararg half: Float): Path {
        val path = Path()
        path.fillType = Path.FillType.WINDING
        path.moveTo(half[0], half[1])
        var i = 2
        while (i < half.size) {
            path.lineTo(half[i], half[i + 1]); i += 2
        }
        i = half.size - 4
        while (i >= 2) {
            path.lineTo(-half[i], half[i + 1]); i -= 2
        }
        path.close()
        return path
    }
}
