package com.transiva.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local image, upload progress, and server acknowledgment for one chat message. */
final class ChatPhotoTile extends LinearLayout {
    private final ImageView photo;
    private final Overlay overlay;
    private final TextView status;
    private int confirmedId;

    ChatPhotoTile(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2);
        row.setMargins(0, dp(context, 4), 0, dp(context, 4));
        setLayoutParams(row);
        FrameLayout frame = new FrameLayout(context);
        styleCorners(frame);
        addView(frame, new LinearLayout.LayoutParams(width(context), height(context)));
        photo = image(context);
        frame.addView(photo, new FrameLayout.LayoutParams(-1, -1));
        overlay = new Overlay(context);
        frame.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(context);
        status.setTextSize(11);
        status.setTextColor(DriverAppSettings.isDarkMode(context) ? 0xffd7e5fb : 0xff425b79);
        status.setPadding(dp(context, 5), dp(context, 4), dp(context, 4), 0);
        addView(status, new LinearLayout.LayoutParams(-2, -2));
        preparing();
    }

    static ImageView image(Context context) {
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        styleCorners(image);
        return image;
    }

    private static void styleCorners(View view) {
        Context context = view.getContext();
        GradientDrawable background = new GradientDrawable();
        background.setColor(DriverAppSettings.isDarkMode(context) ? 0xff1e3048 : 0xffdae8f9);
        background.setCornerRadius(dp(context, 13));
        view.setBackground(background);
        view.setClipToOutline(true);
    }

    static LinearLayout.LayoutParams imageSize(Context context) {
        return new LinearLayout.LayoutParams(width(context), height(context));
    }

    private static int width(Context context) {
        return Math.min(dp(context, 220), Math.round(context.getResources().getDisplayMetrics().widthPixels * .70f));
    }

    private static int height(Context context) { return Math.round(width(context) * .75f); }

    void thumbnail(Bitmap bitmap) {
        if (bitmap != null) photo.setImageBitmap(bitmap);
    }

    void preparing() {
        status.setOnClickListener(null);
        status.setText("Menyiapkan foto…");
        overlay.setVisibility(View.VISIBLE);
        overlay.show(0);
    }

    void progress(int value) {
        status.setText(value >= 95 ? "Menunggu konfirmasi…" : "Mengirim foto…");
        overlay.show(Math.max(0, Math.min(95, value)));
    }

    void confirmed(int id) {
        confirmedId = id;
        status.setOnClickListener(null);
        status.setText("Foto terkirim");
        overlay.show(100);
        overlay.postDelayed(() -> overlay.setVisibility(View.GONE), 140);
    }

    void failed(String message, Runnable retry) {
        overlay.setVisibility(View.GONE);
        status.setText(message);
        status.setOnClickListener(retry == null ? null : v -> retry.run());
    }

    static void reconcile(LinearLayout messages, int id) {
        if (id <= 0) return;
        for (int i = messages.getChildCount() - 1; i >= 0; i--) {
            View row = messages.getChildAt(i);
            if (row instanceof ChatPhotoTile && ((ChatPhotoTile)row).confirmedId == id) {
                messages.removeViewAt(i);
            }
        }
    }

    static void clearServerRows(LinearLayout messages) {
        for (int i = messages.getChildCount() - 1; i >= 0; i--) {
            if (!(messages.getChildAt(i) instanceof ChatPhotoTile)) messages.removeViewAt(i);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Overlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF ring = new RectF();
        private int percent;

        Overlay(Context context) {
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void show(int value) {
            percent = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.argb(110 * (100 - percent) / 100, 0, 0, 0));
            float x = getWidth() * .5f;
            float y = getHeight() * .5f;
            float radius = dp(getContext(), 24);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xaa09162a);
            canvas.drawCircle(x, y, radius + dp(getContext(), 5), paint);
            ring.set(x - radius, y - radius, x + radius, y + radius);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(getContext(), 3));
            paint.setColor(0x55ffffff);
            canvas.drawOval(ring, paint);
            paint.setColor(0xff66cbff);
            canvas.drawArc(ring, -90, percent * 3.6f, false, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(getContext(), 11));
            paint.setColor(Color.WHITE);
            canvas.drawText(percent + "%", x, y - (paint.ascent() + paint.descent()) / 2, paint);
        }
    }
}
