package com.transiva.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted durable intent. Only acknowledged terminal receipts may remove it. All I/O is off UI. */
final class DriverWalletJournal {
    private static final String ALIAS="transiva_driver_wallet_journal_v1";
    private final AtomicFile file;
    private final String owner,action;
    DriverWalletJournal(Context c,String owner,String action) {
        this.owner=owner;this.action=action;
        if(!owner.matches("[0-9]+")||!action.matches("transfer|withdraw"))throw new IllegalArgumentException("Pemilik operasi tidak valid");
        file=new AtomicFile(new File(c.getNoBackupFilesDir(),"driver-wallet-"+owner+"-"+action+".json"));
    }
    private static synchronized SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }
    private byte[] aad(){return (owner+":"+action).getBytes(StandardCharsets.UTF_8);}
    synchronized JSONObject load() throws Exception {
        if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists())return null;
        JSONObject packed=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
        Cipher x=Cipher.getInstance("AES/GCM/NoPadding");
        x.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(packed.getString("iv"),Base64.NO_WRAP)));x.updateAAD(aad());
        JSONObject entry=new JSONObject(new String(x.doFinal(Base64.decode(packed.getString("cipher"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
        if(!owner.equals(entry.optString("owner"))||!action.equals(entry.optString("action")))throw new java.io.IOException("Pemilik journal berubah");
        return entry;
    }
    synchronized void save(JSONObject entry) throws Exception {
        Cipher x=Cipher.getInstance("AES/GCM/NoPadding");x.init(Cipher.ENCRYPT_MODE,key());x.updateAAD(aad());
        JSONObject packed=new JSONObject().put("iv",Base64.encodeToString(x.getIV(),Base64.NO_WRAP))
            .put("cipher",Base64.encodeToString(x.doFinal(entry.toString().getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
        FileOutputStream out=null;
        try{out=file.startWrite();out.write(packed.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception error){if(out!=null)file.failWrite(out);throw error;}
    }
    synchronized void acknowledge(String id) throws Exception {
        JSONObject e=load();if(e==null)return;
        JSONObject receipt=e.optJSONObject("receipt");
        if(!id.equals(e.optString("id"))||receipt==null||!receipt.optBoolean("terminal")
            ||!id.equals(receipt.optString("operation_id"))
            ||!("succeeded".equals(receipt.optString("operation_state"))||"rejected".equals(receipt.optString("operation_state"))))throw new java.io.IOException("Hasil operasi belum final");
        file.delete();if(file.getBaseFile().exists())throw new java.io.IOException("Hasil belum dapat ditutup");
    }
}
