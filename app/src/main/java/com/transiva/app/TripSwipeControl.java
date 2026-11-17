package com.transiva.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

final class TripSwipeControl extends FrameLayout {
    private final TextView label;
    private final TextView thumb;
    private final String instruction;
    private final Runnable submit;
    private java.util.function.Consumer<Boolean> interaction = ignored -> {};
    private float startX;
    private boolean dragging;

    TripSwipeControl(Context context, String instruction, Runnable submit) {
        super(context);
        this.instruction = instruction;
        this.submit = submit;
        setMinimumHeight(dp(48));
        label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setTextSize(13);
        label.setMaxLines(2);
        label.setTypeface(null, Typeface.BOLD);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams text = new LayoutParams(-1, -1);
        text.leftMargin = dp(49);
        text.rightMargin = dp(8);
        addView(label, text);
        thumb = new TextView(context);
        thumb.setGravity(Gravity.CENTER);
        thumb.setTextSize(20);
        thumb.setTextColor(Color.WHITE);
        thumb.setElevation(dp(2));
        thumb.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams thumbParams = new LayoutParams(dp(40), dp(40), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        thumbParams.leftMargin = dp(4);
        addView(thumb, thumbParams);
        setContentDescription(instruction);
        setFocusable(true);
        setOnClickListener(v -> {
            if (isEnabled()) PremiumDialogs.builder(context)
                    .setMessage(instruction.replaceFirst("^Geser untuk ", "") + "?")
                    .setNegativeButton("Batal", null)
                    .setPositiveButton("Konfirmasi", (d, w) -> { if (isEnabled()) submit.run(); }).show();
        });
        setEnabled(false);
    }

    void interaction(java.util.function.Consumer<Boolean> listener) { interaction = listener; }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (label == null) return;
        if (!enabled && dragging) resetDrag();
        label.setText(instruction);
        label.setTextColor(enabled ? DriverThemeTokens.textPrimary(getContext()) : DriverThemeTokens.textSecondary(getContext()));
        setBackground(DriverNavigationUi.roundRect(DriverThemeTokens.color(getContext(),
                enabled ? "#DCEBFF" : "#F2F7FF"), dp(28)));
        thumb.setText(enabled ? "›" : "🔒");
        thumb.setBackground(DriverNavigationUi.roundRect(
                enabled ? Color.rgb(0, 93, 222) : DriverThemeTokens.border(getContext()), dp(24)));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getX() > dp(55)) return false;
                thumb.animate().cancel();
                thumb.setTranslationX(0);
                startX = event.getX();
                dragging = true;
                interaction.accept(true);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                float travel = travel();
                float offset = Math.min(travel, Math.max(0, event.getX() - startX));
                thumb.setTranslationX(offset);
                label.setText(offset >= travel * .90f ? "Lepas untuk konfirmasi" : instruction);
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                boolean confirmed = travel() > 0 && thumb.getTranslationX() >= travel() * .90f;
                resetDrag();
                if (confirmed && isEnabled()) submit.run();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                resetDrag();
                return true;
            default:
                return dragging;
        }
    }

    private void resetDrag() {
        dragging = false;
        thumb.animate().translationX(0).setDuration(150).start();
        label.setText(instruction);
        interaction.accept(false);
    }

    private float travel() { return Math.max(0, getWidth() - dp(48)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
