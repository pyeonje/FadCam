package com.fadcam.ui;

import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.function.IntSupplier;

/** Two-finger density changes only; ordinary scrolling, tile taps and selection remain owned by the grid. */
final class GalleryGridPinchController extends RecyclerView.SimpleOnItemTouchListener implements AutoCloseable {
    interface DensityChange { void apply(int portraitColumns, float focusX, float focusY); }
    private final RecyclerView grid;
    private final ScaleGestureDetector detector;
    private boolean consuming, closed;
    private float accumulated = 1f;

    GalleryGridPinchController(RecyclerView grid, IntSupplier columns, DensityChange change) {
        this.grid = grid;
        detector = new ScaleGestureDetector(grid.getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector scale) { accumulated = 1f; return true; }
            @Override public boolean onScale(ScaleGestureDetector scale) {
                accumulated *= scale.getScaleFactor();
                int current = columns.getAsInt();
                int next = nextColumns(current, accumulated);
                if (next != current) {
                    change.apply(next, scale.getFocusX(), scale.getFocusY()); accumulated = 1f;
                } else if (accumulated >= 1.25f || accumulated <= .8f) accumulated = 1f;
                return true;
            }
        });
        detector.setQuickScaleEnabled(false); detector.setStylusScaleEnabled(false);
        grid.addOnItemTouchListener(this);
    }
    static int nextColumns(int current, float scale) {
        if (!Float.isFinite(scale) || scale <= 0f) return current;
        if (scale >= 1.25f) return current <= 2 ? current : current - 1;
        if (scale <= .8f) return Math.min(5, Math.max(2, current + 1));
        return current;
    }
    @Override public boolean onInterceptTouchEvent(@NonNull RecyclerView view, @NonNull MotionEvent event) {
        if (closed) return false;
        if (consuming) return true;
        if (event.getPointerCount() > 1) {
            consuming = true; view.stopScroll();
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        detector.onTouchEvent(event); return false;
    }
    @Override public void onTouchEvent(@NonNull RecyclerView view, @NonNull MotionEvent event) {
        if (closed) return;
        detector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            consuming = false; accumulated = 1f;
            if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
        }
    }
    @Override public void close() { closed = true; consuming = false; grid.removeOnItemTouchListener(this); }
}
