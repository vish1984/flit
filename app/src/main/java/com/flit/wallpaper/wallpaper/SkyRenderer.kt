package com.flit.wallpaper.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.flit.wallpaper.flights.FlightScene
import com.flit.wallpaper.settings.Appearance
import com.flit.wallpaper.settings.FlitSettings
import kotlin.math.cos
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
        color = Color.argb(120, 200, 210, 235)
        textSize = 9f * density
        letterSpacing = 0.06f
    }

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
        for (t in scene.tracks) {
            val alpha = scene.alphaFor(t, nowMs)
            if (alpha <= 0f) continue
            t.position(nowMs, maxExtrap, pos)
            val x = cx + (pos[0] * pxPerKm).toFloat()
            val y = cy - (pos[1] * pxPerKm).toFloat()
            if (x < -margin || x > width + margin || y < -margin || y > height + margin) continue
            if (t.hasMotion) moving = true

            val obs = t.observation ?: continue
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

            if (showLabels) {
                val cs = obs.callsign
                if (cs != null) {
                    labelPaint.alpha = (120 * alpha).toInt()
                    canvas.drawText(cs, x + planeSize * 1.4f, y + planeSize * 0.4f, labelPaint)
                }
            }
        }
        return moving
    }

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
        val SKY_TOP = Color.rgb(4, 7, 16)
        val SKY_BOTTOM = Color.rgb(13, 22, 44)
        val NEAR_BLACK = Color.rgb(5, 5, 7)
    }
}
