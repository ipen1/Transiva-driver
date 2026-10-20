package com.transiva.app;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DriverWalletJournalTest {
    @Test public void encryptedPendingSurvivesRecreationAndRequiresTerminalAcknowledgement() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String owner="999999999999999",action="withdraw",id="JRN-"+java.util.UUID.randomUUID();
        File file=new File(context.getNoBackupFilesDir(),"driver-wallet-"+owner+"-"+action+".json");
        delete(file);
        try {
            DriverWalletJournal journal=new DriverWalletJournal(context,owner,action);
            JSONObject entry=new JSONObject().put("owner",owner).put("action",action).put("id",id)
                .put("payload",new JSONObject().put("account_number","BANK-SECRET-12345").put("amount",10000));
            journal.save(entry);
            String encrypted=new String(read(file),StandardCharsets.UTF_8);
            assertFalse(encrypted.contains("BANK-SECRET-12345"));assertFalse(encrypted.contains(id));
            DriverWalletJournal recreated=new DriverWalletJournal(context,owner,action);
            assertEquals(id,recreated.load().getString("id"));
            boolean blocked=false;try{recreated.acknowledge(id);}catch(Exception expected){blocked=true;}
            assertTrue(blocked);assertNotNull(recreated.load());
            entry.put("receipt",new JSONObject().put("terminal",true).put("operation_id",id).put("operation_state","succeeded"));
            recreated.save(entry);recreated.acknowledge(id);assertNull(recreated.load());
            journal.save(entry);
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write("corrupt".getBytes(StandardCharsets.UTF_8));}
            boolean corrupt=false;try{journal.load();}catch(Exception expected){corrupt=true;}
            assertTrue("Corruption must never become a fresh-operation permission",corrupt);
        } finally {delete(file);}
    }
    private static byte[] read(File f) throws Exception {
        try(java.io.FileInputStream in=new java.io.FileInputStream(f);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
        }
    }
    private static void delete(File f){f.delete();new File(f+".bak").delete();new File(f+".new").delete();}
}
