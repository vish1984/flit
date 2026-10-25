# Flit

A minimalist Android live wallpaper that shows real aircraft overhead as tiny silhouettes on a quiet night sky.

- Native `WallpaperService` in Kotlin, no third-party runtime libraries
- Live data from the [OpenSky Network](https://opensky-network.org), plus a clearly labelled Demo mode and an Off (static sky) mode
- Android 8.0+ (minSdk 26), target SDK 35
- Permissions: `INTERNET` and `ACCESS_NETWORK_STATE` only. No location, sensors, microphone or Bluetooth.

## Install

1. On the phone, download `flit-debug.apk` from the [latest release](https://github.com/vish1984/flit/releases/tag/latest), or copy it from `C:\Users\viswa\Projects\Flit\flit-debug.apk` over USB.
2. Open it and allow "Install unknown apps" for the browser or file manager when Android asks.
3. Open **Flit** from the app drawer, pick your city or enter coordinates, then tap **Set as wallpaper** → **Set wallpaper** (choose Home screen or Home and lock screen).

If the button doesn't open the picker: long-press the home screen → Wallpaper & style → Live wallpapers (wording varies by maker) → Flit.

## How it works

| Package | Role |
|---|---|
| `wallpaper/` | `FlitWallpaperService` (engine lifecycle), `SkyRenderer` (drawing) |
| `flights/` | `Aircraft` model, `FlightDataProvider` interface, `MockFlightProvider`, `AircraftTrack` (smoothing), `FlightScene`, `FlightRepository` (scheduling) |
| `network/` | `OpenSkyProvider`, `OpenSkyParser`, `RefreshPolicy` (interval, backoff, daily budget) |
| `projection/` | `LocalProjection`: local equirectangular projection around the chosen centre |
| `settings/` | `FlitSettings`, `SettingsActivity`, `SkyPreviewView` |
| `cache/` | `FlightCache`: last snapshot on disk |
| `app/src/test/` | 18 JVM unit tests |

**Rendering.** The sky and stars are drawn once into a bitmap, so each frame is one blit plus a few small paths, with no per-frame allocations. Frames run at about 15 fps only while the wallpaper is visible and something is moving (8 fps in Battery Saver). An empty or static sky is drawn once and then the engine sits idle.

**Motion.** Each aircraft is dead-reckoned along its reported track and speed between updates. When a new position arrives, the difference from the drawn position blends away over about 2.5 s, so planes glide rather than jump. Duplicates are merged by ICAO address. Data older than 2 × interval + 60 s fades out over 30 s, then is removed.

**Network.** All fetching lives in `FlightRepository`, which runs on one low-priority thread, never on the render path. It runs only while at least one wallpaper engine or the preview is visible. Screen-off or covered means no timer and no requests. It skips requests when offline, backs off exponentially on errors (capped at 30 min), and honours OpenSky's `X-Rate-Limit-Retry-After-Seconds`. It keeps a local per-UTC-day credit budget below OpenSky's allowance. Battery Saver doubles the interval. Nothing runs in the background when the wallpaper isn't showing.

### Trade-offs

- **Shorter interval:** fewer and smaller corrections as planes change course, but more data, radio wake-ups and OpenSky credits. Each request is small, typically a few KB for a 60 km radius.
- **Longer interval:** cheaper, but turning aircraft drift further before being corrected, and planes entering the area appear later.
- **Frame rate:** at a 60 km radius a cruising jet moves only a few pixels per second, so 15 fps looks smooth. Higher rates would mostly spend battery.
- I have not measured battery drain on a device, so I make no percentage claim. Use Settings → Battery to check after a day.

## OpenSky usage

- Anonymous use works with no setup. OpenSky's published anonymous allowance is about 400 credits per day. Small boxes like Flit's cost 1 credit per request. At the default 2-minute interval, Flit uses at most about 30 requests per hour of on-screen time.
- For more headroom, create an API client in your OpenSky account and enter its client id and secret under *OpenSky account* in the app. They are stored only in the app's private storage on the phone (backup disabled) and never in the source or APK.
- Data is © The OpenSky Network and attributed in the app. Check OpenSky's terms of use before any commercial use. Positions can be delayed or incomplete and are not for navigation.

## Rebuild

- **GitHub:** every push to `main` runs `.github/workflows/build.yml`. It runs unit tests, assembles the debug APK, publishes it to the `latest` release, and pushes the APK and `build-log.txt` to the `build-output` branch.
- **Locally (Windows):** install Android Studio (it includes the SDK and a JDK), open the `Flit` folder, let it sync, then run from the project folder:
  ```
  gradlew.bat testDebugUnitTest assembleDebug
  ```
  The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Troubleshooting

- **"App not installed":** uninstall any older Flit first; debug builds signed with a different key can't upgrade each other. Make sure the phone runs Android 8.0 or later.
- **Flit missing from the live wallpaper list:** some launchers hide live wallpapers. Use the app's **Set as wallpaper** button, or Settings → Wallpaper.
- **Status says Offline:** check the phone's internet connection, and make sure Data Saver isn't restricting Flit. Updates happen only while the wallpaper is visible, so look at the home screen for a minute.
- **Status says "rate limit" or "daily allowance used":** Flit pauses on its own and resumes automatically. Raise the interval, or add an OpenSky API client.
- **"OpenSky sign-in failed":** recheck the client id and secret, or clear both fields to go back to anonymous.
- **No planes but status is Live:** there may genuinely be none in range. Increase the radius or try Demo mode to confirm rendering works.
