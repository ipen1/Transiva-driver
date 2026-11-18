package com.transiva.app;
import java.net.URI;

/** Existing chat encodings, parsed without executing or trusting message URLs. */
final class TripBubbleContent {
    static boolean image(String raw){return raw.startsWith("[[IMAGE]]")||raw.startsWith("[[IMAGE2]]");}
    static boolean voice(String raw){return raw.startsWith("[[VOICE]]");}
    static String mediaUrl(String raw){
        String value=raw.substring(raw.indexOf("]]" )+2).trim();
        int split=value.indexOf('|');if(split>=0)value=value.substring(0,split).trim();
        if(value.startsWith("/server/"))value="https://transiva.my.id"+value;
        else if(!value.startsWith("https://")&&!value.startsWith("http://"))value="https://transiva.my.id/server/"+(value.startsWith("/")?value.substring(1):value);
        try{URI uri=new URI(value);return "https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null?value:"";}catch(Exception e){return "";}
    }
    static String text(String raw){return raw.startsWith("[[STICKER")?"Stiker · ketuk untuk membuka chat":raw;}
}
