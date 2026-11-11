package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import com.fadcam.MainActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.service.RecordsDeletionRequestItem;
import com.fadcam.service.RecordsDeletionService;
import com.fadcam.service.RecordsDeletionSessionSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Selection UI requests only: fixture URIs, no user files or actual storage operations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class GallerySelectionActionsTest {
    private FragmentActivity host() {
        FragmentActivity host=Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.Base_Theme_FadCam);return host;
    }
    private VideoItem media(int id) {
        return new VideoItem(Uri.parse("content://fixture/selection/"+id),"selected"+id+".mp4",32,id,
                VideoItem.Category.CAMERA,VideoItem.MediaType.VIDEO);
    }
    private RecordsFragment grid(FragmentActivity host,VideoItem... items) {
        RecordsFragment grid=spy(new RecordsFragment());
        doReturn(host).when(grid).getActivity();doReturn(host).when(grid).getContext();
        doReturn(host.getResources()).when(grid).getResources();doReturn(true).when(grid).isAdded();
        ReflectionHelpers.setField(grid,"recordsAdapter",mock(RecordsAdapter.class));
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",mock(SharedPreferencesManager.class));
        ReflectionHelpers.setField(grid,"allLoadedItems",new ArrayList<>(Arrays.asList(items)));
        ReflectionHelpers.setField(grid,"videoItems",new ArrayList<>(Arrays.asList(items)));
        ArrayList<Uri> selected=new ArrayList<>();for(VideoItem item:items)selected.add(item.uri);
        ReflectionHelpers.setField(grid,"selectedUris",selected);
        ReflectionHelpers.setField(grid,"isInSelectionMode",true);return grid;
    }
    private List<Uri> selection(RecordsFragment grid) { return ReflectionHelpers.getField(grid,"selectedUris"); }

    @Test public void actualSelectionPopupRowUsesReadableDarkTextInsideDarkActivity() {
        FragmentActivity host=host();RecordsFragment grid=grid(host,media(1));
        androidx.appcompat.widget.PopupMenu popup=grid.createGallerySelectionMenu(new View(host));
        androidx.appcompat.view.menu.MenuBuilder menu=(androidx.appcompat.view.menu.MenuBuilder)popup.getMenu();
        android.content.Context themed=menu.getContext();
        androidx.appcompat.view.menu.ListMenuItemView row=(androidx.appcompat.view.menu.ListMenuItemView)
                LayoutInflater.from(themed).inflate(androidx.appcompat.R.layout.abc_popup_menu_item_layout,
                        new android.widget.FrameLayout(themed),false);
        row.initialize((androidx.appcompat.view.menu.MenuItemImpl)menu.findItem(2),0);
        android.widget.TextView title=row.findViewById(androidx.appcompat.R.id.title);
        assertEquals(android.graphics.Color.rgb(1,1,2),title.getCurrentTextColor());
        assertEquals(16*themed.getResources().getDisplayMetrics().scaledDensity,title.getTextSize(),.1f);
        assertEquals(host.getString(R.string.video_menu_save_move),title.getText());
    }

    @Test public void selectionMenuCopyAndMoveUseRealServiceRequestsForOneOrManyItems() {
        FragmentActivity host=host();
        for(int count:new int[]{1,2})for(boolean move:new boolean[]{false,true}) {
            VideoItem[] items=count==1?new VideoItem[]{media(1)}:new VideoItem[]{media(1),media(2)};
            RecordsFragment grid=grid(host,items);
            androidx.appcompat.widget.PopupMenu popup=grid.createGallerySelectionMenu(new View(host));
            assertEquals(host.getString(R.string.video_menu_save_copy),popup.getMenu().findItem(1).getTitle());
            assertEquals(host.getString(R.string.video_menu_save_move),popup.getMenu().findItem(2).getTitle());
            assertTrue(popup.getMenu().performIdentifierAction(move?2:1,0));
            Intent request=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertNotNull(request);assertEquals(RecordsDeletionService.class.getName(),request.getComponent().getClassName());
            assertEquals(move?RecordsDeletionSessionSnapshot.OperationKind.SAVE_MOVE_TO_GALLERY.name():
                    RecordsDeletionSessionSnapshot.OperationKind.SAVE_COPY_TO_GALLERY.name(),
                    request.getStringExtra(RecordsDeletionService.EXTRA_OPERATION_KIND));
            List<RecordsDeletionRequestItem> queued=RecordsDeletionRequestItem.fromJson(
                    request.getStringExtra(RecordsDeletionService.EXTRA_DELETE_ITEMS_JSON));
            assertEquals(count,queued.size());for(int i=0;i<count;i++) {
                assertEquals(items[i].uri.toString(),queued.get(i).uriString);
                assertEquals(items[i].displayName,queued.get(i).displayName);assertTrue(queued.get(i).safSource);
            }
            assertTrue(selection(grid).isEmpty());assertFalse(ReflectionHelpers.getField(grid,"isInSelectionMode"));
        }
    }

    @Test public void rejectedSaveRequestPreservesSelectionAndOriginalGrid() {
        FragmentActivity host=host();VideoItem item=media(1);RecordsFragment grid=grid(host,item);
        try(org.mockito.MockedStatic<RecordsDeletionService> service=mockStatic(RecordsDeletionService.class)) {
            service.when(()->RecordsDeletionService.startSaveSession(eq(host),anyList(),eq(true)))
                    .thenThrow(new IllegalStateException("fixture request denied"));
            assertTrue(grid.createGallerySelectionMenu(new View(host)).getMenu().performIdentifierAction(2,0));
            assertEquals(Arrays.asList(item.uri),selection(grid));
            assertTrue(ReflectionHelpers.getField(grid,"isInSelectionMode"));
            assertEquals(Arrays.asList(item),ReflectionHelpers.getField(grid,"allLoadedItems"));
        }
    }

    @Test public void unknownSelectedItemDoesNotClearSelectionOrStartAnOperation() {
        FragmentActivity host=host();RecordsFragment grid=grid(host,media(1));
        ReflectionHelpers.setField(grid,"allLoadedItems",new ArrayList<VideoItem>());
        assertTrue(grid.createGallerySelectionMenu(new View(host)).getMenu().performIdentifierAction(2,0));
        assertEquals(1,selection(grid).size());assertTrue(ReflectionHelpers.getField(grid,"isInSelectionMode"));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @Test public void realFooterMoreRoutesTheVisibleAnchorAndNoSelectionDisablesAllActions() {
        FragmentActivity host=host();RecordsFragment grid=grid(host,media(1));
        View root=LayoutInflater.from(host).inflate(R.layout.fragment_records,null,false);
        doReturn(root).when(grid).getView();
        View more=root.findViewById(R.id.gallery_more);
        androidx.appcompat.widget.PopupMenu popup=mock(androidx.appcompat.widget.PopupMenu.class);
        doReturn(popup).when(grid).createGallerySelectionMenu(more);
        ReflectionHelpers.callInstanceMethod(grid,"setupGalleryPresentation",
                ReflectionHelpers.ClassParameter.from(View.class,root));
        assertTrue(more.performClick());verify(popup).show();
        selection(grid).clear();ReflectionHelpers.callInstanceMethod(grid,"updateGalleryPresentation");
        for(int id:new int[]{R.id.gallery_share,R.id.gallery_delete,R.id.gallery_more})assertFalse(root.findViewById(id).isEnabled());
    }

    @Test public void activityBackConsumesSelectionBeforeLeavingGalleryAndThenKeepsNormalNavigation() {
        FragmentActivity host=host();RecordsFragment grid=grid(host,media(1));
        MainActivity activity=mock(MainActivity.class,CALLS_REAL_METHODS);
        FragmentManager manager=mock(FragmentManager.class);
        doReturn(manager).when(activity).getSupportFragmentManager();
        doReturn(null).when(activity).findViewById(anyInt());
        when(manager.findFragmentByTag(anyString())).thenReturn(grid);
        ReflectionHelpers.setField(activity,"currentFragmentPosition",1);
        doNothing().when(activity).switchFragment(anyInt(),anyBoolean());
        ReflectionHelpers.callInstanceMethod(activity,"handleBackPress");
        assertFalse(ReflectionHelpers.getField(grid,"isInSelectionMode"));assertTrue(selection(grid).isEmpty());
        verify(activity,never()).switchFragment(anyInt(),anyBoolean());
        ReflectionHelpers.callInstanceMethod(activity,"handleBackPress");
        verify(activity).switchFragment(0,true);
    }

    @Test public void selectionBackTakesPriorityOverAnOpenSearchWithoutThrowingAwaySearchResults() {
        FragmentActivity host=host();RecordsFragment grid=grid(host,media(1));
        androidx.appcompat.widget.SearchView search=new androidx.appcompat.widget.SearchView(host);
        search.setIconified(false);ReflectionHelpers.setField(grid,"searchView",search);
        ReflectionHelpers.setField(grid,"isSearchViewActive",true);
        assertTrue(grid.handleBackWithinGallery());
        assertFalse(ReflectionHelpers.getField(grid,"isInSelectionMode"));assertTrue(selection(grid).isEmpty());
        assertFalse(search.isIconified());
    }
}
