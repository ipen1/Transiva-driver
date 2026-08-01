package com.transiva.app;
public final class DriverRetryPolicy { private DriverRetryPolicy(){} public static long delayFor(int status,int retryAfterSeconds,int attempt){if(status==429)return Math.max(1000L,Math.min(120000L,retryAfterSeconds*1000L));if(status>=500)return Math.min(60000L,1000L<<(Math.min(5,Math.max(0,attempt))));return 0L;} }
