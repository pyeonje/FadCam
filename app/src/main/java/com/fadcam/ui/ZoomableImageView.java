package com.fadcam.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomableImageView extends AppCompatImageView {
    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;

    /**
     * Listener for single-tap events on the image, confirmed after
     * ruling out double-tap and scroll gestures.
     */
    public interface OnSingleTapListener {
        void onSingleTap();
    }

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final PointF last = new PointF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private boolean dragging = false;
    @Nullable private java.util.function.IntConsumer mediaSwipeListener;
    private float swipeDownX, swipeDownY;
    private boolean mediaSwipeEligible;
    private float currentScale = 1f;
    private float baseScale = 1f;
    private boolean matrixReady;
    @Nullable private float[] pendingViewport;
    @Nullable
    private OnSingleTapListener singleTapListener;

    public ZoomableImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    /**
     * Sets a listener that will be called when the user performs a confirmed single tap.
     *
     * @param listener The listener to set, or null to remove.
     */
    public void setOnSingleTapListener(@Nullable OnSingleTapListener listener) {
        this.singleTapListener = listener;
    }

    /** Gallery paging applies only at the fitted image size; zoomed drags keep panning. */
    void setOnMediaSwipeListener(@Nullable java.util.function.IntConsumer listener) {
        mediaSwipeListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float[] viewport = matrixReady && oldw > 0 && oldh > 0
                ? captureViewport(oldw, oldh) : pendingViewport;
        super.onSizeChanged(w, h, oldw, oldh);
        fitViewport(viewport);
    }

    @Override
    public void setImageDrawable(@Nullable android.graphics.drawable.Drawable drawable) {
        super.setImageDrawable(drawable);
        matrixReady = false;
        post(() -> { if (!matrixReady) fitViewport(pendingViewport); });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) {
            return super.onTouchEvent(event);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            swipeDownX = event.getX(); swipeDownY = event.getY();
            mediaSwipeEligible = currentScale <= baseScale * 1.01f;
        }
        if (event.getPointerCount() > 1) mediaSwipeEligible = false;
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                last.set(event.getX(), event.getY());
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress() && currentScale > baseScale) {
                    float dx = event.getX() - last.x;
                    float dy = event.getY() - last.y;
                    matrix.postTranslate(dx, dy);
                    clampTranslation();
                    setImageMatrix(matrix);
                    last.set(event.getX(), event.getY());
                }
                break;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - swipeDownX, dy = event.getY() - swipeDownY;
                float threshold = Math.max(android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop() * 2f,
                        48f * getResources().getDisplayMetrics().density);
                if (mediaSwipeEligible && mediaSwipeListener != null && Math.abs(dx) >= threshold
                        && Math.abs(dx) > Math.abs(dy) * 1.25f) mediaSwipeListener.accept(dx > 0 ? 1 : -1);
                mediaSwipeEligible = false;
                dragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                mediaSwipeEligible = false;
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }

    private void fitToCenter() {
        fitViewport(null);
    }

    /** Relative zoom and normalized source-image center, independent of screen dimensions. */
    @Nullable
    float[] captureViewport() {
        return matrixReady ? captureViewport(getWidth(), getHeight()) : pendingViewport;
    }

    private float[] captureViewport(int width, int height) {
        matrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        return new float[] {currentScale / baseScale,
                (width * .5f - values[Matrix.MTRANS_X]) / (scale * getDrawable().getIntrinsicWidth()),
                (height * .5f - values[Matrix.MTRANS_Y]) / (scale * getDrawable().getIntrinsicHeight())};
    }

    void restoreViewport(@Nullable float[] viewport) {
        if (viewport == null || viewport.length != 3) return;
        for (float value : viewport) if (!Float.isFinite(value)) return;
        pendingViewport = viewport.clone();
        fitViewport(pendingViewport);
    }

    private void fitViewport(@Nullable float[] viewport) {
        if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float drawableW = getDrawable().getIntrinsicWidth();
        float drawableH = getDrawable().getIntrinsicHeight();
        if (drawableW <= 0 || drawableH <= 0) {
            return;
        }

        matrix.reset();
        float scale = Math.min(getWidth() / drawableW, getHeight() / drawableH);
        baseScale = scale;
        float zoom = viewport == null ? 1f : Math.max(MIN_SCALE, Math.min(MAX_SCALE, viewport[0]));
        currentScale = scale * zoom;
        matrix.postScale(currentScale, currentScale);
        float centerX = viewport == null ? .5f : Math.max(0f, Math.min(1f, viewport[1]));
        float centerY = viewport == null ? .5f : Math.max(0f, Math.min(1f, viewport[2]));
        float redundantX = getWidth() * .5f - drawableW * currentScale * centerX;
        float redundantY = getHeight() * .5f - drawableH * currentScale * centerY;
        matrix.postTranslate(redundantX, redundantY);
        clampTranslation();
        setImageMatrix(matrix);
        matrixReady = true;
        pendingViewport = null;
    }

    private void resetZoom() {
        fitToCenter();
    }

    private void clampTranslation() {
        if (getDrawable() == null) return;
        float drawableW = getDrawable().getIntrinsicWidth();
        float drawableH = getDrawable().getIntrinsicHeight();

        matrix.getValues(values);
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float scaledW = drawableW * values[Matrix.MSCALE_X];
        float scaledH = drawableH * values[Matrix.MSCALE_Y];

        float minX = Math.min(0, getWidth() - scaledW);
        float maxX = Math.max(0, getWidth() - scaledW);
        float minY = Math.min(0, getHeight() - scaledH);
        float maxY = Math.max(0, getHeight() - scaledH);

        float clampedX = scaledW <= getWidth() ? (getWidth() - scaledW) * .5f
                : Math.min(maxX, Math.max(minX, transX));
        float clampedY = scaledH <= getHeight() ? (getHeight() - scaledH) * .5f
                : Math.min(maxY, Math.max(minY, transY));
        matrix.postTranslate(clampedX - transX, clampedY - transY);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            float targetScale = currentScale * scaleFactor;
            float minAllowed = Math.max(baseScale, MIN_SCALE * baseScale);
            float maxAllowed = MAX_SCALE * baseScale;
            if (targetScale < minAllowed) {
                scaleFactor = minAllowed / currentScale;
            } else if (targetScale > maxAllowed) {
                scaleFactor = maxAllowed / currentScale;
            }
            matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            currentScale *= scaleFactor;
            clampTranslation();
            setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (singleTapListener != null) {
                singleTapListener.onSingleTap();
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            resetZoom();
            return true;
        }
    }
}
