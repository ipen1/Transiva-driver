package com.transiva.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.view.ContextThemeWrapper;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

/**
 * Single visual contract for Transiva dialogs.
 * Keeps legacy AlertDialog call sites compatible while providing a premium,
 * theme-aware presentation from one place.
 */
public final class PremiumDialogs {
    private PremiumDialogs() {}

    public static AlertDialog.Builder builder(Context context) {
        return new PremiumBuilder(context);
    }

    private static int themeFor(Context context) {
        int mask = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES
                ? R.style.TransivaPremiumDialogTheme_Dark
                : R.style.TransivaPremiumDialogTheme;
    }

    private static final class PremiumBuilder extends AlertDialog.Builder {
        private final Context baseContext;

        PremiumBuilder(Context context) {
            super(new ContextThemeWrapper(context, themeFor(context)));
            this.baseContext = context;
        }

        @Override
        public AlertDialog show() {
            AlertDialog dialog = super.show();
            polish(dialog);
            return dialog;
        }

        private void polish(AlertDialog dialog) {
            if (dialog == null) return;
            try {
                Window window = dialog.getWindow();
                if (window != null) {
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.dimAmount = 0.48f;
                    window.setAttributes(lp);
                    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                }

                Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

                styleAction(positive, true);
                styleAction(negative, false);
                styleAction(neutral, false);
            } catch (Throwable ignored) {
                // Visual polish must never block a functional/security dialog.
            }
        }

        private void styleAction(Button button, boolean primary) {
            if (button == null) return;
            int horizontal = dp(15);
            int vertical = dp(8);
            button.setAllCaps(false);
            button.setTypeface(Typeface.create("sans", primary ? Typeface.BOLD : Typeface.NORMAL));
            button.setTextSize(14f);
            button.setMinHeight(dp(42));
            button.setPadding(horizontal, vertical, horizontal, vertical);

            int blue = baseContext.getColor(R.color.transiva_blue);
            int danger = baseContext.getColor(R.color.error);
            int textSecondary = baseContext.getColor(R.color.transiva_dialog_button_secondary);
            String label = String.valueOf(button.getText()).toLowerCase(java.util.Locale.ROOT);
            boolean destructive = label.contains("hapus") || label.contains("tolak")
                    || label.contains("batalkan") || label.contains("keluar")
                    || label.contains("nonaktif") || label.contains("putuskan");
            if (primary) {
                button.setTextColor(baseContext.getColor(android.R.color.white));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    button.setBackgroundTintList(ColorStateList.valueOf(destructive ? danger : blue));
                }
            } else {
                button.setTextColor(textSecondary);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    button.setBackgroundTintList(ColorStateList.valueOf(baseContext.getColor(android.R.color.transparent)));
                }
            }
        }

        private int dp(int value) {
            return Math.round(value * baseContext.getResources().getDisplayMetrics().density);
        }
    }
}
