package com.fadcam.ui;

import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;
import android.graphics.Color;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.forensics.ui.ForensicsEvidenceInfoBottomSheet;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class ImageViewerActivity extends AppCompatActivity {

    private static final int OVERLAY_ANIM_DURATION = 200;
    public static android.content.Intent createGalleryIntent(android.content.Context context, Uri uri) {
        return new android.content.Intent(context, ImageViewerActivity.class).setDataAndType(uri, "image/*")
                .putExtra(VideoPlayerActivity.EXTRA_GALLERY_PREVIEW, true)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    public static final String EXTRA_SOURCE_VIDEO_URI = "com.fadcam.extra.SOURCE_VIDEO_URI";
    public static final String EXTRA_OPEN_AT_MS = "com.fadcam.extra.OPEN_AT_MS";
    public static final String EXTRA_EVENT_TYPE = "com.fadcam.extra.EVENT_TYPE";
    public static final String EXTRA_CLASS_NAME = "com.fadcam.extra.CLASS_NAME";
    public static final String EXTRA_CONFIDENCE = "com.fadcam.extra.CONFIDENCE";
    public static final String EXTRA_CAPTURED_AT = "com.fadcam.extra.CAPTURED_AT";
    public static final String EXTRA_SOURCE_LABEL = "com.fadcam.extra.SOURCE_LABEL";

    @Nullable private String itemCategory;
    @Nullable private String itemDisplayName;

    @Nullable
    private View topDock;
    @Nullable
    private View bottomDock;
    private boolean overlayVisible = true;
    private ZoomableImageView imageView;
    private GalleryMediaStrip galleryMediaStrip;
    private static final String STATE_VIEWPORT = "image_viewport";
    private static final String STATE_OVERLAY = "image_overlay";

    @Nullable
    private Uri sourceVideoUri;
    private long openAtMs = 0L;
    @Nullable
    private String eventType;
    @Nullable
    private String className;
    private float confidence = 0f;
    private long capturedAt = 0L;
    @Nullable
    private String sourceLabel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(this);
        String savedTheme = spm.sharedPreferences.getString(
                Constants.PREF_APP_THEME,
                Constants.DEFAULT_APP_THEME
        );
        if ("Crimson Bloom".equals(savedTheme)) {
            setTheme(R.style.Theme_FadCam_Red);
        } else if ("Faded Night".equals(savedTheme)) {
            setTheme(R.style.Theme_FadCam_Amoled);
        } else {
            setTheme(R.style.Base_Theme_FadCam);
        }
        super.onCreate(savedInstanceState);
        enableEdgeToEdge();
        setContentView(R.layout.activity_image_viewer);

        ImageView back = findViewById(R.id.imageViewerBack);
        ImageView more = findViewById(R.id.imageViewerMore);
        imageView = findViewById(R.id.imageViewerImage);
        TextView metaView = findViewById(R.id.imageViewerMeta);
        topDock = findViewById(R.id.imageViewerTopDock);
        bottomDock = findViewById(R.id.imageViewerBottomDock);
        if (savedInstanceState != null) {
            imageView.restoreViewport(savedInstanceState.getFloatArray(STATE_VIEWPORT));
            overlayVisible = savedInstanceState.getBoolean(STATE_OVERLAY, true);
            if (!overlayVisible) {
                topDock.setVisibility(View.GONE);
                bottomDock.setVisibility(View.GONE);
            }
        }
        applyViewerLayout();
        applySystemInsets();
        if (back != null) {
            back.setOnClickListener(v -> {
                if (getIntent().getBooleanExtra(VideoPlayerActivity.EXTRA_GALLERY_PREVIEW, false)
                        && !getIntent().getBooleanExtra("gallery_return_to_grid", false)) {
                    startActivity(new android.content.Intent(this, com.fadcam.MainActivity.class)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(GalleryViewerActions.EXTRA_OPEN_GALLERY, true));
                }
                finish();
            });
        }
        if (imageView != null) {
            imageView.setOnSingleTapListener(this::toggleOverlay);
        }

        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri == null) {
            Toast.makeText(this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setupGalleryMediaNavigation(uri);
        sourceVideoUri = parseUri(getIntent().getStringExtra(EXTRA_SOURCE_VIDEO_URI));
        openAtMs = Math.max(0L, getIntent().getLongExtra(EXTRA_OPEN_AT_MS, 0L));
        eventType = getIntent().getStringExtra(EXTRA_EVENT_TYPE);
        className = getIntent().getStringExtra(EXTRA_CLASS_NAME);
        confidence = getIntent().getFloatExtra(EXTRA_CONFIDENCE, 0f);
        capturedAt = getIntent().getLongExtra(EXTRA_CAPTURED_AT, 0L);
        sourceLabel = getIntent().getStringExtra(EXTRA_SOURCE_LABEL);
        itemCategory = getIntent().getStringExtra("item_category");
        itemDisplayName = getIntent().getStringExtra("item_display_name");

        if (metaView != null) {
            boolean evidence = sourceVideoUri != null || !TextUtils.isEmpty(className) || !TextUtils.isEmpty(eventType);
            metaView.setVisibility(evidence ? View.VISIBLE : View.GONE);
            if (evidence) metaView.setText(buildInlineMeta());
        }
        if (more != null) {
            more.setOnClickListener(v -> GalleryViewerActions.showMore(this, v, uri, itemDisplayName, "image/*",
                    () -> showInfoSheet(uri), null));
        }
        GalleryViewerActions.bindFavorite(this, findViewById(R.id.gallery_viewer_favorite), uri);
        GalleryViewerActions.bindEdit(this, findViewById(R.id.gallery_viewer_edit), uri, false);
        findViewById(R.id.gallery_viewer_share).setOnClickListener(v -> GalleryViewerActions.share(this, uri, "image/*"));
        findViewById(R.id.gallery_viewer_delete).setOnClickListener(v -> GalleryViewerActions.confirmDelete(this, uri, itemDisplayName));

        Glide.with(this)
                .load(uri)
                .error(R.drawable.ic_video_placeholder)
                .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                        Toast.makeText(ImageViewerActivity.this, R.string.photo_capture_failed, Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model, Target<android.graphics.drawable.Drawable> target, com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(imageView);
    }

    private void setupGalleryMediaNavigation(Uri uri) {
        galleryMediaStrip = findViewById(R.id.gallery_media_strip);
        if (!getIntent().getBooleanExtra(VideoPlayerActivity.EXTRA_GALLERY_PREVIEW, false)) return;
        galleryMediaStrip.setVisibility(View.VISIBLE);
        galleryMediaStrip.showCurrent(uri, new GalleryMediaStrip.Listener() {
            @Override public void onCurrentVideoTap() { }
            @Override public void onMediaSelected(VideoItem item) { selectGalleryMedia(item); }
            @Override public void onFrameSeekStarted() { }
            @Override public void onFrameSeek(long positionMs, boolean finished) { }
        });
        imageView.setOnMediaSwipeListener(direction -> galleryMediaStrip.navigateMedia(direction));
    }

    void selectGalleryMedia(VideoItem item) {
        if (item == null || item.uri == null || item.uri.equals(getIntent().getData())) return;
        android.content.Intent intent = RecentMediaLauncher.createIntent(this, item);
        intent.putExtra("gallery_return_to_grid", getIntent().getBooleanExtra("gallery_return_to_grid", false));
        startActivity(intent);
        finish();
    }

    @Override protected void onDestroy() {
        if (imageView != null) imageView.setOnMediaSwipeListener(null);
        if (galleryMediaStrip != null) galleryMediaStrip.close();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The image view preserves its source-relative zoom/center when its size changes.
        applyViewerLayout();
        ViewCompat.requestApplyInsets(findViewById(android.R.id.content));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (imageView != null) outState.putFloatArray(STATE_VIEWPORT, imageView.captureViewport());
        outState.putBoolean(STATE_OVERLAY, overlayVisible);
        super.onSaveInstanceState(outState);
    }

    private void applyViewerLayout() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        float density = getResources().getDisplayMetrics().density;
        if (topDock != null) {
            int vertical = 0;
            topDock.setPadding(topDock.getPaddingLeft(), vertical, topDock.getPaddingRight(), vertical);
        }
        TextView meta = findViewById(R.id.imageViewerMeta);
        if (meta != null) meta.setMaxLines(landscape ? 1 : 3);
    }

    /**
     * Toggles the visibility of the top and bottom overlay docks with a fade animation.
     * Called on single-tap of the image.
     */
    private void toggleOverlay() {
        overlayVisible = !overlayVisible;
        if (overlayVisible) {
            showDock(topDock);
            showDock(bottomDock);
        } else {
            hideDock(topDock);
            hideDock(bottomDock);
        }
    }

    private void showDock(@Nullable View dock) {
        if (dock == null) return;
        dock.setVisibility(View.VISIBLE);
        dock.setAlpha(0f);
        dock.animate()
                .alpha(1f)
                .setDuration(OVERLAY_ANIM_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .setListener(null)
                .start();
    }

    private void hideDock(@Nullable View dock) {
        if (dock == null) return;
        dock.animate()
                .alpha(0f)
                .setDuration(OVERLAY_ANIM_DURATION)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> dock.setVisibility(View.GONE))
                .start();
    }

    private void enableEdgeToEdge() {
        Window window = getWindow();
        if (window == null) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        com.fadcam.util.SystemBarUtil.setStatusBarColor(window, Color.TRANSPARENT);
        com.fadcam.util.SystemBarUtil.setNavigationBarColor(window, Color.TRANSPARENT);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }
    }

    private void applySystemInsets() {
        final View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        final View top = topDock;
        final View bottom = bottomDock;
        final int topBaseTopMargin = getTopMargin(top);
        final int topBaseStartMargin = getStartMargin(top);
        final int topBaseEndMargin = getEndMargin(top);
        final int bottomBaseBottomMargin = getBottomMargin(bottom);
        final int bottomBaseStartMargin = getStartMargin(bottom);
        final int bottomBaseEndMargin = getEndMargin(bottom);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (top != null && top.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) top.getLayoutParams();
                lp.topMargin = topBaseTopMargin + systemBars.top;
                lp.leftMargin = topBaseStartMargin + systemBars.left;
                lp.rightMargin = topBaseEndMargin + systemBars.right;
                top.setLayoutParams(lp);
            }
            if (bottom != null && bottom.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) bottom.getLayoutParams();
                lp.bottomMargin = bottomBaseBottomMargin + systemBars.bottom;
                lp.leftMargin = bottomBaseStartMargin + systemBars.left;
                lp.rightMargin = bottomBaseEndMargin + systemBars.right;
                bottom.setLayoutParams(lp);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private int getTopMargin(@Nullable View view) {
        if (view == null) return 0;
        if (view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            return ((android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams()).topMargin;
        }
        return 0;
    }

    private int getBottomMargin(@Nullable View view) {
        if (view == null) return 0;
        if (view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            return ((android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams()).bottomMargin;
        }
        return 0;
    }

    private int getStartMargin(@Nullable View view) {
        if (view == null) return 0;
        if (view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            return ((android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams()).leftMargin;
        }
        return 0;
    }

    private int getEndMargin(@Nullable View view) {
        if (view == null) return 0;
        if (view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            return ((android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams()).rightMargin;
        }
        return 0;
    }

    @Nullable
    private Uri parseUri(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (Throwable t) {
            return null;
        }
    }

    @NonNull
    private String buildInlineMeta() {
        String classLabel = !TextUtils.isEmpty(className)
                ? className
                : (!TextUtils.isEmpty(eventType) ? eventType : "Evidence");
        String when = capturedAt > 0L
                ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
                .format(new Date(capturedAt))
                : getString(R.string.forensics_unknown_time);
        return String.format(
                Locale.US,
                "%s • conf %.2f • %s",
                classLabel.toUpperCase(Locale.US),
                confidence,
                when
        );
    }

    private void showInfoSheet(@NonNull Uri snapshotUri) {
        if ("MINIAPPS".equals(itemCategory)) {
            String name = itemDisplayName != null ? itemDisplayName
                    : snapshotUri.getLastPathSegment();
            ScanInfoBottomSheet.newInstance(snapshotUri.toString(), name)
                    .show(getSupportFragmentManager(), "scan_info");
            return;
        }
        if (sourceVideoUri == null && TextUtils.isEmpty(className) && TextUtils.isEmpty(eventType)) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.video_menu_info)
                    .setMessage(GalleryViewerActions.displayName(this, snapshotUri, itemDisplayName)
                            + "\n\n" + snapshotUri)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        ForensicsEvidenceInfoBottomSheet.newInstance(
                className,
                eventType,
                confidence,
                capturedAt,
                openAtMs,
                sourceLabel,
                sourceVideoUri != null ? sourceVideoUri.toString() : null,
                snapshotUri.toString()
        ).show(getSupportFragmentManager(), "ForensicsEvidenceInfoBottomSheet");
    }

}
