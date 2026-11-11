package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.FragmentActivity;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Product view contracts from the official Gallery reference, not a claim of pixel identity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class SamsungGalleryPresentationTest {
    private FragmentActivity host() {
        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.Base_Theme_FadCam);
        return host;
    }
    private RecordsAdapter.RecordViewHolder tile(FragmentActivity host) {
        return new RecordsAdapter.RecordViewHolder(LayoutInflater.from(host)
                .inflate(R.layout.item_record, new FrameLayout(host), false));
    }
    @Test public void pictureGridUsesSquareBorderlessTilesWhileExplicitListKeepsFileDetails() {
        FragmentActivity host = host();
        RecordsAdapter.RecordViewHolder holder = tile(host);
        RecordsAdapter adapter = mock(RecordsAdapter.class, CALLS_REAL_METHODS);
        for (int columns : new int[]{2, 4, 8}) {
            ReflectionHelpers.setField(adapter, "currentGridSpan", columns);
            ReflectionHelpers.callInstanceMethod(adapter, "applyGridSizing",
                    ReflectionHelpers.ClassParameter.from(RecordsAdapter.RecordViewHolder.class, holder));
            assertEquals("H,1:1", ((ConstraintLayout.LayoutParams) holder.thumbnailContainer.getLayoutParams()).dimensionRatio);
            assertEquals(View.GONE, holder.recordContentContainer.getVisibility());
            assertEquals(View.GONE, holder.textViewSerialNumber.getVisibility());
            assertEquals(0f, ((com.google.android.material.card.MaterialCardView) holder.itemView).getRadius(), 0f);
        }
        ReflectionHelpers.setField(adapter, "currentGridSpan", 1);
        ReflectionHelpers.callInstanceMethod(adapter, "applyGridSizing",
                ReflectionHelpers.ClassParameter.from(RecordsAdapter.RecordViewHolder.class, holder));
        assertEquals("H,16:9", ((ConstraintLayout.LayoutParams) holder.thumbnailContainer.getLayoutParams()).dimensionRatio);
        assertEquals(View.VISIBLE, holder.recordContentContainer.getVisibility());
        assertSame(holder.thumbnailContainer, holder.textViewFileTime.getParent());
    }

    @Test public void deferredColdProgressCacheStillFinishesRealTileBindingAndRecycledClickTarget() {
        FragmentActivity host=host();
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        when(settings.getOpenedVideoUris()).thenReturn(java.util.Collections.emptySet());
        java.util.concurrent.ExecutorService executor=mock(java.util.concurrent.ExecutorService.class);
        RecordsAdapter.OnVideoClickListener click=mock(RecordsAdapter.OnVideoClickListener.class);
        VideoItem one=new VideoItem(Uri.parse("content://fixture/cold-grid/1"),"one.mp4",32,1,
                VideoItem.Category.CAMERA,VideoItem.MediaType.VIDEO);
        VideoItem two=new VideoItem(Uri.parse("content://fixture/cold-grid/2"),"two.mp4",32,2,
                VideoItem.Category.CAMERA,VideoItem.MediaType.VIDEO);
        com.fadcam.data.VideoIndexRepository repository=mock(com.fadcam.data.VideoIndexRepository.class);
        try(org.mockito.MockedStatic<com.fadcam.data.VideoIndexRepository> index=
                    mockStatic(com.fadcam.data.VideoIndexRepository.class)) {
            index.when(()->com.fadcam.data.VideoIndexRepository.getInstance(host)).thenReturn(repository);
            RecordsAdapter adapter=new RecordsAdapter(host,Arrays.asList(one,two),executor,settings,click,null,
                    mock(RecordActionListener.class));
            adapter.setGridSpan(4);adapter.setDeferHeavyMetadataWork(true);
            // Neither duration nor saved progress exists; no actual thumbnail I/O
            // is needed to exercise the entire native binding/control path.
            ((java.util.Map<?,?>)ReflectionHelpers.getField(adapter,"durationCache")).clear();
            ((java.util.Map<?,?>)ReflectionHelpers.getField(adapter,"savedPositionCache")).clear();
            RecordsAdapter.RecordViewHolder holder=tile(host);
            holder.itemView.setVisibility(View.INVISIBLE);
            for(VideoItem item:new VideoItem[]{one,two}) {
                adapter.onBindViewHolder(holder,adapter.findPositionByUri(item.uri));
                assertEquals("H,1:1",((ConstraintLayout.LayoutParams)holder.thumbnailContainer.getLayoutParams()).dimensionRatio);
                assertEquals(View.GONE,holder.recordContentContainer.getVisibility());
                assertEquals(View.GONE,holder.textViewSerialNumber.getVisibility());
                assertEquals(View.GONE,holder.iconCheckContainer.getVisibility());
                assertTrue(holder.itemView.isEnabled());
                assertTrue(holder.itemView.performClick());
                verify(click).onVideoClick(item);
            }
            verify(executor,never()).execute(any(Runnable.class));
            adapter.onDetachedFromRecyclerView(new androidx.recyclerview.widget.RecyclerView(host));
        }
    }
    @Test public void selectionShowsSmallTopStartCirclesOnSelectedAndUnselectedTiles() {
        FragmentActivity host = host();
        RecordsAdapter.RecordViewHolder holder = tile(host);
        RecordsAdapter adapter = mock(RecordsAdapter.class, CALLS_REAL_METHODS);
        ReflectionHelpers.setField(adapter, "isSelectionModeActive", true);
        for (boolean selected : new boolean[]{true, false}) {
            ReflectionHelpers.callInstanceMethod(adapter, "applySelectionVisuals",
                    ReflectionHelpers.ClassParameter.from(RecordsAdapter.RecordViewHolder.class, holder),
                    ReflectionHelpers.ClassParameter.from(boolean.class, selected),
                    ReflectionHelpers.ClassParameter.from(boolean.class, false));
            assertEquals(View.VISIBLE, holder.iconCheckContainer.getVisibility());
            assertEquals(selected ? 1f : 0f, holder.checkIcon.getAlpha(), 0f);
            assertEquals(selected ? View.VISIBLE : View.GONE, holder.selectionDimOverlay.getVisibility());
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) holder.iconCheckContainer.getLayoutParams();
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.START, params.gravity);
    }
    @Test public void mainControlsAndSelectionActionsOccupySeparateBottomRows() {
        FragmentActivity host = host();
        View root = LayoutInflater.from(host).inflate(R.layout.fragment_records, null, false);
        assertNotNull(root.findViewById(R.id.action_search));
        assertNotNull(root.findViewById(R.id.gallery_search_field));
        assertNotNull(root.findViewById(R.id.gallery_album_list));
        assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_tabs).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.gallery_selection_bar).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.records_header_stats_row).getVisibility());
        ConstraintLayout.LayoutParams content = (ConstraintLayout.LayoutParams)
                root.findViewById(R.id.swipe_refresh_layout).getLayoutParams();
        assertEquals(R.id.gallery_bottom_controls, content.bottomToTop);
        RecordsFragment fragment = spy(new RecordsFragment());
        doReturn(root).when(fragment).getView();
        ArrayList<Uri> selected = new ArrayList<>(); selected.add(Uri.parse("content://test/media/1"));
        ReflectionHelpers.setField(fragment, "selectedUris", selected);
        ReflectionHelpers.setField(fragment, "isInSelectionMode", true);
        ReflectionHelpers.callInstanceMethod(fragment, "updateGalleryPresentation");
        assertEquals(View.GONE, root.findViewById(R.id.gallery_tabs).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.gallery_selection_bar).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.action_search).getVisibility());
        assertTrue(root.findViewById(R.id.gallery_share).isEnabled());
    }
    @Test public void defaultFourColumnsNeverOverwritesSavedListOrGridDensity() {
        SharedPreferencesManager manager = mock(SharedPreferencesManager.class);
        SharedPreferences preferences = mock(SharedPreferences.class);
        ReflectionHelpers.setField(manager, "sharedPreferences", preferences);
        RecordsFragment fragment = new RecordsFragment();
        ReflectionHelpers.setField(fragment, "sharedPreferencesManager", manager);
        when(preferences.contains(Constants.PREF_RECORDS_GRID_SPAN)).thenReturn(false);
        assertEquals(4, (int) ReflectionHelpers.callInstanceMethod(fragment, "loadPersistedGridSpan"));
        when(preferences.contains(Constants.PREF_RECORDS_GRID_SPAN)).thenReturn(true);
        for (int columns : new int[]{1, 2, 3, 4, 5}) {
            when(manager.getRecordsGridSpan()).thenReturn(columns);
            assertEquals(columns, (int) ReflectionHelpers.callInstanceMethod(fragment, "loadPersistedGridSpan"));
        }
        verify(preferences, never()).edit();
        verify(manager, never()).setRecordsGridSpan(anyInt());
    }
    @Test public void albumsUseExistingCategoriesAndNeverExposeSkeletonPlaceholders() {
        VideoItem camera = new VideoItem(Uri.parse("content://test/1"), "one", 1, 1, VideoItem.Category.CAMERA);
        VideoItem camera2 = new VideoItem(Uri.parse("content://test/2"), "two", 1, 1, VideoItem.Category.CAMERA);
        VideoItem shot = new VideoItem(Uri.parse("content://test/3"), "photo", 1, 1, VideoItem.Category.SHOT);
        VideoItem skeleton = new VideoItem(Uri.parse("skeleton://test/1"), "loading", 0, 0, VideoItem.Category.UNKNOWN);
        skeleton.isSkeleton = true;
        List<GalleryAlbumAdapter.Album> albums = GalleryAlbumAdapter.group(Arrays.asList(camera, camera2, shot, skeleton));
        assertEquals(2, albums.size()); assertEquals(2, albums.get(0).count);
        assertSame(camera, albums.get(0).cover); assertEquals(VideoItem.Category.SHOT, albums.get(1).category);
    }
    @Test public void selectAllIndicatorTracksPartialAllAndPartialAgainWithoutStaleCheck() {
        FragmentActivity host = host();
        View root = LayoutInflater.from(host).inflate(R.layout.fragment_records, null, false);
        RecordsFragment fragment = spy(new RecordsFragment());
        doReturn(true).when(fragment).isAdded();
        doReturn(host).when(fragment).getActivity();
        doReturn(host.getResources()).when(fragment).getResources();
        doReturn(root).when(fragment).getView();
        ReflectionHelpers.setField(fragment, "titleText", new TextView(host));
        ReflectionHelpers.setField(fragment, "selectAllContainer", root.findViewById(R.id.action_select_all_container));
        ReflectionHelpers.setField(fragment, "selectAllCheck", root.findViewById(R.id.action_select_all_check));
        ReflectionHelpers.setField(fragment, "isInSelectionMode", true);
        VideoItem one = new VideoItem(Uri.parse("content://test/media/1"), "one", 1, 1);
        VideoItem two = new VideoItem(Uri.parse("content://test/media/2"), "two", 1, 1);
        ReflectionHelpers.setField(fragment, "videoItems", new ArrayList<>(Arrays.asList(one, two)));
        ArrayList<Uri> selected = new ArrayList<>(); selected.add(one.uri);
        ReflectionHelpers.setField(fragment, "selectedUris", selected);
        ReflectionHelpers.callInstanceMethod(fragment, "updateUiForSelectionMode");
        assertEquals(View.INVISIBLE, root.findViewById(R.id.action_select_all_check).getVisibility());
        selected.add(two.uri);
        ReflectionHelpers.callInstanceMethod(fragment, "updateUiForSelectionMode");
        assertEquals(View.VISIBLE, root.findViewById(R.id.action_select_all_check).getVisibility());
        selected.remove(one.uri);
        ReflectionHelpers.callInstanceMethod(fragment, "updateUiForSelectionMode");
        assertEquals(View.INVISIBLE, root.findViewById(R.id.action_select_all_check).getVisibility());
        assertEquals(1, selected.size());
    }

    @Test public void recyclingAlbumCoverAfterActivityDestroyedDoesNotResolveDestroyedActivityAgain() {
        org.robolectric.android.controller.ActivityController<FragmentActivity> controller =
                Robolectric.buildActivity(FragmentActivity.class).setup();
        FragmentActivity host = controller.get();
        host.setTheme(R.style.Base_Theme_FadCam);
        GalleryAlbumAdapter adapter = new GalleryAlbumAdapter(new GalleryAlbumAdapter.Listener() {
            @Override public String title(VideoItem.Category category) { return "Camera"; }
            @Override public void open(VideoItem.Category category) { }
        });
        VideoItem media = new VideoItem(Uri.parse("content://test/media/1"), "one", 1, 1,
                VideoItem.Category.CAMERA);
        adapter.update(Arrays.asList(media), true); // Exercise the real binding without external I/O.
        GalleryAlbumAdapter.Holder holder = adapter.onCreateViewHolder(new FrameLayout(host), 0);
        adapter.onBindViewHolder(holder, 0);
        controller.pause().stop().destroy();
        assertTrue(host.isDestroyed());
        // RecyclerView.setAdapter(null) during Fragment.onDestroyView reaches this real callback.
        adapter.onViewRecycled(holder);
        adapter.onViewRecycled(holder); // Cleanup remains safe when repeated.
    }

    @Test public void galleryPaletteStaysLightInsideDarkHostForGridAlbumsDatesAndBottomControls() {
        FragmentActivity host = host();
        int hostBackground = themeColor(host, android.R.attr.colorBackground);
        int hostCard = themeColor(host, R.attr.homeRailCardSurface);
        View gallery = LayoutInflater.from(host).inflate(R.layout.fragment_records, null, false);
        int light = android.graphics.Color.rgb(252, 252, 255), ink = android.graphics.Color.rgb(1, 1, 2);
        assertEquals(light, ((android.graphics.drawable.ColorDrawable) gallery.getBackground()).getColor());
        assertEquals(light, ((android.graphics.drawable.ColorDrawable) gallery.findViewById(R.id.header_bar).getBackground()).getColor());
        assertEquals(light, themeColor(gallery.getContext(), android.R.attr.colorBackground));
        for (int id : new int[]{R.id.gallery_tab_pictures, R.id.gallery_tab_albums, R.id.gallery_menu,
                R.id.gallery_share, R.id.gallery_delete, R.id.gallery_more})
            assertEquals(ink, ((TextView) gallery.findViewById(id)).getCurrentTextColor());
        assertEquals(ink, ((android.widget.ImageView) gallery.findViewById(R.id.action_search)).getImageTintList().getDefaultColor());
        View date = LayoutInflater.from(host).inflate(R.layout.item_gallery_date_header, null, false);
        assertEquals(ink, ((TextView) date.findViewById(R.id.text_month_title)).getCurrentTextColor());
        View album = LayoutInflater.from(host).inflate(R.layout.item_gallery_album, null, false);
        assertEquals(ink, ((TextView) album.findViewById(R.id.gallery_album_title)).getCurrentTextColor());
        assertEquals(android.graphics.Color.rgb(107, 107, 114), ((TextView) album.findViewById(R.id.gallery_album_count)).getCurrentTextColor());
        assertEquals(hostBackground, themeColor(host, android.R.attr.colorBackground));
        assertEquals(hostCard, themeColor(host, R.attr.homeRailCardSurface));
    }

    @Test public void selectionRebindKeepsScopedLightCardInsteadOfRestoringDarkHostSurface() {
        FragmentActivity host = host();
        RecordsAdapter.RecordViewHolder holder = tile(host);
        RecordsAdapter adapter = mock(RecordsAdapter.class, CALLS_REAL_METHODS);
        ReflectionHelpers.setField(adapter, "context", host);
        ReflectionHelpers.setField(adapter, "isSelectionModeActive", true);
        ReflectionHelpers.callInstanceMethod(adapter, "applySelectionVisuals",
                ReflectionHelpers.ClassParameter.from(RecordsAdapter.RecordViewHolder.class, holder),
                ReflectionHelpers.ClassParameter.from(boolean.class, true),
                ReflectionHelpers.ClassParameter.from(boolean.class, false));
        assertEquals(android.graphics.Color.rgb(252, 252, 255),
                ((com.google.android.material.card.MaterialCardView) holder.itemView).getCardBackgroundColor().getDefaultColor());
        assertEquals(View.VISIBLE, holder.iconCheckContainer.getVisibility());
        assertEquals(View.VISIBLE, holder.selectionDimOverlay.getVisibility());
    }
    private int themeColor(android.content.Context context, int attribute) {
        android.content.res.TypedArray values = context.obtainStyledAttributes(new int[]{attribute});
        int color = values.getColor(0, 0); values.recycle(); return color;
    }

}
