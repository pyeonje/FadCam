package com.fadcam.ui;

import android.content.Context;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.fadcam.data.VideoIndexDatabase;
import com.fadcam.data.entity.VideoIndexEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Existing app index only; media navigation is separate from the selected video's frame timeline. */
public final class GalleryMediaStrip extends RecyclerView {
    interface Listener {
        void onCurrentVideoTap(); void onMediaSelected(VideoItem item);
        void onFrameSeekStarted(); void onFrameSeek(long positionMs, boolean finished);
    }
    private final LinearLayoutManager layout;
    private final RequestManager images;
    private final List<VideoItem> items = new ArrayList<>();
    private final StripAdapter adapter = new StripAdapter();
    private ExecutorService reader;
    private Uri selected;
    private Listener listener;
    private int generation;
    private boolean closed;
    private boolean expanded, userScrubbing;
    private long durationMs, positionMs;
    private static final int EXPANDED_REFERENCE_WIDTH = 1496;
    private float referenceScale;
    private final Runnable recenter = this::centerSelectedNow;

    public GalleryMediaStrip(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        referenceScale = Math.min(metrics.widthPixels, metrics.heightPixels) / 1440f;
        layout = new LinearLayoutManager(context, HORIZONTAL, false) {
            @Override public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
                // Expanded frames belong to one video. Neighbouring media are
                // still visible, but may never be dragged underneath its cursor.
                if (expanded) {
                    View current = selectedView();
                    if (current == null) return 0;
                    int cursor = getWidth() / 2;
                    int start = current.getLeft() + current.getPaddingLeft() - cursor;
                    int end = current.getRight() - current.getPaddingRight() - cursor;
                    dx = Math.max(Math.min(0, start), Math.min(Math.max(0, end), dx));
                }
                return super.scrollHorizontallyBy(dx, recycler, state);
            }
        };
        setLayoutManager(layout); setAdapter(adapter); setItemAnimator(null);
        setClipToPadding(false); setOverScrollMode(OVER_SCROLL_NEVER);
        images = Glide.with(context);
        addOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (!expanded || listener == null) return;
                if (state == SCROLL_STATE_DRAGGING && !userScrubbing) {
                    userScrubbing = true; listener.onFrameSeekStarted();
                } else if (state == SCROLL_STATE_IDLE && userScrubbing) {
                    positionMs = positionAtCursor();
                    listener.onFrameSeek(positionMs, true); userScrubbing = false;
                }
            }
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (expanded && userScrubbing && listener != null && dx != 0) {
                    positionMs = positionAtCursor(); listener.onFrameSeek(positionMs, false);
                }
            }
        });
    }
    void setExpanded(boolean value, long duration, long position) {
        boolean changed = expanded != value || durationMs != Math.max(0L, duration);
        expanded = value; durationMs = Math.max(0L, duration); positionMs = Math.max(0L, position);
        if (changed) refreshSelected();
        centerSelected();
    }
    public boolean isExpanded() { return expanded; }
    void setReferenceShortEdge(int shortEdge) {
        float next = shortEdge / 1440f;
        if (next <= 0 || referenceScale == next) return;
        referenceScale = next;
        if (getWidth() > 0) {
            int edge = Math.max(0, getWidth() / 2 - px(8));
            setPadding(edge, 0, edge, 0);
        }
        adapter.notifyDataSetChanged(); centerSelected();
    }
    void updatePlayback(long duration, long position) {
        if (closed || userScrubbing) return;
        boolean durationChanged = durationMs != duration;
        durationMs = Math.max(0L, duration); positionMs = Math.max(0L, position);
        if (durationChanged) refreshSelected(); // Prepare this video's previews before the first expansion tap.
        if (expanded) centerSelected();
    }
    private void refreshSelected() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(selected)) {
            adapter.notifyItemChanged(i, Boolean.TRUE); break;
        }
    }
    boolean selectMedia(Uri uri) {
        if (closed || uri == null || uri.equals(selected)) return false;
        int previous = -1, next = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).uri.equals(selected)) previous = i;
            if (items.get(i).uri.equals(uri)) next = i;
        }
        if (next < 0) return false;
        stopScroll(); selected = uri; expanded = false; userScrubbing = false; durationMs = 0; positionMs = 0;
        if (previous >= 0) adapter.notifyItemChanged(previous, Boolean.TRUE);
        adapter.notifyItemChanged(next, Boolean.TRUE); centerSelected();
        return true;
    }
    boolean navigateMedia(int direction) {
        if (closed || listener == null || direction == 0) return false;
        for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(selected)) {
            int next = i + Integer.signum(direction);
            if (next < 0 || next >= items.size()) return false;
            listener.onMediaSelected(items.get(next)); return true;
        }
        return false;
    }
    static long seekPosition(long duration, int cursorX, int frameLeft, int frameWidth) {
        if (duration <= 0 || frameWidth <= 0) return 0;
        double fraction = Math.max(0d, Math.min(1d, (cursorX - (double) frameLeft) / frameWidth));
        return Math.round(duration * fraction);
    }
    private long positionAtCursor() {
        View frames = selectedView();
        if (frames != null) return seekPosition(durationMs, getWidth() / 2,
                frames.getLeft() + frames.getPaddingLeft(), frames.getWidth() - frames.getPaddingLeft() - frames.getPaddingRight());
        return positionMs;
    }
    private View selectedView() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(selected)) return layout.findViewByPosition(i);
        return null;
    }
    void showCurrent(Uri current, Listener callback) {
        if (current == null || closed) return;
        bindMedia(new ArrayList<>(), current, callback);
        int request = ++generation;
        if (reader == null) reader = Executors.newSingleThreadExecutor();
        Context app = getContext().getApplicationContext();
        reader.execute(() -> {
            List<VideoItem> indexed = new ArrayList<>();
            try {
                indexed = fromIndex(VideoIndexDatabase.getInstance(app).videoIndexDao().getAllNewestFirst());
            } catch (RuntimeException ignored) { /* Keep the exact current item if the index is unavailable. */ }
            List<VideoItem> result = indexed;
            post(() -> {
                if (closed || generation != request) return;
                bindMedia(result, current, callback);
            });
        });
    }
    void bindMedia(List<VideoItem> media, Uri current, Listener callback) {
        if (closed) return;
        selected = current; listener = callback;
        items.clear(); items.addAll(media);
        boolean found = false;
        for (VideoItem item : items) if (current.equals(item.uri)) { found = true; break; }
        if (!found) items.add(0, new VideoItem(current, "", 1, Long.MAX_VALUE));
        adapter.notifyDataSetChanged(); centerSelected();
    }
    static List<VideoItem> fromIndex(List<VideoIndexEntity> rows) {
        List<VideoItem> result = new ArrayList<>();
        for (VideoIndexEntity row : rows) {
            if (row == null || row.isTemporary || row.fileSize <= 0 || row.uriString == null) continue;
            Uri uri = Uri.parse(row.uriString);
            if (!"file".equals(uri.getScheme()) && !"content".equals(uri.getScheme())) continue;
            VideoItem.MediaType type = "IMAGE".equals(row.mediaType) ? VideoItem.MediaType.IMAGE : VideoItem.MediaType.VIDEO;
            VideoItem.Category category;
            try { category = VideoItem.Category.valueOf(row.category); } catch (RuntimeException ignored) { category = VideoItem.Category.UNKNOWN; }
            result.add(new VideoItem(uri, row.displayName, row.fileSize, row.lastModified, category, type));
        }
        return result;
    }
    private void centerSelected() {
        removeCallbacks(recenter); post(recenter);
    }
    private void centerSelectedNow() {
        // Playback can post this before the user starts dragging; recheck when it actually runs.
        if (closed || userScrubbing || getScrollState() != SCROLL_STATE_IDLE) return;
        for (int i = 0; i < items.size(); i++) if (items.get(i).uri.equals(selected)) {
            int width = px(expanded ? EXPANDED_REFERENCE_WIDTH : 236);
            float fraction = expanded && durationMs > 0 ? Math.max(0f, Math.min(1f, positionMs / (float) durationMs)) : .5f;
            int inset = expanded ? px(8) : 0;
            layout.scrollToPositionWithOffset(i, Math.round(getWidth() / 2f - getPaddingLeft() - inset - (width - 2 * inset) * fraction)); break;
        }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            // Align actual frame pixels (inside the item's8px padding) to the
            // fixed cursor, including the first and last videos in the list.
            int edge = Math.max(0, w / 2 - px(8));
            setPadding(edge, 0, edge, 0); centerSelected();
        }
    }
    void close() {
        closed = true; ++generation; listener = null;
        removeCallbacks(recenter);
        if (reader != null) reader.shutdownNow(); reader = null;
        for (int i = 0; i < getChildCount(); i++) {
            ViewHolder holder = getChildViewHolder(getChildAt(i));
            if (holder instanceof Holder) ((Holder) holder).frames.clearFrames();
        }
        setAdapter(null);
    }
    private int px(int value) { return Math.max(1, Math.round(value * referenceScale)); }
    private final class StripAdapter extends Adapter<Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout cell = new FrameLayout(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(px(116), px(184)));
            ImageView thumbnail = new ImageView(parent.getContext()); thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cell.addView(thumbnail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(135), Gravity.CENTER));
            GalleryVideoFilmstrip frames = new GalleryVideoFilmstrip(parent.getContext(), null);
            frames.setVisibility(GONE);
            cell.addView(frames, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(165), Gravity.CENTER));
            return new Holder(cell, thumbnail, frames);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            VideoItem item = items.get(position); boolean current = item.uri.equals(selected);
            holder.itemView.getLayoutParams().width = px(current ? (expanded ? EXPANDED_REFERENCE_WIDTH : 236) : 116);
            holder.itemView.getLayoutParams().height = px(184);
            holder.thumbnail.getLayoutParams().height = px(current ? 165 : 135);
            holder.frames.getLayoutParams().height = px(165);
            holder.itemView.setPadding(px(8), 0, px(8), 0); holder.itemView.setSelected(current);
            holder.itemView.setContentDescription(getContext().getString(com.fadcam.R.string.viewer_gallery) + " " + (position + 1) + "/" + items.size());
            holder.itemView.setOnClickListener(v -> {
                if (listener == null) return;
                if (item.uri.equals(selected)) listener.onCurrentVideoTap(); else listener.onMediaSelected(item);
            });
            holder.frames.setVisibility(current && expanded ? VISIBLE : GONE);
            // Keep the existing thumbnail behind the asynchronous frame row: expansion
            // must not erase it and expose an empty strip while decoding catches up.
            holder.thumbnail.setVisibility(VISIBLE);
            if (!item.uri.equals(holder.thumbnailUri)) {
                holder.thumbnailUri = item.uri;
                images.load(item.uri).override(px(236), px(165)).centerCrop().dontAnimate().into(holder.thumbnail);
            }
            if (current) holder.frames.showVideo(item.uri, durationMs);
            else holder.frames.clearFrames();
        }
        @Override public int getItemCount() { return items.size(); }
        @Override public void onViewRecycled(@NonNull Holder holder) {
            images.clear(holder.thumbnail); holder.thumbnail.setImageDrawable(null); holder.itemView.setOnClickListener(null);
            holder.thumbnailUri = null;
            holder.frames.clearFrames();
        }
    }
    private static final class Holder extends ViewHolder {
        final ImageView thumbnail;
        final GalleryVideoFilmstrip frames;
        Uri thumbnailUri;
        Holder(View view, ImageView image, GalleryVideoFilmstrip strip) { super(view); thumbnail = image; frames = strip; }
    }
}
