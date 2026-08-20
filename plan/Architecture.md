Travel Alarm — Project Plan

Problem
Bus/train passengers on unfamiliar routes have to keep checking Google Maps to avoid missing their stop. Turn-by-turn navigation is overkill for a passenger — they just need to know when they're getting close. Risk of falling asleep and missing the stop is a real, recurring pain point.

Solution
A destination-only alert app: set your stop once, get progressive alerts as you approach, no need to babysit a map.

Core Flow

User sets destination
   ↓
Background location tracking
   ↓
Distance & ETA engine (Haversine formula)
   ↓
Direction-aware filter (are we actually approaching, or just nearby on a loop?)
   ↓
Alert Manager
   ↓
Silent → Vibration → Voice (regional language) → Loud alarm

Key Differentiators (vs. WakePoint, Sleep&Arrive, Don't Miss the Stop, Dozy, etc.)

Progressive multi-level alerts — e.g. 10km silent → 5km vibration → 2km voice → 1km loud alarm, instead of one single trigger.
Direction-aware alerts — detects genuine approach vs. passing nearby on a loop/circular route. None of the reviewed competitors solve this, and it's a real false-alarm problem on Indian transit routes.
Regional language voice notifications — localized for Indian users.
Offline-first — no dependency on transit operator APIs; works purely off GPS + stored destination.

MVP Scope

Destination search/selection (Google Maps Platform: Place Search, Geocoding)
Background GPS tracking (every 30 sec–1 min)
Haversine-based distance calculation (via existing library, not custom)
Alert thresholds: silent → vibration → voice → alarm
Local storage only (SQLite or AsyncStorage) — no backend/server needed
Android Foreground Service + Local Notifications + Alarm Manager for reliable background alerting

Explicitly Out of Scope for MVP

Turn-by-turn navigation
Backend/server, live transit operator data
iOS (Android-first)
Directions API (deferred to later)

Target Users: bus/train passengers, tourists, commuters, students, elderly travelers, long-distance travelers — India-focused, with Madurai Junction as the running example.

Tech Stack Decision — Open

React Native vs. Java/Android Studio, both produce standalone APKs (no server hosting either way).
Next step: two-day spike — build the same minimal feature (background GPS updating every 10 sec) in both stacks, pick whichever has less friction.
Maps/Location: Google Maps Platform.
Notifications: Android Foreground Service, Local Notifications, Alarm Manager — flagged as the most critical component to get right.

Immediate Next Steps

Run the two-day stack spike test (Java vs. React Native).
Decide stack based on hands-on experience.
Prototype the direction-awareness logic (loop-route false-alarm detection) — this is your core moat, worth validating early.
Build MVP alert flow end-to-end for one test route (Madurai Junction).