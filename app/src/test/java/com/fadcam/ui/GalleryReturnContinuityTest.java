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

    @Test public void deferredScanReplacesMissingTileEvenWhenItemCountStaysTheSame() {
        RecordsFragment grid=spy(new RecordsFragment());doReturn(true).when(grid).isAdded();
        VideoItem removed=media(1),replacement=media(2);data(grid,removed);
        ReflectionHelpers.setField(grid,"totalItems",1);
        ReflectionHelpers.setField(grid,"progressHandler",new android.os.Handler(Looper.getMainLooper()));
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",settings);
        RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
        VideoIndexRepository repository=mock(VideoIndexRepository.class);
        when(repository.deltaScan(settings)).thenReturn(Arrays.asList(replacement));
        ExecutorService executor=mock(ExecutorService.class);List<Runnable> pending=new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv->{pending.add(inv.getArgument(0));return mock(Future.class);});
        ReflectionHelpers.setField(grid,"deltaExecutor",executor);
        ReflectionHelpers.callInstanceMethod(grid,"scheduleRecordsPostFirstPaintWork",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        Runnable deferred=ReflectionHelpers.getField(grid,"deferredRecordsDeltaScanRunnable");
        deferred.run();
        pending.get(0).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(replacement),loaded(grid));
        deferred.run(); // A subsequent identical snapshot must not rebind the grid.
        pending.get(1).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(repository,times(2)).deltaScan(settings);
        verify(adapter).updateRecords(anyList());
    }

    @Test public void olderDeferredResultCannotReplaceGridAfterWarmRefreshStarts() {
        RecordsFragment grid=spy(new RecordsFragment());doReturn(true).when(grid).isAdded();
        VideoItem original=media(1),stale=media(2),fresh=media(3);data(grid,original);
        ReflectionHelpers.setField(grid,"totalItems",1);
        ReflectionHelpers.setField(grid,"progressHandler",new android.os.Handler(Looper.getMainLooper()));
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",settings);
        RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
        VideoIndexRepository repository=mock(VideoIndexRepository.class);
        when(repository.deltaScan(settings)).thenReturn(Arrays.asList(stale,fresh),Arrays.asList(fresh));
        ExecutorService executor=mock(ExecutorService.class);List<Runnable> pending=new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv->{pending.add(inv.getArgument(0));return mock(Future.class);});
        ReflectionHelpers.setField(grid,"deltaExecutor",executor);
        ReflectionHelpers.callInstanceMethod(grid,"scheduleRecordsPostFirstPaintWork",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        Runnable deferred=ReflectionHelpers.getField(grid,"deferredRecordsDeltaScanRunnable");
        deferred.run();
        pending.get(0).run(); // Old result waiting in main queue; warm request supersedes it.
        ReflectionHelpers.callInstanceMethod(grid,"refreshLoadedRecords",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(original),loaded(grid));
        assertEquals(Integer.valueOf(1),ReflectionHelpers.<Integer>getField(grid,"totalItems"));
        verifyNoInteractions(adapter);
        pending.get(1).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(fresh),loaded(grid));
        verify(adapter).updateRecords(anyList());
    }
    @Test public void twoWarmResultsQueuedOutOfOrderKeepTheNewestGrid() {
        RecordsFragment grid=spy(new RecordsFragment());doReturn(true).when(grid).isAdded();
        VideoItem old=media(1),stale=media(2),latest=media(3);data(grid,old);
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",settings);
        RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
        VideoIndexRepository repository=mock(VideoIndexRepository.class);
        when(repository.deltaScan(settings)).thenReturn(Arrays.asList(latest),Arrays.asList(stale));
        ExecutorService executor=mock(ExecutorService.class);List<Runnable> pending=new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv->{pending.add(inv.getArgument(0));return mock(Future.class);});
        ReflectionHelpers.setField(grid,"deltaExecutor",executor);
        for(int i=0;i<2;i++) ReflectionHelpers.callInstanceMethod(grid,"refreshLoadedRecords",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        pending.get(1).run();
        pending.get(0).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(latest),loaded(grid));
        verify(adapter).updateRecords(anyList());
    }

    @Test public void sameUriWithChangedClassificationInvalidatesAdapterRow() {
        VideoItem before=new VideoItem(Uri.parse("content://fixture/photo/1"),"photo.jpg",32,1,
                VideoItem.Category.SHOT,VideoItem.MediaType.IMAGE,VideoItem.ShotSubtype.BACK);
        VideoItem after=new VideoItem(before.uri,before.displayName,before.size,before.lastModified,
                before.category,before.mediaType,VideoItem.ShotSubtype.SELFIE);
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        RecordsAdapter adapter=new RecordsAdapter(context,Arrays.asList(before),mock(ExecutorService.class),
                SharedPreferencesManager.getInstance(context),null,null,mock(RecordActionListener.class));
        androidx.recyclerview.widget.RecyclerView.AdapterDataObserver observer=
                mock(androidx.recyclerview.widget.RecyclerView.AdapterDataObserver.class);
        adapter.registerAdapterDataObserver(observer);
        adapter.updateRecords(Arrays.asList(after));
        verify(observer).onItemRangeChanged(anyInt(),eq(1),isNull());
    }

    @Test public void latestManualRefreshRejectsOlderWarmResultAndUsesNewSort() {
        RecordsFragment grid=spy(new RecordsFragment());doReturn(true).when(grid).isAdded();
        VideoItem original=media(1),stale=media(2),first=media(3),second=media(4);data(grid,original);
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",settings);
        RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
        VideoIndexRepository repository=mock(VideoIndexRepository.class);
        when(repository.deltaScan(settings)).thenReturn(Arrays.asList(stale),Arrays.asList(second,first));
        ExecutorService executor=mock(ExecutorService.class);List<Runnable> pending=new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv->{pending.add(inv.getArgument(0));return mock(Future.class);});
        ReflectionHelpers.setField(grid,"deltaExecutor",executor);
        ReflectionHelpers.callInstanceMethod(grid,"refreshLoadedRecords",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        pending.get(0).run();
        ReflectionHelpers.callInstanceMethod(grid,"refreshGalleryManually",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        pending.get(1).run();
        Object sort=ReflectionHelpers.getField(grid,"currentSortOption");
        for(Object option:sort.getClass().getEnumConstants()) {
            if(option.toString().equals("OLDEST_FIRST")) ReflectionHelpers.setField(grid,"currentSortOption",option);
        }
        assertEquals("OLDEST_FIRST",ReflectionHelpers.getField(grid,"currentSortOption").toString());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(first,second),loaded(grid));
        verify(adapter).updateRecords(anyList());
    }

    @Test public void detachedGalleryDropsQueuedManualSnapshot() {
        RecordsFragment grid=spy(new RecordsFragment());doReturn(true).when(grid).isAdded();
        VideoItem original=media(1),fresh=media(2);data(grid,original);
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",settings);
        RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
        VideoIndexRepository repository=mock(VideoIndexRepository.class);
        when(repository.deltaScan(settings)).thenReturn(Arrays.asList(fresh));
        ExecutorService executor=mock(ExecutorService.class);List<Runnable> pending=new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv->{pending.add(inv.getArgument(0));return mock(Future.class);});
        ReflectionHelpers.setField(grid,"deltaExecutor",executor);
        ReflectionHelpers.callInstanceMethod(grid,"refreshGalleryManually",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        pending.get(0).run();
        doReturn(false).when(grid).isAdded();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(original),loaded(grid));verifyNoInteractions(adapter);
    }

    @Test public void latestFailureDoesNotLetOlderSuccessReplaceTheGrid() {
        RecordsFragment grid=spy(new RecordsFragment());doReturn(true).when(grid).isAdded();
        VideoItem original=media(1),stale=media(2);data(grid,original);
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",settings);
        RecordsAdapter adapter=mock(RecordsAdapter.class);ReflectionHelpers.setField(grid,"recordsAdapter",adapter);
        VideoIndexRepository repository=mock(VideoIndexRepository.class);
        when(repository.deltaScan(settings)).thenThrow(new IllegalStateException("fixture provider unavailable"))
                .thenReturn(Arrays.asList(stale));
        ExecutorService executor=mock(ExecutorService.class);List<Runnable> pending=new ArrayList<>();
        when(executor.submit(any(Runnable.class))).thenAnswer(inv->{pending.add(inv.getArgument(0));return mock(Future.class);});
        ReflectionHelpers.setField(grid,"deltaExecutor",executor);
        ReflectionHelpers.callInstanceMethod(grid,"refreshLoadedRecords",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        ReflectionHelpers.callInstanceMethod(grid,"refreshGalleryManually",
                ReflectionHelpers.ClassParameter.from(VideoIndexRepository.class,repository));
        pending.get(1).run();pending.get(0).run();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList(original),loaded(grid));verifyNoInteractions(adapter);
    }

}
