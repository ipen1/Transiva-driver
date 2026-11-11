package com.transiva.app;

import org.json.JSONObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Streams multipart bytes without buffering a second copy of the payload. */
final class ChatMediaMultipart {
    private final String boundary = "----TransivaMedia" + UUID.randomUUID();
    private final List<byte[]> parts = new ArrayList<>();
    private long bytes;

    ChatMediaMultipart(JSONObject fields, byte[] preview, byte[] media) {
        for (String field : new String[]{"room_id","order_id","order_db_id","source","operation_id","duration_ms","sender_type"}) {
            header("Content-Disposition: form-data; name=\"" + field + "\"\r\n\r\n" + fields.optString(field) + "\r\n");
        }
        if (preview != null) {
            file("preview_image", "preview.webp", "image/webp", preview);
            file("hd_image", "hd.jpg", "image/jpeg", media);
        } else {
            file("audio", "voice.m4a", "audio/mp4", media);
        }
        add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    void write(HttpURLConnection connection, Progress progress) throws IOException {
        connection.setFixedLengthStreamingMode(bytes);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        long sent = 0;
        int last = -1;
        try (OutputStream output = connection.getOutputStream()) {
            for (byte[] part : parts) {
                for (int offset = 0; offset < part.length;) {
                    int count = Math.min(32768, part.length - offset);
                    output.write(part, offset, count);
                    offset += count;
                    sent += count;
                    int percent = (int)(sent * 95 / bytes);
                    if (progress != null && (percent - last >= 3 || (sent == bytes && last != percent))) {
                        last = percent;
                        progress.update(percent);
                    }
                }
            }
            output.flush();
        }
    }

    private void file(String field, String name, String type, byte[] content) {
        header("Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: " + type + "\r\n\r\n");
        add(content);
        add("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void header(String line) {
        add(("--" + boundary + "\r\n" + line).getBytes(StandardCharsets.UTF_8));
    }

    private void add(byte[] part) {
        parts.add(part);
        bytes += part.length;
    }

    interface Progress { void update(int percent); }
}
