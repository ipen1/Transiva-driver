package com.transiva.app;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.GlRectDrawer;
import org.webrtc.ThreadUtils;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import java.util.concurrent.CountDownLatch;

/** Video stays in the normal view tree, including rounded clipping, PiP and freeform windows. */
public final class CallTextureRenderer extends TextureView implements VideoSink, TextureView.SurfaceTextureListener {
    private final EglRenderer renderer = new EglRenderer("TransivaCallTexture");
    private volatile boolean initialized, released;
    private boolean firstFrameShown;
    private Runnable firstFrame;

    public CallTextureRenderer(Context context) {
        super(context);
        setOpaque(false);
        setSurfaceTextureListener(this);
    }
    public void init(EglBase.Context shared, Runnable onFirstFrame) {
        ThreadUtils.checkIsOnMainThread();
        if (initialized || released) throw new IllegalStateException("Renderer already initialized/released");
        firstFrame = onFirstFrame;
        renderer.init(shared, EglBase.CONFIG_RGBA, new GlRectDrawer());
        initialized = true;
        if (isAvailable() && getSurfaceTexture() != null)
            onSurfaceTextureAvailable(getSurfaceTexture(), getWidth(), getHeight());
    }
    public void setMirror(boolean mirror) { renderer.setMirror(mirror); }
    @Override public void onFrame(VideoFrame frame) {
        if (initialized && !released) renderer.onFrame(frame);
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (!initialized || released) return;
        firstFrameShown = false;
        resize(texture, width, height);
        renderer.createEglSurface(texture);
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        if (initialized && !released) resize(texture, width, height);
    }
    private void resize(SurfaceTexture texture, int width, int height) {
        if (width <= 0 || height <= 0) return;
        texture.setDefaultBufferSize(width, height);
        // EglRenderer crops to this ratio; the TextureView itself always fills its parent bounds.
        renderer.setLayoutAspectRatio((float) width / height);
    }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        if (initialized && !released) {
            CountDownLatch done = new CountDownLatch(1);
            renderer.releaseEglSurface(done::countDown);
            ThreadUtils.awaitUninterruptibly(done);
        }
        return true; // TextureView owns and releases the texture after EGL stops using it.
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        // Notify after a buffer is actually displayed, so an opaque waiting card cannot cover video.
        if (!released && initialized && !firstFrameShown) {
            firstFrameShown = true;
            if (firstFrame != null) firstFrame.run();
        }
    }
    public void release() {
        ThreadUtils.checkIsOnMainThread();
        if (released) return;
        released = true;
        firstFrame = null;
        if (initialized) renderer.release();
        initialized = false;
    }
}
