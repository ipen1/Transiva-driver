package com.transiva.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class DriverMessageApi {

    private static final int TIMEOUT_MS = 45000;

    private DriverMessageApi() {}

    public static JSONObject get(
            SessionManager session,
            String endpoint
    ) throws Exception {
        return request(session, "GET", endpoint, null);
    }

    public static JSONObject post(
            SessionManager session,
            String endpoint,
            JSONObject body
    ) throws Exception {
        return request(session, "POST", endpoint, body);
    }

    public static JSONObject uploadImagePair(
            SessionManager session,
            String endpoint,
            String orderId,
            String orderSource,
            String roomId,
            ChatImageProcessor.ImagePayload payload
    ) throws Exception {
        if (payload == null
                || payload.previewWebp == null
                || payload.hdJpeg == null) {
            throw new IllegalArgumentException("Data foto tidak valid");
        }

        String boundary =
                "----TransivaDriverChat" + System.currentTimeMillis();

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                    "Authorization",
                    "Bearer " + session.getToken()
            );
            connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=" + boundary
            );

            try (OutputStream output = connection.getOutputStream()) {
                writeField(output, boundary, "order_id", orderId);
                writeField(output, boundary, "source", orderSource);
                writeField(output, boundary, "room_id", roomId);
                writeField(output, boundary, "sender_type", "driver");

                writeFile(
                        output,
                        boundary,
                        "preview_image",
                        "preview.webp",
                        "image/webp",
                        payload.previewWebp
                );

                writeFile(
                        output,
                        boundary,
                        "hd_image",
                        "hd.jpg",
                        "image/jpeg",
                        payload.hdJpeg
                );

                output.write(
                        ("--" + boundary + "--\r\n")
                                .getBytes(StandardCharsets.UTF_8)
                );
                output.flush();
            }

            return readResponse(connection);

        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static JSONObject uploadVoice(
            SessionManager session,
            String endpoint,
            String orderId,
            String orderSource,
            String roomId,
            File file,
            long durationMs
    ) throws Exception {
        String boundary = "----TransivaDriverVoice" + System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + session.getToken());
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (OutputStream output = connection.getOutputStream()) {
                writeField(output, boundary, "order_id", orderId);
                writeField(output, boundary, "source", orderSource);
                writeField(output, boundary, "room_id", roomId);
                writeField(output, boundary, "sender_type", "driver");
                writeField(output, boundary, "duration_ms", String.valueOf(durationMs));
                writeFile(output, boundary, "audio", "voice.m4a", "audio/mp4", readFile(file));
                output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            return readResponse(connection);
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static byte[] readFile(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n); return out.toByteArray();
        }
    }

    private static JSONObject request(
            SessionManager session,
            String method,
            String endpoint,
            JSONObject body
    ) throws Exception {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(endpoint).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty(
                    "Authorization",
                    "Bearer " + session.getToken()
            );

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=UTF-8"
                );

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(
                            body.toString().getBytes(StandardCharsets.UTF_8)
                    );
                    output.flush();
                }
            }

            return readResponse(connection);

        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static JSONObject readResponse(
            HttpURLConnection connection
    ) throws Exception {
        int status = connection.getResponseCode();

        InputStream stream =
                status >= 200 && status < 400
                        ? connection.getInputStream()
                        : connection.getErrorStream();

        if (stream == null) {
            throw new IllegalStateException("Respons server kosong");
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        );

        StringBuilder raw = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            raw.append(line);
        }

        reader.close();

        String body = raw.toString().trim();
        int firstBrace = body.indexOf('{');
        int lastBrace = body.lastIndexOf('}');

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            body = body.substring(firstBrace, lastBrace + 1);
        }

        JSONObject response = new JSONObject(body);

        if (status < 200 || status >= 400) {
            throw new IllegalStateException(
                    response.optString("message", "HTTP " + status)
            );
        }

        return response;
    }

    private static void writeField(
            OutputStream output,
            String boundary,
            String name,
            String value
    ) throws Exception {
        output.write(
                ("--" + boundary + "\r\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        output.write(
                ("Content-Disposition: form-data; name=\""
                        + name + "\"\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        output.write(
                (value == null ? "" : value)
                        .getBytes(StandardCharsets.UTF_8)
        );
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFile(
            OutputStream output,
            String boundary,
            String fieldName,
            String filename,
            String mime,
            byte[] bytes
    ) throws Exception {
        output.write(
                ("--" + boundary + "\r\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        output.write(
                ("Content-Disposition: form-data; name=\""
                        + fieldName + "\"; filename=\""
                        + filename + "\"\r\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        output.write(
                ("Content-Type: " + mime + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
        output.write(bytes);
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }
}
