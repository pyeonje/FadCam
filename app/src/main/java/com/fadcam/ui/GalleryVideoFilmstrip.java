package com.fadcam.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;

/** Eight bounded previews from this video only. Failed frames stay empty; no fabricated filmstrip. */
public final class GalleryVideoFilmstrip extends LinearLayout {
    static final int FRAME_COUNT = 8;
    private final RequestManager requests;
    private Uri media;
    private long duration = -1;
    private int generation;
    private boolean anyFrame;
    private Runnable onFramesReady;
    public GalleryVideoFilmstrip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        requests = Glide.with(context);
        for (int i = 0; i < FRAME_COUNT; i++) {
            ImageView frame = new ImageView(context);
            frame.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams params = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
            params.setMarginEnd(Math.round(context.getResources().getDisplayMetrics().density));
            addView(frame, params);
        }
    }
    static long[] sampleTimesMs(long durationMs) {
        if (durationMs <= 0) return new long[0];
        long[] times = new long[FRAME_COUNT];
        long span = Math.min(durationMs, Long.MAX_VALUE / 1000L);
        // Sample the start of each eighth; requesting the exact media endpoint can produce no frame.
        for (int i = 0; i < FRAME_COUNT; i++) times[i] = (span / FRAME_COUNT) * i + ((span % FRAME_COUNT) * i) / FRAME_COUNT;
        return times;
    }
    void showVideo(Uri uri, long durationMs) {
        if (uri == null || (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme()))) {
            clearFrames(); return;
        }
        if (durationMs <= 0) {
            if (!uri.equals(media)) clearFrames();
            return;
        }
        if (uri.equals(media) && duration == durationMs) return;
        clearFrames(); media = uri; duration = durationMs;
        final int requestGeneration = generation;
        long[] times = sampleTimesMs(durationMs);
        for (int i = 0; i < times.length; i++) requests.asBitmap().load(uri).frame(times[i] * 1000L)
                // Glide's existing bounded memory cache reuses prepared frames when
                // the strip expands or its holder is rebound. No media is copied to disk.
                .signature(new ObjectKey(durationMs)).override(96, 72).centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.NONE).skipMemoryCache(false).dontAnimate()
                .listener(new RequestListener<Bitmap>() {
                    @Override public boolean onLoadFailed(@Nullable GlideException error, Object model,
                            Target<Bitmap> target, boolean firstResource) { return false; }
                    @Override public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target,
                            DataSource source, boolean firstResource) {
                        if (generation != requestGeneration || !uri.equals(media)) return true;
                        if (!anyFrame) {
                            anyFrame = true;
                            if (onFramesReady != null) onFramesReady.run();
                        }
                        return false;
                    }
                })
                .into((ImageView) getChildAt(i));
    }
    boolean hasAnyFrame() { return anyFrame; }
    void setOnFramesReadyListener(@Nullable Runnable callback) { onFramesReady = callback; }
    void clearFrames() {
        ++generation; anyFrame = false;
        for (int i = 0; i < getChildCount(); i++) {
            ImageView frame = (ImageView) getChildAt(i);
            requests.clear(frame); frame.setImageDrawable(null);
        }
        media = null; duration = -1;
    }
    // RecyclerView temporarily detaches this same holder while changing its width
    // on expansion. Its owner explicitly clears on recycle/close; detachment must
    // not discard already prepared images or restart eight decoder requests.
}
