package com.transiva.app;

import org.json.*;
import java.util.Locale;
import java.util.regex.*;

/** Read-only service presentation; never changes fare or operational status. */
final class DeliveryTripPresentation {
    static boolean shop(JSONObject o){return o!=null&&(type(o).contains("shop")||"shop".equalsIgnoreCase(FoodTripPresentation.food(o).optString("type")));}
    static boolean send(JSONObject o){return o!=null&&(type(o).contains("send")||"pickup_orders".equals(NavigationTargetPolicy.source(o)));}
    static boolean special(JSONObject o){return shop(o)||send(o);}
    static String kind(JSONObject o){return shop(o)?"shop":send(o)?"send":"food";}
    static String type(JSONObject o){return(o.optString("order_type")+" "+o.optString("service_name")).toLowerCase(Locale.US);}
    static String service(JSONObject o){return shop(o)?"TransShop":send(o)?"TransSend":FoodTripPresentation.isFood(o)?"Transfood":"";}
    static JSONObject plan(JSONObject o){JSONObject p=o.optJSONObject("shopping_plan");return p==null?FoodTripPresentation.food(o).optJSONObject("shopping_plan"):p;}
    static String note(JSONObject o){
        JSONObject p=plan(o),pickup=p==null?null:p.optJSONObject("pickup");
        if(pickup!=null)return pickup.optString("note");
        String raw=o.optString("note");
        return FoodTripPresentation.first(o.optString("customer_note"),o.optString("note_customer"),raw.startsWith("{")?FoodTripPresentation.food(o).optString("text"):raw);
    }
    static String summary(JSONObject o){
        if(send(o))return "Kirim barang";
        for(String line:note(o).split("\\n"))if(line.trim().matches("(?i)^\\d+\\s*[x×].*"))return line.trim().split("\\s*@")[0];
        return "Daftar belanja";
    }
    static double reference(JSONObject o){
        String text=note(o);JSONObject p=plan(o);JSONArray stops=p==null?null:p.optJSONArray("stops");
        if(stops!=null)for(int i=0;i<stops.length();i++){JSONObject stop=stops.optJSONObject(i);if(stop!=null)text+="\n"+stop.optString("note");}
        Matcher m=Pattern.compile("(?i)Subtotal referensi produk\\s*:\\s*Rp\\s*([0-9.,]+)").matcher(text);
        double total=0;boolean found=false;
        while(m.find()){try{total+=Double.parseDouble(m.group(1).replace(".","").replace(",","."));found=true;}catch(NumberFormatException ignored){}}
        return found?total:Double.NaN;
    }
    static boolean customerAtPickup(JSONObject o){return !FoodTripPresentation.isFood(o)&&!shop(o);}
}
