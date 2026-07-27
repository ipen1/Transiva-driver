package com.transiva.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class ChatVoiceNote {
    public static final String PREFIX = "[[VOICE]]";

    public interface Listener {
        void onState(String text, boolean recording, boolean cancelArmed);
        void onReady(File file, long durationMs);
        void onError(String message);
    }

    private ChatVoiceNote() {}

    public static void attachRecorder(
            Activity activity,
            Button button,
            int permissionRequestCode,
            Listener listener
    ) {
        button.setOnTouchListener(new View.OnTouchListener() {
            MediaRecorder recorder;
            File output;
            float downX;
            long startedAt;
            boolean recording;
            boolean cancelArmed;
            final Handler timerHandler = new Handler(Looper.getMainLooper());
            PopupWindow timerPopup;
            TextView timerText;
            final Runnable timerTick = new Runnable() {
                @Override public void run() {
                    if (!recording || timerText == null) return;
                    long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
                    timerText.setText(timerLabel(elapsed));
                    timerHandler.postDelayed(this, 40L);
                }
            };

            private void showTimerPopup(Activity activity, Button anchor) {
                dismissTimerPopup();
                timerText = new TextView(activity);
                timerText.setText("0.00");
                timerText.setTextSize(13);
                timerText.setGravity(Gravity.CENTER);
                timerText.setTextColor(Color.parseColor("#FFFFFF"));
                timerText.setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5));
                timerText.setBackground(background("#0F172A", "#0F172A"));
                timerPopup = new PopupWindow(timerText, dp(activity, 72), dp(activity, 34), false);
                timerPopup.setClippingEnabled(false);
                int x = (anchor.getWidth() - dp(activity, 72)) / 2;
                int y = -(anchor.getHeight() + dp(activity, 44));
                timerPopup.showAsDropDown(anchor, x, y);
            }

            private void dismissTimerPopup() {
                try { if (timerPopup != null) timerPopup.dismiss(); } catch (Exception ignored) {}
                timerPopup = null;
                timerText = null;
            }

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (!button.isEnabled()) return true;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                                != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(
                                    activity,
                                    new String[]{Manifest.permission.RECORD_AUDIO},
                                    permissionRequestCode
                            );
                            listener.onState("Izinkan mikrofon, lalu tahan lagi", false, false);
                            return true;
                        }

                        try {
                            output = new File(
                                    activity.getCacheDir(),
                                    "voice_" + System.currentTimeMillis() + ".m4a"
                            );
                            recorder = new MediaRecorder();
                            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                            recorder.setAudioEncodingBitRate(96000);
                            recorder.setAudioSamplingRate(44100);
                            recorder.setOutputFile(output.getAbsolutePath());
                            recorder.prepare();
                            recorder.start();
                            recording = true;
                            cancelArmed = false;
                            downX = event.getRawX();
                            startedAt = System.currentTimeMillis();
                            button.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            showTimerPopup(activity, button);
                            timerHandler.removeCallbacks(timerTick);
                            timerHandler.post(timerTick);
                            listener.onState("Merekam… geser kiri untuk batal", true, false);
                        } catch (Exception e) {
                            safeRelease(recorder);
                            recorder = null;
                            recording = false;
                            if (output != null) output.delete();
                            listener.onError("Gagal memulai rekaman suara");
                        }
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (recording) {
                            float distance = downX - event.getRawX();
                            boolean nextCancel = distance >= dp(activity, 90);
                            if (nextCancel != cancelArmed) {
                                cancelArmed = nextCancel;
                                listener.onState(
                                        cancelArmed ? "Lepas untuk membatalkan" : "Merekam… geser kiri untuk batal",
                                        true,
                                        cancelArmed
                                );
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!recording) return true;
                        long duration = Math.max(0, System.currentTimeMillis() - startedAt);
                        boolean cancel = cancelArmed || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                        recording = false;
                        timerHandler.removeCallbacks(timerTick);
                        dismissTimerPopup();
                        try {
                            recorder.stop();
                        } catch (Exception ignored) {
                            cancel = true;
                        }
                        safeRelease(recorder);
                        recorder = null;

                        if (cancel || duration < 450) {
                            if (output != null) output.delete();
                            listener.onState(cancel ? "Voice note dibatalkan" : "Rekaman terlalu singkat", false, false);
                        } else {
                            button.performHapticFeedback(
                                    android.os.Build.VERSION.SDK_INT >= 30
                                            ? HapticFeedbackConstants.CONFIRM
                                            : HapticFeedbackConstants.VIRTUAL_KEY);
                            listener.onState("Mengirim voice note…", false, false);
                            listener.onReady(output, duration);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    public static String encode(String url, long durationMs) {
        return PREFIX + (url == null ? "" : url.trim()) + "|" + Math.max(0, durationMs);
    }

    public static boolean isVoice(String message) {
        return message != null && message.startsWith(PREFIX);
    }

    public static String voiceUrl(String message) {
        if (!isVoice(message)) return "";
        String raw = message.substring(PREFIX.length()).trim();
        int split = raw.lastIndexOf('|');
        return split >= 0 ? raw.substring(0, split).trim() : raw;
    }

    public static long voiceDuration(String message) {
        if (!isVoice(message)) return 0;
        String raw = message.substring(PREFIX.length()).trim();
        int split = raw.lastIndexOf('|');
        if (split < 0) return 0;
        try { return Long.parseLong(raw.substring(split + 1).trim()); }
        catch (Exception ignored) { return 0; }
    }

    public static View createPlayerBubble(Activity activity, String message, boolean mine) {
        String url = voiceUrl(message);
        long durationMs = voiceDuration(message);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(activity, 10), dp(activity, 7), dp(activity, 12), dp(activity, 7));
        box.setBackground(background(
                mine ? "#0B7CFF" : "#FFFFFF",
                mine ? "#0B7CFF" : "#D7E6F8"
        ));

        Button play = new Button(activity);
        play.setAllCaps(false);
        play.setText("▶");
        play.setTextSize(16);
        play.setTextColor(Color.parseColor(mine ? "#0B7CFF" : "#0F172A"));
        play.setPadding(0, 0, 0, 0);
        play.setBackground(background("#FFFFFF", "#FFFFFF"));
        box.addView(play, new LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 38)));

        TextView label = new TextView(activity);
        label.setText(String.format(Locale.US, "Voice note  %s", durationLabel(durationMs)));
        label.setTextSize(12);
        label.setTextColor(Color.parseColor(mine ? "#FFFFFF" : "#0F172A"));
        label.setPadding(dp(activity, 9), 0, 0, 0);
        box.addView(label, new LinearLayout.LayoutParams(dp(activity, 150), -2));

        play.setOnClickListener(v -> {
            if (url.isEmpty()) {
                label.setText("Voice note tidak tersedia");
                return;
            }
            play.setEnabled(false);
            play.setText("…");
            label.setText("Memuat voice note…");

            new Thread(() -> {
                File cached = null;
                try {
                    cached = downloadToCache(activity, url);
                    File finalCached = cached;
                    activity.runOnUiThread(() -> playLocalVoice(activity, finalCached, play, label, durationMs));
                } catch (Exception e) {
                    if (cached != null) cached.delete();
                    String reason = e.getMessage();
                    activity.runOnUiThread(() -> {
                        play.setText("▶");
                        play.setEnabled(true);
                        label.setText("Gagal memuat voice note");
                    });
                }
            }, "chat-voice-download").start();
        });

        return box;
    }

    private static File downloadToCache(Activity activity, String sourceUrl) throws Exception {
        HttpURLConnection connection = null;
        InputStream input = null;
        FileOutputStream output = null;
        File temp = new File(activity.getCacheDir(), "play_voice_" + System.nanoTime() + ".m4a");
        try {
            URL current = new URL(sourceUrl);
            int redirects = 0;
            while (true) {
                connection = (HttpURLConnection) current.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "audio/mp4,audio/*,*/*");
                connection.setRequestProperty("User-Agent", "Transiva-Android");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400 && redirects < 5) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.trim().isEmpty()) throw new Exception("Redirect audio tidak valid");
                    current = new URL(current, location);
                    redirects++;
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw new Exception("HTTP " + code);
                }
                input = new BufferedInputStream(connection.getInputStream());
                output = new FileOutputStream(temp);
                byte[] buffer = new byte[16 * 1024];
                int read;
                long total = 0;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                    if (total > 15L * 1024L * 1024L) throw new Exception("Voice note terlalu besar");
                }
                output.flush();
                if (total < 128) throw new Exception("File audio kosong");
                return temp;
            }
        } catch (Exception e) {
            temp.delete();
            throw e;
        } finally {
            try { if (input != null) input.close(); } catch (Exception ignored) {}
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    private static void playLocalVoice(Activity activity, File file, Button play, TextView label, long durationMs) {
        final MediaPlayer player = new MediaPlayer();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            } else {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            player.setVolume(1.0f, 1.0f);
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                play.setText("■");
                play.setEnabled(true);
                label.setText(String.format(Locale.US, "Memutar  %s", durationLabel(durationMs)));
                mp.start();
            });
            player.setOnCompletionListener(mp -> {
                play.setText("▶");
                play.setEnabled(true);
                label.setText(String.format(Locale.US, "Voice note  %s", durationLabel(durationMs)));
                try { mp.release(); } catch (Exception ignored) {}
                try { file.delete(); } catch (Exception ignored) {}
            });
            player.setOnErrorListener((mp, what, extra) -> {
                play.setText("▶");
                play.setEnabled(true);
                label.setText("Audio tidak dapat diputar");
                try { mp.release(); } catch (Exception ignored) {}
                try { file.delete(); } catch (Exception ignored) {}
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            play.setText("▶");
            play.setEnabled(true);
            label.setText("Audio tidak dapat diputar");
            try { player.release(); } catch (Exception ignored) {}
            try { file.delete(); } catch (Exception ignored) {}
        }
    }

    private static String timerLabel(long elapsedMs) {
        long safeMs = Math.max(0L, elapsedMs);
        long totalSeconds = safeMs / 1000L;
        long hundredths = (safeMs % 1000L) / 10L;
        return String.format(Locale.US, "%d.%02d", totalSeconds, hundredths);
    }

    private static String durationLabel(long durationMs) {
        long total = Math.max(0, durationMs / 1000L);
        return String.format(Locale.US, "%d:%02d", total / 60L, total % 60L);
    }

    private static GradientDrawable background(String fill, String stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(fill));
        d.setCornerRadius(36f);
        d.setStroke(1, Color.parseColor(stroke));
        return d;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void safeRelease(MediaRecorder recorder) {
        if (recorder == null) return;
        try { recorder.reset(); } catch (Exception ignored) {}
        try { recorder.release(); } catch (Exception ignored) {}
    }
}
