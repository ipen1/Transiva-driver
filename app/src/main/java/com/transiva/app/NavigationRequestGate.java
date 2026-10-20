package com.transiva.app;

/** Pure generation/target gate: old completion can never settle a newer request. */
public final class NavigationRequestGate {
    private long generation;
    private boolean closed;
    private String target="";
    public synchronized long begin(String key){if(closed)throw new IllegalStateException("Navigation closed");target=key;return ++generation;}
    public synchronized boolean accepts(long id,String key){return !closed&&id==generation&&target.equals(key);}
    public synchronized void invalidate(){generation++;target="";}
    public synchronized void close(){closed=true;generation++;}
}
