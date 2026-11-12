package com.fadcam.opengl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.os.Handler;
import android.os.Looper;
import android.media.MediaCodec;
import android.view.Surface;
import org.robolectric.Shadows;
import android.app.Application;
import android.content.Intent;
import com.fadcam.Constants;
import com.fadcam.RecordingState;
import com.fadcam.VideoCodec;
import com.fadcam.services.RecordingService;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class RecordingOutputOwnershipTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void failedNextSegmentDoesNotOpenAndTruncateCompletedOutput() throws Exception {
        File first = folder.newFile("segment-a.mp4");
        byte[] expected = {2, 4, 6, 8};
        Files.write(first.toPath(), expected);
        GLRecordingPipeline pipeline = new GLRecordingPipeline(RuntimeEnvironment.getApplication(),
                null, 640, 480, 30, first.getPath(), 1L, 1,
                segment -> assertEquals(2, segment), "portrait", 0, VideoCodec.AVC, null, null);
        ReflectionHelpers.setField(pipeline, "isRecording", true);
        ReflectionHelpers.callInstanceMethod(pipeline, "rolloverSegment");
        assertArrayEquals(expected, Files.readAllBytes(first.toPath()));
        assertNull(ReflectionHelpers.getField(pipeline, "outputTarget"));
        assertFalse(pipeline.setNextOutput(new GLRecordingPipeline.OutputTarget(
                first.getPath(), null, null, 1)));
    }

    @Test public void stopRequestedAfterPreparingNextTargetDoesNotOpenIt() throws Exception {
        File first = folder.newFile("segment-a.mp4");
        File next = new File(folder.getRoot(), "segment-b.mp4");
        byte[] expected = {1, 3, 5, 7};
        Files.write(first.toPath(), expected);
        java.util.concurrent.atomic.AtomicReference<GLRecordingPipeline> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        GLRecordingPipeline pipeline = new GLRecordingPipeline(RuntimeEnvironment.getApplication(),
                null, 640, 480, 30, first.getPath(), 1L, 1, segment -> {
                    assertTrue(ref.get().setNextOutput(new GLRecordingPipeline.OutputTarget(
                            next.getPath(), null, null, segment)));
                    ref.get().requestOutputStop();
                    assertFalse(ref.get().setNextOutput(new GLRecordingPipeline.OutputTarget(
                            next.getPath(), null, null, segment)));
                }, "portrait", 0, VideoCodec.AVC, null, null);
        ref.set(pipeline);
        ReflectionHelpers.setField(pipeline, "isRecording", true);
        ReflectionHelpers.callInstanceMethod(pipeline, "rolloverSegment");
        assertFalse(next.exists());
        assertArrayEquals(expected, Files.readAllBytes(first.toPath()));
        assertNull(ReflectionHelpers.getField(pipeline, "recordingFailure"));
    }

    @Test public void startIntentCannotClaimSessionWhileStoppingAfterCameraCooldownClears() {
        RecordingService service = Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service, "isStopping", true);
        service.onStartCommand(new Intent(Constants.INTENT_ACTION_START_RECORDING), 0, 1);
        assertTrue(ReflectionHelpers.<Boolean>getField(service, "isStopping"));
        assertEquals(RecordingState.NONE, ReflectionHelpers.getField(service, "recordingState"));
        assertNull(ReflectionHelpers.getField(service, "glRecordingPipeline"));
        assertNull(ReflectionHelpers.getField(service, "cameraDevice"));
    }

    @Test public void servicePreviewReleaseClosesActualUnstartedWriterAndDescriptor() throws Exception {
        File target = folder.newFile("owned-preview-test.mp4");
        com.fadcam.media.FragmentedMp4MuxerWrapper wrapper =
                new com.fadcam.media.FragmentedMp4MuxerWrapper(target.getPath());
        Object muxer = ReflectionHelpers.getField(wrapper, "muxer");
        Object mediaWriter = ReflectionHelpers.getField(muxer, "fragmentedMp4Writer");
        Thread writer = ReflectionHelpers.getField(mediaWriter, "writerThread");
        java.io.FileOutputStream stream = ReflectionHelpers.getField(wrapper, "fileOutputStream");
        java.io.FileDescriptor descriptor = stream.getFD();
        GLRecordingPipeline pipeline = new GLRecordingPipeline(RuntimeEnvironment.getApplication(),
                null, 640, 480, 30, target.getPath(), 0L, 1,
                null, "portrait", 0, VideoCodec.AVC, null, null);
        ReflectionHelpers.setField(pipeline, "previewOnlyRendering", true);
        ReflectionHelpers.setField(pipeline, "mediaMuxer", wrapper);
        RecordingService service = Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service, "glRecordingPipeline", pipeline);
        try {
            assertTrue(writer.isAlive());
            assertTrue(descriptor.valid());
            java.util.concurrent.CountDownLatch cleanup = new java.util.concurrent.CountDownLatch(1);
            android.os.Handler completionHandler = mock(android.os.Handler.class);
            when(completionHandler.post(any(Runnable.class))).thenAnswer(call -> {
                call.getArgument(0, Runnable.class).run();
                return true;
            });
            ReflectionHelpers.setField(service, "mainHandler", completionHandler);
            ReflectionHelpers.callInstanceMethod(service, "releasePreviewOnlyGlPipeline",
                    ReflectionHelpers.ClassParameter.from(Runnable.class, cleanup::countDown));
            assertTrue("preview cleanup completion", cleanup.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(writer.isAlive());
            assertFalse(descriptor.valid());
            assertNull(ReflectionHelpers.getField(service, "glRecordingPipeline"));
        } finally {
            wrapper.release();
        }
    }

    @Test public void dualPausedCaptureIntentDoesNotReachSnapshotAndShowsPausedHint() {
        com.fadcam.dualcam.service.DualCameraRecordingService service =
                Robolectric.buildService(com.fadcam.dualcam.service.DualCameraRecordingService.class).get();
        GLRecordingPipeline pipeline = mock(GLRecordingPipeline.class);
        ReflectionHelpers.setField(service, "recordingPipeline", pipeline);
        ReflectionHelpers.setField(service, "state", com.fadcam.dualcam.DualCameraState.PAUSED);
        service.onStartCommand(new Intent(Constants.INTENT_ACTION_CAPTURE_PHOTO), 0, 1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verifyNoInteractions(pipeline);
        assertEquals(com.fadcam.dualcam.DualCameraState.PAUSED,
                ReflectionHelpers.getField(service, "state"));
        assertEquals(service.getString(com.fadcam.R.string.photo_capture_paused),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test public void blackFrameIsCoalescedOnGlThreadAndNeverDequeuesOnCaller() {
        GLWatermarkRenderer renderer = mock(GLWatermarkRenderer.class);
        MediaCodec codec = mock(MediaCodec.class);
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        GLRecordingPipeline pipeline = new GLRecordingPipeline(RuntimeEnvironment.getApplication(),
                null, 640, 480, 30, "unused-black-frame.mp4", 1L, 1,
                null, "portrait", 0, VideoCodec.AVC, null, null);
        ReflectionHelpers.setField(pipeline, "handler", new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(pipeline, "glRenderer", renderer);
        ReflectionHelpers.setField(pipeline, "videoEncoder", codec);
        ReflectionHelpers.setField(pipeline, "encoderInputSurface", surface);
        ReflectionHelpers.setField(pipeline, "isRecording", true);
        pipeline.renderBlackFrame();
        pipeline.renderBlackFrame();
        verifyNoInteractions(renderer, codec);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verify(renderer).renderBlackFrame();
        verifyNoInteractions(codec);
        pipeline.renderBlackFrame();
        ReflectionHelpers.setField(pipeline, "released", true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        verifyNoMoreInteractions(renderer);
    }

    @Test public void completionWaitsForActualDrainOwnerExit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        Thread owner = new Thread(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread closer = new Thread(() -> {
            GLRecordingPipeline.awaitDrainOwner(owner);
            completed.countDown();
        });
        owner.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            closer.start();
            assertFalse(completed.await(600, TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            owner.join(3000);
            closer.join(3000);
        }
        assertEquals(0L, completed.getCount());
    }
}
