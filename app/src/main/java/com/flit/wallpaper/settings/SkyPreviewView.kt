package com.flit.wallpaper.settings

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.flit.wallpaper.flights.Aircraft
import com.flit.wallpaper.flights.FlightRepository
import com.flit.wallpaper.flights.FlightScene
import com.flit.wallpaper.flights.RouteRepository
import com.flit.wallpaper.wallpaper.SkyRenderer

/** In-app preview that uses exactly the same renderer and data as the wallpaper. */
class SkyPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), FlightRepository.Listener, RouteRepository.Listener {

    private val settings = FlitSettings(context)
    private val renderer = SkyRenderer(context)
    private val scene = FlightScene()
    private var attachedToRepo = false

    /** Called whenever the set of aircraft or their routes change. */
    var onSceneChanged: ((FlightScene) -> Unit)? = null

    val currentScene: FlightScene get() = scene

    init {
        reloadSettings(clearScene = false)
    }

    fun reloadSettings(clearScene: Boolean) {
        renderer.applySettings(settings)
        if (clearScene) scene.clear()
        scene.configure(settings.projection(), settings.refreshSec)
        onSceneChanged?.invoke(scene)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        renderer.setSize(w, h)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) connect() else disconnect()
    }

    override fun onDetachedFromWindow() {
        disconnect()
        renderer.release()
        super.onDetachedFromWindow()
    }

    private fun connect() {
        if (attachedToRepo) return
        attachedToRepo = true
        RouteRepository.addListener(context, this)
        FlightRepository.attach(context, this)
        invalidate()
    }

    private fun disconnect() {
        if (!attachedToRepo) return
        attachedToRepo = false
        FlightRepository.detach(this)
        RouteRepository.removeListener(this)
    }

    override fun onFlightsUpdated(aircraft: List<Aircraft>, simulated: Boolean) {
        scene.apply(aircraft, System.currentTimeMillis())
        onSceneChanged?.invoke(scene)
        invalidate()
    }

    override fun onRoutesUpdated() {
        onSceneChanged?.invoke(scene)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            if (renderer.handleTap(event.x, event.y, System.currentTimeMillis())) invalidate()
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        scene.prune(now)
        val moving = renderer.draw(canvas, scene, now)
        if (attachedToRepo && !scene.isEmpty) {
            postInvalidateDelayed(if (moving) 66L else 1000L)
        }
    }
}
