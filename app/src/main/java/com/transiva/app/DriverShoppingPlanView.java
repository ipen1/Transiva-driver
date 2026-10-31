package com.transiva.app;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.json.JSONArray;
public final class DriverShoppingPlanView {
 private DriverShoppingPlanView(){}
 public static JSONObject plan(JSONObject order){if(order==null)return null;try{JSONObject p=order.optJSONObject("shopping_plan");if(p==null){JSONObject n=new JSONObject(order.optString("note","{}"));p=n.optJSONObject("shopping_plan");}return p!=null&&p.optInt("version")==1?p:null;}catch(Exception e){return null;}}
 private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 private static void point(Context c,LinearLayout box,String title,JSONObject data,boolean compact){if(data==null)return;TextView t=new TextView(c);t.setText(title);t.setTextSize(12);t.setTypeface(null,Typeface.BOLD);t.setTextColor(DriverThemeTokens.accent(c));t.setPadding(0,dp(c,9),0,dp(c,3));box.addView(t);TextView a=new TextView(c);a.setText(data.optString("address"));a.setTextColor(DriverThemeTokens.textSecondary(c));a.setTextSize(11);box.addView(a);TextView n=new TextView(c);n.setText(data.optString("note"));n.setTextSize(13);n.setTextColor(DriverThemeTokens.textPrimary(c));n.setPadding(0,dp(c,4),0,0);if(compact){n.setMaxLines(3);n.setEllipsize(android.text.TextUtils.TruncateAt.END);}box.addView(n);}
 public static LinearLayout view(Context c,JSONObject p,boolean compact){LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,12));GradientDrawable bg=new GradientDrawable();bg.setColor(DriverThemeTokens.surfaceAlt(c));bg.setCornerRadius(dp(c,14));box.setBackground(bg);TextView h=new TextView(c);h.setText("DAFTAR BELANJA PER TITIK");h.setTextSize(11);h.setTypeface(null,Typeface.BOLD);h.setTextColor(DriverThemeTokens.textSecondary(c));box.addView(h);point(c,box,"Toko pertama",p.optJSONObject("pickup"),compact);JSONArray stops=p.optJSONArray("stops");if(stops!=null)for(int i=0;i<stops.length();i++)point(c,box,"Singgah "+(i+1),stops.optJSONObject(i),compact);if(compact){TextView more=new TextView(c);more.setText("Ketuk untuk membaca daftar lengkap ›");more.setTextSize(11);more.setMinHeight(dp(c,44));more.setGravity(android.view.Gravity.CENTER_VERTICAL);more.setTextColor(DriverThemeTokens.accent(c));box.addView(more);box.setOnClickListener(v->{android.widget.ScrollView scroll=new android.widget.ScrollView(c);scroll.addView(view(c,p,false));new android.app.AlertDialog.Builder(c).setTitle("Daftar belanja").setView(scroll).setPositiveButton("Tutup",null).show();});}return box;}
}
