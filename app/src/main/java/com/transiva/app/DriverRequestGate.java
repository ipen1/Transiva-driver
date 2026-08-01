package com.transiva.app;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
/** Gate idempotensi sisi aplikasi. Backend tetap harus menjamin transaksi atomik. */
public final class DriverRequestGate { private static final ConcurrentHashMap<String,AtomicBoolean> G=new ConcurrentHashMap<>(); private DriverRequestGate(){} public static boolean enter(String key){return G.computeIfAbsent(key==null?"":key,k->new AtomicBoolean()).compareAndSet(false,true);} public static void leave(String key){AtomicBoolean b=G.get(key==null?"":key);if(b!=null)b.set(false);} }
