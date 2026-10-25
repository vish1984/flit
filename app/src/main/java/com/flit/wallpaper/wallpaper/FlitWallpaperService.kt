package com.flit.wallpaper.wallpaper

import android.app.WallpaperColors
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.flit.wallpaper.flights.Aircraft
import com.flit.wallpaper.flights.FlightRepository
import com.flit.wallpaper.flights.FlightScene
import com.flit.wallpaper.settings.DataMode
import com.flit.wallpaper.settings.FlitSettings

/**
 * Native live wallpaper.
 *
 * Lifecycle rules:
 *  - Frames are drawn only while visible AND something is moving. A still sky
 *    is drawn once and then the engine is idle.
 *  - When invisible (another app in front, screen off) the frame loop stops
 *    and the engine detaches from [FlightRepository], which stops fetching.
 *  - Every surface access is guarded; a failed lock simply skips the frame.
 */
class FlitWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = FlitEngine()

    inner class FlitEngine : Engine(),
        FlightRepository.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private val settings = FlitSettings(this@FlitWallpaperService)
        private val renderer = SkyRenderer(this@FlitWallpaperService)
        private val scene = FlightScene()

        private var visible = false
        private var surfaceReady = false
        private var loopScheduled = false
        private var lastMode = settings.dataMode
        private var lastCentre = settings.latitude to settings.longitude

        private val frame = Runnable {
            loopScheduled = false
            drawFrame()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(true)
            renderer.applySettings(settings)
            scene.configure(settings.projection(), settings.refreshSec)
            settings.prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            stopFrames()
            FlightRepository.detach(this)
            settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
            renderer.release()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            renderer.setSize(width, height)
            drawFrame()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            stopFrames()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                FlightRepository.attach(this@FlitWallpaperService, this)
                drawFrame()
            } else {
                stopFrames()
                FlightRepository.detach(this)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int,
        ) {
            renderer.xOffset = xOffset
            if (!loopScheduled) drawFrame()
        }

        override fun onComputeColors(): WallpaperColors? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
            val bg = Color.valueOf(renderer.backgroundColor)
            // Dark wallpaper: no "supports dark text" hint, so launchers use light text.
            return WallpaperColors(bg, Color.valueOf(Color.rgb(159, 184, 232)), null)
        }

        override fun onFlightsUpdated(aircraft: List<Aircraft>, simulated: Boolean) {
            scene.apply(aircraft, System.currentTimeMillis())
            drawFrame()
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            renderer.applySettings(settings)
            val mode = settings.dataMode
            val centre = settings.latitude to settings.longitude
            if (mode != lastMode || centre != lastCentre || mode == DataMode.OFF) {
                scene.clear()
            }
            lastMode = mode
            lastCentre = centre
            scene.configure(settings.projection(), settings.refreshSec)
            notifyColorsChangedSafely()
            if (visible) drawFrame()
        }

        private fun notifyColorsChangedSafely() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    notifyColorsChanged()
                } catch (_: Exception) {
                }
            }
        }

        private fun drawFrame() {
            if (!surfaceReady) return
            val holder = surfaceHolder ?: return
            val now = System.currentTimeMillis()
            scene.prune(now)
            var canvas: Canvas? = null
            var moving = false
            try {
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    holder.lockHardwareCanvas()
                } else {
                    holder.lockCanvas()
                }
                if (canvas != null) moving = renderer.draw(canvas, scene, now)
            } catch (_: Exception) {
                // Surface may be going away; skip this frame.
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) {
                    }
                }
            }
            // Keep animating only while visible and while something moves or is fading.
            if (visible && (moving || !scene.isEmpty) && !loopScheduled) {
                loopScheduled = true
                handler.postDelayed(frame, frameDelayMs(moving))
            }
        }

        private fun frameDelayMs(moving: Boolean): Long {
            if (!moving) return IDLE_FRAME_MS
            return if (FlightRepository.isPowerSave(this@FlitWallpaperService)) SAVER_FRAME_MS else FRAME_MS
        }

        private fun stopFrames() {
            handler.removeCallbacks(frame)
            loopScheduled = false
        }
    }

    companion object {
        /** ~15 fps: aircraft move a few pixels per second, so this is visually smooth. */
        const val FRAME_MS = 66L
        const val SAVER_FRAME_MS = 125L
        /** Stationary but present aircraft only need occasional redraws for fading. */
        const val IDLE_FRAME_MS = 1_000L
    }
}
