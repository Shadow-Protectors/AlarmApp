# WayPoint

[![Organization](https://img.shields.io/badge/Organization-Shadow--Protectors-1E88E5.svg)](https://github.com/Shadow-Protectors)
[![Platform](https://img.shields.io/badge/Platform-Android%20%28Kotlin%29-3DDC84.svg)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35%20%28Android%2015%29-brightgreen.svg)]()
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24%20%28Android%207.0%29-orange.svg)]()
[![License](https://img.shields.io/badge/License-MIT-blue.svg)]()

**WayPoint** is an intelligent, location-aware travel alarm and proximity alert system developed by **Shadow-Protectors**. Engineered for public transit commuters, train passengers, and long-distance travelers, WayPoint ensures you never miss your destination stop, railway junction, or interchange due to sleep or distraction.

Unlike standard clock-based alarms that fail when buses or trains face delays, WayPoint continuously tracks your real-time position, trajectory, and estimated time of arrival (ETA) to trigger staged alerts as you approach your target.

---

## Key Features

### 1. 4-Stage Tiered Alert Progression
WayPoint replaces abrupt, single-point alarms with a gradual 4-level progressive alerting system:

| Level | Alert Type | Bus/Car Threshold | Train Threshold | Action & Feedback |
| :--- | :--- | :--- | :--- | :--- |
| **Level 1** | **Silent Heads-Up** | 5.0 km out | 15 min ETA | Updates persistent notification banner with real-time distance and ETA. |
| **Level 2** | **Vibration Pulse** | 2.0 km out | 7 min ETA | Dual vibration pulses to gently prepare you to collect your luggage. |
| **Level 3** | **Voice Alert (TTS)** | 1.0 km out | 3 min ETA | Spoken voice announcement in your selected language. |
| **Level 4** | **Critical Emergency Alarm** | 0.5 km out | 1 min ETA | Full-screen wake-up screen, ringtone override at 100% volume, and continuous vibration. |

### 2. Multi-Modal Travel Engines
- **Bus / Car Mode**: Optimized for road transit with distance-based thresholds, 5-second GPS polling, and an 85-degree bearing tolerance.
- **Train Express Mode**: Designed for high-speed rail and variable velocities. Evaluates real-time ETA thresholds, operates on a 3-second GPS polling cycle, and applies a tighter 60-degree directional cone.

### 3. Direction & Trajectory Filtering (Anti-False Alarm)
- **Sliding-Window Vector Tracking**: Analyzes recent coordinate history using WGS84 geodesic distance and bearing calculations.
- **Approach vs. Receding Detection**: Distinguishes between approaching your destination, departing on an opposite track, or passing on a detour loop.
- **Receding Warning**: Alerts travelers if they are moving away from their intended destination.
- **Tunnel & GPS Gap Recovery**: Automatically handles signal dropouts (such as railway tunnels or underground stations) by resetting motion windows upon re-acquisition to prevent false triggers.

### 4. Multilingual Voice Alerts (TTS)
WayPoint speaks proximity announcements and arrival warnings in multiple languages:
- **English**
- **Tamil (தமிழ்)**
- **Hindi (हिन्दी)**

### 5. Failsafe Audio and Silent Mode Override
- **Phone Ringtone Integration**: Uses the device phone ringtone (`RingtoneManager.TYPE_RINGTONE`) as the primary alert tone.
- **System Volume Maximization**: Automatically ramps `STREAM_RING`, `STREAM_ALARM`, `STREAM_MUSIC`, and `STREAM_NOTIFICATION` to 100% full volume.
- **Synthetic AudioTrack Siren**: If ringtone streams are suppressed by system policies or Do Not Disturb, a synthetic dual-frequency (880 Hz / 1760 Hz) PCM AudioTrack siren activates to ensure the alarm is heard.

### 6. Seamless Destination Input & Share Sheet Support
- **Interactive OSM Map Picker**: Embedded OpenStreetMap (`osmdroid`) bottom sheet with live pin dropping and radius visualization.
- **Hybrid Geocoding**: Android native Geocoder with local proximity bounding, backed by Photon/Nominatim OSM search for pinpoint transit landmarks and bus stops.
- **Android Share Sheet Integration**: Share locations directly to WayPoint from Google Maps, WhatsApp messages, short links (`maps.app.goo.gl`, `goo.gl/maps`), or `geo:` URIs.
- **Offline SQLite Storage**: Save favorite destinations and presets for instant one-tap tracking.

---

## Architecture Overview

```
app/src/main/java/com/shadowprotectors/alarmapp/
├── MainActivity.kt                # Primary dashboard, map sheet controller, permission flow
├── alert/
│   ├── AlertManager.kt           # State machine evaluating distance/ETA progression
│   ├── AudioAlarmHelper.kt       # Audio focus, ringtone loops, and emergency AudioTrack siren
│   └── VoiceAlertHelper.kt       # Text-To-Speech engine (English, Tamil, Hindi)
├── data/
│   ├── DatabaseHelper.kt         # SQLite storage for saved and preset destinations
│   └── Destination.kt            # Destination entity model
├── engine/
│   ├── DirectionFilter.kt        # Vector trajectory and approach/receding classification
│   ├── DistanceEngine.kt         # WGS84 distance, bearing calculation, and dynamic ETA estimation
│   ├── TripMode.kt               # Bus/Car and Train mode profiles and alert thresholds
│   └── BusUiState.kt             # UI state representation
├── service/
│   ├── LocationTrackingService.kt# Foreground GPS tracking service with WakeLock support
│   └── ServiceEventBus.kt        # Reactive Kotlin SharedFlow event communication
├── ui/
│   ├── AlarmTriggerActivity.kt   # Full-screen lock screen wake-up interface
│   ├── MapPickerBottomSheet.kt   # Interactive OsmDroid map sheet with search and pin drop
│   └── SearchResultAdapter.kt    # Geocoding search results list adapter
└── util/
    ├── GeocodingHelper.kt        # Native geocoder with Photon OSM fallback
    ├── LocationLinkParser.kt     # Deep link parser for Google Maps, geo URIs, and WhatsApp text
    └── NotificationHelper.kt     # Notification channels, foreground notification builder
```

---

## Getting Started

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK 35 (Android 15)
- Physical Android device with GPS capabilities (recommended for live testing)

### Clone & Build

```bash
# Clone the repository
git clone https://github.com/Shadow-Protectors/WayPoint.git

# Navigate to project directory
cd WayPoint

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

---

## Permissions & Privacy

WayPoint requires the following permissions to ensure continuous operation while traveling:

| Permission | Purpose |
| :--- | :--- |
| `ACCESS_FINE_LOCATION` | Accurate GPS coordinates for distance and bearing calculations |
| `ACCESS_BACKGROUND_LOCATION` | Uninterrupted tracking when the phone is locked or app is in background |
| `FOREGROUND_SERVICE_LOCATION` | Persistent foreground execution with notification status |
| `WAKE_LOCK` | Keeps the CPU active during critical alert evaluation |
| `USE_FULL_SCREEN_INTENT` | Displays the emergency wake-up alarm over the lock screen |
| `MODIFY_AUDIO_SETTINGS` | Maximizes volume and ensures alarms sound in silent environments |
| `POST_NOTIFICATIONS` | Displays continuous tracking updates on Android 13+ |

All location processing runs entirely on-device. WayPoint does not transmit your personal location data or route history to any external server.

---

## Organization & Authors

Developed and maintained by **[Shadow-Protectors](https://github.com/Shadow-Protectors)**.

Contributions, issues, and feature requests are welcome. Feel free to check the [Issues](https://github.com/Shadow-Protectors/WayPoint/issues) page.

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.
