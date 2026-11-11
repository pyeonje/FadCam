package com.fadcam.services;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.Intent;
import android.os.Handler;
import com.fadcam.Constants;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.opengl.GLRecordingPipeline;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
public class RecordingStartupCancellationTest {
    @Test public void stoppingBeforeCameraOrPipelineIsReadyDoesNotBroadcastSaveSuccess() throws Exception {
        assertFalse(stopAndGetResult(RecordingState.STARTING, 0L, null));
    }

    @Test public void stoppingWithPreparedButNeverStartedPipelineDoesNotBroadcastSaveSuccess() throws Exception {
        GLRecordingPipeline pipeline = mock(GLRecordingPipeline.class);
        assertFalse(stopAndGetResult(RecordingState.STARTING, 0L, pipeline));
        verify(pipeline).requestOutputStop();
        verify(pipeline).stopRecording();
    }

    @Test public void activePipelineThatFinalizesStillBroadcastsSuccess() throws Exception {
        GLRecordingPipeline pipeline = mock(GLRecordingPipeline.class);
        assertTrue(stopAndGetResult(RecordingState.IN_PROGRESS, 1L, pipeline));
        verify(pipeline).stopRecording();
    }

    private boolean stopAndGetResult(RecordingState state, long startTime, GLRecordingPipeline pipeline)
            throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        RecordingService service = Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service, "sharedPreferencesManager", SharedPreferencesManager.getInstance(app));
        ReflectionHelpers.setField(service, "recordingState", state);
        ReflectionHelpers.setField(service, "recordingStartTime", startTime);
        ReflectionHelpers.setField(service, "glRecordingPipeline", pipeline);
        BlockingQueue<Runnable> mainWork = new LinkedBlockingQueue<>();
        BlockingQueue<Runnable> cooldownWork = new LinkedBlockingQueue<>();
        Handler main = mock(Handler.class);
        when(main.post(any(Runnable.class))).thenAnswer(call -> mainWork.add(call.getArgument(0)));
        when(main.postDelayed(any(Runnable.class), eq((long) Constants.CAMERA_RESOURCE_COOLDOWN_MS)))
                .thenAnswer(call -> cooldownWork.add(call.getArgument(0)));
        ReflectionHelpers.setField(service, "mainHandler", main);
        boolean previousReleasing = ReflectionHelpers.getStaticField(
                RecordingService.class, "isCameraResourceReleasing");
        try {
            service.onStartCommand(new Intent(Constants.INTENT_ACTION_STOP_RECORDING), 0, 1);
            Runnable finalization = mainWork.poll(5, TimeUnit.SECONDS);
            assertNotNull("recording worker must return its result", finalization);
            finalization.run();
            List<Intent> broadcasts = Shadows.shadowOf(app).getBroadcastIntents();
            List<Intent> completed = new java.util.ArrayList<>();
            for (Intent intent : broadcasts) {
                if (Constants.ACTION_RECORDING_COMPLETE.equals(intent.getAction())) completed.add(intent);
            }
            assertEquals("send exactly one explicit completion result", 1, completed.size());
            assertTrue(completed.get(0).hasExtra(Constants.EXTRA_RECORDING_SUCCESS));
            // Our controlled Handler must also execute the real delayed Camera2 cooldown.
            Runnable cooldown = cooldownWork.poll();
            assertNotNull("the fixture must drain the scheduled camera cooldown", cooldown);
            cooldown.run();
            assertFalse(ReflectionHelpers.<Boolean>getStaticField(
                    RecordingService.class, "isCameraResourceReleasing"));
            return completed.get(0).getBooleanExtra(Constants.EXTRA_RECORDING_SUCCESS, false);
        } finally {
            // Even a failing assertion must not leave other Robolectric classes in cooldown.
            ReflectionHelpers.setStaticField(
                    RecordingService.class, "isCameraResourceReleasing", previousReleasing);
        }
    }
}
