package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.exoplayer.ExoPlayer;
import com.fadcam.R;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SamsungGalleryViewerTest {
    private FragmentActivity host() {
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        activity.setTheme(R.style.Base_Theme_FadCam);
        return activity;
    }
    @Test public void recentMediaIntentsOpenExactItemWithReadGrantWithoutForcingPausedPlayback() {
        Uri uri = Uri.parse("content://test/media/last-completed");
        Intent video = VideoPlayerActivity.createGalleryIntent(RuntimeEnvironment.getApplication(), uri);
        assertEquals(uri, video.getData()); assertEquals("video/*", video.getType());
        assertTrue(video.getBooleanExtra(VideoPlayerActivity.EXTRA_GALLERY_PREVIEW, false));
        assertTrue((video.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertFalse(video.hasExtra(com.fadcam.forensics.ui.ForensicsEventsFragment.EXTRA_OPEN_PAUSED));
        Intent image = ImageViewerActivity.createGalleryIntent(RuntimeEnvironment.getApplication(), uri);
        assertEquals(uri, image.getData()); assertEquals("image/*", image.getType());
        assertTrue((image.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }
    @Test public void inlineGalleryUsesBottomPlayTimePillAndDistinctMediaStrip() {
        FragmentActivity activity = host();
        View root = LayoutInflater.from(activity).inflate(R.layout.gallery_video_control_view, null, false);
        View bottom = root.findViewById(R.id.gallery_video_bottom_dock);
        View center = root.findViewById(androidx.media3.ui.R.id.exo_center_controls);
        assertNotNull(bottom);
        assertEquals(android.view.Gravity.CENTER, ((FrameLayout.LayoutParams) center.getLayoutParams()).gravity);
        assertTrue(isInside(root.findViewById(androidx.media3.ui.R.id.exo_play_pause), center));
        assertTrue(isInside(root.findViewById(androidx.media3.ui.R.id.exo_play_pause), bottom));
        assertTrue(isInside(root.findViewById(R.id.material_time_slider), bottom));
        assertNull(root.findViewById(androidx.media3.ui.R.id.exo_controls_background));
        assertNull(bottom.getBackground());
        com.google.android.material.slider.Slider slider = root.findViewById(R.id.material_time_slider);
        int handleSize = Math.round(6 * activity.getResources().getDisplayMetrics().density);
        assertEquals(handleSize, slider.getThumbWidth()); assertEquals(handleSize, slider.getThumbHeight());
        assertEquals(Math.round(2 * activity.getResources().getDisplayMetrics().density), slider.getTrackHeight());
        assertFalse(containsFilmstrip(root));
        assertTrue(root.findViewById(R.id.gallery_media_strip) instanceof GalleryMediaStrip);
        assertEquals(View.GONE, root.findViewById(R.id.player_waveform_container).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.gallery_frame_cursor).getVisibility());
        for (int id : new int[]{R.id.gallery_viewer_favorite, R.id.gallery_viewer_edit,
                R.id.gallery_viewer_share, R.id.gallery_viewer_delete}) assertNull(root.findViewById(id));
        View activityRoot = LayoutInflater.from(activity).inflate(R.layout.activity_gallery_video_viewer, null, false);
        for (int id : new int[]{R.id.gallery_viewer_favorite, R.id.gallery_viewer_edit,
                R.id.gallery_viewer_share, R.id.gallery_viewer_delete, R.id.info_button})
            assertTrue(isInside(activityRoot.findViewById(id), activityRoot.findViewById(R.id.gallery_video_top_actions)));
        assertEquals(android.graphics.Color.rgb(252, 252, 255),
                ((android.graphics.drawable.ColorDrawable) activityRoot.getBackground()).getColor());
        for (int id : new int[]{R.id.gallery_viewer_favorite, R.id.gallery_viewer_edit,
                R.id.gallery_viewer_share, R.id.gallery_viewer_delete, R.id.info_button, R.id.back_button})
            assertEquals(android.graphics.Color.rgb(1, 1, 2), ((ImageButton) activityRoot.findViewById(id)).getImageTintList().getDefaultColor());
        android.graphics.drawable.GradientDrawable actionSurface =
                (android.graphics.drawable.GradientDrawable) activityRoot.findViewById(R.id.gallery_video_top_actions).getBackground();
        assertEquals(android.graphics.Color.rgb(238, 240, 245), actionSurface.getColor().getDefaultColor());
    }
    private boolean containsFilmstrip(View view) {
        if (view instanceof GalleryVideoFilmstrip) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (containsFilmstrip(group.getChildAt(i))) return true;
        }
        return false;
    }
    private boolean isInside(View child, View ancestor) {
        while (child != null) {
            if (child == ancestor) return true;
            child = child.getParent() instanceof View ? (View) child.getParent() : null;
        }
        return false;
    }
    @Test public void imageViewerOffersRealActionsAndKeepsOrdinaryPhotoMetadataClean() {
        View root = LayoutInflater.from(host()).inflate(R.layout.activity_image_viewer, null, false);
        for (int id : new int[]{R.id.gallery_viewer_favorite, R.id.gallery_viewer_edit,
                R.id.gallery_viewer_share, R.id.gallery_viewer_delete, R.id.imageViewerMore}) assertNotNull(root.findViewById(id));
        assertEquals(View.GONE, root.findViewById(R.id.imageViewerMeta).getVisibility());
    }
    @Test public void frameSamplesAreBoundedMonotonicAndUnknownDurationProducesNone() {
        assertEquals(0, GalleryVideoFilmstrip.sampleTimesMs(0).length);
        assertEquals(0, GalleryVideoFilmstrip.sampleTimesMs(-1).length);
        assertEquals(52500L, GalleryVideoFilmstrip.sampleTimesMs(60000)[7]);
        for (long duration : new long[]{1, 1000, 60000, Long.MAX_VALUE}) {
            long[] samples = GalleryVideoFilmstrip.sampleTimesMs(duration);
            assertEquals(8, samples.length); assertEquals(0, samples[0]);
            for (int i = 0; i < samples.length; i++) {
                assertTrue(samples[i] >= 0); assertTrue(samples[i] < duration);
                assertTrue(samples[i] * 1000L >= 0);
                if (i > 0) assertTrue(samples[i] >= samples[i - 1]);
            }
        }
    }
    @Test public void previewMuteToggleOnlyChangesPlayerVolumeAndSessionState() {
        VideoPlayerActivity activity = Robolectric.buildActivity(VideoPlayerActivity.class).get();
        activity.setTheme(R.style.Base_Theme_FadCam);
        FrameLayout root = new FrameLayout(activity);
        ImageButton mute = new ImageButton(activity); mute.setId(R.id.gallery_video_mute); root.addView(mute);
        activity.setContentView(root);
        ExoPlayer player = mock(ExoPlayer.class);
        ReflectionHelpers.setField(activity, "player", player);
        ReflectionHelpers.setField(activity, "galleryPreview", true);
        ReflectionHelpers.callInstanceMethod(activity, "applyMutedStateToPlayer",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        verify(player).setVolume(0f); assertFalse(mute.isSelected());
        ReflectionHelpers.callInstanceMethod(activity, "applyMutedStateToPlayer",
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
        verify(player).setVolume(1f); assertTrue(mute.isSelected());
        assertFalse(ReflectionHelpers.getField(activity, "galleryPreviewMuted"));
        verifyNoMoreInteractions(player);
    }
    @Test public void previewRotationChangesOnlyLayoutWithoutChangingPlayback() {
        VideoPlayerActivity activity = Robolectric.buildActivity(VideoPlayerActivity.class).get();
        activity.setTheme(R.style.Base_Theme_FadCam);
        FrameLayout root = new FrameLayout(activity);
        View timeline = new View(activity); timeline.setId(R.id.player_waveform_container);
        root.addView(timeline, new FrameLayout.LayoutParams(400, 100)); activity.setContentView(root);
        ExoPlayer player = mock(ExoPlayer.class);
        ReflectionHelpers.setField(activity, "player", player); ReflectionHelpers.setField(activity, "galleryPreview", true);
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
        activity.onConfigurationChanged(config);
        assertEquals(Math.round(40 * activity.getResources().getDisplayMetrics().density), timeline.getLayoutParams().height);
        verifyNoInteractions(player);
    }
    @Test public void filmstripCleanupIsSafeAfterActivityDestructionAndRepeatable() {
        ActivityController<FragmentActivity> controller = Robolectric.buildActivity(FragmentActivity.class).setup();
        FragmentActivity activity = controller.get(); activity.setTheme(R.style.Base_Theme_FadCam);
        GalleryVideoFilmstrip strip = new GalleryVideoFilmstrip(activity, null); activity.setContentView(strip);
        assertEquals(8, strip.getChildCount());
        controller.pause().stop().destroy(); assertTrue(activity.isDestroyed());
        strip.clearFrames(); strip.clearFrames();
    }
    @Test public void sharingExactContentUriCarriesReadGrantAndEditUsesActualExistingVideoEditor() {
        FragmentActivity activity = host(); Uri uri = Uri.parse("content://test/media/3");
        Intent share = GalleryViewerActions.shareIntent(activity, uri, "video/*");
        assertEquals(uri, share.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(uri, share.getClipData().getItemAt(0).getUri());
        assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Intent edit = GalleryViewerActions.editIntent(activity, uri, true);
        assertEquals(Intent.ACTION_EDIT, edit.getAction()); assertEquals("video/*", edit.getType());
        assertEquals(uri, edit.getData()); assertNull(edit.getComponent());
        assertEquals(uri, edit.getClipData().getItemAt(0).getUri());
    }
    @Test @Config(qualifiers = "w800dp-h280dp-land-mdpi")
    public void measuredCompactLandscapeRetainsInlinePillAndMediaNavigationInsteadOfMedia3MinimalModeHidingDock() {
        FragmentActivity activity = host();
        View root = LayoutInflater.from(activity).inflate(R.layout.activity_gallery_video_viewer, null, false);
        activity.setContentView(root);
        androidx.media3.ui.PlayerView playerView = root.findViewById(R.id.player_view);
        playerView.setControllerShowTimeoutMs(0); playerView.showController();
        // Finish the host window's pending traversal before imposing this compact viewport.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        int width = Math.round(800 * activity.getResources().getDisplayMetrics().density);
        int height = Math.round(280 * activity.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        assertEquals(width, root.getWidth()); assertEquals(height, root.getHeight());
        assertNull(root.findViewById(androidx.media3.ui.R.id.exo_bottom_bar));
        assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_video_bottom_dock).getVisibility());
        for (int id : new int[]{R.id.gallery_media_strip, androidx.media3.ui.R.id.exo_play_pause,
                R.id.gallery_video_mute}) {
            View control = root.findViewById(id);
            assertTrue(control.getWidth() > 0); assertTrue(control.getHeight() > 0);
            assertHierarchyVisible(control, root);
        }
        View pill = root.findViewById(androidx.media3.ui.R.id.exo_center_controls);
        View strip = root.findViewById(R.id.gallery_media_strip);
        int[] pillLocation = new int[2], stripLocation = new int[2], rootLocation = new int[2];
        pill.getLocationOnScreen(pillLocation); strip.getLocationOnScreen(stripLocation); root.getLocationOnScreen(rootLocation);
        assertEquals(width / 2f, pillLocation[0] - rootLocation[0] + pill.getWidth() / 2f, 1f);
        assertTrue(pillLocation[1] > height / 2f);
        assertTrue(stripLocation[1] >= pillLocation[1] + pill.getHeight());
        assertTrue(root.findViewById(R.id.gallery_video_bottom_dock).getHeight()
                <= Math.round(120 * activity.getResources().getDisplayMetrics().density) + 1);
        playerView.hideController();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));
        assertFalse(strip.isShown());
        playerView.showController();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(350));
        assertHierarchyVisible(strip, root);
    }
    private void assertHierarchyVisible(View child, View root) {
        while (child != null) {
            assertEquals(View.VISIBLE, child.getVisibility());
            if (child == root) return;
            child = child.getParent() instanceof View ? (View) child.getParent() : null;
        }
        fail("Control must remain attached to the measured player");
    }

    private ExoPlayer policyPlayer(androidx.media3.exoplayer.SeekParameters initial) {
        ExoPlayer player = mock(ExoPlayer.class);
        java.util.concurrent.atomic.AtomicReference<androidx.media3.exoplayer.SeekParameters> policy =
                new java.util.concurrent.atomic.AtomicReference<>(initial);
        when(player.getSeekParameters()).thenAnswer(call -> policy.get());
        doAnswer(call -> { policy.set(call.getArgument(0)); return null; }).when(player).setSeekParameters(any());
        return player;
    }
    private VideoPlayerActivity previewActivity(ExoPlayer player) {
        VideoPlayerActivity activity = Robolectric.buildActivity(VideoPlayerActivity.class).get();
        ReflectionHelpers.setField(activity, "player", player);
        ReflectionHelpers.setField(activity, "galleryPreview", true);
        return activity;
    }
    @Test public void exactSeekPolicyIsAcquiredOnceAndRestoresTheCapturedPolicyOnRelease() {
        ExoPlayer player = policyPlayer(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        VideoPlayerActivity activity = previewActivity(player);
        ReflectionHelpers.callInstanceMethod(activity, "acquireGallerySeekPolicy");
        ReflectionHelpers.callInstanceMethod(activity, "acquireGallerySeekPolicy");
        assertSame(androidx.media3.exoplayer.SeekParameters.EXACT, player.getSeekParameters());
        verify(player, times(1)).setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT);
        ReflectionHelpers.callInstanceMethod(activity, "releaseGallerySeekPolicy");
        assertSame(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC, player.getSeekParameters());
        ReflectionHelpers.callInstanceMethod(activity, "releaseGallerySeekPolicy");
        verify(player, times(1)).setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        assertNull(ReflectionHelpers.getField(activity, "gallerySeekPolicyOwner"));
    }
    @Test public void releasingPreviewDoesNotOverwriteAnExternallyChangedPolicyOrTouchReplacementPlayer() {
        ExoPlayer owner = policyPlayer(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        VideoPlayerActivity activity = previewActivity(owner);
        ReflectionHelpers.callInstanceMethod(activity, "acquireGallerySeekPolicy");
        owner.setSeekParameters(androidx.media3.exoplayer.SeekParameters.PREVIOUS_SYNC);
        ExoPlayer replacement = mock(ExoPlayer.class);
        ReflectionHelpers.setField(activity, "player", replacement);
        ReflectionHelpers.callInstanceMethod(activity, "releaseGallerySeekPolicy");
        assertSame(androidx.media3.exoplayer.SeekParameters.PREVIOUS_SYNC, owner.getSeekParameters());
        verify(owner, never()).setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        verifyNoInteractions(replacement);
    }
    @Test public void replacementOwnershipRestoresEachPlayersOwnPriorPolicyAndStandaloneNeverAcquires() {
        ExoPlayer first = policyPlayer(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
        ExoPlayer second = policyPlayer(androidx.media3.exoplayer.SeekParameters.NEXT_SYNC);
        VideoPlayerActivity activity = previewActivity(first);
        ReflectionHelpers.callInstanceMethod(activity, "acquireGallerySeekPolicy");
        ReflectionHelpers.setField(activity, "player", second);
        ReflectionHelpers.callInstanceMethod(activity, "acquireGallerySeekPolicy");
        assertSame(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC, first.getSeekParameters());
        assertSame(androidx.media3.exoplayer.SeekParameters.EXACT, second.getSeekParameters());
        ReflectionHelpers.callInstanceMethod(activity, "releaseGallerySeekPolicy");
        assertSame(androidx.media3.exoplayer.SeekParameters.NEXT_SYNC, second.getSeekParameters());
        ExoPlayer standalone = mock(ExoPlayer.class);
        ReflectionHelpers.setField(activity, "galleryPreview", false);
        ReflectionHelpers.setField(activity, "player", standalone);
        ReflectionHelpers.callInstanceMethod(activity, "acquireGallerySeekPolicy");
        ReflectionHelpers.callInstanceMethod(activity, "releaseGallerySeekPolicy");
        verifyNoInteractions(standalone);
    }
    @Test public void actualReleaseCallbackCommitsLatestSliderValueEvenInsideChangeThrottleWhilePaused() {
        ExoPlayer player = mock(ExoPlayer.class); when(player.getDuration()).thenReturn(10000L);
        VideoPlayerActivity activity = previewActivity(player);
        com.google.android.material.slider.Slider slider = new com.google.android.material.slider.Slider(host());
        slider.setValueFrom(0); slider.setValueTo(10000); slider.setValue(2000);
        com.google.android.material.slider.Slider.OnChangeListener changes = ReflectionHelpers.getField(activity, "timelineChangeListener");
        com.google.android.material.slider.Slider.OnSliderTouchListener touch = ReflectionHelpers.getField(activity, "timelineTouchListener");
        ReflectionHelpers.setField(changes, "lastSeekUptime", android.os.SystemClock.uptimeMillis() - 100L);
        touch.onStartTrackingTouch(slider);
        changes.onValueChange(slider, 2000, true);
        changes.onValueChange(slider, 5000, true); // Inside 50ms: the intermediate change is deliberately throttled.
        verify(player).seekTo(2000L); verify(player, never()).seekTo(5000L);
        slider.setValue(5000);
        touch.onStopTrackingTouch(slider);
        verify(player).seekTo(5000L);
        verify(player, never()).play(); verify(player, never()).pause();
        verify(player, never()).setPlayWhenReady(anyBoolean());
        assertFalse(ReflectionHelpers.getField(activity, "isTimebarScrubbing"));
        assertEquals(5000L, (long) ReflectionHelpers.getField(activity, "lastSeekPositionMs"));
    }

    @Test public void actualVideoMoreKeepsFavoriteEditorSharingDeletionInfoAndGalleryAvailable() {
        ExoPlayer player = mock(ExoPlayer.class);
        VideoPlayerActivity activity = previewActivity(player);
        Uri uri = Uri.parse("content://test/menu/video");
        ReflectionHelpers.setField(activity, "currentVideoUri", uri);
        View anchor = new View(host());
        androidx.appcompat.widget.PopupMenu popup = activity.createVideoMore(anchor);
        android.content.Context popupContext = ReflectionHelpers.getField(popup, "mContext");
        android.content.res.TypedArray text = popupContext.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        assertEquals(android.graphics.Color.rgb(1, 1, 2), text.getColor(0, 0)); text.recycle();
        android.content.res.TypedArray surface = popupContext.obtainStyledAttributes(null,
                new int[]{android.R.attr.popupBackground}, androidx.appcompat.R.attr.popupMenuStyle, 0);
        android.graphics.drawable.GradientDrawable background = (android.graphics.drawable.GradientDrawable) surface.getDrawable(0);
        assertEquals(0xF2FCFCFF, background.getColor().getDefaultColor()); surface.recycle();
        for (int id : new int[]{VideoPlayerActivity.MENU_VIDEO_GALLERY, VideoPlayerActivity.MENU_VIDEO_INFO,
                VideoPlayerActivity.MENU_VIDEO_FAVORITE, VideoPlayerActivity.MENU_VIDEO_EDIT,
                VideoPlayerActivity.MENU_VIDEO_SHARE, VideoPlayerActivity.MENU_VIDEO_DELETE,
                VideoPlayerActivity.MENU_VIDEO_SETTINGS}) {
            assertNotNull(popup.getMenu().findItem(id)); assertTrue(popup.getMenu().findItem(id).isEnabled());
        }
        boolean before = GalleryFavorites.isFavorite(activity, uri);
        assertTrue(popup.getMenu().performIdentifierAction(VideoPlayerActivity.MENU_VIDEO_FAVORITE, 0));
        assertEquals(!before, GalleryFavorites.isFavorite(activity, uri));
        assertTrue(popup.getMenu().performIdentifierAction(VideoPlayerActivity.MENU_VIDEO_FAVORITE, 0));
        assertEquals(before, GalleryFavorites.isFavorite(activity, uri));
        verifyNoInteractions(player);
    }

    @Test public void inlineFrameDragNeverRestoresStandaloneTimedHide() {
        ExoPlayer player=mock(ExoPlayer.class);when(player.getDuration()).thenReturn(2100000L);
        VideoPlayerActivity activity=previewActivity(player);
        androidx.media3.ui.PlayerView view=mock(androidx.media3.ui.PlayerView.class);
        ReflectionHelpers.setField(activity,"playerView",view);
        ReflectionHelpers.setField(activity,"controllerAutoHideMs",3000);
        com.google.android.material.slider.Slider slider=new com.google.android.material.slider.Slider(host());
        slider.setValueTo(2100000);slider.setValue(0);
        com.google.android.material.slider.Slider.OnSliderTouchListener touch=ReflectionHelpers.getField(activity,"timelineTouchListener");
        touch.onStartTrackingTouch(slider);touch.onStopTrackingTouch(slider);
        verify(player).seekTo(0L);verify(view,never()).setControllerShowTimeoutMs(3000);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5));
        verify(view,never()).hideController();
    }

    @Test public void frameCursorMappingRewindsOnRightSwipeAndAdvancesOnLeftSwipeWithEndpointClamps() {
        assertEquals(9000L, GalleryMediaStrip.seekPosition(16000, 400, 130, 480));
        assertEquals(5000L, GalleryMediaStrip.seekPosition(16000, 400, 250, 480));
        assertEquals(13000L, GalleryMediaStrip.seekPosition(16000, 400, 10, 480));
        assertEquals(0L, GalleryMediaStrip.seekPosition(16000, 400, 500, 480));
        assertEquals(16000L, GalleryMediaStrip.seekPosition(16000, 400, -200, 480));
        assertEquals(0L, GalleryMediaStrip.seekPosition(-1, 400, 0, 480));
        assertEquals(0L, GalleryMediaStrip.seekPosition(16000, 400, 0, 0));
    }

    @Test public void indexedNavigationPreservesPhotoVideoOrderAndRejectsIncompleteOrRemoteRows() {
        com.fadcam.data.entity.VideoIndexEntity video = indexed("content://test/video", "VIDEO", 15);
        com.fadcam.data.entity.VideoIndexEntity photo = indexed("file:///test/photo.jpg", "IMAGE", 10);
        com.fadcam.data.entity.VideoIndexEntity pending = indexed("content://test/pending", "VIDEO", 15); pending.isTemporary = true;
        com.fadcam.data.entity.VideoIndexEntity remote = indexed("https://example.invalid/video", "VIDEO", 15);
        com.fadcam.data.entity.VideoIndexEntity empty = indexed("content://test/empty", "VIDEO", 0);
        java.util.List<VideoItem> result = GalleryMediaStrip.fromIndex(Arrays.asList(video, photo, pending, remote, empty));
        assertEquals(2, result.size()); assertEquals(Uri.parse(video.uriString), result.get(0).uri);
        assertEquals(VideoItem.MediaType.VIDEO, result.get(0).mediaType);
        assertEquals(Uri.parse(photo.uriString), result.get(1).uri);
        assertEquals(VideoItem.MediaType.IMAGE, result.get(1).mediaType);
    }
    private com.fadcam.data.entity.VideoIndexEntity indexed(String uri, String type, long size) {
        com.fadcam.data.entity.VideoIndexEntity item = new com.fadcam.data.entity.VideoIndexEntity();
        item.uriString = uri; item.mediaType = type; item.fileSize = size; item.category = "CAMERA";
        return item;
    }

    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    public void actualMediaHolderTapExpandsSelectedVideoAndOtherHolderNavigatesWithoutFabricatingFrames() {
        FragmentActivity activity = host();
        GalleryMediaStrip strip = new GalleryMediaStrip(activity, null);
        Uri current = Uri.parse("content://test/current");
        VideoItem other = new VideoItem(Uri.parse("content://test/other"), "", 1, 1);
        GalleryMediaStrip.Listener listener = mock(GalleryMediaStrip.Listener.class);
        doAnswer(call -> { strip.setExpanded(true, 16000, 9000); return null; }).when(listener).onCurrentVideoTap();
        strip.bindMedia(Arrays.asList(new VideoItem(current, "", 1, 2), other), current, listener);
        androidx.recyclerview.widget.RecyclerView.Adapter adapter = strip.getAdapter();
        androidx.recyclerview.widget.RecyclerView.ViewHolder selectedHolder = adapter.createViewHolder(new FrameLayout(activity), 0);
        adapter.bindViewHolder(selectedHolder, 0); selectedHolder.itemView.performClick();
        assertTrue(strip.isExpanded()); verify(listener).onCurrentVideoTap();
        adapter.bindViewHolder(selectedHolder, 0);
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        assertEquals(Math.round(1496 * Math.min(metrics.widthPixels, metrics.heightPixels) / 1440f), selectedHolder.itemView.getLayoutParams().width);
        assertTrue(containsFilmstrip(selectedHolder.itemView));
        androidx.recyclerview.widget.RecyclerView.ViewHolder otherHolder = adapter.createViewHolder(new FrameLayout(activity), 0);
        adapter.bindViewHolder(otherHolder, 1); otherHolder.itemView.performClick();
        verify(listener).onMediaSelected(other);
        adapter.onViewRecycled(selectedHolder); adapter.onViewRecycled(otherHolder);
        strip.close(); assertNull(strip.getAdapter());
    }

    @Test public void inlineToolbarBackOpensGalleryGridWhileStandaloneToolbarBackFinishes() {
        VideoPlayerActivity inline = previewActivity(mock(ExoPlayer.class));
        ImageButton button = new ImageButton(host());
        ReflectionHelpers.setField(inline, "backButton", button);
        ReflectionHelpers.callInstanceMethod(inline, "setupBackButton"); button.performClick();
        Intent target = org.robolectric.Shadows.shadowOf(inline).getNextStartedActivity();
        assertEquals(com.fadcam.MainActivity.class.getName(), target.getComponent().getClassName());
        assertTrue(target.getBooleanExtra(GalleryViewerActions.EXTRA_OPEN_GALLERY, false));
        assertTrue((target.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        assertTrue((target.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
        VideoPlayerActivity standalone = previewActivity(mock(ExoPlayer.class));
        ReflectionHelpers.setField(standalone, "galleryPreview", false);
        ImageButton standaloneBack = new ImageButton(host());
        ReflectionHelpers.setField(standalone, "backButton", standaloneBack);
        ReflectionHelpers.callInstanceMethod(standalone, "setupBackButton"); standaloneBack.performClick();
        assertTrue(standalone.isFinishing());
        assertNull(org.robolectric.Shadows.shadowOf(standalone).getNextStartedActivity());
    }

    @Test public void queuedPlaybackRecenterRechecksDragAndClosedStateAtExecution() {
        GalleryMediaStrip strip = new GalleryMediaStrip(host(), null);
        Uri current = Uri.parse("content://test/current");
        strip.bindMedia(Arrays.asList(new VideoItem(current, "", 1, 1)), current, mock(GalleryMediaStrip.Listener.class));
        androidx.recyclerview.widget.LinearLayoutManager layout = mock(androidx.recyclerview.widget.LinearLayoutManager.class);
        ReflectionHelpers.setField(strip, "layout", layout);
        Runnable pendingPlayback = ReflectionHelpers.getField(strip, "recenter");
        ReflectionHelpers.setField(strip, "userScrubbing", true);
        pendingPlayback.run(); verifyNoInteractions(layout);
        ReflectionHelpers.setField(strip, "userScrubbing", false);
        pendingPlayback.run(); verify(layout).scrollToPositionWithOffset(eq(0), anyInt());
        clearInvocations(layout);
        strip.close(); pendingPlayback.run(); verifyNoInteractions(layout);
    }

    @Test @Config(qualifiers = "xxxhdpi") @SuppressWarnings({"rawtypes", "unchecked"})
    public void referencePixelGeometryUsesShortEdgeRatherThanInflatingWithDeviceDensity() {
        FragmentActivity activity = host();
        View root = LayoutInflater.from(activity).inflate(R.layout.activity_gallery_video_viewer, null, false);
        activity.setContentView(root);
        ((androidx.media3.ui.PlayerView) root.findViewById(R.id.player_view)).showController();
        // Screen locations require attachment; finish the host traversal before the exact reference layout.
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        root.measure(View.MeasureSpec.makeMeasureSpec(3120, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 3120, 1440);
        assertEquals(3120, root.getWidth()); assertEquals(1440, root.getHeight());
        View pill = root.findViewById(androidx.media3.ui.R.id.exo_center_controls);
        assertTrue(pill.getWidth() >= 492); assertEquals(113, pill.getHeight());
        int[] pillLocation = new int[2], rootLocation = new int[2];
        pill.getLocationOnScreen(pillLocation); root.getLocationOnScreen(rootLocation);
        assertEquals(1560, pillLocation[0] - rootLocation[0] + pill.getWidth()/2); assertEquals(1084, pillLocation[1] - rootLocation[1]);
        assertEquals(184, root.findViewById(R.id.gallery_media_strip_frame).getHeight());
        assertEquals(155, root.findViewById(R.id.gallery_viewer_share).getWidth());
        assertEquals(180, root.findViewById(R.id.gallery_viewer_share).getHeight());
        // The stored S24 reference has128px safe space at its left edge.
        // Runtime insets must supply this, rather than hardcoding it for every window.
        ((GalleryInlineLayout)root).applySafeInsets(new androidx.core.view.WindowInsetsCompat.Builder()
                .setInsetsIgnoringVisibility(androidx.core.view.WindowInsetsCompat.Type.displayCutout(),androidx.core.graphics.Insets.of(128,0,0,0)).build());
        root.measure(View.MeasureSpec.makeMeasureSpec(3120,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));
        root.layout(0,0,3120,1440);
        View back = root.findViewById(R.id.back_button), actions = root.findViewById(R.id.gallery_video_top_actions);
        int[] backLocation = new int[2], actionLocation = new int[2];
        back.getLocationOnScreen(backLocation); actions.getLocationOnScreen(actionLocation);
        assertEquals(173, backLocation[0] - rootLocation[0]);
        assertEquals(15, backLocation[1] - rootLocation[1]);
        assertEquals(2300, actionLocation[0] - rootLocation[0]);
        assertEquals(15, actionLocation[1] - rootLocation[1]);
        assertEquals(45, root.getWidth() - (actionLocation[0] - rootLocation[0] + actions.getWidth()));
        GalleryMediaStrip strip = root.findViewById(R.id.gallery_media_strip);
        Uri current = Uri.parse("content://test/reference");
        strip.bindMedia(Arrays.asList(new VideoItem(current, "", 1, 1),
                new VideoItem(Uri.parse("content://test/neighbor"), "", 1, 0)), current, mock(GalleryMediaStrip.Listener.class));
        androidx.recyclerview.widget.RecyclerView.Adapter adapter = strip.getAdapter();
        androidx.recyclerview.widget.RecyclerView.ViewHolder selected = adapter.createViewHolder(new FrameLayout(activity), 0);
        adapter.bindViewHolder(selected, 0);
        assertEquals(236, selected.itemView.getLayoutParams().width);
        assertEquals(165, ((android.view.ViewGroup) selected.itemView).getChildAt(0).getLayoutParams().height);
        androidx.recyclerview.widget.RecyclerView.ViewHolder neighbor = adapter.createViewHolder(new FrameLayout(activity), 0);
        adapter.bindViewHolder(neighbor, 1);
        assertEquals(116, neighbor.itemView.getLayoutParams().width);
        assertEquals(135, ((android.view.ViewGroup) neighbor.itemView).getChildAt(0).getLayoutParams().height);
        adapter.onViewRecycled(selected); adapter.onViewRecycled(neighbor); strip.close();
    }

}
