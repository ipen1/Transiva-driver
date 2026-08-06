package com.transiva.app;
import org.json.JSONObject;
import java.io.File;
public final class DriverChatMediaApi {
 private DriverChatMediaApi(){}
 private static SessionManager session(){ android.content.Context c=TransivaDriverApplication.getAppContext(); if(c==null) throw new IllegalStateException("Aplikasi belum siap"); return new SessionManager(c); }
 public static JSONObject get(String endpoint)throws Exception{return DriverMessageApi.get(session(),endpoint);}
 public static JSONObject post(String endpoint,JSONObject body)throws Exception{return DriverMessageApi.post(session(),endpoint,body);}
 public static JSONObject uploadImagePair(String endpoint,String roomId,String senderType,ChatImageProcessor.ImagePayload payload)throws Exception{return DriverMessageApi.uploadImagePair(session(),endpoint,"","orders",roomId,payload);}
 public static JSONObject uploadVoice(String endpoint,String roomId,String senderType,File file,long durationMs)throws Exception{return DriverMessageApi.uploadVoice(session(),endpoint,"","orders",roomId,file,durationMs);}
}
