# Keep Wearable API classes
-keep class com.google.android.gms.wearable.** { *; }
-keep class * extends com.google.android.gms.wearable.WearableListenerService { *; }

# Keep our service classes
-keep class dev.borges.shadow.wear.** { *; }

# Keep sensor classes
-keep class android.hardware.Sensor { *; }
-keep class android.hardware.SensorEvent { *; }
-keep class android.hardware.SensorEventListener { *; }

# Keep logging for debugging
# -assumenosideeffects class android.util.Log {
#     public static int v(...);
#     public static int d(...);
# }
