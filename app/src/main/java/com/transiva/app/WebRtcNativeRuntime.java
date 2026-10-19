package com.transiva.app;

import android.content.Context;
import org.webrtc.PeerConnectionFactory;

/** Process-wide JNI initialization. Concurrent/repeated calls never reinitialize the native runtime. */
final class WebRtcNativeRuntime {
    private static boolean initialized;
    private WebRtcNativeRuntime() {}
    static synchronized void initialize(Context context) {
        if (initialized) return;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                .builder(context.getApplicationContext()).createInitializationOptions());
        initialized = true;
    }
}
