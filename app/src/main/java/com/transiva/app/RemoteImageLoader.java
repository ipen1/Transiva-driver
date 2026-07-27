package com.transiva.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RemoteImageLoader {

    private static final ExecutorService EXECUTOR =
            Executors.newFixedThreadPool(3);

    private RemoteImageLoader() {
    }

    public static void loadCenterCrop(
            ImageView view,
            String imageUrl,
            int fallbackDrawable
    ) {
        if (view == null) return;

        String clean =
                imageUrl == null ? "" : imageUrl.trim();

        view.setScaleType(ImageView.ScaleType.CENTER_CROP);

        if (fallbackDrawable != 0) {
            view.setImageResource(fallbackDrawable);
        }

        if (clean.isEmpty()) return;

        view.setTag(clean);

        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            Bitmap bitmap = null;

            try {
                connection =
                        (HttpURLConnection)
                                new URL(clean).openConnection();

                connection.setConnectTimeout(12000);
                connection.setReadTimeout(18000);
                connection.setUseCaches(true);
                connection.setRequestProperty("Accept", "image/*");

                int status = connection.getResponseCode();

                if (status < 200 || status >= 300) return;

                BufferedInputStream input =
                        new BufferedInputStream(
                                connection.getInputStream()
                        );

                bitmap = BitmapFactory.decodeStream(input);
                input.close();

                if (bitmap == null) return;

                Bitmap result = bitmap;
                bitmap = null;

                view.post(() -> {
                    Object tag = view.getTag();

                    if (
                            tag != null
                                    && clean.equals(String.valueOf(tag))
                    ) {
                        view.setImageBitmap(result);
                    } else if (!result.isRecycled()) {
                        result.recycle();
                    }
                });

            } catch (Exception ignored) {
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }

                if (connection != null) connection.disconnect();
            }
        });
    }
}
