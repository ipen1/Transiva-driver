package com.transiva.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public final class ZoomableImageView extends ImageView {

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 6f;

    private final Matrix matrix = new Matrix();

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float currentScale = 1f;
    private float lastX;
    private float lastY;
    private boolean dragging;

    public ZoomableImageView(Context context) {
        super(context);

        setScaleType(ScaleType.MATRIX);

        scaleDetector =
                new ScaleGestureDetector(
                        context,
                        new ScaleListener()
                );

        gestureDetector =
                new GestureDetector(
                        context,
                        new GestureListener()
                );

        setOnTouchListener(
                (view, event) -> handleTouch(event)
        );
    }

    @Override
    protected void onSizeChanged(
            int width,
            int height,
            int oldWidth,
            int oldHeight
    ) {
        super.onSizeChanged(
                width,
                height,
                oldWidth,
                oldHeight
        );

        fitImageToView();
    }

    @Override
    public void setImageDrawable(
            Drawable drawable
    ) {
        super.setImageDrawable(drawable);

        post(this::fitImageToView);
    }

    private boolean handleTouch(
            MotionEvent event
    ) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                break;

            case MotionEvent.ACTION_MOVE:
                if (
                        dragging
                                && !scaleDetector
                                .isInProgress()
                                && currentScale > MIN_SCALE
                ) {
                    float dx =
                            event.getX() - lastX;

                    float dy =
                            event.getY() - lastY;

                    matrix.postTranslate(
                            dx,
                            dy
                    );

                    constrainTranslation();
                    setImageMatrix(matrix);

                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }

        return true;
    }

    private void fitImageToView() {
        Drawable drawable = getDrawable();

        if (
                drawable == null
                        || getWidth() <= 0
                        || getHeight() <= 0
        ) {
            return;
        }

        int drawableWidth =
                drawable.getIntrinsicWidth();

        int drawableHeight =
                drawable.getIntrinsicHeight();

        if (
                drawableWidth <= 0
                        || drawableHeight <= 0
        ) {
            return;
        }

        matrix.reset();

        float scale = Math.min(
                (float) getWidth()
                        / drawableWidth,
                (float) getHeight()
                        / drawableHeight
        );

        float dx =
                (
                        getWidth()
                                - drawableWidth
                                * scale
                ) / 2f;

        float dy =
                (
                        getHeight()
                                - drawableHeight
                                * scale
                ) / 2f;

        matrix.postScale(
                scale,
                scale
        );

        matrix.postTranslate(
                dx,
                dy
        );

        currentScale = MIN_SCALE;
        setImageMatrix(matrix);
    }

    private void constrainTranslation() {
        Drawable drawable = getDrawable();

        if (drawable == null) {
            return;
        }

        RectF rect = new RectF(
                0,
                0,
                drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight()
        );

        matrix.mapRect(rect);

        float dx = 0f;
        float dy = 0f;

        if (rect.width() <= getWidth()) {
            dx =
                    getWidth() / 2f
                            - rect.centerX();
        } else if (rect.left > 0) {
            dx = -rect.left;
        } else if (rect.right < getWidth()) {
            dx =
                    getWidth() - rect.right;
        }

        if (rect.height() <= getHeight()) {
            dy =
                    getHeight() / 2f
                            - rect.centerY();
        } else if (rect.top > 0) {
            dy = -rect.top;
        } else if (rect.bottom < getHeight()) {
            dy =
                    getHeight() - rect.bottom;
        }

        matrix.postTranslate(dx, dy);
    }

    private final class ScaleListener
            extends ScaleGestureDetector
            .SimpleOnScaleGestureListener {

        @Override
        public boolean onScale(
                ScaleGestureDetector detector
        ) {
            float factor =
                    detector.getScaleFactor();

            float target =
                    currentScale * factor;

            if (target < MIN_SCALE) {
                factor =
                        MIN_SCALE / currentScale;

            } else if (target > MAX_SCALE) {
                factor =
                        MAX_SCALE / currentScale;
            }

            matrix.postScale(
                    factor,
                    factor,
                    detector.getFocusX(),
                    detector.getFocusY()
            );

            currentScale *= factor;

            constrainTranslation();
            setImageMatrix(matrix);

            return true;
        }

        @Override
        public void onScaleEnd(
                ScaleGestureDetector detector
        ) {
            super.onScaleEnd(detector);

            if (currentScale <= MIN_SCALE) {
                fitImageToView();
            }
        }
    }

    private final class GestureListener
            extends GestureDetector
            .SimpleOnGestureListener {

        @Override
        public boolean onDoubleTap(
                MotionEvent event
        ) {
            if (currentScale > MIN_SCALE) {
                fitImageToView();
                return true;
            }

            float factor = 2.5f;

            matrix.postScale(
                    factor,
                    factor,
                    event.getX(),
                    event.getY()
            );

            currentScale = factor;

            constrainTranslation();
            setImageMatrix(matrix);

            return true;
        }

        @Override
        public boolean onDown(
                MotionEvent event
        ) {
            return true;
        }
    }
}
