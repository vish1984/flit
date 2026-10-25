package com.flit.wallpaper.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.flit.wallpaper.flights.AircraftTrack
import com.flit.wallpaper.flights.FlightScene
import com.flit.wallpaper.flights.RouteRepository
import com.flit.wallpaper.settings.Appearance
import com.flit.wallpaper.settings.FlitSettings
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the sky and the aircraft onto any Canvas (wallpaper surface or a View).
 *
 * The background, including stars, is rendered once into a bitmap per size or
 * appearance change; each frame is one bitmap blit plus a handful of small
 * paths. No objects are allocated per frame.
 */
class SkyRenderer(context: Context) {

    private val density = context.resources.displayMetrics.density

    private var width = 0
    private var height = 0
    private var appearance = Appearance.DARK_SKY
    private var radiusKm = FlitSettings.DEFAULT_RADIUS_KM
    private var showLabels = false
    private var background: Bitmap? = null

    /** Home-screen page offset 0..1, for a gentle star parallax. */
    var xOffset = 0.5f
    private val parallaxPx = 24f * density

    private val planePath = buildPlanePath()
    private val planeSize = 7.5f * density

    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(232, 236, 245)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(200, 210, 235)
    }
    private val homePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 159, 184, 232)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 210, 235)
        textSize = 9.5f * density
        letterSpacing = 0.05f
    }
    private val labelDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 165, 200)
        textSize = 8.5f * density
        letterSpacing = 0.04f
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 14, 20, 36)
    }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.argb(90, 159, 184, 232)
    }
    private val cardTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 236, 245)
        textSize = 15f * density
        isFakeBoldText = true
    }
    private val cardTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 210, 235)
        textSize = 13f * density
    }
    private val cardDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(139, 147, 167)
        textSize = 12f * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.rgb(159, 184, 232)
    }

    // Screen positions from the last frame, for tap hit-testing.
    private var hitX = FloatArray(64)
    private var hitY = FloatArray(64)
    private val hitTracks = ArrayList<AircraftTrack>(64)

    // Label rectangles drawn this frame, to avoid overlapping labels.
    private var labelRects = FloatArray(4 * 64)
    private var labelCount = 0

    private var selectedId: String? = null
    private var selectedAtMs = 0L
    private val cardRect = RectF()
    private val useImperial = Locale.getDefault().country in setOf("US", "LR", "MM")

    private val pos = DoubleArray(2)

    val backgroundColor: Int
        get() = if (appearance == Appearance.DARK_SKY) SKY_BOTTOM else NEAR_BLACK

    fun setSize(w: Int, h: Int) {
        if (w == width && h == height && background != null) return
        width = w
        height = h
        rebuildBackground()
    }

    fun applySettings(s: FlitSettings) {
        val newAppearance = s.appearance
        radiusKm = s.radiusKm
        showLabels = s.showLabels
        if (newAppearance != appearance) {
            appearance = newAppearance
            rebuildBackground()
        }
    }

    fun release() {
        background?.recycle()
        background = null
    }

    /** Draws one frame. Returns true if anything on screen is moving. */
    fun draw(canvas: Canvas, scene: FlightScene, nowMs: Long): Boolean {
        val bg = background
        if (bg == null) {
            canvas.drawColor(backgroundColor)
        } else {
            canvas.drawBitmap(bg, -xOffset * parallaxPx, 0f, null)
        }
        if (width == 0 || height == 0) return false

        val cx = width / 2f
        val cy = height / 2f
        val pxPerKm = (min(width, height) / 2f) / radiusKm

        canvas.drawCircle(cx, cy, 1.4f * density, homePaint)

        var moving = false
        val maxExtrap = scene.staleAfterMs
        val margin = planeSize * 3
        hitTracks.clear()
        labelCount = 0
        var selX = Float.NaN
        var selY = Float.NaN
        var selTrack: AircraftTrack? = null
        val selId = if (isCardVisible(nowMs)) selectedId else null
        for (t in scene.tracks) {
            val alpha = scene.alphaFor(t, nowMs)
            if (alpha <= 0f) continue
            t.position(nowMs, maxExtrap, pos)
            val x = cx + (pos[0] * pxPerKm).toFloat()
            val y = cy - (pos[1] * pxPerKm).toFloat()
            if (x < -margin || x > width + margin || y < -margin || y > height + margin) continue
            if (t.hasMotion) moving = true

            val obs = t.observation ?: continue
            recordHit(t, x, y)
            if (t.id == selId) {
                selX = x; selY = y; selTrack = t
            }
            // Higher aircraft read slightly smaller and softer, like distance.
            val alt = obs.altitudeM ?: 8000.0
            val altNorm = (alt / 12000.0).coerceIn(0.0, 1.0).toFloat()
            val scale = planeSize * (1.15f - 0.3f * altNorm)
            val baseAlpha = 0.95f - 0.3f * altNorm
            val a = (255 * baseAlpha * alpha).toInt()

            val track = t.trackDeg
            if (track != null) {
                val speed = obs.speedMs ?: 0.0
                if (speed > 30) {
                    val len = (6f + (speed / 250.0).toFloat().coerceAtMost(1.4f) * 12f) * density
                    val rad = Math.toRadians(track)
                    val dx = (sin(rad) * len).toFloat()
                    val dy = (-cos(rad) * len).toFloat()
                    trailPaint.alpha = (a * 0.16f).toInt()
                    canvas.drawLine(x - dx * 0.35f, y - dy * 0.35f, x - dx * 1.6f, y - dy * 1.6f, trailPaint)
                }
            }

            planePaint.alpha = a
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate((track ?: 0.0).toFloat())
            canvas.scale(scale, scale)
            canvas.drawPath(planePath, planePaint)
            canvas.restore()

            if (showLabels && t.id != selId) drawLabel(canvas, t, x, y, alpha)
        }
        val st = selTrack
        if (st != null) {
            drawCard(canvas, st, selX, selY, nowMs)
        }
        return moving || selId != null
    }

    private fun recordHit(t: AircraftTrack, x: Float, y: Float) {
        val i = hitTracks.size
        if (i >= hitX.size) {
            hitX = hitX.copyOf(i * 2)
            hitY = hitY.copyOf(i * 2)
        }
        hitX[i] = x
        hitY[i] = y
        hitTracks.add(t)
    }

    private fun drawLabel(canvas: Canvas, t: AircraftTrack, x: Float, y: Float, alpha: Float) {
        val obs = t.observation ?: return
        val route = RouteRepository.routeFor(obs.callsign)
        val line1 = route?.flightNumber ?: obs.callsign ?: return
        val line2 = route?.shortRoute()
        val lx = x + planeSize * 1.5f
        val top = y - labelPaint.textSize * 0.9f
        val w = maxOf(labelPaint.measureText(line1), line2?.let { labelDimPaint.measureText(it) } ?: 0f)
        val h = labelPaint.textSize * (if (line2 != null) 2.3f else 1.2f)
        if (overlapsLabel(lx, top, lx + w, top + h)) return
        addLabelRect(lx, top, lx + w, top + h)
        labelPaint.alpha = (190 * alpha).toInt()
        canvas.drawText(line1, lx, y + labelPaint.textSize * 0.1f, labelPaint)
        if (line2 != null) {
            labelDimPaint.alpha = (160 * alpha).toInt()
            canvas.drawText(line2, lx, y + labelPaint.textSize * 1.25f, labelDimPaint)
        }
    }

    private fun overlapsLabel(l: Float, t: Float, r: Float, b: Float): Boolean {
        for (i in 0 until labelCount) {
            val o = i * 4
            if (l < labelRects[o + 2] && r > labelRects[o] && t < labelRects[o + 3] && b > labelRects[o + 1]) return true
        }
        return false
    }

    private fun addLabelRect(l: Float, t: Float, r: Float, b: Float) {
        if ((labelCount + 1) * 4 > labelRects.size) labelRects = labelRects.copyOf(labelRects.size * 2)
        val o = labelCount * 4
        labelRects[o] = l; labelRects[o + 1] = t; labelRects[o + 2] = r; labelRects[o + 3] = b
        labelCount++
    }

    // ---- Tap to see details ----

    /** Selects the aircraft nearest a tap. Returns true if one was hit. */
    fun handleTap(x: Float, y: Float, nowMs: Long): Boolean {
        var best = -1
        var bestD = TAP_RADIUS_DP * density
        for (i in 0 until hitTracks.size) {
            val d = hypot(hitX[i] - x, hitY[i] - y)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        if (best < 0) {
            // Tapping empty sky dismisses the card.
            val had = isCardVisible(nowMs)
            selectedId = null
            return had
        }
        selectedId = hitTracks[best].id
        selectedAtMs = nowMs
        return true
    }

    fun isCardVisible(nowMs: Long): Boolean =
        selectedId != null && nowMs - selectedAtMs < CARD_SHOW_MS + CARD_FADE_MS

    private fun drawCard(canvas: Canvas, t: AircraftTrack, x: Float, y: Float, nowMs: Long) {
        val obs = t.observation ?: return
        val age = nowMs - selectedAtMs
        val fade = when {
            age < CARD_FADE_MS -> age.toFloat() / CARD_FADE_MS
            age > CARD_SHOW_MS -> 1f - (age - CARD_SHOW_MS).toFloat() / CARD_FADE_MS
            else -> 1f
        }.coerceIn(0f, 1f)

        val route = RouteRepository.routeFor(obs.callsign)
        val title = buildString {
            append(route?.flightNumber ?: obs.callsign ?: obs.id.uppercase())
            route?.airlineName?.let { append("  ·  ").append(it) }
        }
        val from = route?.origin?.display()
        val to = route?.destination?.display()
        val line2 = when {
            from != null && to != null -> "$from → $to"
            to != null -> "To $to"
            from != null -> "From $from"
            obs.callsign == null -> "No flight number broadcast"
            else -> "Route not available"
        }
        val line3 = buildString {
            obs.altitudeM?.let { append(formatAltitude(it)) }
            obs.speedMs?.let { if (isNotEmpty()) append("  ·  "); append(formatSpeed(it)) }
            if (isEmpty()) append("Aircraft ${obs.id.uppercase()}")
        }

        val pad = 12f * density
        val gap = 6f * density
        val w = maxOf(
            cardTitlePaint.measureText(title),
            cardTextPaint.measureText(line2),
            cardDimPaint.measureText(line3),
        ) + pad * 2
        val h = pad * 2 + cardTitlePaint.textSize + cardTextPaint.textSize + cardDimPaint.textSize + gap * 2
        val maxW = width - 2 * pad
        val cw = min(w, maxW)
        val left = (x - cw / 2).coerceIn(pad, width - pad - cw)
        var top = y - h - 18f * density
        if (top < pad * 3) top = y + 18f * density
        if (top + h > height - pad) top = height - pad - h
        cardRect.set(left, top, left + cw, top + h)

        val a = (255 * fade).toInt()
        ringPaint.alpha = (200 * fade).toInt()
        canvas.drawCircle(x, y, planeSize * 2.2f, ringPaint)
        cardPaint.alpha = (225 * fade).toInt()
        cardStrokePaint.alpha = (90 * fade).toInt()
        val r = 12f * density
        canvas.drawRoundRect(cardRect, r, r, cardPaint)
        canvas.drawRoundRect(cardRect, r, r, cardStrokePaint)

        canvas.save()
        canvas.clipRect(cardRect)
        var baseline = top + pad + cardTitlePaint.textSize * 0.85f
        cardTitlePaint.alpha = a
        canvas.drawText(title, left + pad, baseline, cardTitlePaint)
        baseline += gap + cardTextPaint.textSize
        cardTextPaint.alpha = a
        canvas.drawText(line2, left + pad, baseline, cardTextPaint)
        baseline += gap + cardDimPaint.textSize
        cardDimPaint.alpha = a
        canvas.drawText(line3, left + pad, baseline, cardDimPaint)
        canvas.restore()
    }

    private fun formatAltitude(m: Double): String =
        if (useImperial) String.format(Locale.US, "%,d ft", (m * 3.28084 / 100).roundToInt() * 100)
        else String.format(Locale.US, "%,d m", (m / 10).roundToInt() * 10)

    private fun formatSpeed(ms: Double): String =
        if (useImperial) "${(ms * 2.23694).roundToInt()} mph" else "${(ms * 3.6).roundToInt()} km/h"

    private fun rebuildBackground() {
        if (width <= 0 || height <= 0) return
        val w = width + parallaxPx.toInt()
        val bmp = background?.takeIf { it.width == w && it.height == height && it.isMutable }
            ?: Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888).also { background?.recycle() }
        background = bmp
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        if (appearance == Appearance.DARK_SKY) {
            p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), SKY_TOP, SKY_BOTTOM, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), height.toFloat(), p)
            // Faint glow at the horizon.
            p.shader = RadialGradient(
                w / 2f, height * 1.05f, height * 0.6f,
                Color.argb(38, 60, 90, 150), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w.toFloat(), height.toFloat(), p)
        } else {
            c.drawColor(NEAR_BLACK)
        }
        p.shader = null

        // Deterministic stars so the sky doesn't reshuffle on every rebuild.
        val rnd = Random(0x5F117)
        val count = ((w.toLong() * height) / (9000 * density * density)).toInt().coerceIn(40, 260)
        val starBoost = if (appearance == Appearance.DARK_SKY) 1f else 0.7f
        for (i in 0 until count) {
            val sx = rnd.nextFloat() * w
            val sy = rnd.nextFloat() * height
            val r = (0.35f + rnd.nextFloat() * rnd.nextFloat() * 0.9f) * density
            val alpha = ((25 + rnd.nextInt(110)) * starBoost).toInt()
            p.color = Color.argb(alpha, 210, 220, 255)
            c.drawCircle(sx, sy, r, p)
        }
    }

    private fun buildPlanePath(): Path {
        // Unit aircraft pointing up (north), roughly 2 units nose to tail.
        val half = floatArrayOf(
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
        val path = Path()
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

    companion object {
        const val TAP_RADIUS_DP = 40f
        const val CARD_SHOW_MS = 7_000L
        const val CARD_FADE_MS = 400L
        val SKY_TOP = Color.rgb(4, 7, 16)
        val SKY_BOTTOM = Color.rgb(13, 22, 44)
        val NEAR_BLACK = Color.rgb(5, 5, 7)
    }
}
