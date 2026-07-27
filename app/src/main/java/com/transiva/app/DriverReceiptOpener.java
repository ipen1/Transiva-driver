package com.transiva.app;

import android.app.Activity;
import android.content.Intent;

import org.json.JSONObject;

/**
 * Helper native untuk membuka nota setelah finishOrder.php sukses.
 * Class helper biasa, tidak perlu didaftarkan di AndroidManifest.xml.
 */
public class DriverReceiptOpener {
    public static void openAfterFinish(Activity activity, JSONObject response, JSONObject order, String driverUsername) {
        try {
            JSONObject receipt = response != null ? response.optJSONObject("receipt") : null;
            if (receipt == null) receipt = new JSONObject();

            JSONObject item = new JSONObject();
            JSONObject orderObj = receipt.optJSONObject("order");
            if (orderObj == null) orderObj = order != null ? order : new JSONObject();

            item.put("id", orderObj.optString("id", orderObj.optString("order_id", "")));
            item.put("order_id", orderObj.optString("id", orderObj.optString("order_id", "")));
            item.put("order_code", orderObj.optString("order_id", orderObj.optString("id", "-")));
            item.put("driver", driverUsername == null ? "" : driverUsername);
            item.put("created_at", orderObj.optString("updated_at", orderObj.optString("created_at", "Baru saja")));

            putNumber(item, "ongkir", receipt, "ongkir");
            putNumber(item, "merchant_order", receipt, "merchant_order");
            putNumber(item, "app_fee", receipt, "app_fee");
            putNumber(item, "merchant_grossup_fee", receipt, "merchant_grossup_fee");
            putNumber(item, "total_pendapatan", receipt, "total_pendapatan");
            putNumber(item, "total_potongan", receipt, "total_potongan");
            putNumber(item, "saldo_sebelum", receipt, "saldo_sebelum");
            putNumber(item, "saldo_saat_ini", receipt, "saldo_saat_ini");
            putNumber(item, "sisa_saldo", receipt, "sisa_saldo");
            putNumber(item, "items_count", receipt, "items_count");
            putNumber(item, "is_food", receipt, "is_food");
            item.put("receipt_json", receipt.toString());

            Intent i = new Intent(activity, DriverReceiptDetailActivity.class);
            i.putExtra("receipt_json", item.toString());
            activity.startActivity(i);
        } catch (Exception ignored) {}
    }

    private static void putNumber(JSONObject target, String key, JSONObject source, String sourceKey) {
        try { target.put(key, source.optDouble(sourceKey, 0)); } catch (Exception ignored) {}
    }
}
