package com.transiva.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * Pusat pengaturan tampilan driver.
 *
 * Seluruh halaman customer tetap memakai UI Java programatik yang sudah ada.
 * Class ini mengubah warna halaman, card, divider, input, bottom navigation,
 * dan tulisan secara otomatis ketika mode gelap aktif, termasuk view yang
 * ditambahkan belakangan dari response API.
 */
public final class DriverAppSettings {
    private static final String PREF = "driver_app_settings";
    private static final String KEY_DARK = "dark_mode";
    private static final String KEY_VIBRATE = "vibration_enabled";

    public static final int DARK_PAGE = Color.rgb(9, 22, 37);       // #091625
    public static final int DARK_CARD = Color.rgb(18, 34, 53);      // #122235
    public static final int DARK_CARD_SOFT = Color.rgb(23, 43, 66); // #172B42
    public static final int DARK_BORDER = Color.rgb(45, 67, 92);    // #2D435C
    public static final int DARK_TEXT = Color.rgb(238, 245, 255);   // #EEF5FF
    public static final int DARK_MUTED = Color.rgb(169, 184, 204);  // #A9B8CC

    private static final WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>
            INSTALLED_WATCHERS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> APPLIED_ACTIVITY_THEMES =
            new WeakHashMap<>();

    private DriverAppSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean isDarkMode(Context context) {
        return prefs(context).getBoolean(KEY_DARK, false);
    }

    public static void setDarkMode(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DARK, enabled).apply();
    }

    public static boolean isVibrationEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VIBRATE, true);
    }

    public static void setVibrationEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_VIBRATE, enabled).apply();
    }

    /** Terapkan tema ke activity sesudah setContentView(). */
    public static void apply(Activity activity) {
        final boolean dark = isDarkMode(activity);

        // Activity driver yang masih berada di back stack telah memiliki warna yang
        // dimutasi langsung. Saat pilihan tema berubah, view lama harus dibuat ulang
        // supaya warna asli mode terang dibangun kembali tanpa menutup aplikasi.
        synchronized (APPLIED_ACTIVITY_THEMES) {
            Boolean previouslyApplied = APPLIED_ACTIVITY_THEMES.get(activity);
            if (previouslyApplied != null && previouslyApplied != dark) {
                APPLIED_ACTIVITY_THEMES.put(activity, dark);
                activity.getWindow().getDecorView().post(activity::recreate);
                return;
            }
            APPLIED_ACTIVITY_THEMES.put(activity, dark);
        }

        activity.getWindow().setStatusBarColor(
                dark ? Color.rgb(5, 16, 29) : Color.parseColor("#0B7CFF")
        );
        activity.getWindow().setNavigationBarColor(Color.rgb(5, 16, 29));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            if (dark) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        if (dark) {
            applyRecursive(content, true, 0);
            installDynamicViewWatcher(content);
            // Menangkap card/promo/chat yang dibuat sesudah request API selesai.
            content.post(() -> {
                if (isDarkMode(activity)) applyRecursive(content, true, 0);
            });
            content.postDelayed(() -> {
                if (isDarkMode(activity)) applyRecursive(content, true, 0);
            }, 250L);
            content.postDelayed(() -> {
                if (isDarkMode(activity)) applyRecursive(content, true, 0);
            }, 900L);
        } else {
            removeDynamicViewWatcher(content);
        }
    }

    /** Bisa dipanggil setelah sebuah dialog atau card dinamis baru dibuat. */
    public static void applyToView(Context context, View view) {
        if (view != null && isDarkMode(context)) {
            applyRecursive(view, true, 1);
        }
    }

    private static void installDynamicViewWatcher(final View root) {
        synchronized (INSTALLED_WATCHERS) {
            if (INSTALLED_WATCHERS.containsKey(root)) return;

            ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
                Context context = root.getContext();
                if (context != null && isDarkMode(context)) {
                    applyRecursive(root, true, 0);
                }
            };
            root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            INSTALLED_WATCHERS.put(root, listener);
        }
    }

    private static void removeDynamicViewWatcher(final View root) {
        synchronized (INSTALLED_WATCHERS) {
            ViewTreeObserver.OnGlobalLayoutListener listener = INSTALLED_WATCHERS.remove(root);
            if (listener == null) return;
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (!observer.isAlive()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                observer.removeOnGlobalLayoutListener(listener);
            } else {
                //noinspection deprecation
                observer.removeGlobalOnLayoutListener(listener);
            }
        }
    }

    private static void applyRecursive(View view, boolean dark, int depth) {
        if (!dark || view == null) return;

        transformBackground(view, depth);
        transformText(view);
        transformControls(view);

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyRecursive(group.getChildAt(i), true, depth + 1);
            }
        }
    }

    private static void transformBackground(View view, int depth) {
        Drawable background = view.getBackground();
        if (background == null) return;

        if (background instanceof ColorDrawable) {
            int original = ((ColorDrawable) background).getColor();
            Integer mapped = mapLightBackground(original, depth);
            if (mapped != null && mapped != original) {
                view.setBackgroundColor(mapped);
            }
            return;
        }

        if (background instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            GradientDrawable shape = (GradientDrawable) background.mutate();
            int[] colors = shape.getColors();

            if (colors != null && colors.length > 0) {
                boolean allLight = true;
                for (int color : colors) {
                    if (!isLightSurface(color)) {
                        allLight = false;
                        break;
                    }
                }
                // Gradient biru utama tidak disentuh. Hanya gradient putih/pucat.
                if (allLight) {
                    shape.setColors(new int[]{DARK_CARD_SOFT, DARK_CARD});
                    view.setBackground(shape);
                }
            } else if (shape.getColor() != null) {
                int original = shape.getColor().getDefaultColor();
                Integer mapped = mapLightBackground(original, depth);
                if (mapped != null && mapped != original) {
                    shape.setColor(mapped);
                    view.setBackground(shape);
                }
            }
        }
    }

    private static Integer mapLightBackground(int color, int depth) {
        if (Color.alpha(color) < 100) return null;

        // Putih murni adalah card. Warna halaman #F5F8FD/#F7FAFF dibuat lebih gelap.
        if (isAlmostWhite(color)) {
            return depth <= 2 ? DARK_PAGE : DARK_CARD;
        }
        // Card aktif, input, chip dan panel berwarna biru/abu sangat muda.
        if (isLightSurface(color)) {
            return depth <= 2 ? DARK_PAGE : DARK_CARD_SOFT;
        }
        // Divider dan border abu terang.
        if (isLightGray(color)) {
            return DARK_BORDER;
        }
        return null;
    }

    private static void transformText(View view) {
        if (!(view instanceof TextView)) return;

        TextView text = (TextView) view;
        int current = text.getCurrentTextColor();

        // Tulisan putih di atas kartu biru tetap putih.
        if (isNearWhite(current)) return;

        if (isDarkText(current)) {
            text.setTextColor(DARK_TEXT);
        } else if (isMutedText(current)) {
            text.setTextColor(DARK_MUTED);
        }

        if (text instanceof EditText) {
            EditText edit = (EditText) text;
            edit.setHintTextColor(Color.rgb(132, 153, 178));
        }
    }

    private static void transformControls(View view) {
        if (view instanceof Switch && Build.VERSION.SDK_INT >= 23) {
            Switch toggle = (Switch) view;
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{}
            };
            toggle.setThumbTintList(new ColorStateList(
                    states,
                    new int[]{Color.WHITE, Color.rgb(192, 205, 220)}
            ));
            toggle.setTrackTintList(new ColorStateList(
                    states,
                    new int[]{Color.parseColor("#0B7CFF"), Color.rgb(64, 84, 108)}
            ));
        } else if (view instanceof ProgressBar && Build.VERSION.SDK_INT >= 21) {
            ((ProgressBar) view).setIndeterminateTintList(
                    ColorStateList.valueOf(Color.parseColor("#2494FF"))
            );
        } else if (view instanceof ImageView) {
            // Tidak memberi tint global agar logo/foto/ikon berwarna tidak rusak.
        } else if (view instanceof Button) {
            // Warna tombol aksi dipertahankan; teksnya sudah ditangani di atas.
        }
    }

    private static boolean isAlmostWhite(int c) {
        return Color.alpha(c) > 180
                && Color.red(c) >= 245
                && Color.green(c) >= 245
                && Color.blue(c) >= 245;
    }

    private static boolean isLightSurface(int c) {
        return Color.alpha(c) > 180
                && Color.red(c) >= 218
                && Color.green(c) >= 226
                && Color.blue(c) >= 232;
    }

    private static boolean isLightGray(int c) {
        int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
        int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
        return Color.alpha(c) > 180 && min > 175 && (max - min) < 30;
    }

    private static boolean isNearWhite(int c) {
        return Color.alpha(c) > 180
                && Color.red(c) > 220
                && Color.green(c) > 220
                && Color.blue(c) > 220;
    }

    private static boolean isDarkText(int c) {
        return Color.alpha(c) > 180
                && Color.red(c) < 105
                && Color.green(c) < 125
                && Color.blue(c) < 155;
    }

    private static boolean isMutedText(int c) {
        return Color.alpha(c) > 180
                && Color.red(c) < 180
                && Color.green(c) < 190
                && Color.blue(c) < 205;
    }
}
