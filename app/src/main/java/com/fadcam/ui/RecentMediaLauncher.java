package com.fadcam.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.fadcam.FLog;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.VideoIndexRepository;
import com.google.android.material.button.MaterialButton;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Camera thumbnail opens the latest completed item, independently of gallery filters/sort. */
final class RecentMediaLauncher implements AutoCloseable {
    private final Fragment owner;
    private final MaterialButton button;
    private final Context app;
    private final RequestManager images;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private CustomTarget<Drawable> target;
    private VideoItem current;
    private boolean closed, opening, activeRecording;
    private int generation;

    RecentMediaLauncher(Fragment owner, MaterialButton button) {
        this.owner = owner;
        this.button = button;
        this.app = owner.requireContext().getApplicationContext();
        this.images = Glide.with(owner);
    }

    void refresh() { read(false); }

    void setActiveRecording(boolean active) {
        if (activeRecording == active) return;
        activeRecording = active;
        if (active) {
            if (target != null) images.clear(target);
            target = null;
        } else {
            VideoItem previous = current;
            current = null;
            showThumbnail(previous);
            refresh();
        }
    }

    void open() {
        if (closed || opening || activeRecording) return;
        opening = true;
        read(true);
    }

    private void read(boolean launch) {
        if (closed || (!launch && (opening || activeRecording))) return;
        final int request = ++generation;
        reader.execute(() -> {
            VideoItem newest = null;
            boolean failed = false;
            try {
                VideoIndexRepository repository = VideoIndexRepository.getInstance(app);
                SharedPreferencesManager settings = SharedPreferencesManager.getInstance(app);
                // A tap must see newly finalized/deleted files, not an old session thumbnail.
                // The same repository/scanner is used by the existing Gallery; no new permissions.
                List<VideoItem> items = launch ? repository.deltaScan(settings) : repository.getVideos(settings);
                newest = selectLatest(items);
            } catch (Exception error) {
                failed = true;
                FLog.w("RecentMedia", "Cannot resolve recent media", error);
            }
            final VideoItem result = newest;
            final boolean unavailable = failed;
            main.post(() -> {
                if (closed || request != generation || !owner.isAdded() || owner.getView() == null) return;
                if (launch) opening = false;
                if (!unavailable) showThumbnail(result);
                if (launch) {
                    // A slow storage read must not navigate after the camera was left.
                    if (!owner.isResumed() || owner.isHidden()) return;
                    if (result == null) {
                        if (owner.getActivity() instanceof MainActivity)
                            ((MainActivity) owner.requireActivity()).switchFragment(1, true);
                    } else {
                        Intent intent = createIntent(owner.requireContext(), result);
                        try {
                            SharedPreferencesManager.getInstance(app).addOpenedVideoUri(result.uri.toString());
                            owner.startActivity(intent);
                        } catch (Exception error) {
                            FLog.w("RecentMedia", "Cannot open recent media", error);
                            if (owner.getActivity() instanceof MainActivity)
                                ((MainActivity) owner.requireActivity()).switchFragment(1, true);
                        }
                    }
                }
            });
        });
    }

    @Nullable static VideoItem selectLatest(@NonNull List<VideoItem> items) {
        VideoItem newest = null;
        for (VideoItem item : items) {
            if (item == null || item.uri == null || item.size <= 0 || item.isTemporary
                    || item.isProcessingUri || item.isSkeleton) continue;
            String scheme = item.uri.getScheme();
            if (!"file".equals(scheme) && !"content".equals(scheme)) continue;
            if (newest == null || item.lastModified > newest.lastModified) newest = item;
        }
        return newest;
    }

    static Intent createIntent(Context context, VideoItem item) {
        Intent intent = item.mediaType == VideoItem.MediaType.IMAGE
                ? ImageViewerActivity.createGalleryIntent(context, item.uri)
                : VideoPlayerActivity.createGalleryIntent(context, item.uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra("item_category", item.category.name());
        intent.putExtra("item_display_name", item.displayName);
        return intent;
    }

    private void showThumbnail(@Nullable VideoItem item) {
        if (activeRecording) { current = item; return; }
        if (current != null && item != null && current.uri.equals(item.uri)
                && current.lastModified == item.lastModified && current.size == item.size) return;
        current = item;
        if (target != null) images.clear(target);
        target = null;
        button.setIconResource(R.drawable.ic_photo);
        button.setIconTint(ColorStateList.valueOf(Color.WHITE));
        PersonalCameraLayout geometry = new PersonalCameraLayout(button.getContext());
        button.setIconSize(geometry.px(96));
        if (item == null) return;
        // The S24 reference thumbnail fills its 192px circle. A fixed44dp image
        // inside the scaled192px control left a visibly thick dark ring at600dpi.
        final int size = geometry.px(192);
        target = new CustomTarget<Drawable>(size, size) {
            @Override public void onResourceReady(@NonNull Drawable image,
                    @Nullable Transition<? super Drawable> transition) {
                if (closed || activeRecording || current != item || owner.getView() == null) return;
                button.setIconTint(null);
                button.setIconSize(size);
                button.setIcon(image);
            }
            @Override public void onLoadCleared(@Nullable Drawable placeholder) { }
            @Override public void onLoadFailed(@Nullable Drawable placeholder) {
                if (current == item) current = null;
            }
        };
        images.load(item.uri).circleCrop().override(size, size).into(target);
    }

    @Override public void close() {
        closed = true;
        ++generation;
        opening = false;
        main.removeCallbacksAndMessages(null);
        reader.shutdownNow();
        if (target != null) images.clear(target);
        target = null;
        current = null;
    }
}
