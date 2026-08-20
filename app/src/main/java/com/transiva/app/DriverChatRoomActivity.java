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

public class DriverChatRoomActivity extends Activity {

    private static final String BASE_URL = "https://transiva.my.id/";
    private static final String GET_CHAT_URL =
            BASE_URL + "server/getChat.php";
    private static final String SEND_CHAT_URL =
            BASE_URL + "server/sendChat.php";
    private static final String UPLOAD_IMAGE_URL =
            BASE_URL + "server/upload_chat_image.php";
    private static final String UPLOAD_VOICE_URL =
            BASE_URL + "server/upload_chat_voice.php";

    private static final String IMAGE_PREFIX = "[[IMAGE]]";
    private static final String IMAGE_V2_PREFIX = "[[IMAGE2]]";
    private static final long REFRESH_MS = 20000L;

    private static final int REQUEST_GALLERY = 5101;
    private static final int REQUEST_INTERNAL_CAMERA = 5102;
    private static final int REQUEST_CAMERA_PERMISSION = 5103;
    private static final int REQUEST_AUDIO_PERMISSION = 5104;

    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout messagesBox;
    private ScrollView messagesScroll;
    private TextView participantText;
    private TextView statusText;
    private EditText input;
    private Button sendButton;
    private Button attachButton;
    private Button voiceButton;
    private ProgressBar progress;
    private LinearLayout inputCard;

    private String orderId = "";
    private String orderDbId = "";
    private String roomId = "";
    private String participantName = "Customer";
    private String orderType = "";
    private String orderStatus = "";
    private String orderSource = "orders";
    private SessionManager session;

    private boolean readOnly;
    private boolean loading;
    private boolean sending;
    private boolean uploading;
    private boolean destroyed;
    private boolean chatVisible;
    private volatile long focusedSinceElapsedMs = 0L;
    private volatile int readVisibilityGeneration = 0;
    private volatile int pendingReadThroughId = 0;
    private static final long MIN_READ_VISIBILITY_MS = 1200L;

    /*
     * Receipt dijadwalkan ulang setelah Chat Room benar-benar fokus.
     * Dengan begitu status Dibaca berubah tanpa menunggu pesan balasan.
     */
    private final Runnable readReceiptRunnable =
            () -> {
                int throughId = pendingReadThroughId;
                if (throughId <= 0 || !isChatActuallyVisible()) return;
                sendReadReceiptNow(throughId);
            };
    private int lastId;
    private boolean firstLoad = true;
    private final SparseArray<TextView> receiptViews = new SparseArray<>();

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!destroyed && !readOnly && chatVisible && hasWindowFocus()) {
                loadMessages(false);
                main.postDelayed(this, WaveLoadGuard.jitter(REFRESH_MS));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#0B7CFF"));
        getWindow().setNavigationBarColor(Color.parseColor("#071426"));

        session = new SessionManager(this);
        readIntent();
        setContentView(buildScreen());
        DriverAppSettings.apply(this);
        DriverChatNotificationPoller.requestPermission(this);
        DriverChatNotificationPoller.start(this);
        DriverChatNotificationPoller.setOpenRoom(roomId);

        if (roomId.isEmpty()) {
            showMessage(
                    "Chat tidak tersedia",
                    "Room percakapan tidak ditemukan.");
            return;
        }

        applyReadOnlyState();
        loadMessages(true);

        if (!readOnly) {
            main.postDelayed(refreshRunnable, WaveLoadGuard.jitter(REFRESH_MS));
        }
    }

    private void readIntent() {
        orderId = first(
                getIntent().getStringExtra("order_id"),
                "");
        orderDbId = first(
                getIntent().getStringExtra("order_db_id"),
                getIntent().getStringExtra("id"),
                "");
        roomId = normalizeRoom(first(
                getIntent().getStringExtra("room_id"),
                orderId.isEmpty() ? "" : "ROOM-" + orderId));
        participantName = first(
                getIntent().getStringExtra("participant_name"),
                getIntent().getStringExtra("customer_name"),
                "Customer");
        orderType = first(
                getIntent().getStringExtra("order_type"),
                "");
        orderStatus = first(
                getIntent().getStringExtra("order_status"),
                "");

        orderSource = first(
                getIntent().getStringExtra("order_source"),
                "orders"
        );
        readOnly = getIntent().getBooleanExtra("read_only", false)
                || DriverMessageStatus.isEnded(orderStatus);
    }

    private void callCustomer() {
        if (orderId == null || orderId.trim().isEmpty()) {
            Toast.makeText(this, "Order tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent call = new Intent(this, WebRtcCallActivity.class);
        call.putExtra("order_id", orderId);
        call.putExtra("source", orderSource);
        call.putExtra("peer_name", participantName);
        call.putExtra("incoming", false);
        startActivity(call);
    }

    private View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Color.parseColor("#F4F8FD"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(10));
        page.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), dp(9), dp(10), dp(9));
        header.setBackground(roundStroke(
                "#FFFFFF", "#DCE8F6", 18, 1));

        TextView back = text("←", 24, "#0B7CFF", true);
        back.setGravity(Gravity.CENTER);
        back.setIncludeFontPadding(false);
        back.setPadding(0, 0, 0, dp(1));
        back.setBackground(round("#EAF4FF", 15));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, 0, 0);

        participantText = text(
                participantName, 16, "#0B3A78", true);
        participantText.setSingleLine(true);
        titleBox.addView(participantText);

        statusText = text(
                readOnly ? "Riwayat percakapan" : "Menghubungkan chat...",
                10,
                readOnly ? "#8495A8" : "#0B7CFF",
                true);
        titleBox.addView(statusText);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        TextView service = text(
                serviceName(orderType), 10, "#0B7CFF", true);
        service.setPadding(dp(9), dp(5), dp(9), dp(5));
        service.setBackground(round("#EAF4FF", 12));
        header.addView(service);

        if (!readOnly) {
            TextView callButton = text("☎", 22, "#0B7CFF", true);
            callButton.setGravity(Gravity.CENTER);
            callButton.setContentDescription("Telepon customer");
            callButton.setBackground(round("#EAF4FF", 15));
            LinearLayout.LayoutParams callLp =
                    new LinearLayout.LayoutParams(dp(46), dp(46));
            callLp.setMargins(dp(7), 0, 0, 0);
            header.addView(callButton, callLp);
            callButton.setOnClickListener(v -> callCustomer());
        }

        root.addView(header);

        messagesScroll = new ScrollView(this);
        messagesScroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp =
                new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.setMargins(0, dp(10), 0, dp(10));
        root.addView(messagesScroll, scrollLp);

        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        messagesBox.setPadding(dp(2), dp(8), dp(2), dp(8));
        messagesScroll.addView(
                messagesBox,
                new ScrollView.LayoutParams(-1, -2));

        inputCard = new LinearLayout(this);
        inputCard.setGravity(Gravity.CENTER_VERTICAL);
        inputCard.setPadding(dp(9), dp(7), dp(9), dp(7));
        inputCard.setBackground(roundStroke(
                "#FFFFFF", "#D7E6F8", 20, 1));

        attachButton = new Button(this);
        attachButton.setText("+");
        attachButton.setAllCaps(false);
        attachButton.setTextSize(22);
        attachButton.setTextColor(Color.parseColor("#0B7CFF"));
        attachButton.setBackground(round("#EAF4FF", 15));
        attachButton.setOnClickListener(v -> showAttachmentMenu());
        inputCard.addView(
                attachButton,
                new LinearLayout.LayoutParams(dp(44), -1));

        input = new EditText(this);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setTextSize(13);
        input.setTextColor(Color.parseColor("#0F172A"));
        input.setHintTextColor(Color.parseColor("#94A3B8"));
        input.setHint("Ketik pesan...");
        input.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(dp(13), 0, dp(13), 0);
        input.setBackground(roundStroke(
                "#F8FBFF", "#D8E4F2", 16, 1));

        LinearLayout.LayoutParams inputLp =
                new LinearLayout.LayoutParams(0, -1, 1);
        inputLp.setMargins(dp(7), 0, 0, 0);
        inputCard.addView(input, inputLp);

        sendButton = primaryButton("Kirim");
        LinearLayout.LayoutParams sendLp =
                new LinearLayout.LayoutParams(dp(74), -1);
        sendLp.setMargins(dp(7), 0, 0, 0);
        inputCard.addView(sendButton, sendLp);

        voiceButton = new Button(this);
        voiceButton.setText("🎙"); voiceButton.setTextSize(18); voiceButton.setAllCaps(false);
        voiceButton.setPadding(0, 0, 0, 0); voiceButton.setTextColor(Color.parseColor("#0B7CFF"));
        voiceButton.setBackground(round("#EAF4FF", 15));
        LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(dp(48), -1);
        voiceLp.setMargins(dp(7), 0, 0, 0); inputCard.addView(voiceButton, voiceLp);
        setupVoiceRecorder();

        sendButton.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); sendMessage(); });
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;

            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                sendMessage();
                return true;
            }
            return false;
        });

        root.addView(inputCard, new LinearLayout.LayoutParams(-1, dp(62)));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams p =
                new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
        page.addView(progress, p);
        return page;
    }

    private void showAttachmentMenu() {
        if (readOnly || uploading) return;

        new AlertDialog.Builder(this)
                .setTitle("Kirim Foto")
                .setItems(
                        new String[]{"Ambil Foto", "Pilih dari Galeri"},
                        (dialog, which) -> {
                            if (which == 0) openCamera();
                            else openGallery();
                        })
                .show();
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }

        launchCamera();
    }

    private void launchCamera() {
        try {
            Intent intent = new Intent(this, ChatCameraActivity.class);
            startActivityForResult(intent, REQUEST_INTERNAL_CAMERA);
        } catch (Exception error) {
            toast("Kamera tidak tersedia");
        }
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_GALLERY);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchCamera();
            } else {
                toast("Izin kamera diperlukan.");
            }
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_INTERNAL_CAMERA) {
            if (resultCode != RESULT_OK || data == null) return;

            String path = data.getStringExtra("photo_path");
            if (clean(path).isEmpty()) {
                toast("File kamera tidak ditemukan.");
                return;
            }

            processCameraFile(path);
            return;
        }

        if (requestCode == REQUEST_GALLERY
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            processSelectedPhoto(data.getData());
        }
    }

    private void processSelectedPhoto(Uri uri) {
        if (uri == null || uploading) return;

        uploading = true;
        setSendingEnabled(false);
        progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                ChatImageProcessor.ImagePayload payload =
                        ChatImageProcessor.fromUri(
                                getContentResolver(), uri);

                main.post(() -> {
                    uploading = false;
                    progress.setVisibility(View.GONE);
                    uploadPhoto(payload);
                });

            } catch (Exception error) {
                main.post(() -> {
                    uploading = false;
                    progress.setVisibility(View.GONE);
                    setSendingEnabled(true);
                    toast(first(error.getMessage(),
                            "Foto tidak dapat dibaca."));
                });
            }
        }).start();
    }

    private void processCameraFile(String path) {
        if (clean(path).isEmpty() || uploading) return;

        uploading = true;
        setSendingEnabled(false);
        progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            File file = new File(path);

            try {
                ChatImageProcessor.ImagePayload payload =
                        ChatImageProcessor.fromFile(file);

                main.post(() -> {
                    uploading = false;
                    progress.setVisibility(View.GONE);
                    uploadPhoto(payload);
                    try { file.delete(); } catch (Exception ignored) {}
                });

            } catch (Exception error) {
                main.post(() -> {
                    uploading = false;
                    progress.setVisibility(View.GONE);
                    setSendingEnabled(true);
                    toast("Foto kamera tidak dapat dibaca.");
                });
            }
        }).start();
    }

    private void uploadPhoto(
            ChatImageProcessor.ImagePayload payload
    ) {
        if (payload == null || readOnly || uploading) return;

        uploading = true;
        setSendingEnabled(false);

        final PendingPhotoBubble pendingBubble =
                addPendingPhotoBubble(payload);
        scrollBottom();

        new Thread(() -> {
            try {
                JSONObject response =
                        DriverChatMediaApi.uploadImagePair(
                                UPLOAD_IMAGE_URL,
                                roomId,
                                "driver",
                                payload);

                main.post(() -> {
                    uploading = false;
                    setSendingEnabled(true);

                    if (response.optBoolean("success", false)) {
                        pendingBubble.markSuccess();
                        main.postDelayed(() -> {
                            if (pendingBubble.root.getParent() != null) {
                                messagesBox.removeView(pendingBubble.root);
                            }
                            firstLoad = true;
                            lastId = 0;
                            loadMessages(false);
                        }, 450L);
                    } else {
                        pendingBubble.markFailed(first(
                                response.optString("message"),
                                "Foto gagal dikirim"));
                    }
                });

            } catch (Exception error) {
                main.post(() -> {
                    uploading = false;
                    setSendingEnabled(true);
                    pendingBubble.markFailed(first(
                            error.getMessage(),
                            "Foto gagal dikirim"));
                });
            }
        }).start();
    }

    private PendingPhotoBubble addPendingPhotoBubble(
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

    private final class PendingPhotoBubble {
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

    private void setupVoiceRecorder() {
        ChatVoiceNote.attachRecorder(this, voiceButton, REQUEST_AUDIO_PERMISSION, new ChatVoiceNote.Listener() {
            @Override public void onState(String text, boolean recording, boolean cancelArmed) { statusText.setText(text); voiceButton.setText(cancelArmed ? "✕" : (recording ? "●" : "🎙")); }
            @Override public void onReady(File file, long durationMs) { uploadVoiceNote(file, durationMs); }
            @Override public void onError(String message) { toast(message); voiceButton.setText("🎙"); }
        });
    }

    private void uploadVoiceNote(File file, long durationMs) {
        if (readOnly || uploading || file == null) return;
        uploading = true; voiceButton.setEnabled(false);
        new Thread(() -> {
            try {
                JSONObject upload = DriverMessageApi.uploadVoice(session, UPLOAD_VOICE_URL, orderId, orderSource, roomId, file, durationMs);
                if (!upload.optBoolean("success", false)) throw new IllegalStateException(upload.optString("message", "Upload voice note gagal"));
                String audioUrl = upload.optString("url", upload.optString("audio_url", ""));
                JSONObject body = new JSONObject(); body.put("order_id", orderId); body.put("order_db_id", orderDbId); body.put("source", orderSource); body.put("room_id", roomId); body.put("sender_type", "driver"); body.put("message", ChatVoiceNote.encode(audioUrl, durationMs));
                JSONObject sent = DriverMessageApi.post(session, SEND_CHAT_URL, body);
                main.post(() -> { uploading=false; voiceButton.setEnabled(!readOnly); voiceButton.setText("🎙"); if (sent.optBoolean("success", false)) loadMessages(false); else toast(sent.optString("message", "Voice note gagal dikirim")); });
            } catch (Exception e) { main.post(() -> { uploading=false; voiceButton.setEnabled(!readOnly); voiceButton.setText("🎙"); statusText.setText("Voice note pending • jaringan"); toast(first(e.getMessage(), "Voice note gagal dikirim")); }); }
            finally { file.delete(); }
        }).start();
    }

    private String absoluteVoiceContent(String content) {
        String url = ChatVoiceNote.voiceUrl(content);
        if (!(url.startsWith("http://") || url.startsWith("https://"))) url = BASE_URL + (url.startsWith("/") ? url.substring(1) : url);
        return ChatVoiceNote.encode(url, ChatVoiceNote.voiceDuration(content));
    }

    private PendingText addPendingText(String content) {
        LinearLayout wrapper = messageWrapper(true);
        TextView bubble = text(content, 13, "#FFFFFF", false); bubble.setPadding(dp(13), dp(9), dp(13), dp(9)); bubble.setBackground(gradient("#086BFF", "#2EA2FF", 17)); wrapper.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        TextView state = text("Pending…", 9, "#94A3B8", false); state.setPadding(dp(7), dp(2), dp(7), 0); wrapper.addView(state, new LinearLayout.LayoutParams(-2, -2));
        animateMessage(wrapper, true);
        scrollBottom(); return new PendingText(wrapper, state);
    }

    private static final class PendingText { final LinearLayout root; final TextView state; PendingText(LinearLayout root, TextView state){this.root=root;this.state=state;} void markNetworkPending(){state.setText("Pending • jaringan");} }

    private void loadMessages(boolean showLoading) {
        if (loading) return;

        loading = true;
        if (showLoading) progress.setVisibility(View.VISIBLE);
        int requestedLastId = 0;

        new Thread(() -> {
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

                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    handleResponse(response, firstLoad);
                });

            } catch (Exception error) {
                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    statusText.setText("Koneksi chat bermasalah");
                });
            }
        }).start();
    }

    private void handleResponse(
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

    private boolean isChatActuallyVisible() {
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

    private void scheduleMessagesReadThrough(int readThroughId) {
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

    private void sendReadReceiptNow(int readThroughId) {
        if (readThroughId <= 0 || !isChatActuallyVisible()) return;

        final int generation = readVisibilityGeneration;
        final long visibleMs = Math.max(
                MIN_READ_VISIBILITY_MS,
                SystemClock.elapsedRealtime() - focusedSinceElapsedMs
        );

        new Thread(() -> {
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
        }, "chat-read-ack").start();
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

    private void addBubble(JSONObject message, boolean animate) {
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

    private void updateReceipt(JSONObject message) {
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

    private void animateMessage(View view, boolean mine) {
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

    private LinearLayout messageWrapper(boolean mine) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        messagesBox.addView(wrapper, lp);
        return wrapper;
    }

    private void loadRemoteImage(ImageView view, String rawUrl) {
        String url = absoluteUrl(rawUrl);
        if (url.isEmpty()) return;

        new Thread(() -> {
            HttpURLConnection connection = null;
            InputStream inputStream = null;

            try {
                connection = (HttpURLConnection)
                        new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(20000);
                connection.setUseCaches(true);
                inputStream = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);

                if (bitmap != null) {
                    main.post(() -> {
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
        }).start();
    }

    private void showHdImage(String rawUrl) {
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

    private void sendMessage() {
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

        new Thread(() -> {
            try {
                JSONObject response =
                        DriverMessageApi.post(session, SEND_CHAT_URL, body);

                main.post(() -> {
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
                main.post(() -> {
                    sending = false;
                    setSendingEnabled(true);
                    if (pending != null) pending.markNetworkPending();
                    toast(first(error.getMessage(),
                            "Pesan gagal dikirim"));
                });
            }
        }).start();
    }

    private void applyReadOnlyState() {
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

    private void setSendingEnabled(boolean enabled) {
        attachButton.setEnabled(enabled && !readOnly);
        sendButton.setEnabled(enabled && !readOnly);
        if (voiceButton != null) voiceButton.setEnabled(enabled && !readOnly);
        input.setEnabled(enabled && !readOnly);
    }

    private void addSystemMessage(String value) {
        TextView view = text(value, 11, "#64748B", false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        messagesBox.addView(view);
    }

    private void scrollBottom() {
        messagesScroll.post(() ->
                messagesScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String absoluteUrl(String value) {
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

    private String normalizeRoom(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return "";
        return clean.toUpperCase(Locale.US).startsWith("ROOM-")
                ? clean
                : "ROOM-" + clean;
    }

    private String serviceName(String type) {
        String value = clean(type).toLowerCase(Locale.US);
        if (value.contains("food")) return "TransFood";
        if (value.contains("shop") || value.contains("mart")) return "TransShop";
        if (value.contains("car") || value.contains("mobil")) return "TransCar";
        if (value.contains("pickup")) return "TransPickup";
        return "TransRide";
    }

    private String formatTime(String raw) {
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

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round("#0B7CFF", 15));
        return button;
    }

    private TextView text(
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

    private GradientDrawable round(String fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(fill));
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private GradientDrawable roundStroke(
            String fill,
            String stroke,
            int radius,
            int width
    ) {
        GradientDrawable shape = round(fill, radius);
        shape.setStroke(dp(width), Color.parseColor(stroke));
        return shape;
    }

    private GradientDrawable gradient(
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

    private int dp(int value) {
        return Math.round(value
                * getResources().getDisplayMetrics().density);
    }

    private String first(String... values) {
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

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Tutup", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
            main.postDelayed(refreshRunnable, WaveLoadGuard.jitter(REFRESH_MS));
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

    @Override
    protected void onDestroy() {
        destroyed = true;
        DriverChatNotificationPoller.clearOpenRoom(roomId);
        main.removeCallbacks(readReceiptRunnable);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
