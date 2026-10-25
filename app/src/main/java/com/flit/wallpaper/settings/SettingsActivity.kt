package com.flit.wallpaper.settings

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.flit.wallpaper.R
import com.flit.wallpaper.flights.FlightRepository
import com.flit.wallpaper.wallpaper.FlitWallpaperService
import java.util.Locale

/** Minimal companion screen. Every change is saved immediately to local prefs. */
class SettingsActivity : Activity() {

    private lateinit var settings: FlitSettings
    private lateinit var preview: SkyPreviewView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var suppressCity = true

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = FlitSettings(this)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.apply).setOnClickListener { openWallpaperPicker() }
        setupMode()
        setupLocation()
        setupRadius()
        setupRefresh()
        setupAppearance()
        setupAccount()
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTicker)
        super.onPause()
    }

    private fun changed(locationOrMode: Boolean) {
        preview.reloadSettings(clearScene = locationOrMode)
        FlightRepository.settingsChanged(this, locationOrMode)
        updateStatus()
    }

    private fun updateStatus() {
        val (state, text) = FlightRepository.describeStatus(this)
        val dot = when (state) {
            FlightRepository.Status.LIVE -> "● "
            FlightRepository.Status.STALE -> "◐ "
            else -> "○ "
        }
        status.text = dot + text
    }

    private fun setupMode() {
        val group = findViewById<RadioGroup>(R.id.mode)
        group.check(
            when (settings.dataMode) {
                DataMode.LIVE -> R.id.mode_live
                DataMode.DEMO -> R.id.mode_demo
                DataMode.OFF -> R.id.mode_off
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            settings.dataMode = when (id) {
                R.id.mode_demo -> DataMode.DEMO
                R.id.mode_off -> DataMode.OFF
                else -> DataMode.LIVE
            }
            changed(locationOrMode = true)
        }
    }

    private fun setupLocation() {
        val spinner = findViewById<Spinner>(R.id.city)
        val lat = findViewById<EditText>(R.id.lat)
        val lon = findViewById<EditText>(R.id.lon)
        val names = FlitSettings.CITIES.map { it.name } + CUSTOM
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val idx = FlitSettings.CITIES.indexOfFirst { it.name == settings.placeName }
        spinner.setSelection(if (idx >= 0) idx else names.lastIndex, false)
        fun showCoords() {
            lat.setText(String.format(Locale.US, "%.4f", settings.latitude))
            lon.setText(String.format(Locale.US, "%.4f", settings.longitude))
        }
        showCoords()

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (suppressCity) {
                    suppressCity = false
                    return
                }
                val city = FlitSettings.CITIES.getOrNull(pos) ?: return
                settings.placeName = city.name
                settings.latitude = city.lat
                settings.longitude = city.lon
                showCoords()
                changed(locationOrMode = true)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.use_coords).setOnClickListener {
            val la = lat.text.toString().trim().toDoubleOrNull()
            val lo = lon.text.toString().trim().toDoubleOrNull()
            if (la == null || lo == null || la !in -85.0..85.0 || lo !in -180.0..180.0) {
                Toast.makeText(this, "Enter latitude −85…85 and longitude −180…180", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settings.placeName = CUSTOM
            settings.latitude = la
            settings.longitude = lo
            suppressCity = true
            spinner.setSelection(names.lastIndex, false)
            suppressCity = false
            changed(locationOrMode = true)
            Toast.makeText(this, "Location saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRadius() {
        val bar = findViewById<SeekBar>(R.id.radius)
        val label = findViewById<TextView>(R.id.radius_label)
        val step = 5
        val min = FlitSettings.MIN_RADIUS_KM
        bar.max = (FlitSettings.MAX_RADIUS_KM - min) / step
        bar.progress = (settings.radiusKm - min) / step
        label.text = "${settings.radiusKm} km around the centre"
        bar.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = "${min + p * step} km around the centre"
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                settings.radiusKm = min + s.progress * step
                changed(locationOrMode = false)
            }
        })
    }

    private fun setupRefresh() {
        val bar = findViewById<SeekBar>(R.id.refresh)
        val label = findViewById<TextView>(R.id.refresh_label)
        val step = 30
        val min = FlitSettings.MIN_REFRESH_SEC
        bar.max = (FlitSettings.MAX_REFRESH_SEC - min) / step
        bar.progress = (settings.refreshSec - min) / step
        label.text = describeInterval(settings.refreshSec)
        bar.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = describeInterval(min + p * step)
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                settings.refreshSec = min + s.progress * step
                changed(locationOrMode = false)
            }
        })
    }

    private fun describeInterval(sec: Int): String =
        if (sec < 60 || sec % 60 != 0) "Every $sec seconds" else if (sec == 60) "Every minute" else "Every ${sec / 60} minutes"

    private fun setupAppearance() {
        val group = findViewById<RadioGroup>(R.id.appearance)
        group.check(if (settings.appearance == Appearance.DARK_SKY) R.id.look_sky else R.id.look_black)
        group.setOnCheckedChangeListener { _, id ->
            settings.appearance = if (id == R.id.look_black) Appearance.NEAR_BLACK else Appearance.DARK_SKY
            preview.reloadSettings(clearScene = false)
        }
        val labels = findViewById<Switch>(R.id.labels)
        labels.isChecked = settings.showLabels
        labels.setOnCheckedChangeListener { _, checked ->
            settings.showLabels = checked
            preview.reloadSettings(clearScene = false)
        }
    }

    private fun setupAccount() {
        val id = findViewById<EditText>(R.id.client_id)
        val secret = findViewById<EditText>(R.id.client_secret)
        id.setText(settings.clientId)
        secret.setText(settings.clientSecret)
        findViewById<Button>(R.id.save_account).setOnClickListener {
            settings.clientId = id.text.toString()
            settings.clientSecret = secret.text.toString()
            changed(locationOrMode = false)
            val msg = if (settings.clientId.isBlank()) "Using OpenSky anonymously" else "OpenSky account saved"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWallpaperPicker() {
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(this, FlitWallpaperService::class.java),
        )
        try {
            startActivity(direct)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(
                    this,
                    "Open Settings › Wallpaper › Live wallpapers and choose Flit",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
    }

    private companion object {
        const val CUSTOM = "Custom coordinates"
    }
}
