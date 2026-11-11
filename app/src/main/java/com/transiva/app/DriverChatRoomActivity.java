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
public class DriverChatRoomActivity extends DriverChatRoomActivityLayer2 {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DriverSystemBars.setStatusBarColor(this, Color.parseColor("#0B7CFF"));
        DriverSystemBars.setNavigationBarColor(this, Color.parseColor("#071426"));

        session = new SessionManager(this);
        chatScope=new DriverUiScope(this);
        if(!chatScope.alive()){finish();return;}
        readIntent();
        DriverMessageUnreadRepository.markRead(this, orderId, roomId);
        setContentView(buildScreen());
        DriverResponsiveUi.apply(this);
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
            main.postDelayed(refreshRunnable, WaveLoadGuard.jitter(DriverPollingCoordinator.interval(DriverChatRoomActivity.this, REFRESH_MS)));
        }
    }

    protected void readIntent() {
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

    protected void callCustomer() {openRoomCall(false);}
    private void openRoomCall(boolean video) {
        if (readOnly) return;
        if (orderId == null || orderId.trim().isEmpty()) {
            Toast.makeText(this, "Order tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent call = new Intent(this, WebRtcCallActivity.class);
        call.putExtra("order_id", orderId);
        call.putExtra("source", orderSource);
        call.putExtra("peer_name", participantName);
        call.putExtra("peer_photo",participantPhoto);
        call.putExtra("incoming", false);
        call.putExtra("call_type",video?"video":"audio");
        startActivity(call);
    }

    protected View buildScreen() {
        FrameLayout page = new FrameLayout(this);
        chatStyle.backdrop(page);
        chatChrome = new ChatRoomChrome(this, DriverAppSettings.isDarkMode(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(10));
        page.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        chatChrome.styleBar(header);
        chatChrome.addHeaderAction(header, "back", "Kembali").setOnClickListener(v -> finish());

        participantPhoto = CallPeerIdentity.first(participantPhoto, getIntent().getStringExtra("participant_photo"),
                getIntent().getStringExtra("customer_photo"), getIntent().getStringExtra("profile_photo"));
        chatStyle.addAvatar(header,participantName,participantPhoto);
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPaddingRelative(dp(8), 0, dp(2), 0);
        participantText = text(participantName, 16, "#0F172A", true);
        participantText.setTextColor(chatChrome.textColor());
        participantText.setSingleLine(true);
        participantText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(participantText);

        statusText = text(readOnly ? "Riwayat percakapan" : "Menghubungkan chat…", 11, "#64748B", false);
        statusText.setTextColor(chatChrome.secondaryColor());
        statusText.setMaxLines(2);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBox.addView(statusText);
        header.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!readOnly) {
            chatChrome.addCallActions(header, this::callCustomer, () -> openRoomCall(true));
        }

        root.addView(header);
        root.addView(new ActiveCallBanner(this),new LinearLayout.LayoutParams(-1,-2));

        messagesScroll = new ScrollView(this);
        messagesScroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollLp =
                new LinearLayout.LayoutParams(-1, 0, 1);
        scrollLp.setMargins(0, dp(10), 0, dp(10));
        root.addView(messagesScroll, scrollLp);

        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        chatStyle.overflow(chatChrome.addHeaderAction(header, "more", "Opsi percakapan"),null,query->DriverMessageApi.get(session,chatEndpoint()+query,chatScope.owner),messagesBox);
        messagesBox.setPadding(dp(2), dp(8), dp(2), dp(8));
        messagesScroll.addView(
                messagesBox,
                new ScrollView.LayoutParams(-1, -2));

        inputCard = new LinearLayout(this);
        chatChrome.styleComposer(inputCard);
        chatChrome.addAttachment(inputCard).setOnClickListener(v -> showAttachmentMenu());

        input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(3);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setHint("Ketik pesan…");
        chatChrome.addDraft(inputCard, input);
        chatChrome.addDraftActions(inputCard);
        voiceButton = chatChrome.voiceButton();
        setupVoiceRecorder();
        chatChrome.setComposerAvailable(!readOnly);

        chatChrome.sendButton().setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            sendMessage();
        });
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

        root.addView(inputCard, new LinearLayout.LayoutParams(-1, -2));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams p =
                new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
        page.addView(progress, p);
        return page;
    }

    protected void showAttachmentMenu() {
        if (readOnly || uploading || sending || chatChrome.isRecording()) return;

        PremiumDialogs.builder(this)
                .setTitle("Lampiran")
                .setItems(
                        new String[]{"Ambil foto", "Pilih dari galeri", "Stiker", "Toko Stiker"},
                        (dialog, which) -> {
                            if (which == 0) openCamera();
                            else if (which == 1) openGallery();
                            else openStickerPicker(which == 3);
                        })
                .show();
    }

    private void openStickerPicker(boolean store) {
        if (readOnly || sending || uploading || chatChrome.isRecording()) return;
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
        stickerDialog = StickerPackDialog.show(this, store, this::sendSticker);
    }

    protected void openCamera() {
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

    protected void launchCamera() {
        try {
            Intent intent = new Intent(this, ChatCameraActivity.class);
            startActivityForResult(intent, REQUEST_INTERNAL_CAMERA);
        } catch (Exception error) {
            toast("Kamera tidak tersedia");
        }
    }

    protected void openGallery() {
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

    protected void processSelectedPhoto(Uri uri) {
        if (uri == null) return;
        preparePhoto(preview -> ChatImageProcessor.fromUri(getContentResolver(), uri, preview));
    }

    protected void processCameraFile(String path) {
        if (clean(path).isEmpty()) return;
        File file = new File(path);
        preparePhoto(preview -> {
            try { return ChatImageProcessor.fromFile(file, preview); }
            finally { file.delete(); }
        });
    }

    private interface PhotoSource {
        ChatImageProcessor.ImagePayload read(ChatImageProcessor.Preview preview) throws Exception;
    }

    private void preparePhoto(PhotoSource source) {
        if (readOnly || uploading || sending || chatChrome.isRecording()) return;
        ChatPhotoTile tile = new ChatPhotoTile(this);
        messagesBox.addView(tile);
        scrollBottom();
        uploading = true;
        setSendingEnabled(false);
        String operation = "dcm-" + java.util.UUID.randomUUID();
        boolean queued = chatScope.execute(() -> {
            try {
                ChatImageProcessor.ImagePayload payload = source.read(bitmap -> postUi(() -> tile.thumbnail(bitmap)));
                sendPreparedPhoto(payload, tile, operation);
            } catch (Exception failure) {
                postUi(() -> {
                    photoReady();
                    tile.failed("Foto tidak dapat dibaca", null);
                    toast(first(failure.getMessage(), "Foto tidak dapat dibaca."));
                });
            }
        });
        if (!queued) {
            photoReady();
            tile.failed("Antrean penuh · pilih foto kembali", null);
        }
    }

    private void sendPreparedPhoto(ChatImageProcessor.ImagePayload payload, ChatPhotoTile tile, String operation) {
        try {
            JSONObject response = DriverMessageApi.uploadImagePair(UPLOAD_IMAGE_URL, orderId, orderDbId,
                    orderSource, roomId, payload, chatScope.owner, operation,
                    percent -> postUi(() -> tile.progress(percent)));
            if (!response.optBoolean("success") || response.optInt("id") <= 0) {
                throw new IllegalStateException(response.optString("message", "Foto belum terkonfirmasi"));
            }
            postUi(() -> {
                photoReady();
                int id = response.optInt("id");
                tile.confirmed(id);
                if (id <= lastId) ChatPhotoTile.reconcile(messagesBox, id);
                else loadMessages(false);
            });
        } catch (Exception failure) {
            postUi(() -> {
                photoReady();
                showPhotoRetry(payload, tile, operation);
                toast(first(failure.getMessage(), "Foto belum terkonfirmasi."));
            });
        }
    }

    private void showPhotoRetry(ChatImageProcessor.ImagePayload payload, ChatPhotoTile tile, String operation) {
        if (readOnly) {
            tile.failed("Percakapan sudah ditutup", null);
            return;
        }
        tile.failed("Foto belum terkirim · ketuk untuk coba lagi", () -> {
            if (readOnly || sending || uploading || chatChrome.isRecording()) return;
            uploading = true;
            setSendingEnabled(false);
            tile.preparing();
            if (!chatScope.execute(() -> sendPreparedPhoto(payload, tile, operation))) {
                photoReady();
                showPhotoRetry(payload, tile, operation);
            }
        });
    }

    private void photoReady() {
        uploading = false;
        setSendingEnabled(true);
    }
}
