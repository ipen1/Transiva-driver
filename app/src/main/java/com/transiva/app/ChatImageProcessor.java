package com.transiva.app;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public final class ChatImageProcessor {

    private static final int PREVIEW_MAX_SIDE = 640;
    private static final int HD_MAX_SIDE = 2304;

    private static final int PREVIEW_QUALITY = 74;
    private static final int HD_QUALITY = 85;

    private ChatImageProcessor() {
    }

    public static final class ImagePayload {
        public final byte[] previewWebp;
        public final byte[] hdJpeg;
        public final int originalWidth;
        public final int originalHeight;

        private ImagePayload(
                byte[] previewWebp,
                byte[] hdJpeg,
                int originalWidth,
                int originalHeight
        ) {
            this.previewWebp = previewWebp;
            this.hdJpeg = hdJpeg;
            this.originalWidth = originalWidth;
            this.originalHeight = originalHeight;
        }
    }

    public static ImagePayload fromUri(ContentResolver resolver, Uri uri) throws Exception {
        return fromUri(resolver, uri, null);
    }

    static ImagePayload fromUri(ContentResolver resolver, Uri uri, Preview ready) throws Exception {
        if (resolver == null || uri == null) {
            throw new IllegalArgumentException(
                    "Foto tidak ditemukan"
            );
        }

        int orientation = readExifOrientation(
                resolver,
                uri
        );

        Bitmap decoded = decode(
                resolver,
                uri,
                HD_MAX_SIDE
        );

        if (decoded == null) {
            throw new IllegalStateException(
                    "Foto tidak dapat dibaca"
            );
        }

        Bitmap oriented = applyExifOrientation(
                decoded,
                orientation
        );

        return createPayload(oriented, ready);
    }

    public static ImagePayload fromFile(java.io.File file) throws Exception {
        return fromFile(file, null);
    }

    static ImagePayload fromFile(java.io.File file, Preview ready) throws Exception {
        if (
                file == null
                        || !file.exists()
                        || file.length() <= 0L
        ) {
            throw new IllegalArgumentException(
                    "File foto tidak ditemukan"
            );
        }

        int orientation;

        try {
            ExifInterface exif =
                    new ExifInterface(
                            file.getAbsolutePath()
                    );

            orientation =
                    exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                    );

        } catch (Exception ignored) {
            orientation =
                    ExifInterface.ORIENTATION_NORMAL;
        }

        BitmapFactory.Options bounds =
                new BitmapFactory.Options();

        bounds.inJustDecodeBounds = true;

        BitmapFactory.decodeFile(
                file.getAbsolutePath(),
                bounds
        );

        BitmapFactory.Options options =
                new BitmapFactory.Options();

        options.inSampleSize =
                sampleSize(
                        bounds.outWidth,
                        bounds.outHeight,
                        HD_MAX_SIDE
                );

        options.inPreferredConfig =
                Bitmap.Config.ARGB_8888;

        Bitmap decoded =
                BitmapFactory.decodeFile(
                        file.getAbsolutePath(),
                        options
                );

        if (decoded == null) {
            throw new IllegalStateException(
                    "Foto kamera tidak dapat dibaca"
            );
        }

        Bitmap oriented =
                applyExifOrientation(
                        decoded,
                        orientation
                );

        return createPayload(oriented, ready);
    }

    public static ImagePayload fromBitmap(
            Bitmap bitmap
    ) throws Exception {
        if (bitmap == null) {
            throw new IllegalArgumentException(
                    "Foto tidak ditemukan"
            );
        }

        return createPayload(bitmap, null);
    }

    private static int readExifOrientation(
            ContentResolver resolver,
            Uri uri
    ) {
        try (
                InputStream stream =
                        resolver.openInputStream(uri)
        ) {
            if (stream == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }

            ExifInterface exif =
                    new ExifInterface(stream);

            return exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
            );

        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyExifOrientation(
            Bitmap source,
            int orientation
    ) {
        Matrix matrix = new Matrix();

        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;

            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;

            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;

            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;

            case ExifInterface.ORIENTATION_NORMAL:
            case ExifInterface.ORIENTATION_UNDEFINED:
            default:
                return source;
        }

        try {
            Bitmap transformed =
                    Bitmap.createBitmap(
                            source,
                            0,
                            0,
                            source.getWidth(),
                            source.getHeight(),
                            matrix,
                            true
                    );

            if (transformed != source) {
                source.recycle();
            }

            return transformed;

        } catch (OutOfMemoryError error) {
            return source;
        }
    }

    interface Preview { void ready(Bitmap bitmap); }

    private static ImagePayload createPayload(Bitmap source, Preview ready) throws Exception {
        int originalWidth = source.getWidth();
        int originalHeight = source.getHeight();

        Bitmap hd = scaleInside(
                source,
                HD_MAX_SIDE
        );

        Bitmap preview = scaleInside(
                hd,
                PREVIEW_MAX_SIDE
        );

        if (ready != null) {
            Bitmap thumbnail = scaleInside(preview, 360);
            if (thumbnail == preview) thumbnail = preview.copy(Bitmap.Config.ARGB_8888, false);
            ready.ready(thumbnail);
        }

        byte[] previewBytes = compress(
                preview,
                Bitmap.CompressFormat.WEBP,
                PREVIEW_QUALITY
        );

        byte[] hdBytes = compress(
                hd,
                Bitmap.CompressFormat.JPEG,
                HD_QUALITY
        );

        if (
                preview != hd
                        && preview != source
        ) {
            preview.recycle();
        }

        if (hd != source) {
            hd.recycle();
        }

        if (!source.isRecycled()) {
            source.recycle();
        }

        return new ImagePayload(
                previewBytes,
                hdBytes,
                originalWidth,
                originalHeight
        );
    }

    private static Bitmap decode(
            ContentResolver resolver,
            Uri uri,
            int maxSide
    ) throws Exception {
        BitmapFactory.Options bounds =
                new BitmapFactory.Options();

        bounds.inJustDecodeBounds = true;

        try (
                InputStream stream =
                        resolver.openInputStream(uri)
        ) {
            if (stream == null) {
                throw new IllegalStateException(
                        "File foto tidak dapat dibuka"
                );
            }

            BitmapFactory.decodeStream(
                    stream,
                    null,
                    bounds
            );
        }

        if (
                bounds.outWidth <= 0
                        || bounds.outHeight <= 0
        ) {
            throw new IllegalStateException(
                    "Ukuran foto tidak valid"
            );
        }

        BitmapFactory.Options options =
                new BitmapFactory.Options();

        options.inSampleSize =
                sampleSize(
                        bounds.outWidth,
                        bounds.outHeight,
                        maxSide
                );

        options.inPreferredConfig =
                Bitmap.Config.ARGB_8888;

        try (
                InputStream stream =
                        resolver.openInputStream(uri)
        ) {
            if (stream == null) {
                throw new IllegalStateException(
                        "File foto tidak dapat dibuka"
                );
            }

            return BitmapFactory.decodeStream(
                    stream,
                    null,
                    options
            );
        }
    }

    private static byte[] compress(
            Bitmap bitmap,
            Bitmap.CompressFormat format,
            int quality
    ) throws Exception {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        boolean success = bitmap.compress(
                format,
                quality,
                output
        );

        if (!success) {
            throw new IllegalStateException(
                    "Foto gagal dikompres"
            );
        }

        return output.toByteArray();
    }

    private static Bitmap scaleInside(
            Bitmap source,
            int maxSide
    ) {
        int width = source.getWidth();
        int height = source.getHeight();

        if (
                width <= maxSide
                        && height <= maxSide
        ) {
            return source;
        }

        float ratio = Math.min(
                (float) maxSide / width,
                (float) maxSide / height
        );

        int targetWidth =
                Math.max(
                        1,
                        Math.round(width * ratio)
                );

        int targetHeight =
                Math.max(
                        1,
                        Math.round(height * ratio)
                );

        return Bitmap.createScaledBitmap(
                source,
                targetWidth,
                targetHeight,
                true
        );
    }

    private static int sampleSize(
            int width,
            int height,
            int maxSide
    ) {
        int sample = 1;

        while (
                width / sample > maxSide * 2
                        || height / sample > maxSide * 2
        ) {
            sample *= 2;
        }

        return Math.max(1, sample);
    }
}
