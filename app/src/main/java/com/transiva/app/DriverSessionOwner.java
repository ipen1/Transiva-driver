package com.transiva.app;

import android.content.Context;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Captures credentials once; no retry or callback migrates to a replacement login. */
public final class DriverSessionOwner {
    private static final java.util.concurrent.ExecutorService CANCELLATIONS=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Context context;
    private final String token, uuid, userId;
    private final Set<HttpURLConnection> connections=Collections.synchronizedSet(new HashSet<>());
    private volatile boolean closed;
    private DriverSessionOwner(Context c) {
        context=c.getApplicationContext();
        synchronized(SessionManager.class) {
            SessionManager s=new SessionManager(context);
            token=s.getToken(); uuid=DeviceIdentityManager.getInstallationUuid(context);
            userId=identity(s);
        }
    }
    public static DriverSessionOwner capture(Context c){return new DriverSessionOwner(c);}
    private static String identity(SessionManager s){String id=s.getId();return id==null||id.isEmpty()?s.getUserId():id;}
    public String userId(){return userId;}
    public boolean current(){synchronized(SessionManager.class){return matchesLocked();}}
    private boolean matchesLocked(){
        SessionManager s=new SessionManager(context);
        return !closed&&s.isLoggedIn()&&"driver".equals(s.normalizeRole(s.getRole()))
            &&token!=null&&!token.isEmpty()&&token.equals(s.getToken())
            &&userId!=null&&userId.equals(identity(s))&&uuid.equals(DeviceIdentityManager.getInstallationUuid(context));
    }
    public void check() throws java.io.InterruptedIOException {
        if(Thread.currentThread().isInterrupted()||!current())throw new java.io.InterruptedIOException("Sesi permintaan berubah");
    }
    public void pin(HttpURLConnection c) throws java.io.IOException {
        check();
        if(!NetworkSafetyRules.maySendCredentials(c.getURL().toString()))throw new java.io.IOException("Host permintaan tidak valid");
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization","Bearer "+token);
        c.setRequestProperty("X-Device-UUID",uuid);c.setRequestProperty("X-App-Scope","driver");
        connections.add(c);check();
    }
    public void release(HttpURLConnection c){if(c!=null)connections.remove(c);}
    public boolean runIfCurrent(Runnable task){synchronized(SessionManager.class){if(!matchesLocked())return false;task.run();return true;}}
    public void close(){
        closed=true;final HttpURLConnection[] pending;
        synchronized(connections){pending=connections.toArray(new HttpURLConnection[0]);connections.clear();}
        if(pending.length>0)CANCELLATIONS.execute(()->{for(HttpURLConnection c:pending)try{c.disconnect();}catch(RuntimeException ignored){}});
    }
}
