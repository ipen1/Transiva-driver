package com.transiva.app;

import org.json.JSONObject;

/** Optional display metadata only; never required for a call to start. */
final class CallPeerIdentity {
    private CallPeerIdentity() {}
    static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()
                && !"null".equalsIgnoreCase(value.trim())) return value.trim();
        return "";
    }
    static String photo(JSONObject peer) {
        if (peer == null) return "";
        return first(peer.optString("profile_photo"), peer.optString("driver_photo"),
                peer.optString("photo_url"), peer.optString("avatar_url"), peer.optString("photo"));
    }
    static String photoUrl(String raw) {
        String value=first(raw).replace('\\','/');
        if(value.startsWith("http://transiva.my.id/"))value="https://"+value.substring(7);
        if(value.startsWith("https://"))return value;
        if(value.isEmpty()||value.contains(":")||value.startsWith("//"))return "";
        while(value.startsWith("./"))value=value.substring(2);
        while(value.startsWith("/"))value=value.substring(1);
        if(value.startsWith("server/"))value=value.substring(7);
        return DriverApiConfig.endpoint(value);
    }
    static String initials(String name) {
        String value = first(name, "Transiva");
        String[] words = value.split("\\s+");
        String result = new String(Character.toChars(words[0].codePointAt(0)));
        if (words.length > 1) result += new String(Character.toChars(words[words.length-1].codePointAt(0)));
        return result.toUpperCase(java.util.Locale.ROOT);
    }
}
