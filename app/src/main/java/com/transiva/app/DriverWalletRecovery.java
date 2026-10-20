package com.transiva.app;

import android.app.Activity;
import org.json.JSONObject;

/** Recovery never sends money automatically. A status check only calls the read-only endpoint. */
final class DriverWalletRecovery {
    interface Host {
        void busy(boolean busy);
        void completed(JSONObject receipt);
        void retry(JSONObject immutablePayload);
    }
    private static final java.util.concurrent.ExecutorService IO=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Activity activity;
    private final DriverUiScope scope;
    private final String action;
    private final Host host;
    private final DriverWalletJournal journal;
    private boolean busy;
    DriverWalletRecovery(Activity a,DriverUiScope scope,String action,Host host){
        activity=a;this.scope=scope;this.action=action;this.host=host;
        journal=new DriverWalletJournal(a,scope.owner.userId(),action);
    }
    void restore(){if(!busy)run(null,true);}
    void submit(JSONObject payload){if(!busy)run(payload,false);}
    private void run(JSONObject proposed,boolean inspectOnly){
        if(!scope.alive()||busy)return;busy=true;host.busy(true);
        final String serialized=proposed==null?null:proposed.toString();
        IO.execute(()->{
            JSONObject entry=null;
            try {
                scope.owner.check();entry=journal.load();
                if(entry==null){
                    if(inspectOnly){scope.post(()->done());return;}
                    JSONObject payload=new JSONObject(serialized);
                    String id=payload.optString("request_id");if(id.isEmpty())throw new IllegalStateException("ID operasi kosong");
                    payload.put("operation_owner_id",scope.owner.userId());
                    entry=new JSONObject().put("owner",scope.owner.userId()).put("action",action).put("id",id).put("payload",payload);
                    journal.save(entry); // Durable BEFORE the first mutation can leave the device.
                } else if(!inspectOnly) {
                    JSONObject requested=new JSONObject(serialized).put("operation_owner_id",scope.owner.userId());
                    if(!samePayload(entry.getJSONObject("payload"),requested)){
                        final JSONObject pending=entry;scope.post(()->pending(pending,"Selesaikan operasi sebelumnya terlebih dahulu."));return;
                    }
                }
                JSONObject receipt=entry.optJSONObject("receipt");
                if(receipt==null) {
                    JSONObject response;
                    if(inspectOnly){
                        response=DriverWalletTransport.post(scope.owner,"driver_wallet_operation_status.php",new JSONObject()
                            .put("action",action).put("operation_id",entry.getString("id")).put("operation_owner_id",scope.owner.userId()));
                        receipt=response.optJSONObject("receipt");
                    } else {
                        response=DriverWalletTransport.post(scope.owner,"transfer".equals(action)?"driver_wallet_transfer.php":"requestDriverWithdraw.php",entry.getJSONObject("payload"));
                        receipt=response;
                    }
                    if(receipt==null||!receipt.optBoolean("terminal")||!entry.getString("id").equals(receipt.optString("operation_id"))){
                        final JSONObject pending=entry;scope.post(()->pending(pending,"Hasil belum terkonfirmasi. Periksa hasil sebelum membuat transaksi baru."));return;
                    }
                    String state=receipt.optString("operation_state");
                    if(!(receipt.optBoolean("success")&&"succeeded".equals(state))&&!("rejected".equals(state)&&!receipt.optBoolean("success")))
                        throw new IllegalStateException("Receipt belum final");
                    entry.put("receipt",receipt);journal.save(entry);
                }
                final JSONObject terminal=entry;scope.post(()->terminal(terminal));
            } catch(Exception error) {
                final JSONObject pending=entry;
                scope.post(()->{
                    if(pending!=null)pending(pending,"Koneksi atau hasil operasi belum terkonfirmasi.");
                    else {done();PremiumDialogs.builder(activity).setTitle("Operasi belum dapat dibuka").setMessage("Penyimpanan operasi belum dapat dibaca. Jangan mengulang pengajuan dari perangkat lain sebelum memeriksa riwayat atau menghubungi admin.").setPositiveButton("Tutup",null).show();}
                });
            }
        });
    }
    private boolean samePayload(JSONObject a,JSONObject b){
        if(a.length()!=b.length())return false;
        java.util.Iterator<String> keys=a.keys();while(keys.hasNext()){String k=keys.next();if(!String.valueOf(a.opt(k)).equals(String.valueOf(b.opt(k))))return false;}return true;
    }
    private void done(){busy=false;host.busy(false);}
    private void pending(JSONObject entry,String message){
        done();
        PremiumDialogs.builder(activity).setTitle("Operasi belum terkonfirmasi")
            .setMessage(message+"\n\nID: "+entry.optString("id")+"\nKirim ulang memakai ID dan data yang sama.")
            .setPositiveButton("Periksa hasil",(d,w)->restore())
            .setNeutralButton("Kirim ulang",(d,w)->{if(scope.alive())host.retry(entry.optJSONObject("payload"));})
            .setNegativeButton("Nanti",null).show();
    }
    private void terminal(JSONObject entry){
        done();busy=true;JSONObject receipt=entry.optJSONObject("receipt");
        host.busy(true); // Prevent creating a new intent before this result is acknowledged.
        PremiumDialogs.builder(activity).setTitle(receipt.optBoolean("success")?"Transaksi terkonfirmasi":"Pengajuan ditolak")
            .setMessage(receipt.optString("message","Hasil transaksi tersedia."))
            .setCancelable(false).setPositiveButton("Selesai",(d,w)->{
                busy=true;IO.execute(()->{try{
                    scope.owner.check();journal.acknowledge(entry.optString("id"));
                    scope.post(()->{done();host.completed(receipt);});
                }catch(Exception e){scope.post(()->{done();restore();});}});
            }).show();
    }
}
