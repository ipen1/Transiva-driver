
# WebRTC JNI classes
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Transiva native entry points / Firebase services
-keep class com.transiva.app.TransivaFirebaseService { *; }
-keep class com.transiva.app.TransivaNotificationActionReceiver { *; }
-keep class com.transiva.app.TransivaBootReceiver { *; }
-keep class com.transiva.app.LocationService { *; }

# JSON/domain objects are currently mapped manually; keep names conservative for optimized release.
-keep class com.transiva.app.driver.domain.** { *; }
-keep class com.transiva.app.driver.data.** { *; }

# WorkManager creates this worker reflectively after process restart.
-keep class com.transiva.app.FcmRegistrationWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

-keep class com.transiva.app.CallDeliveryReceiptWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
