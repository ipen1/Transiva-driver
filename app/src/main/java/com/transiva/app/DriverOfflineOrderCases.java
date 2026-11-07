package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;

import com.transiva.app.driver.data.DriverApiClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

final class DriverOfflineOrderCases {
    private static final String ENDPOINT = "offline_order_case_native.php";

    private DriverOfflineOrderCases() {}

    static void open(Activity activity) {
        request(activity, null, response -> {
            if (!response.optBoolean("enabled", true)) {
                show(activity, response.optString("message"));
                return;
            }
            JSONArray cases = response.optJSONArray("items");
            if (cases == null || cases.length() == 0) {
                show(activity, "Belum ada pemberitahuan untuk Anda.");
                return;
            }
            String[] labels = new String[cases.length()];
            for (int i = 0; i < cases.length(); i++) {
                JSONObject item = cases.optJSONObject(i);
                labels[i] = item.optString("service_type") + " · " + item.optString("order_code")
                        + "\n" + label(item.optString("state")) + " · Kasus #" + item.optLong("id");
            }
            new AlertDialog.Builder(activity).setTitle("Peninjauan Perjalanan")
                    .setItems(labels, (dialog, index) -> details(activity, cases.optJSONObject(index)))
                    .setNegativeButton("Tutup", null).show();
        });
    }

    private static void details(Activity activity, JSONObject item) {
        String state = item.optString("state");
        boolean proposed = "proposed".equals(state);
        boolean canAppeal = (proposed || "closed".equals(state)) && item.isNull("appeal_note");
        String message = label(state) + "\n" + item.optString("decision_note", "");
        if (!"dismissed".equals(state)) {
            message += "\n\nFee " + rupiah(item.optLong("fee")) + " · Penalti " + rupiah(item.optLong("penalty"));
        }
        if (proposed) message += "\n\nTransiva menunggu setidaknya 72 jam sejak pemberitahuan dibaca sebelum finalisasi.";
        if (!item.isNull("appeal_note")) {
            message += "\n\nSanggahan Anda: " + item.optString("appeal_note");
            message += "\n" + (item.isNull("appeal_result") ? "Menunggu tanggapan admin." : item.optString("appeal_result"));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle(item.optString("order_code"))
                .setMessage(message).setNegativeButton("Tutup", null);
        if (proposed && item.isNull("notice_seen_at")) {
            dialog.setPositiveButton("Sudah saya baca", (d, w) -> send(activity, item, "seen", ""));
        }
        if (canAppeal) dialog.setNeutralButton("Ajukan sanggah", (d, w) -> appeal(activity, item));
        dialog.show();
    }

    private static void appeal(Activity activity, JSONObject item) {
        EditText note = new EditText(activity);
        note.setHint("Jelaskan apa yang terjadi");
        note.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        new AlertDialog.Builder(activity).setTitle("Sanggah keputusan")
                .setView(note).setNegativeButton("Kembali", null)
                .setPositiveButton("Kirim", (d, w) -> {
                    String value = note.getText().toString().trim();
                    if (value.isEmpty()) show(activity, "Sertakan alasan sanggahan.");
                    else send(activity, item, "appeal", value);
                }).show();
    }

    private static void send(Activity activity, JSONObject item, String action, String note) {
        try {
            JSONObject body = new JSONObject().put("action", action).put("id", item.optLong("id")).put("note", note);
            request(activity, body, response -> {
                Toast.makeText(activity, "Tanggapan tersimpan.", Toast.LENGTH_SHORT).show();
                open(activity);
            });
        } catch (org.json.JSONException error) {
            show(activity, "Tanggapan belum dapat disiapkan.");
        }
    }

    private interface Response { void accept(JSONObject result); }

    private static void request(Activity activity, JSONObject body, Response action) {
        DriverSessionOwner owner = DriverSessionOwner.capture(activity);
        DriverApiClient api = new DriverApiClient(new SessionManager(activity));
        if (!DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject result = (body == null ? api.get(ENDPOINT) : api.post(ENDPOINT, body)).body;
                activity.runOnUiThread(() -> {
                    if (!owner.current() || activity.isFinishing() || activity.isDestroyed()) return;
                    if (result.optBoolean("success")) action.accept(result);
                    else show(activity, result.optString("message", "Coba kembali."));
                });
            } catch (DriverApiClient.ApiException error) {
                activity.runOnUiThread(() -> {
                    if (owner.current()) show(activity, error.getMessage());
                });
            }
        })) show(activity, "Sistem sedang sibuk. Coba kembali.");
    }

    private static String label(String state) {
        switch (state) {
            case "proposed": return "Menunggu finalisasi";
            case "closed": return "Keputusan final";
            case "reversed": return "Keputusan dikoreksi";
            case "dismissed": return "Ditutup tanpa sanksi";
            default: return "Dalam peninjauan";
        }
    }

    private static String rupiah(long value) {
        return "Rp" + NumberFormat.getIntegerInstance(new Locale("id", "ID")).format(value);
    }

    private static void show(Activity activity, String message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity).setTitle("Peninjauan Perjalanan").setMessage(message)
                .setPositiveButton("Tutup", null).show();
    }
}
