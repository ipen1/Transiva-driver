package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.animation.DecelerateInterpolator;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
abstract class DriverChatRoomActivityLayer2 extends DriverChatRoomActivityLayer1 {

    protected PendingPhotoBubble addPendingPhotoBubble(
            ChatImageProcessor.ImagePayload payload
    ) {
        LinearLayout wrapper = messageWrapper(true);

        FrameLayout imageFrame = new FrameLayout(this);
        imageFrame.setBackground(round("#EAF1FA", 16));

        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);

        Bitmap bitmap = BitmapFactory.decodeByteArray(
                payload.previewWebp,
                0,
                payload.previewWebp.length);
        if (bitmap != null) preview.setImageBitmap(bitmap);

        imageFrame.addView(
                preview,
                new FrameLayout.LayoutParams(-1, -1));

        FrameLayout loadingLayer = new FrameLayout(this);
        loadingLayer.setBackgroundColor(Color.argb(72, 0, 0, 0));

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        FrameLayout.LayoutParams spinnerLp =
                new FrameLayout.LayoutParams(dp(42), dp(42));
        spinnerLp.gravity = Gravity.CENTER;
        loadingLayer.addView(spinner, spinnerLp);

        imageFrame.addView(
                loadingLayer,
                new FrameLayout.LayoutParams(-1, -1));

        wrapper.addView(
                imageFrame,
                new LinearLayout.LayoutParams(dp(220), dp(165)));

        TextView state = text(
                "Mengirim foto…",
                9,
                "#64748B",
                true);
        state.setGravity(Gravity.RIGHT);
        state.setPadding(dp(7), dp(3), dp(7), 0);
        wrapper.addView(
                state,
                new LinearLayout.LayoutParams(-2, -2));

        return new PendingPhotoBubble(wrapper, loadingLayer, state);
    }

    protected void setupVoiceRecorder() {
        ChatVoiceNote.attachRecorder(this, voiceButton, REQUEST_AUDIO_PERMISSION, new ChatVoiceNote.Listener() {
            @Override public void onState(String text, boolean recording, boolean cancelArmed) { statusText.setText(text); voiceButton.setText(cancelArmed ? "✕" : (recording ? "●" : "🎙")); }
            @Override public void onReady(File file, long durationMs) { uploadVoiceNote(file, durationMs); }
            @Override public void onError(String message) { toast(message); voiceButton.setText("🎙"); }
        });
    }

    protected void uploadVoiceNote(File file, long durationMs) {
        if (readOnly || uploading || file == null) return;
        uploading = true; voiceButton.setEnabled(false);
        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject upload = DriverMessageApi.uploadVoice(session, UPLOAD_VOICE_URL, orderId, orderSource, roomId, file, durationMs);
                if (!upload.optBoolean("success", false)) throw new IllegalStateException(upload.optString("message", "Upload voice note gagal"));
                String audioUrl = upload.optString("url", upload.optString("audio_url", ""));
                JSONObject body = new JSONObject(); body.put("order_id", orderId); body.put("order_db_id", orderDbId); body.put("source", orderSource); body.put("room_id", roomId); body.put("sender_type", "driver"); body.put("message", ChatVoiceNote.encode(audioUrl, durationMs));
                JSONObject sent = DriverMessageApi.post(session, SEND_CHAT_URL, body);
                postUi(() -> { uploading=false; voiceButton.setEnabled(!readOnly); voiceButton.setText("🎙"); if (sent.optBoolean("success", false)) loadMessages(false); else toast(sent.optString("message", "Voice note gagal dikirim")); });
            } catch (Exception e) { postUi(() -> { uploading=false; voiceButton.setEnabled(!readOnly); voiceButton.setText("🎙"); statusText.setText("Voice note pending • jaringan"); toast(first(e.getMessage(), "Voice note gagal dikirim")); }); }
            finally { file.delete(); }
        });
    }

    protected String absoluteVoiceContent(String content) {
        String url = ChatVoiceNote.voiceUrl(content);
        if (!(url.startsWith("http://") || url.startsWith("https://"))) url = BASE_URL + (url.startsWith("/") ? url.substring(1) : url);
        return ChatVoiceNote.encode(url, ChatVoiceNote.voiceDuration(content));
    }

    protected PendingText addPendingText(String content) {
        LinearLayout wrapper = messageWrapper(true);
        TextView bubble = text(content, 13, "#FFFFFF", false); bubble.setPadding(dp(13), dp(9), dp(13), dp(9)); bubble.setBackground(gradient("#086BFF", "#2EA2FF", 17)); wrapper.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        TextView state = text("Pending…", 9, "#94A3B8", false); state.setPadding(dp(7), dp(2), dp(7), 0); wrapper.addView(state, new LinearLayout.LayoutParams(-2, -2));
        animateMessage(wrapper, true);
        scrollBottom(); return new PendingText(wrapper, state);
    }

    protected void loadMessages(boolean showLoading) {
        if (loading) return;

        loading = true;
        if (showLoading) progress.setVisibility(View.VISIBLE);
        int requestedLastId = 0;

        DriverNetworkExecutor.execute(() -> {
            try {
                String endpoint = GET_CHAT_URL
                        + "?order_id="
                        + URLEncoder.encode(
                        orderId,
                        StandardCharsets.UTF_8.name()
                )
                        + "&order_db_id="
                        + URLEncoder.encode(orderDbId, StandardCharsets.UTF_8.name())
                        + "&source="
                        + URLEncoder.encode(
                        orderSource,
                        StandardCharsets.UTF_8.name()
                )
                        + "&room_id="
                        + URLEncoder.encode(
                        roomId,
                        StandardCharsets.UTF_8.name()
                )
;

                if (requestedLastId > 0) {
                    endpoint += "&last_id=" + requestedLastId;
                }

                JSONObject response =
                        DriverMessageApi.get(session, endpoint);

                postUi(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    handleResponse(response, firstLoad);
                });

            } catch (Exception error) {
                postUi(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    statusText.setText("Koneksi chat bermasalah");
                });
            }
        });
    }

    protected void handleResponse(
            JSONObject response,
            boolean reset
    ) {
        orderStatus = response.optString("status", orderStatus);
        String canonicalRoom = normalizeRoom(response.optString("room_id", ""));
        if (!canonicalRoom.isEmpty() && !canonicalRoom.equals(roomId)) {
            roomId = canonicalRoom;
            DriverChatNotificationPoller.setOpenRoom(roomId);
        }

        boolean ended = response.optBoolean("ended", false)
                || DriverMessageStatus.isEnded(orderStatus);

        if (ended) {
            readOnly = true;
            applyReadOnlyState();
            main.removeCallbacks(refreshRunnable);
        } else {
            statusText.setText(DriverMessageStatus.orderLabel(
                    orderStatus, orderType));
        }

        JSONObject customer = response.optJSONObject("customer");
        if (customer != null) {
            String serverName = first(
                    customer.optString("name"),
                    customer.optString("username"),
                    customer.optString("customer_name"));
            if (!serverName.isEmpty()) {
                participantName = serverName;
                participantText.setText(serverName);
            }
        }

        if (!response.optBoolean("success", false)) {
            statusText.setText(first(
                    response.optString("message"),
                    "Gagal memuat chat"));
            return;
        }

        JSONArray array = response.optJSONArray("messages");
        if (array == null) return;

        if (reset) {
            messagesBox.removeAllViews();
            receiptViews.clear();
        }

        boolean added = false;

        for (int i = 0; i < array.length(); i++) {
            JSONObject message = array.optJSONObject(i);
            if (message == null) continue;

            int id = message.optInt("id", 0);
            if (!reset && id <= lastId) {
                updateReceipt(message);
                continue;
            }
            if (id > lastId) lastId = id;

            addBubble(message, !firstLoad);
            added = true;
        }

        if (reset && array.length() == 0) {
            addSystemMessage("Belum ada pesan pada percakapan ini.");
        }

        firstLoad = false;
        if (added || reset) scrollBottom();

        if (lastId > 0) {
            scheduleMessagesReadThrough(lastId);
        }
    }

    protected boolean isChatActuallyVisible() {
        if (destroyed || !chatVisible || !hasWindowFocus()) return false;

        PowerManager powerManager =
                (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && !powerManager.isInteractive()) return false;

        KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) return false;

        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor == null || decor.getWindowVisibility() != View.VISIBLE || !decor.isShown()) return false;

        ActivityManager.RunningAppProcessInfo processInfo =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(processInfo);
        if (processInfo.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return false;

        long focusedFor =
                SystemClock.elapsedRealtime() - focusedSinceElapsedMs;
        return focusedSinceElapsedMs > 0L
                && focusedFor >= MIN_READ_VISIBILITY_MS;
    }

    protected void scheduleMessagesReadThrough(int readThroughId) {
        if (readThroughId <= 0 || readOnly || destroyed) return;

        pendingReadThroughId = Math.max(pendingReadThroughId, readThroughId);
        main.removeCallbacks(readReceiptRunnable);

        if (!chatVisible || !hasWindowFocus()) return;

        long focusedFor = focusedSinceElapsedMs > 0L
                ? SystemClock.elapsedRealtime() - focusedSinceElapsedMs
                : 0L;
        long delay = Math.max(0L, MIN_READ_VISIBILITY_MS - focusedFor);
        main.postDelayed(readReceiptRunnable, delay);
    }

    protected void sendReadReceiptNow(int readThroughId) {
        if (readThroughId <= 0 || !isChatActuallyVisible()) return;

        final int generation = readVisibilityGeneration;
        final long visibleMs = Math.max(
                MIN_READ_VISIBILITY_MS,
                SystemClock.elapsedRealtime() - focusedSinceElapsedMs
        );

        DriverNetworkExecutor.execute(() -> {
            try {
                if (generation != readVisibilityGeneration || !isChatActuallyVisible()) return;

                String endpoint = GET_CHAT_URL
                        + "?room_id=" + URLEncoder.encode(roomId, StandardCharsets.UTF_8.name())
                        + "&viewer_type=driver"
                        + "&mark_read=1"
                        + "&read_source=chat_room_foreground_v2"
                        + "&visible_ms=" + visibleMs
                        + "&read_through_id=" + readThroughId;

                String raw = DriverMessageApi.get(session, endpoint).toString();
                JSONObject result = new JSONObject(raw == null ? "{}" : raw);

                if (result.optBoolean("success", false)) {
                    if (pendingReadThroughId <= readThroughId) {
                        pendingReadThroughId = 0;
                    }
                    main.postDelayed(() -> {
                        if (!destroyed && chatVisible) loadMessages(false);
                    }, 250L);
                } else {
                    main.postDelayed(
                            () -> scheduleMessagesReadThrough(readThroughId),
                            800L
                    );
                }
            } catch (Exception ignored) {
                main.postDelayed(
                        () -> scheduleMessagesReadThrough(readThroughId),
                        1200L
                );
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        readVisibilityGeneration++;
        main.removeCallbacks(readReceiptRunnable);
        if (hasFocus && chatVisible) {
            focusedSinceElapsedMs = SystemClock.elapsedRealtime();
            if (lastId > 0) {
                scheduleMessagesReadThrough(lastId);
            }
        } else {
            focusedSinceElapsedMs = 0L;
        }
    }

    protected void addBubble(JSONObject message, boolean animate) {
        String sender = DriverMessageStatus.normalize(
                message.optString("sender_type", ""));
        boolean mine = sender.equals("driver");
        String content = message.optString("message", "");

        LinearLayout wrapper = messageWrapper(mine);

        if (ChatVoiceNote.isVoice(content)) {
            wrapper.addView(ChatVoiceNote.createPlayerBubble(this, absoluteVoiceContent(content), mine), new LinearLayout.LayoutParams(-2, -2));
        } else if (content.startsWith(IMAGE_V2_PREFIX)
                || content.startsWith(IMAGE_PREFIX)) {
            String previewUrl;
            String hdUrl;

            if (content.startsWith(IMAGE_V2_PREFIX)) {
                String value = content.substring(
                        IMAGE_V2_PREFIX.length()).trim();
                String[] parts = value.split("\\|", 2);
                previewUrl = parts.length > 0 ? parts[0].trim() : "";
                hdUrl = parts.length > 1 ? parts[1].trim() : previewUrl;
            } else {
                previewUrl = content.substring(
                        IMAGE_PREFIX.length()).trim();
                hdUrl = previewUrl;
            }

            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(roundStroke("#FFFFFF", "#D7E6F8", 16, 1));
            image.setPadding(dp(2), dp(2), dp(2), dp(2));
            wrapper.addView(
                    image,
                    new LinearLayout.LayoutParams(dp(220), dp(165)));

            loadRemoteImage(image, previewUrl);

            TextView hint = text(
                    "Ketuk untuk lihat HD",
                    9,
                    "#0B7CFF",
                    true);
            wrapper.addView(
                    hint,
                    new LinearLayout.LayoutParams(-2, -2));

            image.setOnClickListener(v -> showHdImage(hdUrl));
            hint.setOnClickListener(v -> showHdImage(hdUrl));

        } else {
            TextView bubble = text(
                    content,
                    13,
                    mine ? "#FFFFFF" : "#0F172A",
                    false);
            bubble.setPadding(dp(13), dp(9), dp(13), dp(9));
            bubble.setMaxWidth((int)(
                    getResources().getDisplayMetrics().widthPixels * 0.75));
            bubble.setBackground(
                    mine
                            ? gradient("#086BFF", "#2EA2FF", 17)
                            : roundStroke(
                            "#FFFFFF", "#D7E6F8", 17, 1));
            wrapper.addView(
                    bubble,
                    new LinearLayout.LayoutParams(-2, -2));
        }

        String time = formatTime(
                message.optString("created_at", ""));
        if (!time.isEmpty()) {
            String receiptText = mine
                    ? (message.optInt("is_read", message.optString("read_at", "").trim().isEmpty() ? 0 : 1) == 0
                    ? "  ✓ Terkirim" : "  ✓✓ Dibaca")
                    : "";
            TextView view = text(time + receiptText, 9, "#94A3B8", false);
            view.setPadding(dp(7), dp(3), dp(7), 0);
            wrapper.addView(
                    view,
                    new LinearLayout.LayoutParams(-2, -2));
            if (mine) receiptViews.put(message.optInt("id", 0), view);
        }

        if (animate) animateMessage(wrapper, mine);
    }

    protected void updateReceipt(JSONObject message) {
        int id = message.optInt("id", 0);
        TextView receipt = receiptViews.get(id);
        if (receipt == null) return;

        String sender = DriverMessageStatus.normalize(message.optString("sender_type", ""));
        if (!"driver".equals(sender)) return;

        String time = formatTime(message.optString("created_at", ""));
        boolean read = message.optInt("is_read", message.optString("read_at", "").trim().isEmpty() ? 0 : 1) == 1;
        String next = time + (read ? "  ✓✓ Dibaca" : "  ✓ Terkirim");
        if (!next.contentEquals(receipt.getText())) {
            receipt.setText(next);
            receipt.setAlpha(0.35f);
            receipt.animate().alpha(1f).setDuration(220).start();
        }
    }
}
