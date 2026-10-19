
# WebRTC JNI classes
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Transiva native entry points / Firebase services
-keep class com.transiva.app.TransivaFirebaseService { *; }
-keep class com.transiva.app.TransivaNotificationActionReceiver { *; }
-keep class com.transiva.app.TransivaBootReceiver { *; }
-keep class com.transiva.app.LocationService { *; }

# Domain/data JSON is mapped explicitly; names are not used for reflection.
# Keep only actual reflection/JNI entry points, not whole application packages.

# WorkManager creates this worker reflectively after process restart.
-keep class com.transiva.app.FcmRegistrationWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

-keep class com.transiva.app.CallDeliveryReceiptWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# Retain source/line information; archive mapping.txt for manual crash deobfuscation.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod

# JavaScript bridge methods may be invoked by name from WebView.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
