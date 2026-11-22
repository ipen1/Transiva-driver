package com.transiva.app;
import java.net.URI;

/** Existing chat encodings, parsed without executing or trusting message URLs. */
final class TripBubbleContent {
    static boolean image(String raw){return raw.startsWith("[[IMAGE]]")||raw.startsWith("[[IMAGE2]]");}
    static boolean voice(String raw){return raw.startsWith("[[VOICE]]");}
    static String mediaUrl(String raw) {
        String[] urls=mediaUrls(raw);
        return urls.length==0?"":urls[0];
    }

    static String[] mediaUrls(String raw) {
        int end=raw.indexOf("]]");
        if (end<0 || (!image(raw) && !voice(raw))) return new String[0];
        String[] fields=raw.substring(end+2).trim().split("\\|",image(raw)?3:2);
        java.util.ArrayList<String> urls=new java.util.ArrayList<>();
        for (int i=0;i<Math.min(fields.length,image(raw)?2:1);i++) {
            String url=normalize(fields[i].trim(),image(raw));
            if (!url.isEmpty() && !urls.contains(url)) urls.add(url);
        }
        return urls.toArray(new String[0]);
    }

    private static String normalize(String value,boolean image) {
        if (value.isEmpty() || value.startsWith("//")) return "";
        if (!value.contains("://")) {
            String path=value.startsWith("/")?value.substring(1):value;
            value="https://transiva.my.id/"+(path.startsWith("server/")?"":"server/")+path;
        }
        try {
            URI uri=new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) return "";
            String path=uri.getPath();
            if (image && "transiva.my.id".equalsIgnoreCase(uri.getHost())
                    && path.matches("/server/uploads/chat/(preview|hd)/[A-Za-z0-9_.-]+")) {
                return "https://transiva.my.id/server/view_chat_image.php?file="
                        +path.substring("/server/uploads/chat/".length()).replace("/","%2F");
            }
            return value;
        } catch (Exception e) { return ""; }
    }
    static String text(String raw){return raw.startsWith("[[STICKER")?"Stiker · ketuk untuk membuka chat":raw;}
}
