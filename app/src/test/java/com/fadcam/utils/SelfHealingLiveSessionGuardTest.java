package com.fadcam.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import androidx.media3.muxer.FragmentedMp4Muxer;
import com.fadcam.Constants;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.data.VideoIndexRepository;
import com.fadcam.services.RecordingService;
import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Live-session exclusion using the actual scanner; muxer conversion is explicitly mocked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class SelfHealingLiveSessionGuardTest {
    @Rule public TemporaryFolder files=new TemporaryFolder();
    private Context app;
    private SharedPreferencesManager prefs;
    private RecordingService service;
    private VideoIndexRepository repo,previousRepo;
    private String guardedUri;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        prefs=SharedPreferencesManager.getInstance(app);prefs.setRepairV2ResetDone(true);
        prefs.setRecordingInProgress(false);
        service=Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service,"sharedPreferencesManager",prefs);
        ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",service);
        previousRepo=ReflectionHelpers.getStaticField(VideoIndexRepository.class,"instance");
        repo=mock(VideoIndexRepository.class);
        // The public scanner launches a worker. A process-wide mocked singleton,
        // rather than a thread-local static mock, prevents any real DB work even
        // when this regression is run against the unguarded implementation.
        ReflectionHelpers.setStaticField(VideoIndexRepository.class,"instance",repo);
        when(repo.getUnfinalizedUris()).thenReturn(Collections.emptyList());
    }
    @After public void cleanup() throws Exception {
        AtomicBoolean running=ReflectionHelpers.getStaticField(RecordingService.class,"scanRunning");
        long deadline=System.nanoTime()+2_000_000_000L;
        while(running.get() && System.nanoTime()<deadline)Thread.sleep(1);
        assertFalse("Owned scanner worker must finish before restoring its test repository",running.get());
        if(guardedUri!=null)guards().remove(guardedUri);
        ReflectionHelpers.setStaticField(VideoIndexRepository.class,"instance",previousRepo);
        ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",null);
    }
    private Set<String> guards(){return ReflectionHelpers.getStaticField(RecordingService.class,"IN_FLIGHT_FINALIZATIONS");}
    private void scan(){ReflectionHelpers.callStaticMethod(RecordingService.class,"doSelfHealingScan",ReflectionHelpers.ClassParameter.from(Context.class,app));}

    @Test public void pausedAndOtherActiveStatesBlockPublicScanEvenWhenPersistedRecordingFlagIsFalse() {
        for(RecordingState state:new RecordingState[]{RecordingState.STARTING,RecordingState.IN_PROGRESS,RecordingState.PAUSED}) {
            ReflectionHelpers.setField(service,"recordingState",state);
            AtomicBoolean callback=new AtomicBoolean();
            RecordingService.runSelfHealingScan(app,()->callback.set(true));
            assertTrue("A live session is skipped synchronously: "+state,callback.get());
            verifyNoInteractions(repo);
            assertFalse(prefs.isRecordingInProgress());
        }
    }
    @Test public void finalizationBlocksBothPublicEntryAndAlreadyDispatchedWorker() {
        ReflectionHelpers.setField(service,"isStopping",true);
        AtomicBoolean callback=new AtomicBoolean();
        RecordingService.runSelfHealingScan(app,()->callback.set(true));
        assertTrue(callback.get());
        scan();
        verifyNoInteractions(repo);
        assertFalse(prefs.isRecordingInProgress());
    }
    @Test public void queuedScanRechecksLivePauseImmediatelyBeforeTouchingEachPendingFile() {
        String uri=Uri.fromFile(new File(files.getRoot(),"not-opened.mp4")).toString();
        when(repo.getUnfinalizedUris()).thenAnswer(invocation->{
            ReflectionHelpers.setField(service,"recordingState",RecordingState.PAUSED);
            return Collections.singletonList(uri);
        });
        scan();
        verify(repo).getUnfinalizedUris();
        verify(repo,never()).markUnrepairableWithRetry(anyString(),anyLong());
        verify(repo,never()).markFinalized(anyString(),anyInt());
        assertTrue(prefs.getRecordingRepairEvents().isEmpty());
    }
    @Test public void idleAbandonedRepairStillWorksInFlightIsSkippedAndNewSessionFlagIsPreserved() throws Exception {
        File pending=files.newFile("abandoned-fixture.mp4");
        Files.write(pending.toPath(),new byte[]{1,2,3,4});
        String uri=Uri.fromFile(pending).toString();
        when(repo.getUnfinalizedUris()).thenReturn(Collections.singletonList(uri));
        try(MockedStatic<FragmentedMp4Muxer> finalizer=mockStatic(FragmentedMp4Muxer.class)) {
            finalizer.when(()->FragmentedMp4Muxer.finalizeAbandonedFile(any(FileChannel.class))).thenReturn(1);
            guardedUri=uri;guards().add(uri);scan();
            finalizer.verifyNoInteractions();verify(repo,never()).markFinalized(anyString(),anyInt());
            assertTrue(prefs.getRecordingRepairEvents().isEmpty());
            guards().remove(uri);
            doAnswer(invocation->{
                ReflectionHelpers.setField(service,"recordingState",RecordingState.STARTING);
                prefs.setRecordingInProgress(true);
                return null;
            }).when(repo).markFinalized(uri,1);
            scan();
            finalizer.verify(()->FragmentedMp4Muxer.finalizeAbandonedFile(any(FileChannel.class)),times(1));
            verify(repo).markFinalized(uri,1);
            assertEquals(1,prefs.getRecordingRepairEvents().size());
            assertTrue(prefs.getRecordingRepairEvents().get(0)[2].endsWith("abandoned-fixture.mp4"));
            assertTrue("The scan cannot clear a newly started service's recording flag",prefs.isRecordingInProgress());
        }
    }
}
