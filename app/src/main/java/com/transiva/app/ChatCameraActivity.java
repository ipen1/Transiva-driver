package com.transiva.app;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Camera;
import android.widget.PopupMenu;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatCameraActivity
        extends ComponentActivity {

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private Button captureButton;
    private boolean capturing;
    private Camera boundCamera;
    private Button flashButton;
    private int flashMode = ImageCapture.FLASH_MODE_AUTO;

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(
                Color.BLACK
        );

        getWindow().setNavigationBarColor(
                Color.BLACK
        );

        cameraExecutor =
                Executors.newSingleThreadExecutor();

        setContentView(buildScreen());
        if (savedInstanceState != null) flashMode = savedInstanceState.getInt("flash_mode", flashMode);
        startCamera();
    }

    private FrameLayout buildScreen() {
        FrameLayout root =
                new FrameLayout(this);

        root.setBackgroundColor(Color.BLACK);

        previewView =
                new PreviewView(this);

        previewView.setScaleType(
                PreviewView.ScaleType.FILL_CENTER
        );

        root.addView(
                previewView,
                new FrameLayout.LayoutParams(
                        -1,
                        -1
                )
        );

        LinearLayout controls =
                new LinearLayout(this);

        controls.setGravity(Gravity.CENTER);

        controls.setPadding(
                20,
                18,
                20,
                26
        );

        captureButton = new Button(this);
        captureButton.setText("Ambil Foto");
        captureButton.setAllCaps(false);

        captureButton.setOnClickListener(
                view -> takePhoto()
        );

        controls.addView(
                captureButton,
                new LinearLayout.LayoutParams(
                        -1,
                        -2
                )
        );

        flashButton = new Button(this);
        flashButton.setAllCaps(false);
        flashButton.setText("⚡ Auto");
        flashButton.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, flashButton);
            menu.getMenu().add(0, 0, 0, "Flash otomatis");
            menu.getMenu().add(0, 1, 1, "Flash aktif");
            menu.getMenu().add(0, 2, 2, "Flash nonaktif");
            menu.setOnMenuItemClickListener(item -> {
                flashMode = item.getItemId() == 0 ? ImageCapture.FLASH_MODE_AUTO :
                        item.getItemId() == 1 ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF;
                applyFlash();
                return true;
            });
            menu.show();
        });
        FrameLayout.LayoutParams flashLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        flashLp.setMargins(12, 24, 16, 0);
        root.addView(flashButton, flashLp);

        FrameLayout.LayoutParams controlsLp =
                new FrameLayout.LayoutParams(
                        -1,
                        -2
                );

        controlsLp.gravity = Gravity.BOTTOM;

        root.addView(
                controls,
                controlsLp
        );

        return root;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider>
                providerFuture =
                ProcessCameraProvider.getInstance(
                        this
                );

        providerFuture.addListener(
                () -> {
                    try {
                        ProcessCameraProvider provider =
                                providerFuture.get();

                        Preview preview =
                                new Preview.Builder()
                                        .build();

                        preview.setSurfaceProvider(
                                previewView
                                        .getSurfaceProvider()
                        );

                        imageCapture =
                                new ImageCapture.Builder()
                                        .setCaptureMode(
                                                ImageCapture
                                                        .CAPTURE_MODE_MAXIMIZE_QUALITY
                                        )
                                        .setJpegQuality(95)
                                        .build();

                        provider.unbindAll();

                        boundCamera = provider.bindToLifecycle(
                                this,
                                CameraSelector
                                        .DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture
                        );
                        boolean supported = boundCamera.getCameraInfo().hasFlashUnit();
                        flashButton.setEnabled(supported);
                        if (!supported) { flashMode = ImageCapture.FLASH_MODE_OFF; flashButton.setText("Flash tidak tersedia"); }
                        else applyFlash();

                    } catch (Exception error) {
                        toast(
                                "Kamera gagal dibuka: "
                                        + error.getMessage()
                        );

                        finish();
                    }
                },
                ContextCompat.getMainExecutor(
                        this
                )
        );
    }

    private void applyFlash() {
        if (imageCapture == null || boundCamera == null) return;
        boundCamera.getCameraControl().enableTorch(false);
        imageCapture.setFlashMode(flashMode);
        flashButton.setText(flashMode == ImageCapture.FLASH_MODE_AUTO ? "⚡ Auto" :
                flashMode == ImageCapture.FLASH_MODE_ON ? "⚡ Aktif" : "⚡ Mati");
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt("flash_mode", flashMode);
        super.onSaveInstanceState(state);
    }

    private void takePhoto() {
        if (
                capturing
                        || imageCapture == null
        ) {
            return;
        }

        capturing = true;

        captureButton.setEnabled(false);
        captureButton.setText("Memproses…");

        File directory = new File(
                getCacheDir(),
                "chat_camera_full"
        );

        if (
                !directory.exists()
                        && !directory.mkdirs()
        ) {
            toast("Folder kamera gagal dibuat");
            finish();
            return;
        }

        File output = new File(
                directory,
                "chat_"
                        + System.currentTimeMillis()
                        + ".jpg"
        );

        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions
                        .Builder(output)
                        .build();

        imageCapture.takePicture(
                options,
                cameraExecutor,
                new ImageCapture
                        .OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(
                            @NonNull
                            ImageCapture
                                    .OutputFileResults
                                    outputFileResults
                    ) {
                        runOnUiThread(() -> {
                            Intent result =
                                    new Intent();

                            result.putExtra(
                                    "photo_path",
                                    output.getAbsolutePath()
                            );

                            setResult(
                                    RESULT_OK,
                                    result
                            );

                            finish();
                        });
                    }

                    @Override
                    public void onError(
                            @NonNull
                            ImageCaptureException exception
                    ) {
                        runOnUiThread(() -> {
                            capturing = false;

                            captureButton
                                    .setEnabled(true);

                            captureButton.setText(
                                    "Ambil Foto"
                            );

                            toast(
                                    "Foto gagal: "
                                            + exception
                                            .getMessage()
                            );
                        });
                    }
                }
        );
    }

    private void toast(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    protected void onDestroy() {
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        super.onDestroy();
    }
}
