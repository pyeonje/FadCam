package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.net.Uri;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import androidx.fragment.app.FragmentActivity;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.service.RecordsDeletionSessionSnapshot;
import com.fadcam.service.RecordsDeletionSessionStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Exercises real store replay and warm refresh, without user files or device I/O. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class GalleryReturnContinuityTest {
    private VideoItem media(int id) {
        return new VideoItem(Uri.parse("content://fixture/media/"+id),"video"+id+".mp4",32,id,
                VideoItem.Category.CAMERA,VideoItem.MediaType.VIDEO);
    }
    private RecordsDeletionSessionSnapshot snapshot(RecordsDeletionSessionSnapshot.OperationKind kind,
            RecordsDeletionSessionSnapshot.State state, VideoItem item) {
        RecordsDeletionSessionSnapshot value=new RecordsDeletionSessionSnapshot();
        value.operationKind=kind;value.state=state;value.completedUriStrings.add(item.uri.toString());
        value.totalItemCount=1;value.completedItemCount=1;return value;
    }
    private void data(RecordsFragment grid,VideoItem... items) {
        ReflectionHelpers.setField(grid,"allLoadedItems",new ArrayList<>(Arrays.asList(items)));
        ReflectionHelpers.setField(grid,"videoItems",new ArrayList<>(Arrays.asList(items)));
    }
    private List<VideoItem> loaded(RecordsFragment grid) {
        return ReflectionHelpers.getField(grid,"allLoadedItems");
    }
    private void replay(RecordsFragment grid,RecordsDeletionSessionSnapshot value) {
        RecordsDeletionSessionStore store=mock(RecordsDeletionSessionStore.class);
        when(store.read()).thenReturn(value);ReflectionHelpers.setField(grid,"deletionSessionStore",store);
        ReflectionHelpers.callInstanceMethod(grid,"syncDeletionUiFromStore",
                ReflectionHelpers.ClassParameter.from(boolean.class,true));
    }

    @Test public void completedCopyAndExportNeverRemoveOriginalsWhenReturningToGrid() {
        for(RecordsDeletionSessionSnapshot.OperationKind kind:new RecordsDeletionSessionSnapshot.OperationKind[]{
                RecordsDeletionSessionSnapshot.OperationKind.SAVE_COPY_TO_GALLERY,
                RecordsDeletionSessionSnapshot.OperationKind.SAVE_EXPORT_TO_CUSTOM_TREE}) {
            RecordsFragment grid=new RecordsFragment();VideoItem one=media(1),two=media(2);data(grid,one,two);
            RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
            replay(grid,snapshot(kind,RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS,one));
            replay(grid,snapshot(kind,RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS,one));
            assertEquals(Arrays.asList(one,two),loaded(grid));
            verify(adapter,never()).updateRecords(anyList());verify(adapter,never()).evictCachesForUris(anyList());
        }
    }

    @Test public void deleteAndMoveReplayRemovesOnceWithoutRepeatedThumbnailEviction() {
        for(RecordsDeletionSessionSnapshot.OperationKind kind:new RecordsDeletionSessionSnapshot.OperationKind[]{
                RecordsDeletionSessionSnapshot.OperationKind.DELETE,
                RecordsDeletionSessionSnapshot.OperationKind.SAVE_MOVE_TO_GALLERY}) {
            RecordsFragment grid=new RecordsFragment();VideoItem one=media(1),two=media(2);data(grid,one,two);
            RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
            RecordsDeletionSessionSnapshot value=snapshot(kind,RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS,one);
            replay(grid,value);replay(grid,value);
            assertEquals(Arrays.asList(two),loaded(grid));
            verify(adapter,times(1)).evictCachesForUris(anyList());verify(adapter,times(1)).updateRecords(anyList());
        }
    }

    @Test public void allSuccessfulStorageKindsHideCompletionDockButErrorsAndCancelRemainAvailable() {
        FragmentActivity host=Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.Base_Theme_FadCam);
        View root=LayoutInflater.from(host).inflate(R.layout.fragment_records,null,false);
        RecordsFragment grid=spy(new RecordsFragment());
        doReturn(host).when(grid).getActivity();doReturn(host).when(grid).getContext();
        doReturn(host.getResources()).when(grid).getResources();
        String[] fields={"deletionDock","deletionDockIcon","deletionDockTitle","deletionDockProgress",
                "deletionDockCurrentItem","deletionDockEta","deletionDockSummary","deletionDockOk"};
        int[] ids={R.id.records_header_delete_dock,R.id.records_header_delete_icon,R.id.records_header_delete_title,
                R.id.records_header_delete_progress,R.id.records_header_delete_current,R.id.records_header_delete_eta,
                R.id.records_header_delete_summary,R.id.records_header_delete_ok};
        for(int i=0;i<fields.length;i++)ReflectionHelpers.setField(grid,fields[i],root.findViewById(ids[i]));
        for(RecordsDeletionSessionSnapshot.OperationKind kind:RecordsDeletionSessionSnapshot.OperationKind.values())
        for(RecordsDeletionSessionSnapshot.State state:new RecordsDeletionSessionSnapshot.State[]{
                RecordsDeletionSessionSnapshot.State.RUNNING,RecordsDeletionSessionSnapshot.State.COMPLETED_FAILED,
                RecordsDeletionSessionSnapshot.State.COMPLETED_PARTIAL,RecordsDeletionSessionSnapshot.State.CANCELLED,
                RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS}) {
            RecordsDeletionSessionSnapshot value=snapshot(kind,state,media(1));
            ReflectionHelpers.setField(grid,"deletionSnapshot",value);
            ReflectionHelpers.callInstanceMethod(grid,"renderDeletionSnapshot",
                    ReflectionHelpers.ClassParameter.from(RecordsDeletionSessionSnapshot.class,value),
                    ReflectionHelpers.ClassParameter.from(boolean.class,true));
            assertEquals(state==RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS?View.GONE:View.VISIBLE,
                    root.findViewById(R.id.records_header_delete_dock).getVisibility());
            assertFalse("UI hiding must not erase the recorded outcome",value.completionAcknowledged);
        }
        // An inconsistent success marker must not hide an actual recorded failure.
        RecordsDeletionSessionSnapshot failed=snapshot(RecordsDeletionSessionSnapshot.OperationKind.SAVE_MOVE_TO_GALLERY,
                RecordsDeletionSessionSnapshot.State.COMPLETED_SUCCESS,media(1));
        failed.failedItemCount=1;ReflectionHelpers.setField(grid,"deletionSnapshot",failed);
        ReflectionHelpers.callInstanceMethod(grid,"renderDeletionSnapshot",
                ReflectionHelpers.ClassParameter.from(RecordsDeletionSessionSnapshot.class,failed),
                ReflectionHelpers.ClassParameter.from(boolean.class,true));
        assertEquals(View.VISIBLE,root.findViewById(R.id.records_header_delete_dock).getVisibility());
    }

    @Test public void warmRefreshKeepsGridUntilSingleFinalScanAndNeverReplaysStaleDbRows() {
        RecordsFragment grid=spy(new RecordsFragment());doReturn(true).when(grid).isAdded();
        VideoItem one=media(1),two=media(2);data(grid,one,two);
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",settings);
        RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
        VideoIndexRepository repository=mock(VideoIndexRepository.class);
        when(repository.deltaScan(settings)).thenReturn(Arrays.asList(two));
        ExecutorService executor=mock(ExecutorService.class);List<Runnable> pending=new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv->{pending.add(inv.getArgument(0));return mock(Future.class);});
        ReflectionHelpers.setField(grid,"deltaExecutor",executor);ReflectionHelpers.setField(grid,"isLoading",true);
        ReflectionHelpers.callInstanceMethod(grid,"refreshLoadedRecords",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        assertEquals(Arrays.asList(one,two),loaded(grid));verifyNoInteractions(adapter);
        pending.get(0).run();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1,loaded(grid).size());assertEquals(two.uri,loaded(grid).get(0).uri);
        verify(repository,never()).getVideos(any());verify(repository,times(1)).deltaScan(settings);
        verify(adapter,times(1)).updateRecords(anyList());assertFalse(ReflectionHelpers.getField(grid,"isLoading"));
    }
}
