#!/usr/bin/env python3
"""Run shared action and preview receipt rules with isolated Android collaborators."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[2]; app=root/'app/src/main/java/com/transiva/app'
base=Path(__file__).with_name('navigation_consistency_regression.py'); ns={'__file__':str(base)}
exec(base.read_text().split("files['com/transiva/app/ConsistencyProbe.java']")[0],ns)
files=ns['files']
# Only platform/account collaborators are needed for these production policies.
keep=['android/content/Context.java','android/content/SharedPreferences.java','android/location/Location.java','android/os/SystemClock.java','com/transiva/app/SessionManager.java']
files={k:v for k,v in files.items() if k in keep}
files['android/content/Context.java']='package android.content; public class Context{public static final int MODE_PRIVATE=0;static final java.util.Map<String,SharedPreferences> prefs=new java.util.HashMap<>();public SharedPreferences getSharedPreferences(String n,int m){return prefs.computeIfAbsent(n,k->new SharedPreferences());}}'
files['com/transiva/app/NavigationSessionStore.java']='package com.transiva.app;class NavigationSessionStore{static String owner="account-a";static String identity(android.content.Context c){return owner;}}'
files.pop('com/transiva/app/SessionManager.java')
files['com/transiva/app/ActionSyncProbe.java']='''package com.transiva.app;
import org.json.*;import android.content.Context;import android.location.Location;import android.os.SystemClock;
public class ActionSyncProbe{
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
 public static void main(String[]args)throws Exception{
 Context trip=new Context(),nav=new Context();JSONObject order=new JSONObject().put("id",12).put("order_id","ORD-12").put("source","orders").put("status","driver_accepted").put("pickup_lat",-.8).put("pickup_lng",120).put("delivery_lat",-.79).put("delivery_lng",120);
 TripActionSession a=TripActionSession.get(trip,order),b=TripActionSession.get(nav,order);
 check(a==b,"activities share one order session");check(!TripActionPolicy.resolve(a).enabled,"unverified server state remains locked");
 a.synced(order);Location gps=new Location("gps");gps.setLatitude(-.8);gps.setLongitude(120);gps.setAccuracy(5);a.fix(gps);
 check(TripActionPolicy.resolve(b).enabled,"verified raw location unlocks both screens");
 long version=a.version();check(a.begin()&&!b.begin()&&!TripActionPolicy.resolve(b).enabled,"one in-flight action blocks duplicates on both screens");check(a.version()!=version,"mutation invalidates earlier polls");
 order.put("status","arrived_pickup");a.completed(order);check("on_delivery".equals(TripActionPolicy.resolve(b).next)&&TripActionPolicy.resolve(b).enabled,"navigation offers start after pickup instead of locked arrival");
 check(TripActionSession.get(nav,order)==a&&a.fresh(),"activity handoff preserves fresh state");
 order.put("status","on_delivery");b.completed(order);check(!TripActionPolicy.resolve(a).enabled,"delivery cannot unlock using pickup coordinates");gps.setLatitude(-.79);b.fix(gps);check(TripActionPolicy.resolve(a).enabled,"navigation GPS updates trip eligibility");
 order.put("status","arrived_delivery");b.completed(order);check(!TripActionPolicy.resolve(a).enabled,"finish requires customer confirmation");order.put("customer_received",1);b.synced(order);check("finished".equals(TripActionPolicy.resolve(a).next)&&TripActionPolicy.resolve(a).enabled,"both screens offer finish after confirmation");
 a.failed();check(!TripActionPolicy.resolve(b).enabled,"failed mutation requires resync everywhere");b.synced(order);SystemClock.now+=16000;check(!TripActionPolicy.resolve(a).enabled,"expired server state does not stay unlocked");
 a.synced(order);order.put("status","on_delivery").put("ecosystem",new JSONObject().put("waypoints",new JSONArray().put(new JSONObject().put("sequence",2).put("latitude",-.79).put("longitude",120).put("status","pending"))));a.synced(order);gps=new Location("gps");gps.setLatitude(-.79);gps.setLongitude(120);a.fix(gps);check("waypoint".equals(TripActionPolicy.resolve(b).next)&&TripActionPolicy.resolve(b).stop==2&&TripActionPolicy.resolve(b).enabled,"pending waypoint uses same sequence and GPS gate");
 check(!TripChatDisplayStore.shown(trip,order,51),"fetching alone does not consume preview");check(TripChatDisplayStore.claim(trip,order,51),"first visible preview claims receipt");check(!TripChatDisplayStore.claim(nav,order,51)&&TripChatDisplayStore.shown(new Context(),order,51),"same message cannot replay after screen recreation");check(TripChatDisplayStore.claim(nav,order,49),"delayed message is not hidden by newer receipt");
 check(TripChatDisplayStore.duration("a")>=2200&&TripChatDisplayStore.duration("a".repeat(200))>TripChatDisplayStore.duration("a".repeat(40)),"reading duration grows with message length");
 check(TripChatDisplayStore.claim(trip,order,51,true)&&!TripChatDisplayStore.claim(nav,order,51,true),"merchant preview has its own deduplicated room receipt");
 JSONObject food=new JSONObject().put("type","food").put("restaurant_name","Ci Resto").put("food_total",7300).put("delivery_fee",10000).put("total",17300).put("merchant_status","preparing").put("cook_minutes",10).put("items",new JSONArray().put(new JSONObject().put("name","Risol ori").put("qty",1)).put(new JSONObject().put("name","Teh").put("qty",2)));
 JSONObject meal=new JSONObject().put("id",99).put("order_id","FOOD-99").put("status","driver_accepted").put("order_type","food").put("note",food.toString()).put("pickup_lat",-.79).put("pickup_lng",120);
 check(FoodTripPresentation.isFood(meal)&&FoodTripPresentation.restaurant(meal).equals("Ci Resto"),"Transfood routes to compact layout with real restaurant name");
 check(FoodTripPresentation.items(meal).equals("1× Risol ori +1 menu"),"long menu lists have a compact summary with remaining item count");
 check(FoodTripPresentation.total(meal)==17300&&FoodTripPresentation.payment(meal).equals("Tunai"),"cash summary uses total food plus delivery");
 meal.put("payment_method","qris");check(FoodTripPresentation.payment(meal).equals("QRIS"),"QRIS never falls back to cash label");meal.put("payment_method","transpay");check(FoodTripPresentation.payment(meal).equals("TransPay"),"wallet uses noncash collection label");
 check(FoodTripPresentation.kitchen(meal).equals("Sedang disiapkan • ±10 menit"),"preparation estimate is based on merchant data");food.put("merchant_status","ready");meal.put("note",food.toString());check(FoodTripPresentation.kitchen(meal).equals("Siap diambil"),"ready orders stop displaying old cooking estimate");
 TripActionSession foodSession=TripActionSession.get(nav,meal);foodSession.synced(meal);foodSession.fix(gps);check(TripActionPolicy.resolve(foodSession).label.equals("Geser untuk tiba di resto")&&TripActionPolicy.resolve(foodSession).enabled,"food pickup shares action session and verified GPS");

 food.put("driver_first",true);meal.put("status","arrived_pickup").put("note",food.toString());foodSession.synced(meal);
 check(!TripActionPolicy.resolve(foodSession).enabled,"navigation blocks food departure while merchant confirmation is pending");
 food.put("merchant_accepted_at","2026-11-22 18:00:00");meal.put("note",food.toString());foodSession.synced(meal);
 check(TripActionPolicy.resolve(foodSession).enabled,"navigation unlocks food departure after merchant acceptance");

 JSONObject shop=new JSONObject().put("id",201).put("order_id","SHOP-201").put("order_type","TransShop").put("status","driver_accepted").put("pickup_lat",-.79).put("pickup_lng",120).put("note",new JSONObject().put("type","shop").put("shopping_plan",new JSONObject().put("version",1).put("pickup",new JSONObject().put("note","Belanja di Cii Shop\\n1x Setcel @ Rp 88.000\\nSubtotal referensi produk: Rp 88.000"))).toString());
 check(DeliveryTripPresentation.shop(shop)&&!DeliveryTripPresentation.customerAtPickup(shop),"TransShop bubble anchors at customer destination");
 check(DeliveryTripPresentation.reference(shop)==88000,"shop reference is separate from delivery fee");
 TripActionSession shopSession=TripActionSession.get(trip,shop);shopSession.synced(shop);shopSession.fix(gps);check(TripActionPolicy.resolve(shopSession).label.equals("Geser untuk tiba di toko")&&TripActionPolicy.resolve(shopSession).enabled,"shop arrival uses shared verified action");
 JSONObject send=new JSONObject(shop.toString()).put("id",202).put("order_id","SEND-202").put("order_type","TransSend").put("source","pickup_orders").put("note","antar kipas angin");
 check(DeliveryTripPresentation.send(send)&&DeliveryTripPresentation.customerAtPickup(send),"TransSend bubble anchors at pickup");check(DeliveryTripPresentation.note(send).equals("antar kipas angin"),"parcel instructions remain intact");
 TripBubbleStore.offer(trip,shop,100,"lama",false,false);check(TripBubbleStore.latest(nav,shop).raw.equals("lama"),"latest message survives screen handoff");
 TripBubbleStore.offer(nav,shop,99,"terlambat",false,false);check(TripBubbleStore.latest(trip,shop).id==100,"old poll cannot replace new bubble");
 TripBubbleStore.offer(nav,shop,101,"baru",false,false);check(TripBubbleStore.latest(trip,shop).raw.equals("baru"),"new incoming message replaces old bubble");
 SystemClock.now+=120000;check(TripBubbleStore.latest(nav,shop).id==101,"bubble has no seconds-based expiration");
 TripBubbleStore.ack(nav,shop,TripBubbleStore.latest(nav,shop));check(TripBubbleStore.latest(trip,shop).ack,"opening or playing acknowledges reminder across screens");
 TripBubbleStore.offer(trip,shop,101,"baru",false,false);check(TripBubbleStore.latest(nav,shop).ack,"repeated polling cannot restart reminder");
 TripBubbleStore.offer(nav,meal,120,"pesan merchant",true,false);TripBubbleStore.offer(trip,meal,121,"pesan pelanggan",false,false);check(!TripBubbleStore.latest(nav,meal).merchant,"newest incoming room wins one bubble without overlap");
 TripBubbleStore.offer(trip,send,300,"sudah dibaca",false,true);check(TripBubbleStore.latest(nav,send)==null,"initial historical read messages do not resurface");
 check(TripBubbleContent.image("[[IMAGE2]]uploads/a.webp|uploads/a.jpg")&&TripBubbleContent.mediaUrl("[[IMAGE2]]uploads/a.webp|uploads/a.jpg").equals("https://transiva.my.id/server/uploads/a.webp"),"image bubble uses lightweight preview encoding");
 check(TripBubbleContent.voice("[[VOICE]]uploads/a.m4a|2000")&&TripBubbleContent.mediaUrl("[[VOICE]]uploads/a.m4a|2000").endsWith("/uploads/a.m4a"),"voice bubble separates playback URL from duration");
 check(TripBubbleContent.mediaUrl("[[IMAGE]]http://example.com/a.jpg").isEmpty(),"insecure media URL is rejected");
 NavigationSessionStore.owner="account-b";check(TripBubbleStore.latest(nav,shop)==null,"persistent bubble cannot leak to another account");check(!TripChatDisplayStore.shown(nav,order,51)&&TripActionSession.get(nav,order)!=a,"actions and display receipts are isolated by account");
 System.out.println("PASS "+checks+" shared trip and chat checks");
 }}'''
production=['TripBubbleStore','TripBubbleContent','TripActionSession','DeliveryTripPresentation','FoodTripPresentation','TripActionPolicy','TripArrivalLocation','TripChatDisplayStore','NavigationTargetPolicy','NavigationGeometry','DriverEcosystemFeatures','DriverOrderStateMachine']
with tempfile.TemporaryDirectory(prefix='trip-action-sync-') as tmp:
 d=Path(tmp)
 for name,value in files.items():
  p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(value)
 subprocess.run([os.environ.get('JAVAC_BIN','javac'),'-cp',os.environ['JSON_JAR'],'-d',str(d/'classes'),*map(str,list(d.rglob('*.java'))+[app/(n+'.java') for n in production])],check=True)
 subprocess.run([os.environ.get('JAVA_BIN','java'),'-cp',str(d/'classes')+os.pathsep+os.environ['JSON_JAR'],'com.transiva.app.ActionSyncProbe'],check=True,timeout=30)
