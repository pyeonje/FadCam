package com.fadcam.dualcam.service;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.DualCameraCapability;
import com.fadcam.dualcam.DualCameraConfig;
import com.fadcam.dualcam.DualCameraState;
import com.fadcam.opengl.GLRecordingPipeline;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DualCameraFollowupTest {
    private DualCameraRecordingService service;
    private SharedPreferencesManager prefs;
    private CameraManager cameras;
    private DualCameraCapability capability;
    private Handler callbacks;

    @Before public void setup() {
        Application app=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
        service=Robolectric.buildService(DualCameraRecordingService.class).get();
        prefs=SharedPreferencesManager.getInstance(app);
        cameras=mock(CameraManager.class);
        capability=mock(DualCameraCapability.class);
        callbacks=new Handler(Looper.getMainLooper());
        ReflectionHelpers.setField(service,"prefs",prefs);
        ReflectionHelpers.setField(service,"cameraManager",cameras);
        ReflectionHelpers.setField(service,"capability",capability);
        ReflectionHelpers.setField(service,"backgroundHandler",callbacks);
        when(capability.isSupported()).thenReturn(true);
        when(capability.getConcurrentBackCameraId()).thenReturn("back");
        when(capability.getConcurrentFrontCameraId()).thenReturn("front");
    }

    private void start() {
        service.onStartCommand(new Intent(Constants.INTENT_ACTION_START_DUAL_RECORDING),0,1);
    }

    @Test public void silentDualStartsWithoutMicrophoneAndUsesCameraOnlyForegroundType() throws Exception {
        prefs.setRecordAudioEnabled(false);
        start();
        verify(cameras).openCamera(anyString(),any(CameraDevice.StateCallback.class),same(callbacks));
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,service.getForegroundServiceType());
    }

    @Test public void audibleDualWithoutMicrophoneIsRejectedBeforeOpeningCamera() throws Exception {
        prefs.setRecordAudioEnabled(true);
        start();
        verify(cameras,never()).openCamera(anyString(),any(CameraDevice.StateCallback.class),any(Handler.class));
    }

    @Test public void unsupportedDeviceDoesNotOpenAnyCameraOrPromoteTestFallback() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        prefs.setRecordAudioEnabled(true);
        when(capability.isSupported()).thenReturn(false);
        CameraCharacteristics back=mock(CameraCharacteristics.class);
        when(cameras.getCameraIdList()).thenReturn(new String[]{"fallback-back"});
        when(cameras.getCameraCharacteristics("fallback-back")).thenReturn(back);
        when(back.get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_BACK);
        start();
        verify(cameras,never()).openCamera(anyString(),any(CameraDevice.StateCallback.class),any(Handler.class));
        assertEquals(DualCameraState.DISABLED,ReflectionHelpers.getField(service,"state"));
    }

    @Test public void delayedBothCameraClosedCallbacksKeepPipelineAndOutputAlive() throws Exception {
        DualCameraConfig config=mock(DualCameraConfig.class);
        when(config.getPrimaryCamera()).thenReturn(DualCameraConfig.PrimaryCamera.BACK);
        ReflectionHelpers.setField(service,"config",config);
        ReflectionHelpers.setField(service,"frontCameraId","front");
        ReflectionHelpers.setField(service,"backCameraId","back");
        ReflectionHelpers.setField(service,"state",DualCameraState.INITIALIZING);
        ReflectionHelpers.callInstanceMethod(service,"openBothCameras");
        CameraDevice primary=mock(CameraDevice.class),secondary=mock(CameraDevice.class);
        ReflectionHelpers.setField(service,"primaryCameraDevice",primary);
        ReflectionHelpers.callInstanceMethod(service,"openSecondaryCamera",ClassParameter.from(String.class,"front"));
        ReflectionHelpers.setField(service,"secondaryCameraDevice",secondary);

        ArgumentCaptor<CameraDevice.StateCallback> primaryCallback=ArgumentCaptor.forClass(CameraDevice.StateCallback.class);
        ArgumentCaptor<CameraDevice.StateCallback> secondaryCallback=ArgumentCaptor.forClass(CameraDevice.StateCallback.class);
        verify(cameras).openCamera(eq("back"),primaryCallback.capture(),same(callbacks));
        verify(cameras).openCamera(eq("front"),secondaryCallback.capture(),same(callbacks));
        CameraCaptureSession primarySession=mock(CameraCaptureSession.class),secondarySession=mock(CameraCaptureSession.class);
        GLRecordingPipeline pipeline=mock(GLRecordingPipeline.class);
        android.os.ParcelFileDescriptor output=mock(android.os.ParcelFileDescriptor.class);
        ReflectionHelpers.setField(service,"primarySession",primarySession);
        ReflectionHelpers.setField(service,"secondarySession",secondarySession);
        ReflectionHelpers.setField(service,"recordingPipeline",pipeline);
        ReflectionHelpers.setField(service,"safRecordingPfd",output);

        HandlerThread callbackThread=mock(HandlerThread.class);
        ReflectionHelpers.setField(service,"backgroundThread",callbackThread);
        CountDownLatch closing=new CountDownLatch(2), finalizing=new CountDownLatch(1), quit=new CountDownLatch(1);
        when(callbackThread.quitSafely()).thenAnswer(call->{quit.countDown();return true;});
        doAnswer(call->{closing.countDown();return null;}).when(primary).close();
        doAnswer(call->{closing.countDown();return null;}).when(secondary).close();
        doAnswer(call->{finalizing.countDown();return null;}).when(output).close();
        try {
            ReflectionHelpers.callInstanceMethod(service,"releasePipelineAndCamerasAsync",ClassParameter.from(Consumer.class,null));
            assertTrue("both camera closes should be requested off the UI thread",closing.await(3,TimeUnit.SECONDS));
            verify(primarySession).close();verify(secondarySession).close();
            service.onDestroy();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
            verify(callbackThread,never()).quitSafely();
            verify(pipeline,never()).stopRecording();
            verify(output,never()).close();
            primaryCallback.getValue().onClosed(primary);
            verify(pipeline,never()).stopRecording();
            secondaryCallback.getValue().onClosed(secondary);
            assertTrue("output finalizes after both cameras close",finalizing.await(3,TimeUnit.SECONDS));
            assertTrue("callback worker is stopped only after finalization",quit.await(3,TimeUnit.SECONDS));
            org.mockito.InOrder order=inOrder(pipeline,output);
            order.verify(pipeline).stopRecording();
            order.verify(output).close();
        } finally {
            primaryCallback.getValue().onClosed(primary);
            secondaryCallback.getValue().onClosed(secondary);
        }
    }

    @Test public void rejectedPhotoSaveHandlerRecyclesBitmapAndNotifiesFailure() {
        GLRecordingPipeline pipeline=mock(GLRecordingPipeline.class);
        Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        doAnswer(call->{
            ((GLRecordingPipeline.PhotoCaptureCallback)call.getArgument(0)).onCaptured(bitmap);
            return null;
        }).when(pipeline).capturePhotoFrame(any(GLRecordingPipeline.PhotoCaptureCallback.class));
        Handler rejected=mock(Handler.class);
        when(rejected.post(any(Runnable.class))).thenReturn(false);
        ReflectionHelpers.setField(service,"recordingPipeline",pipeline);
        ReflectionHelpers.setField(service,"state",DualCameraState.RECORDING);
        ReflectionHelpers.setField(service,"backgroundHandler",rejected);
        service.onStartCommand(new Intent(Constants.INTENT_ACTION_CAPTURE_PHOTO),0,1);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(bitmap.isRecycled());
        assertEquals(service.getString(R.string.photo_capture_failed),ShadowToast.getTextOfLatestToast());
    }
    @Test public void singleCameraRejectedPhotoSaveAlsoRecyclesAndNotifies() {
        com.fadcam.services.RecordingService single=
                Robolectric.buildService(com.fadcam.services.RecordingService.class).get();
        GLRecordingPipeline pipeline=mock(GLRecordingPipeline.class);
        Bitmap bitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
        doAnswer(call->{
            ((GLRecordingPipeline.PhotoCaptureCallback)call.getArgument(0)).onCaptured(bitmap);
            return null;
        }).when(pipeline).capturePhotoFrame(any(GLRecordingPipeline.PhotoCaptureCallback.class));
        Handler rejected=mock(Handler.class);
        when(rejected.post(any(Runnable.class))).thenReturn(false);
        ReflectionHelpers.setField(single,"glRecordingPipeline",pipeline);
        ReflectionHelpers.setField(single,"recordingState",com.fadcam.RecordingState.IN_PROGRESS);
        ReflectionHelpers.setField(single,"backgroundHandler",rejected);
        ReflectionHelpers.callInstanceMethod(single,"capturePhotoFromRecording");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(bitmap.isRecycled());
        assertEquals(single.getString(R.string.photo_capture_failed),ShadowToast.getTextOfLatestToast());
    }
}
