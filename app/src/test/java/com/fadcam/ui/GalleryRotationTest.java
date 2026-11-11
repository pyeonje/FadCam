package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ImageView;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class GalleryRotationTest {
    @Test public void landscapeUsesExtraColumnsAndReturnsToPreferredPortraitDensity() {
        assertEquals(2, GalleryLayoutPolicy.columns(2, 400, 400));
        assertEquals(4, GalleryLayoutPolicy.columns(2, 800, 400));
        assertEquals(6, GalleryLayoutPolicy.columns(3, 800, 400));
        assertEquals(3, GalleryLayoutPolicy.columns(3, 400, 400));
    }

    @Test public void explicitListPreferenceAndMaximumDensityRemainBounded() {
        assertEquals(1, GalleryLayoutPolicy.columns(1, 1000, 400));
        assertEquals(8, GalleryLayoutPolicy.columns(5, 2000, 400));
        assertEquals(3, GalleryLayoutPolicy.columns(3, 0, 0));
    }

    @Test public void galleryReflowPreservesAnchorOffsetAndUriSelectionWithoutReplacingAdapter() {
        RecordsFragment fragment = spy(new RecordsFragment());
        Resources resources = mock(Resources.class);
        Configuration configuration = new Configuration();
        configuration.screenWidthDp = 800;
        configuration.screenHeightDp = 400;
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.density = 1f;
        when(resources.getConfiguration()).thenReturn(configuration);
        when(resources.getDisplayMetrics()).thenReturn(metrics);
        doReturn(resources).when(fragment).getResources();
        RecyclerView recycler = mock(RecyclerView.class);
        GridLayoutManager grid = mock(GridLayoutManager.class);
        RecordsAdapter adapter = mock(RecordsAdapter.class);
        View anchor = new View(RuntimeEnvironment.getApplication());
        when(recycler.getLayoutManager()).thenReturn(grid);
        when(recycler.getWidth()).thenReturn(800);
        when(grid.getSpanCount()).thenReturn(2);
        when(grid.findFirstVisibleItemPosition()).thenReturn(17);
        when(grid.findViewByPosition(17)).thenReturn(anchor);
        when(grid.getDecoratedTop(anchor)).thenReturn(-34);
        List<Uri> selection = new ArrayList<>();
        selection.add(Uri.parse("content://test/media/17"));
        ReflectionHelpers.setField(fragment, "recyclerView", recycler);
        ReflectionHelpers.setField(fragment, "recordsAdapter", adapter);
        ReflectionHelpers.setField(fragment, "selectedUris", selection);
        ReflectionHelpers.setField(fragment, "isInSelectionMode", true);
        ReflectionHelpers.callInstanceMethod(fragment, "updateAdaptiveGrid");
        verify(grid).setSpanCount(4);
        verify(grid).scrollToPositionWithOffset(17, -34);
        verify(adapter).setGridSpan(4);
        verify(recycler, never()).setAdapter(any());
        assertSame(selection, ReflectionHelpers.getField(fragment, "selectedUris"));
        assertTrue(ReflectionHelpers.getField(fragment, "isInSelectionMode"));
    }

    @Test public void rotationUsesAnchorCapturedBeforeIntermediateOldSpanLayoutOnlyOnce() {
        RecordsFragment fragment = spy(new RecordsFragment());
        Resources resources = mock(Resources.class);
        Configuration portrait = new Configuration();
        portrait.screenWidthDp = 411;
        portrait.screenHeightDp = 914;
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.density = 2.625f;
        when(resources.getConfiguration()).thenReturn(portrait);
        when(resources.getDisplayMetrics()).thenReturn(metrics);
        doReturn(resources).when(fragment).getResources();
        RecyclerView recycler = mock(RecyclerView.class);
        GridLayoutManager grid = mock(GridLayoutManager.class);
        RecordsAdapter adapter = mock(RecordsAdapter.class);
        View oldFirst = new View(RuntimeEnvironment.getApplication());
        View shiftedFirst = new View(RuntimeEnvironment.getApplication());
        when(recycler.getLayoutManager()).thenReturn(grid);
        when(recycler.getWidth()).thenReturn(2400);
        when(grid.getSpanCount()).thenReturn(4);
        when(grid.findFirstVisibleItemPosition()).thenReturn(13);
        when(grid.findViewByPosition(13)).thenReturn(oldFirst);
        when(grid.getDecoratedTop(oldFirst)).thenReturn(-170);
        ReflectionHelpers.setField(fragment, "recyclerView", recycler);
        ReflectionHelpers.setField(fragment, "recordsAdapter", adapter);
        fragment.onConfigurationChanged(portrait);
        // A posted check before the window has resized must retain the snapshot.
        ReflectionHelpers.callInstanceMethod(fragment, "updateAdaptiveGrid");
        assertEquals(13, (int) ReflectionHelpers.getField(fragment, "pendingGridAnchorPosition"));
        // Model the observed old4-column layout at the new portrait width:
        // it has already moved firstVisible13 to9 before the span update runs.
        when(recycler.getWidth()).thenReturn(1080);
        when(grid.findFirstVisibleItemPosition()).thenReturn(9);
        when(grid.findViewByPosition(9)).thenReturn(shiftedFirst);
        when(grid.getDecoratedTop(shiftedFirst)).thenReturn(-52);
        ReflectionHelpers.callInstanceMethod(fragment, "updateAdaptiveGrid");
        verify(grid).scrollToPositionWithOffset(13, -170);
        verify(grid, never()).scrollToPositionWithOffset(9, -52);
        assertEquals(RecyclerView.NO_POSITION,
                (int) ReflectionHelpers.getField(fragment, "pendingGridAnchorPosition"));
        // A later ordinary width change uses its own current anchor, not this snapshot.
        when(recycler.getWidth()).thenReturn(2400);
        when(grid.getSpanCount()).thenReturn(2);
        when(grid.findFirstVisibleItemPosition()).thenReturn(7);
        when(grid.findViewByPosition(7)).thenReturn(shiftedFirst);
        when(grid.getDecoratedTop(shiftedFirst)).thenReturn(-21);
        ReflectionHelpers.callInstanceMethod(fragment, "updateAdaptiveGrid");
        verify(grid).scrollToPositionWithOffset(7, -21);
        verify(grid, times(1)).scrollToPositionWithOffset(13, -170);
    }

    @Test public void configurationWithNoVisibleAnchorDiscardsPreviousSnapshot() {
        RecordsFragment fragment = new RecordsFragment();
        RecyclerView recycler = mock(RecyclerView.class);
        GridLayoutManager grid = mock(GridLayoutManager.class);
        when(recycler.getLayoutManager()).thenReturn(grid);
        when(grid.getSpanCount()).thenReturn(4);
        when(grid.findFirstVisibleItemPosition()).thenReturn(RecyclerView.NO_POSITION);
        ReflectionHelpers.setField(fragment, "recyclerView", recycler);
        ReflectionHelpers.setField(fragment, "pendingGridAnchorPosition", 13);
        Configuration portrait = new Configuration();
        portrait.screenWidthDp = 411;
        portrait.screenHeightDp = 914;
        fragment.onConfigurationChanged(portrait);
        assertEquals(RecyclerView.NO_POSITION,
                (int) ReflectionHelpers.getField(fragment, "pendingGridAnchorPosition"));
    }

    @Test public void destroyingGalleryViewDiscardsPendingAnchorAndPostedGridWork() {
        RecordsFragment fragment = new RecordsFragment();
        RecyclerView recycler = mock(RecyclerView.class);
        ReflectionHelpers.setField(fragment, "recyclerView", recycler);
        ReflectionHelpers.setField(fragment, "pendingGridAnchorPosition", 13);
        fragment.onDestroyView();
        assertEquals(RecyclerView.NO_POSITION,
                (int) ReflectionHelpers.getField(fragment, "pendingGridAnchorPosition"));
        verify(recycler).removeCallbacks(any(Runnable.class));
    }

    private RecordsFragment selectedGallery(RecordsAdapter adapter, TextView title) {
        RecordsFragment fragment = spy(new RecordsFragment());
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        doReturn(true).when(fragment).isAdded();
        doReturn(host).when(fragment).getActivity();
        doReturn(host.getResources()).when(fragment).getResources();
        ReflectionHelpers.setField(fragment, "titleText", title);
        ReflectionHelpers.setField(fragment, "originalToolbarTitle", "Recordings");
        ReflectionHelpers.setField(fragment, "recordsAdapter", adapter);
        ReflectionHelpers.setField(fragment, "closeButton", new ImageView(host));
        ArrayList<Uri> selection = new ArrayList<>();
        selection.add(Uri.parse("content://test/media/14"));
        ReflectionHelpers.setField(fragment, "selectedUris", selection);
        ReflectionHelpers.setField(fragment, "isInSelectionMode", true);
        return fragment;
    }

    @Test public void recreatedGalleryAdapterRestoresRetainedSelectionAndCountTitle() {
        RecordsAdapter adapter = mock(RecordsAdapter.class);
        TextView title = new TextView(RuntimeEnvironment.getApplication());
        title.setText("Recordings");
        RecordsFragment fragment = selectedGallery(adapter, title);
        ReflectionHelpers.callInstanceMethod(fragment, "restoreSelectionUi");
        List<Uri> selection = ReflectionHelpers.getField(fragment, "selectedUris");
        verify(adapter).setSelectionModeActive(true, selection);
        assertEquals(Uri.parse("content://test/media/14"), selection.get(0));
        assertTrue(title.getText().toString().startsWith("1 selected"));
    }

    @Test public void configurationChangeKeepsSelectionTitleInsteadOfNormalResumeTitle() {
        RecordsAdapter adapter = mock(RecordsAdapter.class);
        TextView title = new TextView(RuntimeEnvironment.getApplication());
        RecordsFragment fragment = selectedGallery(adapter, title);
        Configuration config = new Configuration();
        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
        fragment.onConfigurationChanged(config);
        assertTrue(title.getText().toString().startsWith("1 selected"));
        List<Uri> selection = ReflectionHelpers.getField(fragment, "selectedUris");
        assertEquals(1, selection.size());
        verify(adapter).setSelectionModeActive(true, selection);
    }

    private ZoomableImageView image() {
        ZoomableImageView view = new ZoomableImageView(RuntimeEnvironment.getApplication());
        Bitmap bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888);
        view.setImageDrawable(new BitmapDrawable(view.getResources(), bitmap));
        view.layout(0, 0, 400, 800);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return view;
    }

    @Test public void photoRotationPreservesZoomAndSourceCenterInBothDirections() {
        ZoomableImageView view = image();
        view.restoreViewport(new float[] {3f, .65f, .5f});
        float[] before = view.captureViewport();
        view.layout(0, 0, 800, 400);
        assertArrayEquals(before, view.captureViewport(), .0001f);
        view.layout(0, 0, 400, 800);
        assertArrayEquals(before, view.captureViewport(), .0001f);
    }

    @Test public void photoStateRestoresAfterDelayedDrawableAndLayout() {
        ZoomableImageView restored = new ZoomableImageView(RuntimeEnvironment.getApplication());
        restored.restoreViewport(new float[] {3f, .65f, .5f});
        restored.setImageDrawable(new BitmapDrawable(restored.getResources(),
                Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)));
        restored.layout(0, 0, 800, 400);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertArrayEquals(new float[] {3f, .65f, .5f}, restored.captureViewport(), .0001f);
    }

    @Test public void invalidPhotoStateDoesNotPoisonMatrixAndZoomIsBounded() {
        ZoomableImageView view = image();
        view.restoreViewport(new float[] {Float.NaN, .5f, .5f});
        assertEquals(1f, view.captureViewport()[0], .0001f);
        view.restoreViewport(new float[] {100f, .5f, .5f});
        assertEquals(5f, view.captureViewport()[0], .0001f);
    }

    @Test public void videoRotationLeavesPlayingOrPausedPlayerAndPositionUntouched() {
        // Use the real configuration handler without opening media or starting services.
        VideoPlayerActivity activity = Robolectric.buildActivity(VideoPlayerActivity.class).get();
        activity.setTheme(R.style.Base_Theme_FadCam);
        FrameLayout root = new FrameLayout(activity);
        View waveform = new View(activity);
        waveform.setId(R.id.player_waveform_container);
        root.addView(waveform, new FrameLayout.LayoutParams(300, 56));
        activity.setContentView(root);
        ExoPlayer player = mock(ExoPlayer.class);
        ReflectionHelpers.setField(activity, "player", player);
        for (boolean playing : new boolean[] {true, false}) {
            when(player.getPlayWhenReady()).thenReturn(playing);
            when(player.getCurrentPosition()).thenReturn(12345L);
            Configuration config = new Configuration(activity.getResources().getConfiguration());
            config.orientation = Configuration.ORIENTATION_LANDSCAPE;
            activity.onConfigurationChanged(config);
            assertSame(player, ReflectionHelpers.getField(activity, "player"));
        }
        verifyNoInteractions(player);
    }

    @Test public void recreatedVideoRestoresBothPausedAndPlayingIntentWithoutForcingAutoplay() {
        VideoPlayerActivity activity = Robolectric.buildActivity(VideoPlayerActivity.class).get();
        ExoPlayer player = mock(ExoPlayer.class);
        ReflectionHelpers.setField(activity, "player", player);
        Uri uri = Uri.parse("content://test/video/3");
        for (boolean playing : new boolean[] {false, true}) {
            Bundle state = new Bundle();
            state.putString("viewer_uri", uri.toString());
            state.putLong("viewer_position", 12345L);
            state.putBoolean("viewer_play_when_ready", playing);
            ReflectionHelpers.setField(activity, "restoredViewerState", state);
            clearInvocations(player);
            assertTrue(activity.restorePlaybackIntent(uri));
            verify(player).seekTo(12345L);
            verify(player).setPlayWhenReady(playing);
            verify(player, never()).play();
            assertFalse(activity.restorePlaybackIntent(uri));
        }
    }

    @Test public void recreatedVideoNeverUsesAnotherVideosSavedState() {
        VideoPlayerActivity activity = Robolectric.buildActivity(VideoPlayerActivity.class).get();
        ExoPlayer player = mock(ExoPlayer.class);
        ReflectionHelpers.setField(activity, "player", player);
        Bundle state = new Bundle();
        state.putString("viewer_uri", "content://test/video/old");
        state.putBoolean("viewer_play_when_ready", false);
        ReflectionHelpers.setField(activity, "restoredViewerState", state);
        assertFalse(activity.restorePlaybackIntent(Uri.parse("content://test/video/new")));
        verifyNoInteractions(player);
    }
}
