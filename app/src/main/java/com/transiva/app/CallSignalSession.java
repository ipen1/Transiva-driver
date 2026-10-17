package com.transiva.app;
import android.content.Context;
import java.net.HttpURLConnection;
/** Immutable identity of an ongoing call; no request migrates to a replacement login. */
final class CallSignalSession {
    private final String token,uuid;
    private CallSignalSession(Context c){token=new SessionManager(c).getToken();uuid=DeviceIdentityManager.getInstallationUuid(c);}
    static CallSignalSession capture(Context c){return new CallSignalSession(c);}
    boolean matches(Context c){SessionManager s=new SessionManager(c);return s.isLoggedIn()&&"driver".equalsIgnoreCase(s.getRole())&&token!=null&&!token.isEmpty()&&token.equals(s.getToken())&&uuid.equals(DeviceIdentityManager.getInstallationUuid(c));}
    void pin(Context c,HttpURLConnection connection)throws InterruptedException{
        check(c);if(!NetworkSafetyRules.maySendCredentials(connection.getURL().toString()))throw new InterruptedException("Host signaling tidak valid");
        connection.setRequestProperty("Authorization","Bearer "+token);connection.setRequestProperty("X-Device-UUID",uuid);connection.setRequestProperty("X-App-Scope","driver");check(c);
    }
    void check(Context c)throws InterruptedException{if(!matches(c))throw new InterruptedException("Sesi panggilan berubah");}
}
