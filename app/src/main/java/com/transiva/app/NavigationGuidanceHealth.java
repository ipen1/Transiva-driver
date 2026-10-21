package com.transiva.app;

/** In-process acknowledgement of actual foreground promotion; stale starts cannot acknowledge a new session. */
final class NavigationGuidanceHealth {
    enum State { PENDING, READY, FAILED }
    private static String token="",owner="",key="";
    private static long changed;
    private static State state=State.FAILED;
    static synchronized String begin(String session,String route,long now){
        token=java.util.UUID.randomUUID().toString();owner=session;key=route;changed=now;state=State.PENDING;return token;
    }
    static synchronized boolean owns(String id,String session,String route){return !token.isEmpty()&&token.equals(id)&&owner.equals(session)&&key.equals(route);}
    static synchronized boolean acknowledge(String id,String session,String route,long now){
        if(!owns(id,session,route)||state!=State.PENDING||now<changed||now-changed>5000)return false;
        state=State.READY;changed=now;return true;
    }
    static synchronized void heartbeat(String id,long now){if(token.equals(id)&&state==State.READY)changed=now;}
    static synchronized void failed(String id){if(!token.isEmpty()&&token.equals(id))state=State.FAILED;}
    static synchronized State status(String id,String session,String route,long now){
        if(!owns(id,session,route))return State.FAILED;
        if(now<changed||now-changed>(state==State.PENDING?5000:6000))state=State.FAILED;
        return state;
    }
    static synchronized void clear(String session){if(owner.equals(session)){token="";owner="";key="";state=State.FAILED;}}
}
