package com.transiva.app;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class DriverWalletTransport {
    private DriverWalletTransport(){}
    static JSONObject post(DriverSessionOwner owner,String endpoint,JSONObject payload) throws Exception {
        owner.check();HttpURLConnection c=com.transiva.app.driver.data.DriverConnectionRepository.open(DriverApiConfig.endpoint(endpoint));
        try {
            c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=UTF-8");c.setRequestProperty("Accept","application/json");owner.pin(c);
            String id=payload.optString("request_id");if(!id.isEmpty())c.setRequestProperty("Idempotency-Key",id);
            byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){owner.check();out.write(bytes);}
            int status=c.getResponseCode();InputStream stream=status>=200&&status<300?c.getInputStream():c.getErrorStream();
            if(stream==null)throw new java.io.IOException("Respons server kosong");
            try(InputStream in=stream;ByteArrayOutputStream body=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){owner.check();if(body.size()+n>524288)throw new java.io.IOException("Respons terlalu besar");body.write(buffer,0,n);}
                owner.check();return new JSONObject(new String(body.toByteArray(),StandardCharsets.UTF_8));
            }
        } finally {owner.release(c);c.disconnect();}
    }
}
