package com.transiva.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Locale;

/**
 * Transiva Premium Dialog V2.
 *
 * Keeps the existing AlertDialog.Builder API so legacy call sites and callback
 * logic stay untouched, but replaces the generic Android appearance with one
 * visual contract: branded header, rounded card, premium typography, pill
 * actions, dark-mode support and subtle entrance motion.
 */
public final class PremiumDialogs {
    private PremiumDialogs() {}

    public static AlertDialog.Builder builder(Context context) {
        return new PremiumBuilder(context);
    }

    /**
     * Apply the visual contract to dialogs created with .create().
     * Safe to call from a custom OnShowListener before custom button handlers.
     */
    public static void applyPremiumStyle(AlertDialog dialog) {
        if (dialog == null) return;
        Context context = dialog.getContext();
        try {
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.dimAmount = 0.64f;
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                int horizontalMargin = dp(context, dm.widthPixels / Math.max(1f, dm.density) < 360f ? 12 : 18);
                int maxDialogWidth = dp(context, 560);
                lp.width = Math.min(dm.widthPixels - (horizontalMargin * 2), maxDialogWidth);
                window.setAttributes(lp);
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    window.getDecorView().setElevation(dp(context, 22));
                }
            }

            int parentId = context.getResources().getIdentifier("parentPanel", "id", "android");
            View parent = parentId == 0 ? null : dialog.findViewById(parentId);
            if (parent != null) {
                parent.setBackground(cardDrawable(context));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    parent.setClipToOutline(true);
                    parent.setElevation(dp(context, 12));
                }
            }

            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) {
                message.setTextColor(color(context, R.color.transiva_dialog_text));
                message.setTextSize(15f);
                message.setLineSpacing(0f, 1.18f);
                message.setGravity(Gravity.START);
            }

            ListView list = dialog.getListView();
            if (list != null) {
                list.setDivider(new ColorDrawable(Color.TRANSPARENT));
                list.setDividerHeight(dp(context, 6));
                list.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 8));
                list.setClipToPadding(false);
            }

            styleAction(context, dialog.getButton(AlertDialog.BUTTON_POSITIVE), Action.PRIMARY);
            styleAction(context, dialog.getButton(AlertDialog.BUTTON_NEGATIVE), Action.SECONDARY);
            styleAction(context, dialog.getButton(AlertDialog.BUTTON_NEUTRAL), Action.GHOST);
            styleActionPanel(context, dialog);

            View decor = window == null ? null : window.getDecorView();
            if (decor != null) {
                decor.setAlpha(0f);
                decor.setScaleX(.965f);
                decor.setScaleY(.965f);
                decor.setTranslationY(dp(context, 10));
                decor.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                        .setDuration(190L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        } catch (Throwable ignored) {
            // Styling is intentionally fail-open. Security/order dialogs must work
            // even on unusual OEM AlertDialog implementations.
        }
    }

    private enum Action { PRIMARY, SECONDARY, GHOST }

    private static final class PremiumBuilder extends AlertDialog.Builder {
        private final Context baseContext;
        private CharSequence premiumTitle;

        PremiumBuilder(Context context) {
            super(new ContextThemeWrapper(context, themeFor(context)));
            this.baseContext = context;
        }

        @Override
        public PremiumBuilder setTitle(CharSequence title) {
            premiumTitle = title;
            return this;
        }

        @Override
        public PremiumBuilder setTitle(int titleId) {
            premiumTitle = baseContext.getText(titleId);
            return this;
        }

        @Override public PremiumBuilder setMessage(CharSequence message) { super.setMessage(message); return this; }
        @Override public PremiumBuilder setMessage(int messageId) { super.setMessage(messageId); return this; }
        @Override public PremiumBuilder setCancelable(boolean cancelable) { super.setCancelable(cancelable); return this; }
        @Override public PremiumBuilder setView(View view) { super.setView(view); return this; }
        @Override public PremiumBuilder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) { super.setItems(items, listener); return this; }
        @Override public PremiumBuilder setItems(int itemsId, DialogInterface.OnClickListener listener) { super.setItems(itemsId, listener); return this; }
        @Override public PremiumBuilder setSingleChoiceItems(CharSequence[] items, int checkedItem, DialogInterface.OnClickListener listener) { super.setSingleChoiceItems(items, checkedItem, listener); return this; }
        @Override public PremiumBuilder setSingleChoiceItems(int itemsId, int checkedItem, DialogInterface.OnClickListener listener) { super.setSingleChoiceItems(itemsId, checkedItem, listener); return this; }
        @Override public PremiumBuilder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) { super.setPositiveButton(text, listener); return this; }
        @Override public PremiumBuilder setPositiveButton(int textId, DialogInterface.OnClickListener listener) { super.setPositiveButton(textId, listener); return this; }
        @Override public PremiumBuilder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) { super.setNegativeButton(text, listener); return this; }
        @Override public PremiumBuilder setNegativeButton(int textId, DialogInterface.OnClickListener listener) { super.setNegativeButton(textId, listener); return this; }
        @Override public PremiumBuilder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) { super.setNeutralButton(text, listener); return this; }
        @Override public PremiumBuilder setNeutralButton(int textId, DialogInterface.OnClickListener listener) { super.setNeutralButton(textId, listener); return this; }

        @Override
        public AlertDialog create() {
            if (!TextUtils.isEmpty(premiumTitle)) super.setCustomTitle(buildPremiumHeader(baseContext, premiumTitle));
            AlertDialog dialog = super.create();
            // Works for ordinary create()->show() paths. Callers that replace this
            // listener call applyPremiumStyle(dialog) in their own listener.
            dialog.setOnShowListener(ignored -> applyPremiumStyle(dialog));
            return dialog;
        }

        @Override
        public AlertDialog show() {
            AlertDialog dialog = create();
            dialog.show();
            // OnShowListener is OEM-dependent, so apply once more defensively.
            applyPremiumStyle(dialog);
            return dialog;
        }
    }

    private static View buildPremiumHeader(Context context, CharSequence title) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(context, 24), dp(context, 22), dp(context, 24), dp(context, 10));

        TextView icon = new TextView(context);
        icon.setGravity(Gravity.CENTER);
        icon.setText(iconFor(title));
        icon.setTextSize(20f);
        icon.setTextColor(iconColor(context, title));
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bubble = new GradientDrawable();
        bubble.setShape(GradientDrawable.OVAL);
        bubble.setColor(iconSurface(context, title));
        icon.setBackground(bubble);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48));
        iconLp.setMarginEnd(dp(context, 15));
        root.addView(icon, iconLp);

        LinearLayout copy = new LinearLayout(context);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);

        TextView eyebrow = new TextView(context);
        eyebrow.setText("TRANSIVA DRIVER");
        eyebrow.setTextSize(10f);
        eyebrow.setLetterSpacing(.12f);
        eyebrow.setTextColor(color(context, R.color.transiva_blue));
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        copy.addView(eyebrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(20f);
        titleView.setTextColor(color(context, R.color.transiva_dialog_title));
        titleView.setTypeface(Typeface.create("sans", Typeface.BOLD));
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(context, 2);
        copy.addView(titleView, titleLp);

        root.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return root;
    }

    private static void styleAction(Context context, Button button, Action action) {
        if (button == null) return;
        String label = String.valueOf(button.getText());
        boolean destructive = isDestructive(label);
        boolean primary = action == Action.PRIMARY;

        button.setAllCaps(false);
        button.setTextSize(13.5f);
        button.setTypeface(Typeface.create("sans", primary ? Typeface.BOLD : Typeface.NORMAL));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setStateListAnimator(null);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(context, 14));

        if (primary) {
            int fill = destructive ? color(context, R.color.error) : color(context, R.color.transiva_blue);
            bg.setColor(fill);
            button.setTextColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) button.setElevation(dp(context, 2));
        } else if (action == Action.SECONDARY) {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(context, 1), color(context, R.color.transiva_dialog_border));
            button.setTextColor(color(context, R.color.transiva_dialog_button_secondary));
        } else {
            bg.setColor(Color.TRANSPARENT);
            button.setTextColor(destructive ? color(context, R.color.error) : color(context, R.color.transiva_dialog_button_secondary));
        }
        button.setBackground(bg);

        ViewGroup.LayoutParams raw = button.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.setMargins(dp(context, 4), dp(context, 5), dp(context, 4), dp(context, 8));
            button.setLayoutParams(lp);
        }
    }

    /**
     * Android's stock AlertDialog button bar gives long labels a fixed/minimum width.
     * On compact phones this can clip the primary action against the rounded card.
     * Normalize the panel after show: equal-width actions, safe side padding and up to
     * two text lines. This keeps existing click listeners untouched.
     */
    private static void styleActionPanel(Context context, AlertDialog dialog) {
        try {
            int panelId = context.getResources().getIdentifier("buttonPanel", "id", "android");
            View panelView = panelId == 0 ? null : dialog.findViewById(panelId);
            if (panelView instanceof LinearLayout) {
                LinearLayout panel = (LinearLayout) panelView;
                panel.setPadding(dp(context, 18), dp(context, 2), dp(context, 18), dp(context, 10));
                panel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            }

            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            Button[] buttons = new Button[]{neutral, negative, positive};
            int visible = 0;
            for (Button b : buttons) if (b != null && b.getVisibility() == View.VISIBLE && !TextUtils.isEmpty(b.getText())) visible++;
            if (visible == 0) return;

            // Equal width for the common two-action layout. For one action, keep it
            // compact but never wider than the available panel. Three actions also
            // share the row to avoid OEM minimum-width clipping.
            for (Button b : buttons) {
                if (b == null || b.getVisibility() != View.VISIBLE || TextUtils.isEmpty(b.getText())) continue;
                ViewGroup.LayoutParams raw = b.getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
                    if (visible >= 2) {
                        lp.width = 0;
                        lp.weight = 1f;
                    } else {
                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                        lp.weight = 0f;
                    }
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    lp.setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
                    b.setLayoutParams(lp);
                }
            }
        } catch (Throwable ignored) {
            // Never let presentation changes block a security/order action dialog.
        }
    }

    private static GradientDrawable cardDrawable(Context context) {
        GradientDrawable card = new GradientDrawable();
        card.setColor(color(context, R.color.transiva_dialog_surface));
        card.setCornerRadius(dp(context, 28));
        card.setStroke(dp(context, 1), color(context, R.color.transiva_dialog_border));
        return card;
    }

    private static boolean isDestructive(String text) {
        String label = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return label.contains("hapus") || label.contains("tolak") || label.contains("batalkan")
                || label.contains("keluar") || label.contains("nonaktif") || label.contains("putus")
                || label.contains("tutup aplikasi") || label.contains("reset perangkat") || label.contains("sos");
    }

    private static String iconFor(CharSequence title) {
        String t = title == null ? "" : title.toString().toLowerCase(Locale.ROOT);
        if (t.contains("berhasil") || t.contains("aktif")) return "✓";
        if (t.contains("lokasi") || t.contains("gps")) return "⌖";
        if (t.contains("pin") || t.contains("otp") || t.contains("aman") || t.contains("perangkat")) return "◆";
        if (t.contains("transfer") || t.contains("deposit") || t.contains("saldo")) return "₿";
        if (t.contains("bubble") || t.contains("pesan")) return "●";
        if (t.contains("keluar") || t.contains("reset") || t.contains("batal") || t.contains("tolak") || t.contains("darurat") || t.contains("sos")) return "!";
        return "i";
    }

    private static int iconColor(Context context, CharSequence title) {
        String t = title == null ? "" : title.toString().toLowerCase(Locale.ROOT);
        if (t.contains("keluar") || t.contains("reset") || t.contains("batal") || t.contains("tolak") || t.contains("darurat") || t.contains("sos") || t.contains("palsu") || t.contains("tidak aman"))
            return color(context, R.color.error);
        if (t.contains("berhasil")) return color(context, R.color.success);
        return color(context, R.color.transiva_blue);
    }

    private static int iconSurface(Context context, CharSequence title) {
        int fg = iconColor(context, title);
        return blendWithSurface(context, fg, isNight(context) ? .22f : .11f);
    }

    private static int blendWithSurface(Context context, int fg, float amount) {
        int bg = color(context, R.color.transiva_dialog_surface);
        float a = Math.max(0f, Math.min(1f, amount));
        return Color.rgb(
                Math.round(Color.red(bg) * (1f-a) + Color.red(fg) * a),
                Math.round(Color.green(bg) * (1f-a) + Color.green(fg) * a),
                Math.round(Color.blue(bg) * (1f-a) + Color.blue(fg) * a));
    }

    private static int themeFor(Context context) {
        return isNight(context) ? R.style.TransivaPremiumDialogTheme_Dark : R.style.TransivaPremiumDialogTheme;
    }

    private static boolean isNight(Context context) {
        int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int color(Context context, int id) {
        return Build.VERSION.SDK_INT >= 23 ? context.getColor(id) : context.getResources().getColor(id);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
