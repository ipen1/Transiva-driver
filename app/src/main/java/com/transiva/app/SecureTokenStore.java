package com.transiva.app;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
/** Auth secrets are persisted only as AES-GCM ciphertext. No rewrite on ordinary reads. */
public final class SecureTokenStore {
    private static final String ALIAS="transiva_driver_session_key",PREF="transiva_driver_secure",CIPHER="token_cipher",IV="token_iv";
    private static final String[] NAMES={"token","access_token","auth_token","api_token","session_token"};
    private static final String[] JSON_KEYS={"raw_user","raw_session","user_json"};
    private static boolean cleaned;
    private SecureTokenStore(){}
    public static synchronized boolean put(Context c,String token){
        if(c==null)return false;String value=token==null?"":token.trim();if(value.isEmpty()){clear(c);return true;}
        try{
            Cipher x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.ENCRYPT_MODE,key(true));
            String cipher=Base64.encodeToString(x.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
            boolean saved=c.getSharedPreferences(PREF,0).edit().putString(CIPHER,cipher).putString(IV,Base64.encodeToString(x.getIV(),Base64.NO_WRAP)).remove("token").commit();
            if(saved){cleaned=cleanLegacy(c);}return saved;
        }catch(Exception failure){android.util.Log.w("TransivaAuth","AUTH_ENCRYPT_FAILED");return false;}
    }
    public static synchronized String get(Context c){
        if(c==null)return "";
        SharedPreferences p=c.getSharedPreferences(PREF,0);String encrypted=p.getString(CIPHER,"");
        if(encrypted.isEmpty()){
            String packed=p.getString("token","");int colon=packed.indexOf(':');
            if(colon>0){
                String oldIv=packed.substring(0,colon);encrypted=packed.substring(colon+1);
                if(!p.edit().putString(CIPHER,encrypted).putString(IV,oldIv).remove("token").commit())return "";
            }
        }
        if(!encrypted.isEmpty()){
            try{
                Cipher x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,Base64.decode(p.getString(IV,""),Base64.NO_WRAP)));
                String value=new String(x.doFinal(Base64.decode(encrypted,Base64.NO_WRAP)),StandardCharsets.UTF_8).trim();
                if(!cleaned)cleaned=cleanLegacy(c);return value;
            }catch(Exception unavailable){android.util.Log.w("TransivaAuth","AUTH_DECRYPT_UNAVAILABLE");return "";}
        }
        // Upgrade existing installs. If encryption fails, remove plaintext and require a fresh login.
        for(String pref:new String[]{"transiva_native_session","transiva"}){
            SharedPreferences old=c.getSharedPreferences(pref,0);
            for(String k:NAMES){String v=old.getString(k,"");if(v!=null&&!v.trim().isEmpty())return migrate(c,v);}
            for(String k:JSON_KEYS){String v=TokenJsonSanitizer.extract(old.getString(k,"{}"));if(!v.isEmpty())return migrate(c,v);}
        }
        return "";
    }
    private static String migrate(Context c,String value){
        if(put(c,value))return value.trim();
        cleanLegacy(c);android.util.Log.w("TransivaAuth","AUTH_MIGRATION_RELOGIN_REQUIRED");return "";
    }
    public static void migrateLegacy(Context c){get(c);}
    public static String read(Context c){return get(c);}
    public static void save(Context c,String token){put(c,token);}
    public static synchronized void clear(Context c){
        if(c==null)return;cleaned=false;
        c.getSharedPreferences(PREF,0).edit().clear().commit();cleanLegacy(c);
    }
    private static boolean cleanLegacy(Context c){
        boolean ok=true;
        for(String pref:new String[]{"transiva_native_session","transiva"}){
            SharedPreferences old=c.getSharedPreferences(pref,0);SharedPreferences.Editor e=old.edit();
            for(String k:NAMES)e.remove(k);
            for(String k:JSON_KEYS)if(old.contains(k))e.putString(k,TokenJsonSanitizer.cleanString(old.getString(k,"{}")));
            ok=e.commit()&&ok;
        }return ok;
    }
    private static SecretKey key(boolean create)throws Exception{
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);java.security.Key existing=ks.getKey(ALIAS,null);
        if(existing instanceof SecretKey)return (SecretKey)existing;
        if(!create)throw new IllegalStateException("KEY_UNAVAILABLE");
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return g.generateKey();
    }
}
