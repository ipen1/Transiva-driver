package com.transiva.app;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
/** Local brand accents; respects the app's light/dark surfaces outside the header. */
public final class DriverKdiUi {
    private DriverKdiUi() {}
    private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    private static TextView label(Context c,String value,int size,int color,boolean bold){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);
        if(bold)t.setTypeface(null,Typeface.BOLD);return t;
    }
    public static LinearLayout header(Context c,String title,String subtitle){
        LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(c,12),dp(c,10),dp(c,12),dp(c,10));
        GradientDrawable bg=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{Color.rgb(7,28,52),Color.rgb(11,63,119)});bg.setCornerRadius(dp(c,14));bg.setStroke(dp(c,1),Color.rgb(45,91,139));box.setBackground(bg);
        TextView brand=label(c,"TRANSIVA  ·  KARYA DIGITAL INDONESIA",8,Color.rgb(216,188,125),true);brand.setLetterSpacing(.06f);box.addView(brand);
        TextView name=label(c,title,18,Color.WHITE,true);name.setPadding(0,dp(c,5),0,dp(c,3));name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(name);
        if(subtitle!=null&&!subtitle.isEmpty()){TextView note=label(c,subtitle,11,Color.rgb(183,212,245),false);note.setMaxLines(1);note.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(note);}
        return box;
    }
    public static LinearLayout distances(Context c,String pickup,String journey){
        LinearLayout row=new LinearLayout(c);row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(c,10),dp(c,9),dp(c,10),dp(c,9));
        GradientDrawable bg=new GradientDrawable();bg.setColor(DriverThemeTokens.surfaceAlt(c));bg.setCornerRadius(dp(c,12));row.setBackground(bg);
        String[] names={"Ke titik jemput","Jarak perjalanan"},values={pickup,journey};
        for(int i=0;i<2;i++){LinearLayout cell=new LinearLayout(c);cell.setOrientation(LinearLayout.VERTICAL);cell.addView(label(c,names[i],10,DriverThemeTokens.textSecondary(c),false));TextView value=label(c,values[i],16,DriverThemeTokens.textPrimary(c),true);value.setPadding(0,dp(c,4),0,0);cell.addView(value);row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}
        return row;
    }
    public static TextView appreciation(Context c){
        TextView t=label(c,"100% biaya tunggu untuk Anda\nKarena kami menghargai waktu driver",11,DriverThemeTokens.textSecondary(c),false);
        t.setPadding(0,dp(c,8),0,dp(c,4));return t;
    }
}
