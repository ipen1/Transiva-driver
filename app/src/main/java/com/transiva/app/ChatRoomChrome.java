package com.transiva.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Visual controls and draft/recording state shared by the two order chat screens. */
final class ChatRoomChrome {
    private final Context context;
    private final boolean dark;
    private EditText draft;
    private Action attach;
    private Action send;
    private Action voice;
    private Button voiceCall;
    private Button videoCall;
    private boolean available;
    private boolean recording;

    ChatRoomChrome(Context context, boolean dark) {
        this.context = context;
        this.dark = dark;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    int textColor() { return dark ? 0xfff4f7ff : 0xff172844; }

    int secondaryColor() { return dark ? 0xffbbcadd : 0xff50627d; }

    private GradientDrawable surface(int top, int bottom, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{top, bottom});
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    void styleBar(LinearLayout bar) {
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(3), dp(5), dp(3), dp(5));
        bar.setBackground(surface(dark ? 0xff122238 : 0xfff9fcff,
                dark ? 0xff101c30 : 0xffeef5ff, 16, dark ? 0xff253650 : 0xffdce7f5));
    }

    Button addHeaderAction(LinearLayout header, String icon, String description) {
        Action button = new Action(icon, description, textColor());
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x26c1dcff : 0x1a1264d4),
                null, surface(0xffffffff, 0xffffffff, 14, 0)));
        header.addView(button, new LinearLayout.LayoutParams(dp(44), dp(48)));
        return button;
    }

    void addCallActions(LinearLayout header, Runnable call, Runnable video) {
        voiceCall = addHeaderAction(header, "phone", "Panggilan suara");
        voiceCall.setOnClickListener(v -> call.run());
        videoCall = addHeaderAction(header, "video", "Panggilan video");
        videoCall.setOnClickListener(v -> video.run());
    }

    void hideCallActions() {
        if (voiceCall == null) return;
        voiceCall.setVisibility(View.GONE);
        videoCall.setVisibility(View.GONE);
    }

    void styleComposer(LinearLayout composer) {
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(5), dp(5), dp(5), dp(5));
        composer.setBackground(surface(dark ? 0xff101e33 : 0xfff9fcff,
                dark ? 0xff0b1729 : 0xffeef5ff, 16, dark ? 0xff253650 : 0xffdce7f5));
    }

    Button addAttachment(LinearLayout composer) {
        attach = new Action("plus", "Lampiran dan stiker", dark ? 0xff9bcbff : 0xff1357a8);
        attach.setBackground(buttonSurface(false));
        composer.addView(attach, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return attach;
    }

    void addDraft(LinearLayout composer, EditText field) {
        draft = field;
        draft.setTextColor(textColor());
        draft.setHintTextColor(secondaryColor());
        draft.setTextSize(14);
        draft.setGravity(Gravity.CENTER_VERTICAL);
        draft.setMinHeight(dp(48));
        draft.setPadding(dp(13), dp(6), dp(13), dp(6));
        draft.setBackground(surface(dark ? 0xff111e30 : 0xffffffff,
                dark ? 0xff0f1a2a : 0xfff9fbff, 16, dark ? 0xff344861 : 0xffcfdded));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMarginStart(dp(7));
        composer.addView(draft, params);
    }

    void addDraftActions(LinearLayout composer) {
        FrameLayout slot = new FrameLayout(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMarginStart(dp(7));
        composer.addView(slot, params);
        voice = new Action("mic", "Tahan untuk merekam pesan suara", 0xffffffff);
        send = new Action("send", "Kirim pesan", 0xffffffff);
        voice.setBackground(buttonSurface(true));
        send.setBackground(buttonSurface(true));
        slot.addView(voice, new FrameLayout.LayoutParams(-1, -1));
        slot.addView(send, new FrameLayout.LayoutParams(-1, -1));
        draft.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                updateActions();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        updateActions();
    }

    Button sendButton() { return send; }

    Button voiceButton() { return voice; }

    boolean isRecording() { return recording; }

    void setRecording(boolean active, boolean cancelArmed) {
        recording = active;
        voice.setIcon(active ? (cancelArmed ? "close" : "record") : "mic");
        voice.setContentDescription(active ? (cancelArmed ? "Lepas untuk membatalkan" : "Sedang merekam")
                : "Tahan untuk merekam pesan suara");
        updateActions();
    }

    void setComposerAvailable(boolean enabled) {
        available = enabled;
        if (send != null) updateActions();
    }

    private void updateActions() {
        boolean showSend = !recording && !draft.getText().toString().trim().isEmpty();
        send.setVisibility(showSend ? View.VISIBLE : View.GONE);
        voice.setVisibility(showSend ? View.GONE : View.VISIBLE);
        enable(attach, available && !recording);
        enable(send, available && !recording);
        // Let the recorder receive UP/CANCEL even if the order ends during a hold.
        enable(voice, recording || available);
    }

    private static void enable(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.46f);
    }

    private Drawable buttonSurface(boolean primary) {
        GradientDrawable fill = primary ? surface(0xff087dff, 0xff0958c9, 16, 0)
                : surface(dark ? 0xff1c304a : 0xffe6f1ff,
                        dark ? 0xff15273e : 0xffdbeaff, 16, 0);
        return new RippleDrawable(ColorStateList.valueOf(primary ? 0x45ffffff : 0x3078b9ff), fill, null);
    }

    private final class Action extends Button {
        private final int color;
        private Drawable glyph;

        Action(String icon, String description, int color) {
            super(context);
            this.color = color;
            setBackgroundTintList(null);
            setMinimumWidth(0);
            setMinWidth(0);
            setMinimumHeight(0);
            setMinHeight(0);
            setPadding(0, 0, 0, 0);
            setContentDescription(description);
            setIcon(icon);
        }

        void setIcon(String icon) {
            glyph = new CallVisuals.Icon(icon, false);
            glyph.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int edge = dp(22);
            int left = (getWidth() - edge) / 2;
            int top = (getHeight() - edge) / 2;
            glyph.setBounds(left, top, left + edge, top + edge);
            glyph.draw(canvas);
        }
    }
}
