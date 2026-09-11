# Waypoint - Version Log & Release Notes

## Version 1.1.0 (Build 2) - 2026-09-10

### 🎨 Visual & Branding Updates
- **New App Logo & Icon**: Integrated the official **Waypoint** logo (`Waypoint_Logo.png`) across all Android density buckets (`mipmap-mdpi`, `mipmap-hdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, `mipmap-xxxhdpi`), including round adaptive launcher variants and header bar branding.
- **Updated App Name**: Application name set to **Waypoint** in `strings.xml`.

### 🔊 Audio & Alarm Enhancements
- **Phone Ringtone Integration**: Modified critical alarm trigger to use the device's default phone call ringtone (`RingtoneManager.TYPE_RINGTONE`) as primary alarm tone.
- **System Volume Overrides**: Configured automatic stream volume maximization for `STREAM_RING`, `STREAM_ALARM`, `STREAM_MUSIC`, and `STREAM_NOTIFICATION` to 100% full volume, even when the user's phone ringtone volume is set low or on silent/vibrate mode.
- **Audio Permission**: Added `android.permission.MODIFY_AUDIO_SETTINGS` to `AndroidManifest.xml`.

### 🛠 System Improvements
- Added ProGuard/R8 rules for OsmDroid, data models, and services.
- Updated build target to Android SDK 35 with optimized release compilation.

---

## Version 1.0.0 (Build 1) - Initial Release
- Multi-modal travel mode detection (Bus/Car, Train, Flight).
- Sequential distance & ETA alerts (Level 1 Silent, Level 2 Vibration, Level 3 Voice TTS in EN/HI/TA, Level 4 Emergency Alarm).
- Embedded OpenStreetMap (OsmDroid) pin picker and geocoding.
- Lockscreen wake-up alarm interface and background GPS tracking service.
