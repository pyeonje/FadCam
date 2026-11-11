package com.fadcam.opengl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import com.fadcam.VideoCodec;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class PhotoOffscreenOwnershipTest {
    private GLRecordingPipeline preview(GLWatermarkRenderer renderer, Handler handler) {
        GLRecordingPipeline pipeline = new GLRecordingPipeline(RuntimeEnvironment.getApplication(),
                null, 640, 480, 30, "unused-preview-photo.mp4", 0, 1,
                null, "portrait", 0, VideoCodec.AVC, null, null);
        ReflectionHelpers.setField(pipeline, "previewOnlyRendering", true);
        ReflectionHelpers.setField(pipeline, "glRenderer", renderer);
        ReflectionHelpers.setField(pipeline, "handler", handler);
        return pipeline;
    }

    @Test public void repeatedPhotosInvokeOnlyOffscreenBoundaryAndDoNotClaimAVideoTimestamp() {
        GLWatermarkRenderer renderer = mock(GLWatermarkRenderer.class);
        Bitmap one = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        Bitmap two = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        one.eraseColor(Color.RED);
        two.eraseColor(Color.GREEN);
        when(renderer.capturePhotoFrameOffscreen()).thenReturn(one, two);
        GLRecordingPipeline pipeline = preview(renderer, new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(pipeline, "recordingStartSystemTimeNanos", 17L);
        List<Bitmap> calls = new ArrayList<>();
        pipeline.capturePhotoFrame(calls::add);
        pipeline.capturePhotoFrame(calls::add);
        assertTrue(calls.isEmpty());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        try {
            assertEquals(java.util.Arrays.asList(one, two), calls);
            assertEquals(17L, (long) ReflectionHelpers.<Long>getField(pipeline, "recordingStartSystemTimeNanos"));
            verify(renderer, times(2)).capturePhotoFrameOffscreen();
            verifyNoMoreInteractions(renderer);
        } finally {
            one.recycle();
            two.recycle();
        }
    }

    @Test public void stopFinishesEachQueuedPhotoOnceWithoutDrawing() {
        GLWatermarkRenderer renderer = mock(GLWatermarkRenderer.class);
        GLRecordingPipeline pipeline = preview(renderer, new Handler(Looper.getMainLooper()));
        List<Bitmap> calls = new ArrayList<>();
        pipeline.capturePhotoFrame(calls::add);
        pipeline.capturePhotoFrame(calls::add);
        pipeline.stopRecording();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(java.util.Arrays.asList(null, null), calls);
        verify(renderer, never()).capturePhotoFrameOffscreen();
    }

    @Test public void stopDuringReadbackCompletesOnceAndRecyclesTheLateBitmap() throws Exception {
        GLWatermarkRenderer renderer = mock(GLWatermarkRenderer.class);
        Handler handler = mock(Handler.class);
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finishRead = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Runnable> queued = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger posts = new java.util.concurrent.atomic.AtomicInteger();
        when(handler.post(any(Runnable.class))).thenAnswer(call -> {
            Runnable command = call.getArgument(0);
            if (posts.getAndIncrement() == 0) queued.set(command);
            else command.run(); // Only the fake release-EGL command remains.
            return true;
        });
        Bitmap late = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        late.eraseColor(Color.RED);
        when(renderer.capturePhotoFrameOffscreen()).thenAnswer(ignored -> {
            reading.countDown();
            assertTrue(finishRead.await(3, java.util.concurrent.TimeUnit.SECONDS));
            return late;
        });
        GLRecordingPipeline pipeline = preview(renderer, handler);
        List<Bitmap> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        pipeline.capturePhotoFrame(calls::add);
        java.util.concurrent.FutureTask<Void> read = new java.util.concurrent.FutureTask<>(queued.get(), null);
        Thread thread = new Thread(read);
        thread.start();
        try {
            assertTrue(reading.await(3, java.util.concurrent.TimeUnit.SECONDS));
            pipeline.stopRecording();
            assertEquals(java.util.Collections.singletonList(null), calls);
        } finally {
            finishRead.countDown();
            read.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(java.util.Collections.singletonList(null), calls);
        assertTrue(late.isRecycled());
    }

    @Test public void handlerRejectionAndReadbackFailureEachCompleteOnce() {
        GLWatermarkRenderer renderer = mock(GLWatermarkRenderer.class);
        Handler reject = mock(Handler.class);
        GLRecordingPipeline unavailable = preview(renderer, reject);
        List<Bitmap> calls = new ArrayList<>();
        unavailable.capturePhotoFrame(calls::add);
        assertEquals(java.util.Collections.singletonList(null), calls);
        verifyNoInteractions(renderer);
        when(renderer.capturePhotoFrameOffscreen()).thenThrow(new IllegalStateException("injected"));
        GLRecordingPipeline accepted = preview(renderer, new Handler(Looper.getMainLooper()));
        accepted.capturePhotoFrame(calls::add);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(java.util.Arrays.asList(null, null), calls);
        verify(renderer).capturePhotoFrameOffscreen();
        verifyNoMoreInteractions(renderer);
    }
}
