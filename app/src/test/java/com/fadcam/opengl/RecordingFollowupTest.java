package com.fadcam.opengl;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.VideoCodec;
import com.fadcam.media.FragmentedMp4MuxerWrapper;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class RecordingFollowupTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    private GLRecordingPipeline pipeline() {
        return new GLRecordingPipeline(RuntimeEnvironment.getApplication(), null, 640, 480, 30,
                new File(files.getRoot(), "capture.mp4").getPath(), 0, 1,
                null, "portrait", 0, VideoCodec.AVC, null, null);
    }

    @Test public void audioBeforeAnyVideoUsesValidStartAndPcmSampleSpacing() throws Exception {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(RuntimeEnvironment.getApplication());
        boolean saved = prefs.isRecordAudioEnabled();
        prefs.setRecordAudioEnabled(false);
        GLRecordingPipeline pipeline = pipeline();
        ReflectionHelpers.setField(pipeline, "glRenderer", mock(GLWatermarkRenderer.class));
        ReflectionHelpers.setField(pipeline, "encoderInputSurface", mock(Surface.class));
        AudioRecord input = mock(AudioRecord.class);
        MediaCodec codec = mock(MediaCodec.class);
        AtomicInteger readCount = new AtomicInteger();
        CountDownLatch allowAudio = new CountDownLatch(1);
        CountDownLatch inputs = new CountDownLatch(2);
        CountDownLatch allowExit = new CountDownLatch(1);
        List<Long> timestamps = java.util.Collections.synchronizedList(new ArrayList<>());
        when(input.getRecordingState()).thenReturn(AudioRecord.RECORDSTATE_RECORDING);
        when(input.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(call -> {
            if (readCount.getAndIncrement() == 0) {
                if (!allowAudio.await(3, TimeUnit.SECONDS)) throw new AssertionError("start did not release PCM");
                return 8192;
            }
            allowExit.await(3, TimeUnit.SECONDS);
            return 0;
        });
        when(codec.dequeueInputBuffer(anyLong())).thenReturn(0);
        when(codec.getInputBuffer(0)).thenReturn(ByteBuffer.allocate(4096));
        doAnswer(call -> {
            if ((int) call.getArgument(4) == 0) {
                timestamps.add(call.getArgument(3, Long.class));
                inputs.countDown();
            }
            return null;
        }).when(codec).queueInputBuffer(anyInt(), anyInt(), anyInt(), anyLong(), anyInt());
        try {
            pipeline.startRecording();
            ReflectionHelpers.setField(pipeline, "audioRecordingEnabled", true);
            ReflectionHelpers.setField(pipeline, "audioRecord", input);
            ReflectionHelpers.setField(pipeline, "audioEncoder", codec);
            ReflectionHelpers.setField(pipeline, "audioSampleRate", 48000);
            ReflectionHelpers.callInstanceMethod(pipeline, "startAudioThread");
            allowAudio.countDown();
            assertTrue(inputs.await(3, TimeUnit.SECONDS));
            assertTrue("first PCM must use the initialized common origin", timestamps.get(0) < 5_000_000L);
            assertEquals(21_333L, timestamps.get(1) - timestamps.get(0).longValue());
            long firstVideo = pipeline.getSynchronizedVideoTimestamp(0);
            assertTrue("audio and video share a bounded origin", firstVideo < 5_000_000L);
        } finally {
            ReflectionHelpers.setField(pipeline, "audioThreadRunning", false);
            allowAudio.countDown();
            allowExit.countDown();
            Thread audio = ReflectionHelpers.getField(pipeline, "audioThread");
            if (audio != null) audio.join(3000);
            ReflectionHelpers.setField(pipeline, "audioEncoder", null);
            ReflectionHelpers.setField(pipeline, "audioRecord", null);
            ReflectionHelpers.setField(pipeline, "audioRecordingEnabled", false);
            prefs.setRecordAudioEnabled(saved);
            // These clock-only fixtures intentionally never supply a video format or sample.
            IllegalStateException failure = assertThrows(IllegalStateException.class, pipeline::stopRecording);
            assertTrue(failure.getMessage().contains("영상 형식"));
        }
    }

    @Test public void commonClockSurvivesPauseCameraSwitchAndSegmentButNotASeparateSession() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(RuntimeEnvironment.getApplication());
        boolean saved = prefs.isRecordAudioEnabled();
        prefs.setRecordAudioEnabled(false);
        java.util.concurrent.atomic.AtomicReference<GLRecordingPipeline> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        GLRecordingPipeline pipeline = new GLRecordingPipeline(RuntimeEnvironment.getApplication(),
                null, 640, 480, 30, new File(files.getRoot(), "first.mp4").getPath(), 0, 1,
                segment -> assertTrue(ref.get().setNextOutput(new GLRecordingPipeline.OutputTarget(
                        new File(files.getRoot(), "second.mp4").getPath(), null, null, segment))),
                "portrait", 0, VideoCodec.AVC, null, null);
        ref.set(pipeline);
        ReflectionHelpers.setField(pipeline, "glRenderer", mock(GLWatermarkRenderer.class));
        ReflectionHelpers.setField(pipeline, "encoderInputSurface", mock(Surface.class));
        try {
            assertEquals(-1L, pipeline.getSynchronizedVideoTimestamp(0));
            pipeline.startRecording();
            long firstVideo = pipeline.getSynchronizedVideoTimestamp(0);
            long commonOrigin = ReflectionHelpers.getField(pipeline, "recordingStartSystemTimeNanos");
            assertTrue(firstVideo >= 0);
            assertTrue(commonOrigin != -1);
            pipeline.prepareCameraSwitch();
            pipeline.pauseRecording();
            assertEquals(-1L, pipeline.getSynchronizedVideoTimestamp(0));
            pipeline.resumeRecording();
            assertTrue(pipeline.getSynchronizedVideoTimestamp(0) >= firstVideo);
            ReflectionHelpers.callInstanceMethod(pipeline, "rolloverSegment");
            assertTrue(new File(files.getRoot(), "second.mp4").isFile());
            assertEquals(commonOrigin,
                    (long) ReflectionHelpers.<Long>getField(pipeline, "recordingStartSystemTimeNanos"));
            assertEquals(-1L, pipeline().getSynchronizedVideoTimestamp(0));
        } finally {
            prefs.setRecordAudioEnabled(saved);
            // These clock-only fixtures intentionally never supply a video format or sample.
            IllegalStateException failure = assertThrows(IllegalStateException.class, pipeline::stopRecording);
            assertTrue(failure.getMessage().contains("영상 형식"));
        }
    }

    @Test public void secondBufferedVideoWriteFailureIsRetained() throws Exception {
        GLRecordingPipeline pipeline = pipeline();
        FragmentedMp4MuxerWrapper muxer = mock(FragmentedMp4MuxerWrapper.class);
        IllegalStateException fault = new IllegalStateException("second video");
        doNothing().doThrow(fault).when(muxer).writeSampleData(eq(0), any(), any());
        ReflectionHelpers.setField(pipeline, "mediaMuxer", muxer);
        ReflectionHelpers.setField(pipeline, "videoTrackIndex", 0);
        List<Object> pending = ReflectionHelpers.getField(pipeline, "preMuxerVideoFrameBuffer");
        Class<?> frame = Class.forName(GLRecordingPipeline.class.getName() + "$PendingVideoFrame");
        for (int i=0; i<2; i++) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, 1, i + 1, 0);
            pending.add(ReflectionHelpers.callConstructor(frame,
                    ClassParameter.from(ByteBuffer.class, ByteBuffer.wrap(new byte[]{3})),
                    ClassParameter.from(MediaCodec.BufferInfo.class, info)));
        }
        ReflectionHelpers.callInstanceMethod(pipeline, "flushPreMuxerVideoBuffer");
        verify(muxer, times(2)).writeSampleData(eq(0), any(), any());
        assertFailure(pipeline, fault);
    }

    @Test public void secondAudioWriteFailureIsRetainedAndBothCodecBuffersAreReturned() {
        GLRecordingPipeline pipeline = pipeline();
        MediaCodec codec = mock(MediaCodec.class);
        FragmentedMp4MuxerWrapper muxer = mock(FragmentedMp4MuxerWrapper.class);
        IllegalStateException fault = new IllegalStateException("second audio");
        doNothing().doThrow(fault).when(muxer).writeSampleData(eq(0), any(), any());
        AtomicInteger index = new AtomicInteger();
        when(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo.class), anyLong())).thenAnswer(call -> {
            int i = index.getAndIncrement();
            if (i >= 2) return MediaCodec.INFO_TRY_AGAIN_LATER;
            call.getArgument(0, MediaCodec.BufferInfo.class).set(0, 1, i + 1, 0);
            return i;
        });
        when(codec.getOutputBuffer(anyInt())).thenAnswer(call -> ByteBuffer.wrap(new byte[]{5}));
        ReflectionHelpers.setField(pipeline, "audioEncoder", codec);
        ReflectionHelpers.setField(pipeline, "audioRecordingEnabled", true);
        ReflectionHelpers.setField(pipeline, "audioEncoderStarted", true);
        ReflectionHelpers.setField(pipeline, "audioTrackIndex", 0);
        ReflectionHelpers.setField(pipeline, "muxerStarted", true);
        ReflectionHelpers.setField(pipeline, "mediaMuxer", muxer);
        ReflectionHelpers.callInstanceMethod(pipeline, "drainAudioEncoderWithBudget",
                ClassParameter.from(long.class, 10_000L), ClassParameter.from(boolean.class, false));
        verify(muxer, times(2)).writeSampleData(eq(0), any(), any());
        verify(codec).releaseOutputBuffer(0, false);
        verify(codec).releaseOutputBuffer(1, false);
        assertFailure(pipeline, fault);
    }

    private static void assertFailure(GLRecordingPipeline pipeline, Throwable original) {
        Throwable failure = ReflectionHelpers.getField(pipeline, "recordingFailure");
        assertNotNull("first failure must remain available to the final save result", failure);
        assertTrue(failure == original || failure.getCause() == original);
    }

    @Test public void eosTimeoutExceptionAndSuccessAreNotInterchangeable() {
        GLRecordingPipeline timeout = pipeline();
        assertFalse(timeout.waitForEos(() -> false, "영상", 0));
        Throwable timeoutFailure = ReflectionHelpers.getField(timeout, "recordingFailure");
        assertNotNull(timeoutFailure);
        assertTrue(timeoutFailure.getMessage().contains("도착하지"));
        IllegalStateException codec = new IllegalStateException("codec dequeue");
        assertFalse(timeout.waitForEos(() -> { throw codec; }, "오디오", 0));
        assertSame(timeoutFailure, ReflectionHelpers.getField(timeout, "recordingFailure"));
        assertArrayEquals(new Throwable[]{codec}, timeoutFailure.getSuppressed());
        GLRecordingPipeline success = pipeline();
        assertTrue(success.waitForEos(() -> true, "영상", 0));
        assertNull(ReflectionHelpers.getField(success, "recordingFailure"));
    }

    @Test public void previewStopDoesNotReleaseRendererWhileItsTaskIsStillRunning() throws Exception {
        GLRecordingPipeline pipeline = pipeline();
        GLWatermarkRenderer renderer = mock(GLWatermarkRenderer.class);
        HandlerThread worker = new HandlerThread("render-fixture");
        worker.start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        doAnswer(call -> { released.countDown(); return null; }).when(renderer).release();
        new Handler(worker.getLooper()).post(() -> {
            entered.countDown();
            try { unblock.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        ReflectionHelpers.setField(pipeline, "renderThread", worker);
        ReflectionHelpers.setField(pipeline, "glRenderer", renderer);
        FutureTask<Void> stop = new FutureTask<>(() -> { pipeline.stopRecording(); return null; });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            new Thread(stop).start();
            assertFalse("release must wait beyond the former 250ms timeout", released.await(600, TimeUnit.MILLISECONDS));
        } finally {
            unblock.countDown();
            stop.get(3, TimeUnit.SECONDS);
            worker.join(3000);
        }
        assertEquals(0L, released.getCount());
        assertFalse(worker.isAlive());
    }

    @Test public void audioStopSignalHasCrossThreadVisibility() throws Exception {
        assertTrue(Modifier.isVolatile(GLRecordingPipeline.class.getDeclaredField("audioThreadRunning").getModifiers()));
    }
}
