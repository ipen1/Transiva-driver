
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

# WebView JavaScript bridge. Method ini dipanggil dari JavaScript dan tidak boleh
# dihapus/diubah nama oleh R8.
-keep class com.transiva.app.ApiClient { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Pertahankan metadata anotasi/generic yang dapat dipakai library Android/Firebase.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

