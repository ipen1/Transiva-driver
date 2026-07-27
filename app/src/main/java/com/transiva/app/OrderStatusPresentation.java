package com.transiva.app;

import org.json.JSONObject;
import java.util.Locale;

public final class OrderStatusPresentation {
    private OrderStatusPresentation() {}

    public static String label(String rawStatus, String rawType) {
        String s = n(rawStatus), t = n(rawType);
        switch (s) {
            case "pending": return isFood(t) ? "Menunggu Merchant" : (isCar(t) ? "Menunggu Driver Mobil" : "Menunggu Driver");
            case "merchant_accepted": return "Diterima Merchant";
            case "merchant_rejected": return "Ditolak Merchant";
            case "taken": return "Driver Menuju Pickup";
            case "arrived_pickup": return isFood(t) ? "Driver Tiba di Restoran" : "Driver Tiba di Pickup";
            case "on_delivery": return isFood(t) ? "Menuju Lokasi Customer" : "Menuju Lokasi Tujuan";
            case "arrived_delivery": return isFood(t) ? "Driver Tiba di Customer" : "Driver Tiba di Tujuan";
            case "finished": case "finish": case "completed": return "Selesai";
            case "canceled": case "cancelled": return "Dibatalkan";
            default: return readable(s);
        }
    }

    public static String description(JSONObject o) {
        if (o == null) return "";
        String s=n(o.optString("status"));
        String t=n(first(o.optString("order_type"),o.optString("service_type"),o.optString("service"),o.optString("service_name")));
        String d=first(o.optString("driver"),o.optString("driver_username"),"Driver");
        switch(s){
            case "pending": return isFood(t)?"Menunggu merchant mengonfirmasi pesanan":(isCar(t)?"Menunggu driver mobil menerima order":"Menunggu driver menerima order");
            case "merchant_accepted": return "Merchant sedang menyiapkan pesanan dan menunggu driver";
            case "merchant_rejected": return "Merchant menolak pesanan";
            case "taken": return isFood(t)?d+" sedang menuju restoran":d+" sedang menuju lokasi pickup";
            case "arrived_pickup": return isFood(t)?d+" sudah tiba di restoran":d+" sudah tiba di lokasi pickup";
            case "on_delivery": return isFood(t)?d+" sedang mengantar pesanan ke customer":d+" sedang menuju lokasi tujuan";
            case "arrived_delivery": return isFood(t)?d+" sudah tiba di lokasi customer":d+" sudah tiba di lokasi tujuan";
            case "finished": case "finish": case "completed": return "Pesanan telah selesai";
            case "canceled": case "cancelled": return "Pesanan telah dibatalkan";
            default: return label(s,t);
        }
    }

    public static String textColor(String s){s=n(s); if(doneOrArrived(s))return "#07864B"; if(cancel(s))return "#C23636"; if(waiting(s))return "#B66A00"; return "#0B7CFF";}
    public static String backgroundColor(String s){s=n(s); if(doneOrArrived(s))return "#EAFBF2"; if(cancel(s))return "#FFF0F0"; if(waiting(s))return "#FFF7E5"; return "#EAF4FF";}
    public static String dotColor(String s){s=n(s); if(doneOrArrived(s))return "#14A867"; if(cancel(s))return "#E35353"; if(waiting(s))return "#F0A51A"; return "#0B7CFF";}

    private static boolean waiting(String s){return s.equals("pending")||s.equals("merchant_accepted");}
    private static boolean cancel(String s){return s.equals("merchant_rejected")||s.equals("canceled")||s.equals("cancelled");}
    private static boolean doneOrArrived(String s){return s.equals("finished")||s.equals("finish")||s.equals("completed")||s.equals("arrived_pickup")||s.equals("arrived_delivery");}
    private static boolean isFood(String t){return t.contains("food");}
    private static boolean isCar(String t){return t.contains("car")||t.contains("mobil");}
    private static String n(String v){return v==null?"":v.trim().toLowerCase(Locale.ROOT).replace('-','_').replace(' ','_');}
    private static String first(String...v){for(String x:v)if(x!=null&&!x.trim().isEmpty()&&!"null".equalsIgnoreCase(x.trim()))return x.trim();return "";}
    private static String readable(String v){if(v.isEmpty())return "Status Tidak Diketahui";StringBuilder b=new StringBuilder();for(String w:v.replace('_',' ').split("\\s+")){if(w.isEmpty())continue;if(b.length()>0)b.append(' ');b.append(Character.toUpperCase(w.charAt(0)));if(w.length()>1)b.append(w.substring(1));}return b.toString();}
}
