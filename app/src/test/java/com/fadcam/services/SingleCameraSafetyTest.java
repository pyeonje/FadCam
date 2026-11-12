package com.fadcam.services;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraDevice;
import android.os.Handler;
import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.opengl.GLRecordingPipeline;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SingleCameraSafetyTest {
    private final Application app = RuntimeEnvironment.getApplication();
    private final RecordingService service = Robolectric.buildService(RecordingService.class).get();
    private final GLRecordingPipeline pipeline = mock(GLRecordingPipeline.class);
    private final SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(app);
    private final Handler main = mock(Handler.class);
    private final LinkedBlockingQueue<Runnable> mainWork = new LinkedBlockingQueue<>();

    private void setup() {
        ReflectionHelpers.setField(service, "sharedPreferencesManager", prefs);
        ReflectionHelpers.setField(service, "glRecordingPipeline", pipeline);
        ReflectionHelpers.setField(service, "mainHandler", main);
        when(main.post(any(Runnable.class))).thenAnswer(call -> mainWork.add(call.getArgument(0)));
    }

    @Test public void previewStopCannotReleasePipelineUntilOwningCameraAcknowledges() throws Exception {
        setup();
        CameraDevice camera = mock(CameraDevice.class);
        ReflectionHelpers.setField(service, "cameraDevice", camera);
        ReflectionHelpers.setField(service, "previewOnlyActive", true);
        try {
            ReflectionHelpers.callInstanceMethod(service, "stopPreviewOnlyMode");
            verify(camera).close();
            verify(pipeline, never()).stopRecording();
            org.mockito.ArgumentCaptor<Runnable> timeout = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(main).postDelayed(timeout.capture(), eq(2_000L));
            timeout.getValue().run(); // Timer elapsed with no callback; must still retain old GL.
            verify(pipeline, never()).stopRecording();
        } finally {
            callback().onClosed(camera);
        }
        verify(pipeline, timeout(1500).times(1)).stopRecording();
        Runnable cleanup = mainWork.poll(2, TimeUnit.SECONDS);
        if (cleanup != null) cleanup.run();
        callback().onClosed(camera);
        verify(camera, times(1)).close();
        verify(pipeline, times(1)).stopRecording();
    }

    @Test public void lateOldCameraClosedDoesNotClearActiveCameraOrPendingStart() {
        setup();
        CameraDevice stale = mock(CameraDevice.class);
        CameraDevice active = mock(CameraDevice.class);
        ReflectionHelpers.setField(service, "cameraDevice", active);
        ReflectionHelpers.setField(service, "isCameraOpen", true);
        ReflectionHelpers.setField(service, "pendingStartRecording", true);
        callback().onError(stale, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
        callback().onClosed(stale);
        verify(active, never()).close();
        assertSame(active, ReflectionHelpers.getField(service, "cameraDevice"));
        assertTrue(ReflectionHelpers.<Boolean>getField(service, "isCameraOpen"));
        assertTrue(ReflectionHelpers.<Boolean>getField(service, "pendingStartRecording"));
    }

    @Test public void recordingStopTimeoutReportsFailureAndDoesNotReleaseGlBeforeAcknowledgement() throws Exception {
        setup();
        CameraDevice camera = mock(CameraDevice.class);
        ReflectionHelpers.setField(service, "cameraDevice", camera);
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        ReflectionHelpers.setField(service, "recordingStartTime", 1L);
        boolean previous = ReflectionHelpers.getStaticField(RecordingService.class, "isCameraResourceReleasing");
        try {
            ReflectionHelpers.callInstanceMethod(service, "stopRecording");
            Runnable publication = mainWork.poll(5, TimeUnit.SECONDS);
            assertNotNull("bounded failure notification", publication);
            publication.run();
            verify(pipeline, never()).stopRecording();
            assertFalse(result().getBooleanExtra(Constants.EXTRA_RECORDING_SUCCESS, true));
            service.onStartCommand(new Intent(Constants.INTENT_ACTION_STOP_RECORDING), 0, 6);
            assertSame(service, shutdownOwner().get());
            service.onStartCommand(new Intent(Constants.INTENT_ACTION_START_RECORDING), 0, 7);
            assertTrue(ReflectionHelpers.<Boolean>getField(service, "isStopping"));
            verify(camera, times(1)).close();
        } finally {
            callback().onClosed(camera);
            Runnable cleanup = mainWork.poll(2, TimeUnit.SECONDS);
            if (cleanup != null) cleanup.run();
            ReflectionHelpers.setStaticField(RecordingService.class, "isCameraResourceReleasing", previous);
        }
        verify(pipeline, timeout(1500).times(1)).stopRecording();
    }

    @Test public void shutterOwnsFrontClassificationWhenPreferencesChangeBeforeStorage() {
        assertShotClassification(CameraType.FRONT, CameraType.BACK, "Selfie");
    }

    @Test public void shutterOwnsBackClassificationWhenPreferencesChangeBeforeStorage() {
        assertShotClassification(CameraType.BACK, CameraType.FRONT, "Back");
    }

    private void assertShotClassification(CameraType atShutter, CameraType atSave, String expected) {
        setup();
        Handler storage = mock(Handler.class);
        List<Runnable> queued = new ArrayList<>();
        when(storage.post(any(Runnable.class))).thenAnswer(call -> queued.add(call.getArgument(0)));
        ReflectionHelpers.setField(service, "backgroundHandler", storage);
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        select(atShutter);
        prefs.sharedPreferences.edit().putString(Constants.PREF_WATERMARK_OPTION, "no_watermark").commit();
        ReflectionHelpers.callInstanceMethod(service, "capturePhotoFromRecording");
        org.mockito.ArgumentCaptor<GLRecordingPipeline.PhotoCaptureCallback> frames =
                org.mockito.ArgumentCaptor.forClass(GLRecordingPipeline.PhotoCaptureCallback.class);
        verify(pipeline).capturePhotoFrame(frames.capture());
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.GREEN);
        frames.getValue().onCaptured(bitmap);
        assertEquals(1, queued.size());
        select(atSave);
        ReflectionHelpers.setField(service, "recordingState", RecordingState.NONE);
        ReflectionHelpers.setField(service, "previewOnlyActive", true);
        queued.get(0).run();
        assertTrue(bitmap.isRecycled());
        Intent saved = result();
        assertTrue(saved.getBooleanExtra(Constants.EXTRA_RECORDING_SUCCESS, false));
        String name = android.net.Uri.parse(saved.getStringExtra(Constants.EXTRA_RECORDING_URI_STRING)).getLastPathSegment();
        assertNotNull(name);
        assertTrue(name, name.contains("_" + expected + "_"));
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(
                android.net.Uri.parse(saved.getStringExtra(Constants.EXTRA_RECORDING_URI_STRING)).getPath(), options);
        assertEquals("a recording photo is not cropped as the next preview session", 16, options.outWidth);
        assertEquals(16, options.outHeight);
    }

    @Test public void delayedStorageUsesTheShuttersWatermarkOption() {
        setup();
        Handler storage = mock(Handler.class);
        List<Runnable> queued = new ArrayList<>();
        when(storage.post(any(Runnable.class))).thenAnswer(call -> queued.add(call.getArgument(0)));
        ReflectionHelpers.setField(service, "backgroundHandler", storage);
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        prefs.sharedPreferences.edit().putString(Constants.PREF_WATERMARK_OPTION, "timestamp").commit();
        ReflectionHelpers.callInstanceMethod(service, "capturePhotoFromRecording");
        org.mockito.ArgumentCaptor<GLRecordingPipeline.PhotoCaptureCallback> frames =
                org.mockito.ArgumentCaptor.forClass(GLRecordingPipeline.PhotoCaptureCallback.class);
        verify(pipeline).capturePhotoFrame(frames.capture());
        Bitmap bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.BLACK);
        bitmap.setPixel(5, 5, android.graphics.Color.DKGRAY);
        frames.getValue().onCaptured(bitmap);
        assertEquals(1, queued.size());
        prefs.sharedPreferences.edit().putString(Constants.PREF_WATERMARK_OPTION, "no_watermark").commit();
        queued.get(0).run();
        assertTrue(bitmap.isRecycled());
        Intent saved = result();
        assertNotNull(saved);
        Bitmap decoded = android.graphics.BitmapFactory.decodeFile(
                android.net.Uri.parse(saved.getStringExtra(Constants.EXTRA_RECORDING_URI_STRING)).getPath());
        assertNotNull(decoded);
        try {
            boolean textPixel = false;
            for (int y = 20; y < 90 && !textPixel; y++) {
                for (int x = 20; x < decoded.getWidth(); x++) {
                    if (android.graphics.Color.red(decoded.getPixel(x, y)) > 155) {
                        textPixel = true;
                        break;
                    }
                }
            }
            assertTrue("timestamp drawn from shutter configuration despite disabled setting at save", textPixel);
        } finally {
            decoded.recycle();
        }
    }

    @Test public void latePhotoCallbackFromReplacedPipelineRecyclesWithoutPublishing() {
        setup();
        Handler storage = mock(Handler.class);
        when(storage.post(any(Runnable.class))).thenAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return true;
        });
        ReflectionHelpers.setField(service, "backgroundHandler", storage);
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        ReflectionHelpers.callInstanceMethod(service, "capturePhotoFromRecording");
        org.mockito.ArgumentCaptor<GLRecordingPipeline.PhotoCaptureCallback> frames =
                org.mockito.ArgumentCaptor.forClass(GLRecordingPipeline.PhotoCaptureCallback.class);
        verify(pipeline).capturePhotoFrame(frames.capture());
        ReflectionHelpers.setField(service, "glRecordingPipeline", mock(GLRecordingPipeline.class));
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        frames.getValue().onCaptured(bitmap);
        assertTrue(bitmap.isRecycled());
        verify(storage, never()).post(any(Runnable.class));
        assertNull(result());
    }

    private void select(CameraType camera) {
        prefs.sharedPreferences.edit().putString(Constants.PREF_CAMERA_SELECTION, camera.toString()).commit();
    }

    private CameraDevice.StateCallback callback() {
        return ReflectionHelpers.getField(service, "cameraStateCallback");
    }

    private Intent result() {
        return Shadows.shadowOf(app).getBroadcastIntents().stream()
                .filter(i -> Constants.ACTION_RECORDING_COMPLETE.equals(i.getAction())).findFirst().orElse(null);
    }
    @Test public void idleStopIntentNeverClaimsCameraShutdown() {
        setup();
        var owner = shutdownOwner();
        RecordingService previousOwner = owner.get();
        try {
            ReflectionHelpers.setField(service, "glRecordingPipeline", null);
            service.onStartCommand(new Intent(Constants.INTENT_ACTION_STOP_RECORDING), 0, 1);
            assertFalse(ReflectionHelpers.<Boolean>getField(service, "cameraGlShutdownPending"));
            assertFalse(ReflectionHelpers.<Boolean>getField(service, "isStopping"));
            assertSame(previousOwner, owner.get());
        } finally {
            owner.compareAndSet(service, previousOwner);
        }
    }

    @Test public void duplicateIdleStopIntentsDoNotRetireLivePreviewOrAdvanceCameraGeneration() {
        setup();
        var owner = shutdownOwner();
        RecordingService previousOwner = owner.get();
        CameraDevice previewCamera = mock(CameraDevice.class);
        ReflectionHelpers.setField(service, "cameraDevice", previewCamera);
        ReflectionHelpers.setField(service, "previewOnlyActive", true);
        java.util.concurrent.atomic.AtomicLong generation = ReflectionHelpers.getField(service, "cameraOpenGeneration");
        long before = generation.get();
        try {
            service.onStartCommand(new Intent(Constants.INTENT_ACTION_STOP_RECORDING), 0, 1);
            service.onStartCommand(new Intent(Constants.INTENT_ACTION_STOP_RECORDING), 0, 2);
            verify(pipeline, never()).requestOutputStop();
            verify(pipeline, never()).stopRecording();
            verify(previewCamera, never()).close();
            assertEquals(before, generation.get());
            assertFalse(ReflectionHelpers.<Boolean>getField(service, "cameraGlShutdownPending"));
            assertSame(previousOwner, owner.get());
        } finally {
            owner.compareAndSet(service, previousOwner);
        }
    }

    @Test public void idleStopOnAnotherInstanceDoesNotStealOrStrandPendingCameraShutdown() throws Exception {
        setup();
        var owner = shutdownOwner();
        RecordingService previousOwner = owner.get();
        CameraDevice camera = mock(CameraDevice.class);
        ReflectionHelpers.setField(service, "cameraDevice", camera);
        ReflectionHelpers.setField(service, "previewOnlyActive", true);
        RecordingService replacement = Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(replacement, "sharedPreferencesManager", prefs);
        try {
            ReflectionHelpers.callInstanceMethod(service, "stopPreviewOnlyMode");
            assertSame(service, owner.get());
            replacement.onStartCommand(new Intent(Constants.INTENT_ACTION_STOP_RECORDING), 0, 1);
            assertSame(service, owner.get());
            assertFalse(ReflectionHelpers.<Boolean>getField(replacement, "cameraGlShutdownPending"));
            verify(pipeline, never()).stopRecording();
            var watermark = mock(com.fadcam.watermark.WatermarkManager.class);
            RuntimeException reachedRecordingSetup = new RuntimeException("recording setup gate reached");
            doThrow(reachedRecordingSetup).when(watermark).initialize(any());
            ReflectionHelpers.setField(replacement, "watermarkManager", watermark);
            replacement.onStartCommand(new Intent(Constants.INTENT_ACTION_START_RECORDING), 0, 2);
            verifyNoInteractions(watermark);
            callback().onClosed(camera);
            Runnable cleanup = mainWork.poll(2, TimeUnit.SECONDS);
            assertNotNull(cleanup);
            cleanup.run();
            assertNull(owner.get());
            verify(pipeline).stopRecording();
            verify(camera).close();
            assertFalse(ReflectionHelpers.<Boolean>callInstanceMethod(replacement, "anotherCameraShutdownIsPending"));
            // Stop at the first setup operation: this proves the actual START_RECORDING
            // intent passed the teardown guard without opening a device.
            assertSame(reachedRecordingSetup, assertThrows(RuntimeException.class,
                    () -> replacement.onStartCommand(new Intent(Constants.INTENT_ACTION_START_RECORDING), 0, 3)));
            replacement.onStartCommand(new Intent(Constants.INTENT_ACTION_START_PREVIEW_ONLY), 0, 4);
            assertTrue("the replacement accepts a preview request once the real close completes",
                    ReflectionHelpers.<Boolean>getField(replacement, "pendingPreviewOnlyStart"));
        } finally {
            callback().onClosed(camera);
            Runnable cleanup = mainWork.poll(1, TimeUnit.SECONDS);
            if (cleanup != null) cleanup.run();
            owner.compareAndSet(service, previousOwner);
            owner.compareAndSet(replacement, previousOwner);
        }
    }

    private static java.util.concurrent.atomic.AtomicReference<RecordingService> shutdownOwner() {
        return ReflectionHelpers.getStaticField(RecordingService.class, "cameraShutdownOwner");
    }

}
