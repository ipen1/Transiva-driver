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
public class DriverChatRoomActivity extends DriverChatRoomActivityLayer2 {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor("#0B7CFF"));
        getWindow().setNavigationBarColor(Color.parseColor("#071426"));

        session = new SessionManager(this);
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

    protected void callCustomer() {
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

    protected View buildScreen() {
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

    protected void showAttachmentMenu() {
        if (readOnly || uploading) return;

        PremiumDialogs.builder(this)
                .setTitle("Kirim Foto")
                .setItems(
                        new String[]{"Ambil Foto", "Pilih dari Galeri"},
                        (dialog, which) -> {
                            if (which == 0) openCamera();
                            else openGallery();
                        })
                .show();
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
        if (uri == null || uploading) return;

        uploading = true;
        setSendingEnabled(false);
        progress.setVisibility(View.VISIBLE);

        DriverNetworkExecutor.execute(() -> {
            try {
                ChatImageProcessor.ImagePayload payload =
                        ChatImageProcessor.fromUri(
                                getContentResolver(), uri);

                postUi(() -> {
                    uploading = false;
                    progress.setVisibility(View.GONE);
                    uploadPhoto(payload);
                });

            } catch (Exception error) {
                postUi(() -> {
                    uploading = false;
                    progress.setVisibility(View.GONE);
                    setSendingEnabled(true);
                    toast(first(error.getMessage(),
                            "Foto tidak dapat dibaca."));
                });
            }
        });
    }

    protected void processCameraFile(String path) {
        if (clean(path).isEmpty() || uploading) return;

        uploading = true;
        setSendingEnabled(false);
        progress.setVisibility(View.VISIBLE);

        DriverNetworkExecutor.execute(() -> {
            File file = new File(path);

            try {
                ChatImageProcessor.ImagePayload payload =
                        ChatImageProcessor.fromFile(file);

                postUi(() -> {
                    uploading = false;
                    progress.setVisibility(View.GONE);
                    uploadPhoto(payload);
                    try { file.delete(); } catch (Exception ignored) {}
                });

            } catch (Exception error) {
                postUi(() -> {
                    uploading = false;
                    progress.setVisibility(View.GONE);
                    setSendingEnabled(true);
                    toast("Foto kamera tidak dapat dibaca.");
                });
            }
        });
    }

    protected void uploadPhoto(
            ChatImageProcessor.ImagePayload payload
    ) {
        if (payload == null || readOnly || uploading) return;

        uploading = true;
        setSendingEnabled(false);

        final PendingPhotoBubble pendingBubble =
                addPendingPhotoBubble(payload);
        scrollBottom();

        DriverNetworkExecutor.execute(() -> {
            try {
                JSONObject response =
                        DriverChatMediaApi.uploadImagePair(
                                UPLOAD_IMAGE_URL,
                                roomId,
                                "driver",
                                payload);

                postUi(() -> {
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
                postUi(() -> {
                    uploading = false;
                    setSendingEnabled(true);
                    pendingBubble.markFailed(first(
                            error.getMessage(),
                            "Foto gagal dikirim"));
                });
            }
        });
    }
}
