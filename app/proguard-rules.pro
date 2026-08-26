# Project-specific ProGuard / R8 Rules
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# OpenStreetMap / OsmDroid Keep Rules
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Data Models & SQLite helpers
-keep class com.shadowprotectors.alarmapp.data.** { *; }
-keep class com.shadowprotectors.alarmapp.engine.** { *; }
-keep class com.shadowprotectors.alarmapp.alert.** { *; }
-keep class com.shadowprotectors.alarmapp.service.TrackingState { *; }
