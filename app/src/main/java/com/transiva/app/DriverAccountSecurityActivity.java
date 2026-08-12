package com.transiva.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DriverAccountSecurityActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_USERNAME = "username";
    public static final String MODE_PASSWORD = "password";
    private static final String URL_ACCOUNT = "https://transiva.my.id/server/driver_account_security.php";

    private SessionManager session;
    private String mode;
    private EditText currentPassword, value1, value2;
    private TextView message;
    private Button save;
    private boolean loading;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        session = new SessionManager(this);
        mode = getIntent() == null ? MODE_PASSWORD : getIntent().getStringExtra(EXTRA_MODE);
        if (!MODE_USERNAME.equals(mode)) mode = MODE_PASSWORD;
        if (!session.isLoggedIn() || safe(session.getToken()).isEmpty()) {
            startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish(); return;
        }
        setContentView(build());
        DriverAppSettings.apply(this);
    }

    private ScrollView build() {
        boolean usernameMode = MODE_USERNAME.equals(mode);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.parseColor("#F5F8FD"));
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(22),dp(20),dp(30)); scroll.addView(root);

        TextView back=text("‹  Kembali",14,"#0B7CFF",true); back.setPadding(0,dp(6),0,dp(16)); back.setOnClickListener(v->finish()); root.addView(back);
        root.addView(text(usernameMode?"Ubah Username":"Ubah Password",26,"#0B3A78",true));
        TextView sub=text(usernameMode
                ?"Username dipakai pada identitas Driver. Password saat ini wajib untuk mengonfirmasi perubahan."
                :"Gunakan password baru yang kuat. Setelah berhasil, sesi Driver di perangkat lain akan dicabut.",
                12,"#64748B",false); sub.setPadding(0,dp(5),0,dp(18)); root.addView(sub);

        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(17),dp(18),dp(17),dp(18)); card.setBackground(round("#FFFFFF",20)); root.addView(card);

        message=text("",12,"#B91C1C",true); message.setVisibility(TextView.GONE); card.addView(message);

        currentPassword=input("Password saat ini",true); card.addView(currentPassword,lp());
        if(usernameMode) {
            value1=input("Username baru",false); value1.setText(session.getUsername()); card.addView(value1,lp());
            value2=null;
        } else {
            value1=input("Password baru (min. 8 karakter)",true); card.addView(value1,lp());
            value2=input("Konfirmasi password baru",true); card.addView(value2,lp());
        }

        save=new Button(this); save.setAllCaps(false); save.setText(usernameMode?"Simpan Username":"Simpan Password");
        save.setTextColor(Color.WHITE); save.setTypeface(Typeface.DEFAULT,Typeface.BOLD); save.setTextSize(15);
        save.setBackground(round("#0B7CFF",15)); save.setOnClickListener(v->submit());
        card.addView(save,new LinearLayout.LayoutParams(-1,dp(54)));
        return scroll;
    }

    private void submit() {
        if(loading)return;
        String password=safe(currentPassword.getText().toString());
        if(password.isEmpty()){show("Masukkan password saat ini.",false);return;}

        JSONObject payload=new JSONObject();
        try {
            payload.put("action", MODE_USERNAME.equals(mode)?"change_username":"change_password");
            payload.put("current_password",password);
            if(MODE_USERNAME.equals(mode)){
                String u=safe(value1.getText().toString());
                if(!u.matches("[A-Za-z0-9._-]{4,32}")){show("Username harus 4–32 karakter dan hanya boleh huruf, angka, titik, _ atau -.",false);return;}
                if(u.equalsIgnoreCase(session.getUsername())){show("Username baru masih sama.",false);return;}
                payload.put("new_username",u);
            } else {
                String a=value1.getText().toString(), b=value2.getText().toString();
                if(a.length()<8){show("Password baru minimal 8 karakter.",false);return;}
                if(!a.equals(b)){show("Konfirmasi password tidak sama.",false);return;}
                if(a.equals(password)){show("Password baru harus berbeda.",false);return;}
                payload.put("new_password",a);
            }
        } catch(Exception e){return;}

        loading=true; save.setEnabled(false);
        boolean accepted=DriverNetworkExecutor.execute(()->{
            Result r=request(payload);
            runOnUiThread(()->{
                loading=false; save.setEnabled(true); show(r.message,r.ok);
                if(r.ok){
                    currentPassword.setText("");
                    if(MODE_USERNAME.equals(mode)){
                        String changed=r.username;
                        if(!changed.isEmpty()) session.put("username",changed);
                    }else{
                        value1.setText(""); value2.setText("");
                    }
                }
            });
        });
        if(!accepted){loading=false;save.setEnabled(true);show("Aplikasi sedang sibuk. Coba lagi beberapa detik.",false);}
    }

    private Result request(JSONObject payload) {
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new URL(URL_ACCOUNT).openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setDoOutput(true); c.setUseCaches(false);
            c.setRequestProperty("Accept","application/json"); c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            c.setRequestProperty("Authorization","Bearer "+safe(session.getToken()));
            c.setRequestProperty("X-Device-UUID",DeviceIdentityManager.getInstallationUuid(this));
            c.setRequestProperty("X-App-Scope","driver");
            try(BufferedWriter w=new BufferedWriter(new OutputStreamWriter(c.getOutputStream(),StandardCharsets.UTF_8))){w.write(payload.toString());}
            int status=c.getResponseCode(); InputStream is=status>=200&&status<400?c.getInputStream():c.getErrorStream();
            String raw=read(is); JSONObject j=raw.isEmpty()?new JSONObject():new JSONObject(raw);
            boolean ok=status>=200&&status<300&&j.optBoolean("success",false);
            return new Result(ok,j.optString("message",ok?"Berhasil disimpan.":"Gagal menyimpan."),j.optString("username",""));
        }catch(Exception e){return new Result(false,"Tidak dapat terhubung ke server.","");}
        finally{if(c!=null)c.disconnect();}
    }

    private String read(InputStream is)throws Exception{
        if(is==null)return ""; StringBuilder o=new StringBuilder();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)o.append(line);}
        return o.toString();
    }
    private void show(String m,boolean ok){message.setVisibility(TextView.VISIBLE);message.setText(m);message.setTextColor(Color.parseColor(ok?"#166534":"#B91C1C"));message.setPadding(dp(10),dp(9),dp(10),dp(9));}
    private EditText input(String hint,boolean password){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);e.setTextSize(15);e.setPadding(dp(12),0,dp(12),0);e.setBackground(round("#F7FAFE",13));if(password)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);return e;}
    private LinearLayout.LayoutParams lp(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.setMargins(0,0,0,dp(13));return p;}
    private TextView text(String v,int sp,String color,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(sp);t.setTextColor(Color.parseColor(color));if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable round(String color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(Color.parseColor(color));d.setCornerRadius(dp(radius));return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private String safe(String v){return v==null?"":v.trim();}
    private static final class Result{final boolean ok;final String message,username;Result(boolean o,String m,String u){ok=o;message=m;username=u;}}
}
