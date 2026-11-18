package com.transiva.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.util.Locale;

/** Compact food copy derived only from the existing authenticated order. */
final class FoodTripPresentation {
    static JSONObject food(JSONObject order) {
        if(order==null)return new JSONObject();
        try { return new JSONObject(order.optString("note", "{}")); }
        catch(org.json.JSONException ignored){return new JSONObject();}
    }
    static boolean isFood(JSONObject order) {
        if(order==null)return false;
        String type=(order.optString("order_type")+" "+order.optString("service_name")).toLowerCase(Locale.US);
        return type.contains("food") || "food".equalsIgnoreCase(food(order).optString("type"));
    }
    static String restaurant(JSONObject order) {
        JSONObject food=food(order);
        return first(food.optString("restaurant_name"),order.optString("restaurant_name"),order.optString("pickup_address"),"Resto");
    }
    static String items(JSONObject order) {
        JSONArray items=food(order).optJSONArray("items");
        if(items==null || items.length()==0)return "Detail pesanan makanan";
        JSONObject first=items.optJSONObject(0);if(first==null)return "Detail pesanan makanan";
        String quantity=first.optString("qty",first.optString("quantity","1"));
        String title=quantity+"× "+first(first.optString("name"),first.optString("food_name"),first.optString("menu_name"),"Menu");
        return title+(items.length()>1?" +"+(items.length()-1)+" menu":"");
    }
    static String money(double value) {return "Rp"+NumberFormat.getIntegerInstance(new Locale("id","ID")).format(value);}
    static String payment(JSONObject order) {
        String method=first(order.optString("payment_method"),food(order).optString("payment_method"),"cash").toLowerCase(Locale.US);
        if(method.contains("qris"))return "QRIS";
        if(method.equals("balance")||method.contains("transpay")||method.contains("transiva_pay")||method.equals("wallet")||method.equals("saldo"))return "TransPay";
        return "Tunai";
    }
    static double total(JSONObject order) {return food(order).optDouble("total",order.optDouble("total",order.optDouble("price")));}
    static String kitchen(JSONObject order) {
        JSONObject food=food(order);String state=food.optString("merchant_status").toLowerCase(Locale.US);
        String label;
        switch(state){
            case "ready":case "ready_pickup":case "ready_for_pickup":label="Siap diambil";break;
            case "preparing":case "cooking":case "processing":label="Sedang disiapkan";break;
            case "accepted":case "merchant_accepted":label="Pesanan diterima resto";break;
            case "picked_up":case "on_delivery":label="Makanan sudah diambil";break;
            case "completed":case "finished":label="Pesanan selesai";break;
            case "cancelled":case "canceled":case "rejected":label="Pesanan dibatalkan";break;
            default:label="Menunggu informasi resto";
        }
        int minutes=food.optInt("cook_minutes");
        return label+((label.equals("Sedang disiapkan")&&minutes>0)?" • ±"+minutes+" menit":"");
    }
    static String first(String... values){for(String s:values)if(s!=null&&!s.trim().isEmpty()&&!"null".equalsIgnoreCase(s))return s.trim();return "";}
}
