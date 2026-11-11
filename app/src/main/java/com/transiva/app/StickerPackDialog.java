package com.transiva.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

final class StickerPackDialog {
    private final Activity activity;
    private final StickerPackStore store;
    private final TraviSivaStickers.Sender sender;
    private final LinearLayout rows;
    private final AlertDialog dialog;
    private boolean shopping;
    private boolean installing;
    private boolean offline;
    private boolean browsingItems;
    private JSONArray catalog;
    private Map<String, JSONObject> installed = new HashMap<>();

    private StickerPackDialog(Activity activity, boolean shopping, TraviSivaStickers.Sender sender) {
        this.activity = activity;
        this.shopping = shopping;
        this.sender = sender;
        store = new StickerPackStore(activity);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(8));
        LinearLayout tabs = new LinearLayout(activity);
        addTab(tabs, "Gratis", true);
        addTab(tabs, "Koleksi Saya", false);
        body.addView(tabs);
        ScrollView scroll = new ScrollView(activity);
        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        body.addView(scroll, new LinearLayout.LayoutParams(-1,
                Math.min(dp(460), Math.round(activity.getResources().getDisplayMetrics().heightPixels * .52f))));
        dialog = PremiumDialogs.builder(activity)
                .setTitle("Toko Stiker").setView(body).setNegativeButton("Tutup", null).create();
    }

    static AlertDialog show(Activity activity, boolean store, TraviSivaStickers.Sender sender) {
        StickerPackDialog screen = new StickerPackDialog(activity, store, sender);
        screen.dialog.show();
        screen.load();
        return screen.dialog;
    }

    static View received(Activity activity, String id) {
        StickerPackStore store = new StickerPackStore(activity);
        int edge = Math.min(dp(activity, 170), Math.round(activity.getResources().getDisplayMetrics().widthPixels * .55f));
        android.widget.FrameLayout frame = new android.widget.FrameLayout(activity);
        frame.setLayoutParams(new LinearLayout.LayoutParams(edge, edge));
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(image, new android.widget.FrameLayout.LayoutParams(-1, -1));
        TextView state = new TextView(activity);
        state.setTextSize(12);
        state.setGravity(Gravity.CENTER);
        state.setText("Memuat stiker…");
        frame.addView(state, new android.widget.FrameLayout.LayoutParams(-1, -1));
        View.OnClickListener load = view -> {
            state.setText("Memuat stiker…");
            state.setOnClickListener(null);
            frame.setClickable(false);
            boolean queued = StickerPackStore.submit(() -> {
                Bitmap bitmap = null;
                try { bitmap = decode(store.lookup(id), 512); } catch (Exception unavailable) { }
                Bitmap result = bitmap;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (result != null) {
                        image.setImageBitmap(result);
                        image.setContentDescription("Stiker");
                        state.setVisibility(View.GONE);
                    } else {
                        state.setText("Ketuk untuk muat stiker");
                        state.setOnClickListener(v -> frame.performClick());
                    }
                });
            });
            if (!queued) {
                state.setText("Ketuk untuk muat stiker");
                state.setOnClickListener(v -> frame.performClick());
            }
        };
        frame.setOnClickListener(load);
        load.onClick(frame);
        return frame;
    }

    private void addTab(LinearLayout tabs, String label, boolean storeTab) {
        Button tab = button(label);
        tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
        tab.setOnClickListener(v -> {
            if (installing) return;
            shopping = storeTab;
            render();
        });
    }

    private void load() {
        render();
        boolean queued = StickerPackStore.submit(() -> {
            try {
                JSONArray local = store.catalog(false);
                if (local != null) applyCatalog(local);
            } catch (Exception ignored) { }
            try { applyCatalog(store.catalog(true)); }
            catch (Exception network) {
                activity.runOnUiThread(() -> {
                    if (!visible()) return;
                    offline = true;
                    if (!browsingItems && !installing) render();
                });
            }
        });
        if (!queued) {
            offline = true;
            render();
        }
    }

    private void applyCatalog(JSONArray packs) {
        Map<String, JSONObject> local = new HashMap<>();
        for (int i = 0; i < packs.length(); i++) {
            JSONObject pack = packs.optJSONObject(i);
            if (pack == null) continue;
            try {
                JSONObject current = store.installed(pack);
                if (current != null) local.put(pack.optString("slug"), current);
            } catch (Exception ignored) { }
        }
        activity.runOnUiThread(() -> {
            if (!visible()) return;
            catalog = packs;
            installed = local;
            offline = false;
            if (!browsingItems && !installing) render();
        });
    }

    private void render() {
        browsingItems = false;
        rows.removeAllViews();
        if (offline && shopping) {
            note("Katalog offline. Pack terpasang tetap bisa dipakai.");
            Button retry = button("Muat ulang");
            rows.addView(retry);
            retry.setOnClickListener(v -> load());
        }
        if (catalog == null) {
            if (!shopping) {
                Button legacy = button("Travi & Siva · 48 stiker");
                rows.addView(legacy);
                legacy.setOnClickListener(v -> {
                    // Keep this sheet as owner so leaving chat also dismisses the picker.
                    JSONArray bundled = new JSONArray();
                    for (int i = 1; i <= 48; i++) {
                        JSONObject item = new JSONObject();
                        try {
                            String id = String.format(java.util.Locale.US, "ts_v1_%02d", i);
                            item.put("id", id).put("label", TraviSivaStickers.preview(TraviSivaStickers.PREFIX + id));
                            bundled.put(item);
                        } catch (org.json.JSONException ignored) { }
                    }
                    showItems("Travi & Siva", bundled, true);
                });
            } else if (!offline) note("Memuat katalog…");
            return;
        }
        int shown = 0;
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject pack = catalog.optJSONObject(i);
            if (pack == null) continue;
            JSONObject local = installed.get(pack.optString("slug"));
            if (!shopping && local == null) continue;
            shown++;
            addPack(pack, local);
        }
        if (shown == 0) note(shopping ? "Belum ada pack gratis." : "Unduh pack dari tab Gratis.");
    }

    private void addPack(JSONObject pack, JSONObject local) {
        JSONArray items = pack.optJSONArray("items");
        if (items == null) return;
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        ImageView cover = tile();
        row.addView(cover, new LinearLayout.LayoutParams(dp(54), dp(54)));
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && pack.optString("cover_id").equals(item.optString("id"))) {
                loadImage(cover, item);
                break;
            }
        }
        TextView title = new TextView(activity);
        title.setTextSize(14);
        title.setPadding(dp(10), 0, dp(4), 0);
        JSONArray visibleItems = shopping || local == null ? items : local.optJSONArray("items");
        int count = visibleItems == null ? 0 : visibleItems.length();
        title.setText(pack.optString("name") + "\n" + count + " stiker");
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        boolean current = local != null && local.optInt("revision") == pack.optInt("revision");
        Button action = button(!shopping || current ? "Buka" : local == null ? "Unduh gratis" : "Perbarui");
        row.addView(action);
        rows.addView(row);
        action.setOnClickListener(v -> {
            if (installing) return;
            if (!shopping || current) {
                showItems(pack.optString("name"), local.optJSONArray("items"), true);
            } else install(pack);
        });
        if (shopping) {
            cover.setContentDescription("Pratinjau " + pack.optString("name"));
            cover.setOnClickListener(v -> {
                JSONArray sample = new JSONArray();
                for (int i = 0; i < Math.min(items.length(), 4); i++) sample.put(items.optJSONObject(i));
                showItems("Pratinjau " + pack.optString("name"), sample, false);
            });
            if (!pack.optString("description").isEmpty()) note(pack.optString("description"));
        }
    }

    private void install(JSONObject pack) {
        installing = true;
        rows.removeAllViews();
        TextView progress = note("Menyiapkan unduhan…");
        boolean queued = StickerPackStore.submit(() -> {
            String error = null;
            try {
                store.install(pack, (done, total) -> activity.runOnUiThread(() -> {
                    if (visible()) progress.setText("Mengunduh stiker " + done + " / " + total);
                }));
            } catch (Exception failure) { error = "Unduhan belum selesai. Coba lagi untuk melanjutkan."; }
            String message = error;
            activity.runOnUiThread(() -> {
                if (!visible()) return;
                installing = false;
                if (message == null) {
                    installed.put(pack.optString("slug"), pack);
                    shopping = false;
                } else Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                render();
            });
        });
        if (!queued) {
            installing = false;
            render();
            Toast.makeText(activity, "Antrean penuh. Coba lagi.", Toast.LENGTH_SHORT).show();
        }
    }

    private void showItems(String title, JSONArray items, boolean canSend) {
        browsingItems = true;
        rows.removeAllViews();
        Button back = button("‹  " + title);
        rows.addView(back);
        back.setOnClickListener(v -> render());
        if (items == null) return;
        LinearLayout line = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (line == null || line.getChildCount() == 4) {
                line = new LinearLayout(activity);
                rows.addView(line);
            }
            ImageView image = tile();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(68), 1f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            line.addView(image, lp);
            String id = item.optString("id");
            image.setContentDescription(item.optString("label", "Stiker"));
            loadImage(image, item);
            if (canSend) {
                image.setOnClickListener(v -> {
                    dialog.dismiss();
                    sender.send(TraviSivaStickers.PREFIX + id);
                });
            }
        }
        if (line != null) {
            while (line.getChildCount() < 4) line.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private void loadImage(ImageView image, JSONObject item) {
        int res = TraviSivaStickers.resource(TraviSivaStickers.PREFIX + item.optString("id"));
        if (res != 0) {
            image.setImageResource(res);
            return;
        }
        StickerPackStore.submit(() -> {
            try {
                Bitmap bitmap = decode(store.asset(item), 192);
                activity.runOnUiThread(() -> {
                    if (visible()) image.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) { }
        });
    }

    private static Bitmap decode(java.io.File file, int edge) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        int sample = 1;
        while (Math.max(options.outWidth, options.outHeight) / sample > edge * 2) sample *= 2;
        options.inSampleSize = sample;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private ImageView tile() {
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(DriverAppSettings.isDarkMode(activity) ? 0xff20354f : 0xffeaf3ff);
        background.setCornerRadius(dp(12));
        image.setBackground(background);
        return image;
    }

    private TextView note(String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setPadding(dp(4), dp(7), dp(4), dp(7));
        rows.addView(label);
        return label;
    }

    private Button button(String text) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(text);
        return button;
    }

    private boolean visible() { return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed(); }
    private int dp(int value) { return dp(activity, value); }
    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
