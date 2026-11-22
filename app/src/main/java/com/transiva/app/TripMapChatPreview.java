package com.transiva.app;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import org.json.*;
import java.util.*;
import java.util.function.*;

/** Last-message map bubble shared across screens, with no disappearance timer. */
final class TripMapChatPreview {
    private final Activity activity;private final FrameLayout mapLayer;
    private final Supplier<JSONObject> order;private final Supplier<PointF> customerPin;
    private Supplier<PointF> merchantPin;private final IntSupplier visibleTop,visibleBottom;
    private final Runnable openCustomer;private Runnable openMerchant;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinearLayout bubble,mediaBox;private final TextView heading,message,badge;
    private final TripBubbleMedia media;
    private DriverUiScope scope;private boolean registered,busy,nextMerchant;
    private TripBubbleStore.Message current;private String identity="",shown="";
    private final ArrayList<String> pages=new ArrayList<>();private int page;private long pageAt,lastBounce;
    TripMapChatPreview(Activity activity,FrameLayout mapLayer,Supplier<JSONObject> order,Supplier<PointF> customer,
                       IntSupplier top,IntSupplier bottom,Runnable open){
        this.activity=activity;this.mapLayer=mapLayer;this.order=order;customerPin=customer;visibleTop=top;visibleBottom=bottom;openCustomer=open;
        bubble=new LinearLayout(activity);bubble.setOrientation(LinearLayout.VERTICAL);bubble.setPadding(dp(11),dp(8),dp(11),dp(8));bubble.setElevation(dp(6));
        int surface=DriverThemeTokens.surface(activity);
        android.graphics.drawable.GradientDrawable glass=DriverNavigationUi.roundRect(Color.argb(178,Color.red(surface),Color.green(surface),Color.blue(surface)),dp(17));glass.setStroke(dp(1),DriverThemeTokens.border(activity));bubble.setBackground(glass);
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        heading=new TextView(activity);heading.setTextSize(11);heading.setTypeface(null,Typeface.BOLD);heading.setTextColor(DriverThemeTokens.accent(activity));row.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        badge=new TextView(activity);badge.setTextSize(10);badge.setTextColor(DriverThemeTokens.textSecondary(activity));row.addView(badge);
        TextView close=new TextView(activity);
        close.setText("×");close.setGravity(Gravity.CENTER);close.setTextSize(20);
        close.setTextColor(DriverThemeTokens.textPrimary(activity));close.setContentDescription("Tutup bubble pesan ini");
        close.setOnClickListener(v->dismissCurrent());
        row.addView(close,new LinearLayout.LayoutParams(dp(48),dp(40)));
        bubble.addView(row);
        message=new TextView(activity);message.setTextSize(14);message.setMaxLines(3);message.setTextColor(DriverThemeTokens.textPrimary(activity));message.setPadding(0,dp(3),0,0);bubble.addView(message,new LinearLayout.LayoutParams(-1,-2));
        mediaBox=new LinearLayout(activity);mediaBox.setOrientation(LinearLayout.VERTICAL);bubble.addView(mediaBox,new LinearLayout.LayoutParams(-1,-2));media=new TripBubbleMedia(activity,mediaBox);
        mapLayer.addView(bubble,new FrameLayout.LayoutParams(dp(216),-2,Gravity.TOP|Gravity.LEFT));bubble.setVisibility(View.INVISIBLE);
        bubble.setOnClickListener(v->openRoom());
    }
    TripMapChatPreview merchant(Supplier<PointF> point,Runnable open){merchantPin=point;openMerchant=open;return this;}
    private void acknowledge(){if(current!=null){TripBubbleStore.ack(activity,order.get(),current);current=TripBubbleStore.latest(activity,order.get());bubble.setScaleX(1);bubble.setScaleY(1);}}
    private void openRoom(){if(scope==null||!scope.alive()||current==null)return;boolean merchant=current.merchant;dismissCurrent();if(merchant&&openMerchant!=null)openMerchant.run();else openCustomer.run();}
    private void dismissCurrent(){
        if(current==null)return;
        TripBubbleStore.dismiss(activity,order.get(),current);
        current=TripBubbleStore.latest(activity,order.get());
        bubble.animate().cancel();
        bubble.setVisibility(View.INVISIBLE);
        media.close();
    }
    void start(){
        if(scope!=null)return;scope=new DriverUiScope(activity);
        try{ContextCompat.registerReceiver(activity,updates,new IntentFilter(TransivaFirebaseService.ACTION_DRIVER_DATA_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED);registered=true;}catch(RuntimeException e){NavigationDiagnostics.error(activity,"MAP_CHAT_RECEIVER_FAILED",e);}
        shown="";restore();main.post(poll);main.post(position);
    }
    void stop(){
        if(scope!=null)scope.close();scope=null;busy=false;
        if(registered){try{activity.unregisterReceiver(updates);}catch(IllegalArgumentException ignored){}}registered=false;
        main.removeCallbacksAndMessages(null);media.close();bubble.animate().cancel();message.animate().cancel();bubble.setScaleX(1);bubble.setScaleY(1);bubble.setRotation(0);bubble.setVisibility(View.INVISIBLE);shown="";
    }
    private final BroadcastReceiver updates=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        String type=String.valueOf(intent.getStringExtra("type")).toLowerCase(Locale.US);
        if((!type.contains("chat")&&!type.contains("message"))||type.contains("global")||type.contains("mention"))return;
        if(type.contains("merchant")&&(!type.equals("merchant_driver_chat")||merchantPin==null))return;
        JSONObject o=order.get();String id=intent.getStringExtra("order_id");
        if(o!=null&&(NavigationTargetPolicy.id(o).equalsIgnoreCase(id)||o.optString("id").equals(id))){nextMerchant=type.equals("merchant_driver_chat");if(!busy){main.removeCallbacks(poll);main.postDelayed(poll,180);}}
    }};
    private final Runnable poll=this::fetch;
    private void fetch(){
        DriverUiScope request=scope;JSONObject o=order.get();
        if(request==null||!request.alive()||busy||o==null||NavigationTargetPolicy.terminal(o)||NavigationTargetPolicy.id(o).isEmpty())return;
        final boolean merchant=nextMerchant&&merchantPin!=null&&FoodTripPresentation.isFood(o);
        final String key=NavigationTargetPolicy.identity(o);
        String room=merchant?"ROOM-MD-"+NavigationTargetPolicy.id(o).toUpperCase(Locale.US).replaceAll("[^A-Z0-9-]",""):FoodTripPresentation.first(o.optString("room_id"),o.optString("chat_room_id"),"ROOM-"+NavigationTargetPolicy.id(o));
        String url=android.net.Uri.parse("https://transiva.my.id/server/"+(merchant?"getMerchantDriverChat.php":"getChat.php")).buildUpon()
                .appendQueryParameter("order_id",NavigationTargetPolicy.id(o)).appendQueryParameter("order_db_id",o.optString("id"))
                .appendQueryParameter("source",NavigationTargetPolicy.source(o)).appendQueryParameter("room_id",room)
                .appendQueryParameter("chat_ui","1").appendQueryParameter("page_mode","latest").appendQueryParameter("limit","24").build().toString();
        busy=true;
        if(!request.execute(()->{try{JSONObject response=DriverMessageApi.get(new SessionManager(activity),url,request.owner);request.post(()->delivered(request,key,merchant,response));}
            catch(Exception e){request.post(()->delivered(request,key,merchant,null));}}))delivered(request,key,merchant,null);
    }
    private void delivered(DriverUiScope request,String key,boolean merchant,JSONObject result){
        if(scope!=request)return;busy=false;
        if(key.equals(NavigationTargetPolicy.identity(order.get()))&&result!=null&&result.optBoolean("success")&&"driver".equalsIgnoreCase(result.optString("viewer_type","driver"))){
            JSONArray rows=result.optJSONArray("messages");if(rows!=null)for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)continue;
                if("driver".equalsIgnoreCase(row.optString("sender_type"))){
                    TripBubbleStore.Message previous=TripBubbleStore.get(activity,order.get(),merchant);
                    if(previous!=null&&row.optLong("id")>previous.id)TripBubbleStore.ack(activity,order.get(),previous);
                    continue;
                }
                if(!"customer".equalsIgnoreCase(row.optString("sender_type")))continue;
                boolean read=row.optBoolean("is_read")||row.optInt("is_read")!=0;
                TripBubbleStore.offer(activity,order.get(),row.optLong("id"),row.optString("message"),merchant,read);
            }
        }
        restore();nextMerchant=!merchant;main.removeCallbacks(poll);
        main.postDelayed(poll,!merchant&&merchantPin!=null&&FoodTripPresentation.isFood(order.get())?250:7000);
    }
    private void restore(){
        if(scope==null||!scope.alive())return;
        String id=NavigationTargetPolicy.identity(order.get());
        if(!identity.equals(id)){identity=id;shown="";media.close();}
        current=TripBubbleStore.latest(activity,order.get());
        if(current==null||current.dismissed||NavigationTargetPolicy.terminal(order.get())){bubble.setVisibility(View.INVISIBLE);media.close();return;}
        if(shown.equals(current.key()))return;
        bubble.animate().cancel();
        bubble.setVisibility(View.INVISIBLE);
        bubble.setRotation(0f);
        shown=current.key();page=0;pageAt=SystemClock.elapsedRealtime();lastBounce=pageAt;
        heading.setText(current.merchant?"●  Pesan merchant":"●  Pesan pelanggan");pages.clear();media.close();
        String raw=current.raw;
        if(TripBubbleContent.image(raw)||TripBubbleContent.voice(raw)){
            message.setVisibility(View.GONE);mediaBox.setVisibility(View.VISIBLE);badge.setText(TripBubbleContent.image(raw)?"Foto":"Suara");
            media.show(raw,this::acknowledge,this::openRoom);
        }else{
            message.setVisibility(View.VISIBLE);mediaBox.setVisibility(View.GONE);
            String text=TripBubbleContent.text(raw);
            int width=Math.max(dp(104),bubble.getLayoutParams().width-dp(22));
            StaticLayout layout=StaticLayout.Builder.obtain(text,0,text.length(),message.getPaint(),width).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            for(int line=0;line<layout.getLineCount();line+=3){int end=Math.min(line+2,layout.getLineCount()-1);pages.add(text.substring(layout.getLineStart(line),layout.getLineEnd(end)).trim());}
            showPage();
        }
        bubble.setContentDescription((current.merchant?"Pesan merchant. ":"Pesan pelanggan. ")+(TripBubbleContent.image(raw)?"Gambar":TripBubbleContent.voice(raw)?"Pesan suara":raw)+". Ketuk untuk membuka chat.");
    }
    private void showPage(){if(pages.isEmpty())return;message.setText(pages.get(page));badge.setText(pages.size()>1?(page+1)+"/"+pages.size():"");pageAt=SystemClock.elapsedRealtime();}
    private final Runnable position=new Runnable(){@Override public void run(){
        if(scope==null||!scope.alive())return;restore();
        if(current!=null&&!current.dismissed&&!NavigationTargetPolicy.terminal(order.get())){
            PointF point=current.merchant&&merchantPin!=null?merchantPin.get():customerPin.get();
            int top=Math.max(dp(6),visibleTop.getAsInt()+dp(6)),bottom=Math.min(mapLayer.getHeight(),visibleBottom.getAsInt())-dp(6);
            int width=Math.min(dp(218),Math.max(dp(100),mapLayer.getWidth()-dp(18)));
            if(bubble.getLayoutParams().width!=width){bubble.getLayoutParams().width=width;bubble.requestLayout();shown="";}
            media.fit(bottom-top-dp(58));
            int height=bubble.getHeight();boolean fits=height>0&&top+height<=bottom;
            if(fits){
                boolean onMap=point!=null&&point.x>=0&&point.x<=mapLayer.getWidth()&&point.y>=top&&point.y<=bottom;
                float x=onMap?point.x-width/2f:mapLayer.getWidth()-width-dp(9),y=top;
                if(onMap){float above=point.y-height-dp(30);if(above>=top)y=above;else if(point.y+height+dp(10)<=bottom)y=point.y+dp(10);else x=point.x<mapLayer.getWidth()/2f?mapLayer.getWidth()-width-dp(9):dp(9);}
                bubble.setTranslationX(Math.max(dp(8),Math.min(x,mapLayer.getWidth()-width-dp(8))));bubble.setTranslationY(y);
                if(bubble.getVisibility()!=View.VISIBLE){
                    bubble.setVisibility(View.VISIBLE);bubble.setPivotX(width/2f);bubble.setPivotY(height);
                    if (Build.VERSION.SDK_INT<26 || android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        bubble.setScaleX(.28f);bubble.setScaleY(.28f);bubble.setAlpha(0);
                        bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(245)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                    } else {
                        bubble.setScaleX(1f);bubble.setScaleY(1f);bubble.setAlpha(1f);
                    }
                    pageAt=SystemClock.elapsedRealtime();
                }
                long now=SystemClock.elapsedRealtime();
                if(pages.size()>1&&now-pageAt>=TripChatDisplayStore.duration(pages.get(page))){page=(page+1)%pages.size();showPage();}
                if(!current.ack&&!media.busy()&&System.currentTimeMillis()-current.at>=15000&&now-lastBounce>=12000&&(Build.VERSION.SDK_INT<26 || android.animation.ValueAnimator.areAnimatorsEnabled())){
                    lastBounce=now;bubble.animate().rotation(-2).scaleX(1.035f).scaleY(1.035f).setDuration(160).withEndAction(()->{if(scope!=null)bubble.animate().rotation(0).scaleX(1).scaleY(1).setDuration(180).start();}).start();
                }
            }else bubble.setVisibility(View.INVISIBLE);
        }
        main.postDelayed(this,200);
    }};
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
