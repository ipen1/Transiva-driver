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
abstract class DriverChatRoomActivityLayer1 extends Activity {

    protected static final String BASE_URL = "https://transiva.my.id/";
    protected static final String GET_CHAT_URL =
            BASE_URL + "server/getChat.php";
    protected static final String SEND_CHAT_URL =
            BASE_URL + "server/sendChat.php";
    protected static final String UPLOAD_IMAGE_URL =
            BASE_URL + "server/upload_chat_image.php";
    protected static final String UPLOAD_VOICE_URL =
            BASE_URL + "server/upload_chat_voice.php";

    protected static final String IMAGE_PREFIX = "[[IMAGE]]";
    protected static final String IMAGE_V2_PREFIX = "[[IMAGE2]]";
    protected static final long REFRESH_MS = 20000L;

    protected static final int REQUEST_GALLERY = 5101;
    protected static final int REQUEST_INTERNAL_CAMERA = 5102;
    protected static final int REQUEST_CAMERA_PERMISSION = 5103;
    protected static final int REQUEST_AUDIO_PERMISSION = 5104;

    protected final Handler main = new Handler(Looper.getMainLooper());

    protected LinearLayout messagesBox;
    protected ScrollView messagesScroll;
    protected TextView participantText;
    protected TextView statusText;
    protected EditText input;
    protected Button sendButton;
    protected Button attachButton;
    protected Button voiceButton;
    protected ProgressBar progress;
    protected LinearLayout inputCard;

    protected String orderId = "";
    protected String orderDbId = "";
    protected String roomId = "";
    protected String participantName = "Customer";
    protected String orderType = "";
    protected String orderStatus = "";
    protected String orderSource = "orders";
    protected SessionManager session;

    protected boolean readOnly;
    protected boolean loading;
    protected boolean sending;
    protected boolean uploading;
    protected boolean destroyed;
    protected boolean chatVisible;
    protected volatile long focusedSinceElapsedMs = 0L;
    protected volatile int readVisibilityGeneration = 0;
    protected volatile int pendingReadThroughId = 0;
    protected static final long MIN_READ_VISIBILITY_MS = 1200L;

    /*
     * Receipt dijadwalkan ulang setelah Chat Room benar-benar fokus.
     * Dengan begitu status Dibaca berubah tanpa menunggu pesan balasan.
     */
    protected final Runnable readReceiptRunnable =
            () -> {
                int throughId = pendingReadThroughId;
                if (throughId <= 0 || !isChatActuallyVisible()) return;
                sendReadReceiptNow(throughId);
            };
    protected int lastId;
    protected boolean firstLoad = true;
    protected final SparseArray<TextView> receiptViews = new SparseArray<>();

    protected final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && !readOnly && chatVisible && hasWindowFocus()) {
                loadMessages(false);
                main.postDelayed(this, WaveLoadGuard.jitter(DriverPollingCoordinator.interval(DriverChatRoomActivityLayer1.this, REFRESH_MS)));
            }
        }
    };

    protected final class PendingPhotoBubble {
        final LinearLayout root;
        final FrameLayout loadingLayer;
        final TextView state;

        PendingPhotoBubble(
                LinearLayout root,
                FrameLayout loadingLayer,
                TextView state
        ) {
            this.root = root;
            this.loadingLayer = loadingLayer;
            this.state = state;
        }

        void markSuccess() {
            loadingLayer.setVisibility(View.GONE);
            state.setText("Terkirim • memuat chat…");
            state.setTextColor(Color.parseColor("#0B7CFF"));
        }

        void markFailed(String message) {
            loadingLayer.setVisibility(View.GONE);
            state.setText("Gagal dikirim");
            state.setTextColor(Color.parseColor("#DC2626"));
            toast(first(message, "Foto gagal dikirim"));
        }
    }

    protected static final class PendingText { final LinearLayout root; final TextView state; PendingText(LinearLayout root, TextView state){this.root=root;this.state=state;} void markNetworkPending(){state.setText("Pending • jaringan");} }

    protected void animateMessage(View view, boolean mine) {
        view.setAlpha(0f);
        view.setTranslationX(dp(mine ? 18 : -18));
        view.setScaleX(0.97f);
        view.setScaleY(0.97f);
        view.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    protected LinearLayout messageWrapper(boolean mine) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        messagesBox.addView(wrapper, lp);
        return wrapper;
    }

    protected void loadRemoteImage(ImageView view, String rawUrl) {
        String url = absoluteUrl(rawUrl);
        if (url.isEmpty()) return;

        DriverNetworkExecutor.execute(() -> {
            HttpURLConnection connection = null;
            InputStream inputStream = null;

            try {
                connection = DriverHttpTransport.open(url);
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(20000);
                connection.setUseCaches(true);
                inputStream = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

                if (bitmap != null) {
                    postUi(() -> {
                        view.setAlpha(0f);
                        view.setImageBitmap(bitmap);
                        view.animate().alpha(1f).setDuration(180).start();
                    });
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    if (inputStream != null) inputStream.close();
                } catch (Exception ignored) {}
                if (connection != null) connection.disconnect();
            }
        });
    }

    protected void showHdImage(String rawUrl) {
        String url = absoluteUrl(rawUrl);
        if (url.isEmpty()) return;

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));

        TextView close = text("✕", 24, "#FFFFFF", true);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round("#66000000", 20));
        close.setOnClickListener(v -> dialog.dismiss());

        FrameLayout.LayoutParams closeLp =
                new FrameLayout.LayoutParams(dp(48), dp(48));
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.setMargins(0, dp(12), dp(12), 0);
        frame.addView(close, closeLp);

        dialog.setContentView(frame);
        Window window = dialog.getWindow();

        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(-1, -1);
        }

        dialog.show();

        if (window != null) window.setLayout(-1, -1);
        loadRemoteImage(image, url);
    }

    protected void sendMessage() {
        if (readOnly || sending || uploading) return;

        String message = input.getText().toString().trim();
        if (message.isEmpty()) return;

        sending = true;
        setSendingEnabled(false);
        final PendingText pending = addPendingText(message);
        input.setText("");

        JSONObject body = new JSONObject();

        try {
            body.put("order_id", orderId);
            body.put("order_db_id", orderDbId);
            body.put("source", orderSource);
            body.put("room_id", roomId);
            body.put("sender_type", "driver");
            body.put("message", message);
        } catch (Exception error) {
            sending = false;
            setSendingEnabled(true);
            return;
        }

        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject response =
                        DriverMessageApi.post(session, SEND_CHAT_URL, body);

                postUi(() -> {
                    sending = false;
                    setSendingEnabled(true);

                    if (response.optBoolean("success", false)) {
                        JSONObject sentChat = response.optJSONObject("chat");
                        if (sentChat != null) {
                            String canonical = normalizeRoom(sentChat.optString("room_id", ""));
                            if (!canonical.isEmpty()) roomId = canonical;
                            if (pending != null) messagesBox.removeView(pending.root);
                            int sentId = sentChat.optInt("id", 0);
                            if (sentId > lastId) { lastId = sentId; addBubble(sentChat, true); scrollBottom(); }
                        } else if (pending != null) {
                            messagesBox.removeView(pending.root);
                        }
                        DriverChatNotificationPoller.setOpenRoom(roomId);
                        main.postDelayed(() -> loadMessages(false), 180L);
                    } else {
                        if (pending != null) pending.markNetworkPending();
                        toast(first(
                                response.optString("message"),
                                "Pesan gagal dikirim"));
                    }
                });

            } catch (Exception error) {
                postUi(() -> {
                    sending = false;
                    setSendingEnabled(true);
                    if (pending != null) pending.markNetworkPending();
                    toast(first(error.getMessage(),
                            "Pesan gagal dikirim"));
                });
            }
        });
    }

    protected void applyReadOnlyState() {
        if (inputCard == null || !readOnly) return;

        input.setEnabled(false);
        input.setHint("Percakapan ini hanya dapat dibaca");
        attachButton.setEnabled(false);
        attachButton.setAlpha(0.45f);
        if (voiceButton != null) { voiceButton.setEnabled(false); voiceButton.setAlpha(0.45f); }
        sendButton.setEnabled(false);
        sendButton.setText("Selesai");
        sendButton.setAlpha(0.55f);
        statusText.setText("Order selesai • riwayat hanya baca");
    }

    protected void setSendingEnabled(boolean enabled) {
        attachButton.setEnabled(enabled && !readOnly);
        sendButton.setEnabled(enabled && !readOnly);
        if (voiceButton != null) voiceButton.setEnabled(enabled && !readOnly);
        input.setEnabled(enabled && !readOnly);
    }

    protected void addSystemMessage(String value) {
        TextView view = text(value, 11, "#64748B", false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        messagesBox.addView(view);
    }

    protected void scrollBottom() {
        messagesScroll.post(() ->
                messagesScroll.fullScroll(View.FOCUS_DOWN));
    }

    protected String absoluteUrl(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return "";
        if (clean.startsWith("http://")
                || clean.startsWith("https://")) {
            return clean;
        }
        if (clean.startsWith("/")) {
            return BASE_URL.substring(0, BASE_URL.length() - 1) + clean;
        }
        return BASE_URL + clean;
    }

    protected String normalizeRoom(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return "";
        return clean.toUpperCase(Locale.US).startsWith("ROOM-")
                ? clean
                : "ROOM-" + clean;
    }

    protected String serviceName(String type) {
        String value = clean(type).toLowerCase(Locale.US);
        if (value.contains("food")) return "TransFood";
        if (value.contains("shop") || value.contains("mart")) return "TransShop";
        if (value.contains("car") || value.contains("mobil")) return "TransCar";
        if (value.contains("pickup")) return "TransPickup";
        return "TransRide";
    }

    protected String formatTime(String raw) {
        if (clean(raw).isEmpty()) return "";

        String[] formats = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss"
        };

        for (String format : formats) {
            try {
                Date date = new SimpleDateFormat(
                        format, Locale.US).parse(raw);
                if (date != null) {
                    return new SimpleDateFormat(
                            "HH:mm",
                            new Locale("id", "ID")
                    ).format(date);
                }
            } catch (Exception ignored) {}
        }

        return raw;
    }

    protected Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round("#0B7CFF", 15));
        return button;
    }

    protected TextView text(
            String value,
            int sp,
            String color,
            boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    protected GradientDrawable round(String fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(fill));
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    protected GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable shape = round(fill, radius);
        shape.setStroke(dp(width), Color.parseColor(stroke));
        return shape;
    }

    protected GradientDrawable gradient(
            String start,
            String end,
            int radius
    ) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.parseColor(start),
                        Color.parseColor(end)
                });
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    protected int dp(int value) {
        return Math.round(value
                * getResources().getDisplayMetrics().density);
    }

    protected String first(String... values) {
        if (values == null) return "";

        for (String value : values) {
            String clean = clean(value);
            if (!clean.isEmpty()
                    && !"null".equalsIgnoreCase(clean)
                    && !"undefined".equalsIgnoreCase(clean)) {
                return clean;
            }
        }

        return "";
    }

    protected String clean(String value) {
        return value == null ? "" : value.trim();
    }

    protected void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    protected void showMessage(String title, String message) {
        PremiumDialogs.builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Tutup", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DriverMessageUnreadRepository.markRead(this, orderId, roomId);
        chatVisible = true;
        readVisibilityGeneration++;
        focusedSinceElapsedMs = hasWindowFocus()
                ? SystemClock.elapsedRealtime() : 0L;
        DriverChatNotificationPoller.setOpenRoom(roomId);
        main.removeCallbacks(refreshRunnable);
        if (!readOnly) {
            loadMessages(false);
            if (lastId > 0) {
                scheduleMessagesReadThrough(lastId);
            }
            main.postDelayed(refreshRunnable, WaveLoadGuard.jitter(DriverPollingCoordinator.interval(DriverChatRoomActivityLayer1.this, REFRESH_MS)));
        }
    }

    @Override
    protected void onPause() {
        chatVisible = false;
        readVisibilityGeneration++;
        focusedSinceElapsedMs = 0L;
        main.removeCallbacks(refreshRunnable);
        main.removeCallbacks(readReceiptRunnable);
        DriverChatNotificationPoller.clearOpenRoom(roomId);
        super.onPause();
    }

    /** Post hasil async hanya selama Activity masih hidup. */
    protected void postUi(Runnable action) {
        if (action == null || destroyed || isFinishing()) return;
        main.post(() -> {
            if (!destroyed && !isFinishing()) action.run();
        });
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        DriverChatNotificationPoller.clearOpenRoom(roomId);
        main.removeCallbacks(readReceiptRunnable);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    // Cross-layer contracts keep the split type-safe without duplicating state.
    protected abstract void readIntent();
    protected abstract void callCustomer();
    protected abstract View buildScreen();
    protected abstract void showAttachmentMenu();
    protected abstract void openCamera();
    protected abstract void launchCamera();
    protected abstract void openGallery();
    protected abstract void processSelectedPhoto(Uri uri);
    protected abstract void processCameraFile(String path);
    protected abstract void uploadPhoto( ChatImageProcessor.ImagePayload payload );
    protected abstract PendingPhotoBubble addPendingPhotoBubble( ChatImageProcessor.ImagePayload payload );
    protected abstract void setupVoiceRecorder();
    protected abstract void uploadVoiceNote(File file, long durationMs);
    protected abstract String absoluteVoiceContent(String content);
    protected abstract PendingText addPendingText(String content);
    protected abstract void loadMessages(boolean showLoading);
    protected abstract void handleResponse( JSONObject response, boolean reset );
    protected abstract boolean isChatActuallyVisible();
    protected abstract void scheduleMessagesReadThrough(int readThroughId);
    protected abstract void sendReadReceiptNow(int readThroughId);
    protected abstract void addBubble(JSONObject message, boolean animate);
    protected abstract void updateReceipt(JSONObject message);

}
