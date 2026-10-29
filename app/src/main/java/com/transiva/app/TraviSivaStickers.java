package com.transiva.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.*;

/** Versioned built-in catalogue. Wire payloads contain no remote URL or uploaded media. */
public final class TraviSivaStickers {
    private TraviSivaStickers() {}
    public static final String PREFIX = "[[STICKER]]";
    private static final int[] ART = {R.drawable.ts_01,R.drawable.ts_02,R.drawable.ts_03,R.drawable.ts_04,R.drawable.ts_05,R.drawable.ts_06,R.drawable.ts_07,R.drawable.ts_08,R.drawable.ts_09,R.drawable.ts_10,R.drawable.ts_11,R.drawable.ts_12,R.drawable.ts_13,R.drawable.ts_14,R.drawable.ts_15,R.drawable.ts_16,R.drawable.ts_17,R.drawable.ts_18,R.drawable.ts_19,R.drawable.ts_20,R.drawable.ts_21,R.drawable.ts_22,R.drawable.ts_23,R.drawable.ts_24,R.drawable.ts_25,R.drawable.ts_26,R.drawable.ts_27,R.drawable.ts_28,R.drawable.ts_29,R.drawable.ts_30,R.drawable.ts_31,R.drawable.ts_32,R.drawable.ts_33,R.drawable.ts_34,R.drawable.ts_35,R.drawable.ts_36,R.drawable.ts_37,R.drawable.ts_38,R.drawable.ts_39,R.drawable.ts_40,R.drawable.ts_41,R.drawable.ts_42,R.drawable.ts_43,R.drawable.ts_44,R.drawable.ts_45,R.drawable.ts_46,R.drawable.ts_47,R.drawable.ts_48};
    private static final String[] LABELS = {"Hai, aku Travi!","Hai, aku Siva!","Salam kenal!","Teman barumu","Siap bantu!","Yuk, pesan!","Makan bareng!","Kompak terus!","Hihihi!","Gemes deh!","Peluk dulu!","Gemas bareng!","Lapar nih","Mantap!","Buat kamu","Malu ah","Ngakak!","Gaskeun!","Peluk dulu","Ya ampun!","Terima kasih","Santai dulu","Kangen nih","Oke deh!","Pagi!","Ngantuk...","Semangat!","Ngopi yuk","Serius?!","Tunggu ya","Hehehe","Sedih nih","Bisa dong!","Beres!","Ih, kesel!","Selamat malam","Halo!","Siap!","Hai!","Makasih","OTW","Sudah sampai!","Pesan dulu","Yuk, makan!","Wkwkwk","Maaf ya","Sayang kamu","Asiiik!"};
    public static int index(String value) {
        if (value == null || !value.startsWith(PREFIX)) return -1;
        String id = value.substring(PREFIX.length());
        for (int i=0;i<ART.length;i++) if (id.equals(id(i))) return i;
        return -1;
    }
    private static String id(int i) { return String.format(java.util.Locale.US,"ts_v1_%02d",i+1); }
    public static String preview(String value) {
        int i=index(value);
        return i>=0 ? "🐻 " + LABELS[i] : "🐻 Stiker Travi & Siva";
    }
    private static int dp(Activity a, int n) { return Math.round(n*a.getResources().getDisplayMetrics().density); }
    private static TextView label(Activity a,String text,int size) {
        TextView t=new TextView(a);t.setText(text);t.setTextSize(size);t.setTextColor(Color.rgb(33,66,113));return t;
    }
    private static GradientDrawable background() {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffeef6ff,0xfffff2f7});
        d.setCornerRadius(18);return d;
    }
    public static View launcher(Activity a, Runnable open) {
        LinearLayout row=new LinearLayout(a);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a,10),0,dp(a,10),0);row.setBackground(background());
        ImageView icon=new ImageView(a);icon.setImageResource(ART[0]);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon,new LinearLayout.LayoutParams(dp(a,42),dp(a,42)));
        TextView title=label(a,"Travi & Siva  ·  Kirim stiker ♡",12);
        row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(label(a,"＋",22));row.setContentDescription("Buka paket stiker Travi dan Siva");
        row.setFocusable(true);row.setOnClickListener(v->open.run());return row;
    }
    public interface Sender { void send(String payload); }
    public static Dialog show(Activity a, Sender sender) {
        Dialog dialog=new Dialog(a);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(a,12),dp(a,12),dp(a,12),dp(a,10));body.setBackground(background());
        LinearLayout heading=new LinearLayout(a);heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label(a,"Travi & Siva ♡",18),new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(a);close.setText("Tutup");close.setAllCaps(false);close.setOnClickListener(v->dialog.dismiss());heading.addView(close);body.addView(heading);
        final int[] chosen={-1};
        LinearLayout footer=new LinearLayout(a);footer.setGravity(Gravity.CENTER_VERTICAL);
        ImageView selected=new ImageView(a);selected.setScaleType(ImageView.ScaleType.FIT_CENTER);
        footer.addView(selected,new LinearLayout.LayoutParams(dp(a,64),dp(a,64)));
        TextView caption=label(a,"Pilih stiker kesukaanmu",12);footer.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
        Button send=new Button(a);send.setAllCaps(false);send.setText("Kirim");send.setEnabled(false);footer.addView(send);
        ScrollView scroll=new ScrollView(a);LinearLayout grid=new LinearLayout(a);grid.setOrientation(LinearLayout.VERTICAL);scroll.addView(grid);
        Runnable[] refresh={null};final int[] pack={0};
        refresh[0]=()->{
            grid.removeAllViews();scroll.scrollTo(0,0);
            for(int r=0;r<4;r++) {
                LinearLayout row=new LinearLayout(a);
                for(int c=0;c<3;c++) {
                    final int i=pack[0]*12+r*3+c;
                    LinearLayout cell=new LinearLayout(a);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER);
                    ImageView image=new ImageView(a);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageResource(ART[i]);image.setContentDescription(LABELS[i]);
                    cell.addView(image,new LinearLayout.LayoutParams(-1,dp(a,86)));
                    TextView text=label(a,LABELS[i],10);text.setGravity(Gravity.CENTER);cell.addView(text);
                    cell.setPadding(0,dp(a,3),0,dp(a,7));cell.setFocusable(true);cell.setContentDescription("Pilih stiker: "+LABELS[i]);
                    cell.setOnClickListener(v->{chosen[0]=i;selected.setImageResource(ART[i]);caption.setText(LABELS[i]);send.setEnabled(true);});
                    row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
                }
                grid.addView(row);
            }
        };
        LinearLayout tabs=new LinearLayout(a);
        String[] names={"Kenalan","Ekspresi","Harian","Perjalanan"};
        for(int p=0;p<4;p++){final int k=p;Button tab=new Button(a);tab.setText(names[p]);tab.setTextSize(10);tab.setPadding(0,0,0,0);tab.setMinWidth(0);tab.setMinimumWidth(0);tab.setAllCaps(false);tab.setOnClickListener(v->{pack[0]=k;refresh[0].run();});tabs.addView(tab,new LinearLayout.LayoutParams(0,dp(a,44),1));}
        body.addView(tabs);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body.addView(footer);
        send.setOnClickListener(v->{if(chosen[0]>=0){String wire=PREFIX+id(chosen[0]);dialog.dismiss();sender.send(wire);}});
        refresh[0].run();dialog.setContentView(body);dialog.setOwnerActivity(a);dialog.show();
        Window w=dialog.getWindow();if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setGravity(Gravity.BOTTOM);w.setLayout(-1,Math.min(dp(a,580),(int)(a.getResources().getDisplayMetrics().heightPixels*.78f)));}
        return dialog;
    }
    public static View bubble(Activity a,String wire) {
        int i=index(wire);
        if(i<0)return label(a,"🐻 Stiker belum tersedia. Perbarui aplikasi ya.",12);
        StickerView v=new StickerView(a);v.setImageResource(ART[i]);v.setContentDescription("Stiker: "+LABELS[i]+". Ketuk untuk bergerak lagi");
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8));
        int side=Math.min(dp(a,180),(int)(a.getResources().getDisplayMetrics().widthPixels*.55f));
        v.setLayoutParams(new LinearLayout.LayoutParams(side,side));v.setOnClickListener(view->v.play());return v;
    }
    /** Short native motion, no recurring handlers or playback while activity is hidden. */
    private static final class StickerView extends ImageView {
        private long started;
        private final Rect visible=new Rect();
        StickerView(Activity a){super(a);}
        void play(){started=SystemClock.uptimeMillis();invalidate();}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();play();}
        @Override protected void onDetachedFromWindow(){started=0;super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)started=0;}
        @Override protected void onDraw(Canvas canvas){
            long elapsed=started==0?2400:SystemClock.uptimeMillis()-started;
            boolean motion=elapsed<2400 && hasWindowFocus() && getGlobalVisibleRect(visible);
            if(android.provider.Settings.Global.getFloat(getContext().getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)==0f)motion=false;
            int save=canvas.save();
            if(motion){float wave=(float)Math.sin(elapsed*Math.PI/600);canvas.rotate(wave*3,getWidth()/2f,getHeight()/2f);float scale=1f+.025f*wave;canvas.scale(scale,scale,getWidth()/2f,getHeight()/2f);}
            super.onDraw(canvas);canvas.restoreToCount(save);
            if(motion)postInvalidateDelayed(50);
        }
    }
}
