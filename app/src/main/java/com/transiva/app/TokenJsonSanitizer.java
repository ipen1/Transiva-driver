package com.transiva.app;
import org.json.*;
import java.util.Iterator;
/** Never mutates the caller's authenticated response or strips unrelated profile fields. */
final class TokenJsonSanitizer {
    static boolean secret(String key){return "token".equals(key)||"access_token".equals(key)||"auth_token".equals(key)||"api_token".equals(key)||"session_token".equals(key);}
    static JSONObject clean(JSONObject source){
        JSONObject out=new JSONObject();if(source==null)return out;
        Iterator<String> keys=source.keys();while(keys.hasNext()){
            String key=keys.next();if(secret(key))continue;
            try{out.put(key,cleanValue(source.opt(key)));}catch(JSONException error){throw new IllegalStateException(error);}
        }return out;
    }
    private static Object cleanValue(Object v)throws JSONException{
        if(v instanceof JSONObject)return clean((JSONObject)v);
        if(v instanceof JSONArray){JSONArray out=new JSONArray();for(int i=0;i<((JSONArray)v).length();i++)out.put(cleanValue(((JSONArray)v).opt(i)));return out;}
        return v;
    }
    static String cleanString(String json){try{return clean(new JSONObject(json==null?"{}":json)).toString();}catch(Exception invalid){return "{}";}}
    static String extract(String json){
        try{JSONObject j=new JSONObject(json);for(String k:new String[]{"token","access_token","auth_token","api_token","session_token"}){String s=j.optString(k,"").trim();if(!s.isEmpty()&&!"null".equals(s))return s;}JSONObject nested=j.optJSONObject("user");return nested==null?"":extract(nested.toString());}
        catch(Exception invalid){return "";}
    }
}
