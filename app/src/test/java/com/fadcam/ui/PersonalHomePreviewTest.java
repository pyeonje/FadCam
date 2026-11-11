package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.Manifest;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.View;
import android.widget.LinearLayout;
import com.fadcam.CameraType;

import android.view.Surface;
import android.view.TextureView;
import androidx.fragment.app.FragmentActivity;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.ServiceUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class PersonalHomePreviewTest {
    public static class Host extends FragmentActivity {
        @Override public void onCreate(Bundle bundle) {
            setTheme(R.style.Base_Theme_FadCam);
            super.onCreate(bundle);
        }
    }
    private ActivityController<Host> controller;
    private PersonalHomeFragment fragment;
    private RecordingService service;
    private Context app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        com.fadcam.utils.PersonalCameraTelemetry.callback(CameraType.BACK,"0");
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class, "instance", null);
        app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(RecordingService.class, "liveInstance", null);
        ReflectionHelpers.setStaticField(DualCameraRecordingService.class, "liveInstance", null);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
        service = Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setStaticField(RecordingService.class, "liveInstance", service);
        CameraCharacteristics back = mock(CameraCharacteristics.class);
        when(back.get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_BACK);
        when(back.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)).thenReturn(new Range<>(0.6f, 4f));
        when(back.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[] {
                CaptureRequest.CONTROL_AF_MODE_OFF, CaptureRequest.CONTROL_AF_MODE_AUTO,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO});
        when(back.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(1);
        when(back.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)).thenReturn(new Range<>(-6, 6));
        when(back.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)).thenReturn(new Rational(1, 3));
        when(back.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)).thenReturn(true);
        when(back.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR});
        when(back.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AE_MODE_OFF,CaptureRequest.CONTROL_AE_MODE_ON});
        when(back.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenReturn(new Range<>(50,3200));
        when(back.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)).thenReturn(new Range<>(100_000L,1_000_000_000L));
        when(back.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(5f);
        when(back.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AWB_MODE_AUTO,CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT});
        Shadows.shadowOf((CameraManager) app.getSystemService(Context.CAMERA_SERVICE)).addCamera("0", back);
        controller = Robolectric.buildActivity(Host.class).setup();
        fragment = new PersonalHomeFragment();
        controller.get().getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commitNow();
        TextureView view = fragment.requireView().findViewById(R.id.personal_home_preview);
        view.layout(0, 0, 640, 480);
        Surface surface = mock(Surface.class);
        when(surface.isValid()).thenReturn(true);
        ReflectionHelpers.setField(fragment, "previewSurface", surface);
        ReflectionHelpers.setField(fragment, "state", RecordingState.NONE);
        drainServices();
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        ReflectionHelpers.setStaticField(RecordingService.class, "liveInstance", null);
        ReflectionHelpers.setStaticField(DualCameraRecordingService.class, "liveInstance", null);
    }

    private List<Intent> drainServices() {
        List<Intent> intents = new ArrayList<>();
        Intent next;
        while ((next = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService()) != null) {
            intents.add(next);
        }
        return intents;
    }

    private void event(String action) {
        BroadcastReceiver receiver = ReflectionHelpers.getField(fragment, "recordingReceiver");
        receiver.onReceive(app, new Intent(action));
    }

    @Test public void enlargedPreviewReattachesSurfaceDimensionsWithoutRestartingRecording() {
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        ReflectionHelpers.setField(fragment, "state", RecordingState.IN_PROGRESS);
        TextureView preview = fragment.requireView().findViewById(R.id.personal_home_preview);
        try (org.mockito.MockedStatic<ServiceUtils> services = mockStatic(ServiceUtils.class)) {
            services.when(() -> ServiceUtils.isServiceRunning(fragment.requireContext(), RecordingService.class))
                    .thenReturn(true);
            preview.layout(0, 0, 1440, 2600);
            ReflectionHelpers.callInstanceMethod(fragment, "render");
            List<Intent> first = drainServices();
            assertEquals(1, first.size());
            assertEquals(Constants.INTENT_ACTION_CHANGE_SURFACE, first.get(0).getAction());
            assertEquals(1440, first.get(0).getIntExtra("SURFACE_WIDTH", 0));
            assertEquals(2600, first.get(0).getIntExtra("SURFACE_HEIGHT", 0));
            assertEquals(fragment.getString(R.string.personal_home_stop),
                    fragment.requireView().findViewById(R.id.personal_home_record).getContentDescription());
            preview.layout(0, 0, 1080, 1920);
            ReflectionHelpers.callInstanceMethod(fragment, "syncPreview");
            ReflectionHelpers.callInstanceMethod(fragment, "syncPreview");
            List<Intent> resized = drainServices();
            assertEquals(1, resized.size());
            assertEquals(Constants.INTENT_ACTION_CHANGE_SURFACE, resized.get(0).getAction());
            assertEquals(1920, resized.get(0).getIntExtra("SURFACE_HEIGHT", 0));
            assertEquals(RecordingState.IN_PROGRESS, RecordingService.getSessionState());
        }
    }

    @Test public void idlePreviewKeepsScreenAwakePastTenMinutesWithoutStartingRecording() {
        ReflectionHelpers.callInstanceMethod(fragment, "syncPreview");
        List<Intent> starts = drainServices();
        assertEquals(1, starts.size());
        assertEquals(Constants.INTENT_ACTION_START_PREVIEW_ONLY, starts.get(0).getAction());
        assertTrue(starts.get(0).hasExtra("SURFACE"));
        assertTrue(fragment.requireView().getKeepScreenOn());
        ReflectionHelpers.setField(service, "previewOnlyActive", true);
        event(Constants.BROADCAST_ON_PREVIEW_ONLY_STARTED);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(11));
        assertTrue(fragment.requireView().getKeepScreenOn());
        assertTrue(drainServices().isEmpty()); // no timeout, repeated start, or recording command
        assertFalse(RecordingService.hasActiveSession());
        controller.pause();
        assertFalse(fragment.requireView().getKeepScreenOn());
        List<Intent> stops = drainServices();
        assertTrue(stops.stream().anyMatch(i -> Constants.INTENT_ACTION_STOP_PREVIEW_ONLY.equals(i.getAction())));
        assertFalse(stops.stream().anyMatch(i -> Constants.INTENT_ACTION_STOP_RECORDING.equals(i.getAction())));
    }

    @Test public void idlePreviewWaitsForRealRecordingCleanupBeforeReopeningCamera() {
        ReflectionHelpers.setField(service, "isStopping", true);
        ReflectionHelpers.callInstanceMethod(fragment, "syncPreview");
        assertTrue(drainServices().isEmpty());
        ReflectionHelpers.setField(service, "isStopping", false);
        ReflectionHelpers.setField(service, "isCameraResourceReleasing", true);
        ReflectionHelpers.callInstanceMethod(fragment, "syncPreview");
        assertTrue(drainServices().isEmpty());
        ReflectionHelpers.setField(service, "isCameraResourceReleasing", false);
        ReflectionHelpers.callInstanceMethod(fragment, "syncPreview");
        assertEquals(Constants.INTENT_ACTION_START_PREVIEW_ONLY, drainServices().get(0).getAction());
    }

    @Test public void leavingHomeDuringRecordingDoesNotSendARecordingStop() {
        ReflectionHelpers.callInstanceMethod(fragment, "syncPreview");
        drainServices();
        ReflectionHelpers.setField(service, "previewOnlyActive", false);
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        ReflectionHelpers.setField(fragment, "state", RecordingState.IN_PROGRESS);
        controller.pause();
        List<Intent> intents = drainServices();
        assertFalse(intents.stream().anyMatch(i -> Constants.INTENT_ACTION_STOP_RECORDING.equals(i.getAction())));
        // Exercise the real service guard: a late preview-stop must not stop this recording.
        service.onStartCommand(new Intent(app, RecordingService.class)
                .setAction(Constants.INTENT_ACTION_STOP_PREVIEW_ONLY), 0, 1);
        assertEquals(RecordingState.IN_PROGRESS, RecordingService.getSessionState());
    }

    @Test public void failedPreviewDoesNotRetryEveryTickAndRecoversOnNextForegroundEntry() {
        ReflectionHelpers.callInstanceMethod(fragment, "syncPreview");
        drainServices();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        List<Intent> timedOut = drainServices();
        assertTrue(timedOut.stream().anyMatch(i -> Constants.INTENT_ACTION_STOP_PREVIEW_ONLY.equals(i.getAction())));
        assertFalse(timedOut.stream().anyMatch(i -> Constants.INTENT_ACTION_START_PREVIEW_ONLY.equals(i.getAction())));
        controller.pause().resume();
        assertTrue(drainServices().stream().anyMatch(i -> Constants.INTENT_ACTION_START_PREVIEW_ONLY.equals(i.getAction())));
    }
    @Test public void explicitPreviewStopWaitsForStopAcknowledgementAndRealServiceTeardown() {
        readyControls();ReflectionHelpers.setField(fragment,"previewOnlyOwned",true);
        ReflectionHelpers.callInstanceMethod(fragment,"stopIdlePreview");drainServices();
        ReflectionHelpers.callInstanceMethod(fragment,"syncPreview");assertTrue(drainServices().isEmpty());
        ReflectionHelpers.setField(service,"previewOnlyActive",false);
        ReflectionHelpers.setField(service,"previewOnlyStopping",true);
        event(Constants.BROADCAST_ON_PREVIEW_ONLY_STOPPED);
        assertFalse((boolean)ReflectionHelpers.getField(fragment,"previewFailed"));
        ReflectionHelpers.callInstanceMethod(fragment,"syncPreview");assertTrue(drainServices().isEmpty());
        ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",null);
        ReflectionHelpers.callInstanceMethod(fragment,"syncPreview");
        assertEquals(Constants.INTENT_ACTION_START_PREVIEW_ONLY,drainServices().get(0).getAction());
    }

    @Test public void idleDisplayRotationKeepsCameraAndSurfaceAndSendsOnlyOnePreviewResize() {
        readyControls();ReflectionHelpers.setField(fragment,"previewOnlyOwned",true);
        TextureView texture=fragment.requireView().findViewById(R.id.personal_home_preview);
        android.view.Display display=((android.view.WindowManager)app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Shadows.shadowOf(display).setRotation(Surface.ROTATION_0);
        try(org.mockito.MockedStatic<ServiceUtils> services=mockStatic(ServiceUtils.class)) {
            services.when(()->ServiceUtils.isServiceRunning(fragment.requireContext(),RecordingService.class)).thenReturn(true);
            ReflectionHelpers.callInstanceMethod(fragment,"syncPreview");drainServices();
            Object originalSurface=ReflectionHelpers.getField(fragment,"previewSurface");
            Shadows.shadowOf(display).setRotation(Surface.ROTATION_90);
            android.content.res.Configuration configuration=new android.content.res.Configuration(app.getResources().getConfiguration());
            configuration.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            fragment.onConfigurationChanged(configuration);texture.layout(0,0,1440,640);
            ReflectionHelpers.callInstanceMethod(fragment,"syncPreview");
            ReflectionHelpers.callInstanceMethod(fragment,"syncPreview");
            List<Intent> actions=drainServices();
            assertFalse(actions.stream().anyMatch(action->Constants.INTENT_ACTION_STOP_PREVIEW_ONLY.equals(action.getAction())
                    || Constants.INTENT_ACTION_START_PREVIEW_ONLY.equals(action.getAction())));
            List<Intent> resizes=new ArrayList<>();for(Intent action:actions)if(Constants.INTENT_ACTION_CHANGE_SURFACE.equals(action.getAction()))resizes.add(action);
            assertEquals(1,resizes.size());assertFalse(resizes.get(0).hasExtra("SURFACE"));
            assertEquals(Surface.ROTATION_90,resizes.get(0).getIntExtra(RecordingService.EXTRA_PREVIEW_DISPLAY_ROTATION,-1));
            assertEquals(1440,resizes.get(0).getIntExtra("SURFACE_WIDTH",0));
            assertSame(originalSurface,ReflectionHelpers.getField(fragment,"previewSurface"));
            assertFalse((boolean)ReflectionHelpers.getField(fragment,"previewStopPending"));
            assertTrue(RecordingService.hasPreviewOnlySession());
            // Reverse-landscape has the same dimensions and still needs one orientation update.
            Shadows.shadowOf(display).setRotation(Surface.ROTATION_270);
            ReflectionHelpers.callInstanceMethod(fragment,"syncPreview");
            actions=drainServices();assertEquals(1,actions.size());
            assertFalse(actions.get(0).hasExtra("SURFACE"));
            assertEquals(Surface.ROTATION_270,actions.get(0).getIntExtra(RecordingService.EXTRA_PREVIEW_DISPLAY_ROTATION,-1));
        } finally {Shadows.shadowOf(display).setRotation(Surface.ROTATION_0);}
    }

    @Test public void rotationReusesExistingLensViewsInsteadOfRebuildingCameraControls() throws Exception {
        addS24Lenses();readyControls();
        LinearLayout lenses=fragment.requireView().findViewById(R.id.personal_zoom_presets);
        List<View> original=new ArrayList<>();for(int index=0;index<lenses.getChildCount();index++)original.add(lenses.getChildAt(index));
        assertTrue(original.size()>1);
        android.content.res.Configuration configuration=new android.content.res.Configuration(app.getResources().getConfiguration());
        configuration.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;fragment.onConfigurationChanged(configuration);
        assertEquals(original.size(),lenses.getChildCount());
        for(int index=0;index<original.size();index++)assertSame(original.get(index),lenses.getChildAt(original.size()-1-index));
        configuration.orientation=android.content.res.Configuration.ORIENTATION_PORTRAIT;fragment.onConfigurationChanged(configuration);
        for(int index=0;index<original.size();index++)assertSame(original.get(index),lenses.getChildAt(index));
    }

    @Test public void previewRotationIntentNeverChangesAnActiveRecordingOrientation() {
        com.fadcam.opengl.GLRecordingPipeline pipeline=mock(com.fadcam.opengl.GLRecordingPipeline.class);
        ReflectionHelpers.setField(service,"glRecordingPipeline",pipeline);
        Surface surface=mock(Surface.class);when(surface.isValid()).thenReturn(true);
        ReflectionHelpers.setField(service,"previewSurface",surface);
        ReflectionHelpers.setField(service,"previewOnlyActive",true);
        Intent change=new Intent().putExtra("SURFACE_WIDTH",1440).putExtra("SURFACE_HEIGHT",640)
                .putExtra(RecordingService.EXTRA_PREVIEW_DISPLAY_ROTATION,Surface.ROTATION_90);
        ReflectionHelpers.callInstanceMethod(service,"setupSurfaceTexture",ReflectionHelpers.ClassParameter.from(Intent.class,change));
        verify(pipeline).updatePreviewOnlyDisplay(Surface.ROTATION_90,1440,640);
        verify(pipeline,never()).updateSurfaceDimensions(anyInt(),anyInt());
        clearInvocations(pipeline);
        ReflectionHelpers.setField(service,"previewOnlyActive",false);
        ReflectionHelpers.setField(service,"recordingState",RecordingState.IN_PROGRESS);
        ReflectionHelpers.callInstanceMethod(service,"setupSurfaceTexture",ReflectionHelpers.ClassParameter.from(Intent.class,change));
        verify(pipeline,never()).updatePreviewOnlyDisplay(anyInt(),anyInt(),anyInt());
        verify(pipeline).updateSurfaceDimensions(1440,640);
    }
    @Test public void stoppedPreviewRejectsLateCameraOpenInsteadOfRecreatingClosedPipeline() {
        ReflectionHelpers.setField(service,"previewOnlyStopping",true);
        android.hardware.camera2.CameraDevice camera=mock(android.hardware.camera2.CameraDevice.class);
        android.hardware.camera2.CameraDevice.StateCallback callback=ReflectionHelpers.getField(service,"cameraStateCallback");
        callback.onOpened(camera);verify(camera).close();
        assertNull(ReflectionHelpers.getField(service,"cameraDevice"));
        assertFalse((boolean)ReflectionHelpers.getField(service,"isCameraOpen"));
    }
    @Test public void stateQueryCannotDestroyInFlightRecordingFinalization() {
        Intent query=new Intent(app,RecordingService.class).setAction(Constants.BROADCAST_ON_RECORDING_STATE_REQUEST);
        ReflectionHelpers.setField(service,"isStopping",true);
        service.onStartCommand(query,0,1);
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
        ReflectionHelpers.setField(service,"isStopping",false);
        ReflectionHelpers.setField(service,"isCameraResourceReleasing",true);
        service.onStartCommand(query,0,2);
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf());
        ReflectionHelpers.setField(service,"isCameraResourceReleasing",false);
        service.onStartCommand(query,0,3);
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
        assertTrue(RecordingService.isCameraCleanupInProgress());
    }
    @Test public void recordingCleanupWaitsForActualCameraCloseBeforeReleasingInputSurface() throws Exception {
        android.hardware.camera2.CameraDevice camera=mock(android.hardware.camera2.CameraDevice.class);
        ReflectionHelpers.setField(service,"cameraDevice",camera);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> completion=worker.submit(() -> ReflectionHelpers.callInstanceMethod(service,"closeCameraBeforePipelineShutdown"));
            verify(camera,timeout(1000)).close();
            assertFalse("GL shutdown must wait for the real camera callback",completion.isDone());
            android.hardware.camera2.CameraDevice.StateCallback callback=ReflectionHelpers.getField(service,"cameraStateCallback");
            callback.onClosed(camera);completion.get(1,java.util.concurrent.TimeUnit.SECONDS);
            assertNull(ReflectionHelpers.getField(service,"cameraDevice"));
        } finally {worker.shutdownNow();}
    }
    private TextureView readyControls() {
        ReflectionHelpers.setField(service, "previewOnlyActive", true);
        ReflectionHelpers.callInstanceMethod(fragment, "render");
        drainServices();
        return fragment.requireView().findViewById(R.id.personal_home_preview);
    }

    private void touch(TextureView preview, long time, int action, float... positions) {
        int[] ids=new int[positions.length/2];for(int i=0;i<ids.length;i++)ids[i]=i;
        touchIds(preview,time,action,ids,positions);
    }
    private void touchIds(TextureView preview,long time,int action,int[] ids,float... positions) {
        int count=ids.length;assertEquals(count*2,positions.length);
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++) {
            properties[i]=new MotionEvent.PointerProperties();properties[i].id=ids[i];properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i]=new MotionEvent.PointerCoords();coordinates[i].x=positions[i*2];coordinates[i].y=positions[i*2+1];coordinates[i].pressure=1;coordinates[i].size=1;
        }
        MotionEvent event=MotionEvent.obtain(0,time,action,count,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        preview.dispatchTouchEvent(event);event.recycle();
    }

    @Test public void pinchChangesCaptureZoomFlushesLastValueAndNeverFocusesOrScalesOnlyTheView() {
        TextureView preview = readyControls();
        touch(preview, 0, MotionEvent.ACTION_DOWN, 240, 240);
        touch(preview, 10, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 240, 240, 400, 240);
        touch(preview, 30, MotionEvent.ACTION_MOVE, 220, 240, 420, 240);
        touch(preview, 50, MotionEvent.ACTION_MOVE, 160, 240, 480, 240);
        touch(preview, 70, MotionEvent.ACTION_MOVE, 80, 240, 560, 240);
        touch(preview, 80, MotionEvent.ACTION_POINTER_UP | (1 << 8), 80, 240, 560, 240);
        touch(preview, 90, MotionEvent.ACTION_UP, 80, 240);
        List<Intent> intents = drainServices();
        float saved = SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK);
        assertTrue("Real multi-touch must increase capture zoom", saved > 1f);
        assertTrue(saved <= 4f);
        assertFalse(intents.isEmpty());
        Intent last = intents.get(intents.size() - 1);
        assertEquals(Constants.INTENT_ACTION_SET_ZOOM_RATIO, last.getAction());
        assertEquals(saved, last.getFloatExtra(Constants.EXTRA_ZOOM_RATIO, 0), .0001f);
        assertEquals(0f, last.getFloatExtra(Constants.EXTRA_PAN_X, 99), 0f);
        assertTrue(preview.getTransform(null).isIdentity());
        assertFalse(intents.stream().anyMatch(i -> Constants.INTENT_ACTION_TAP_TO_FOCUS.equals(i.getAction())));
        assertFalse(intents.stream().anyMatch(i -> Constants.INTENT_ACTION_START_RECORDING.equals(i.getAction())));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertTrue("Final flush removes stale queued zoom", drainServices().isEmpty());
    }

    @Test public void pinchCommitsWhenSecondFingerLiftsBeforeTheRemainingFingerFinishes() {
        TextureView preview=readyControls();
        touch(preview,0,MotionEvent.ACTION_DOWN,240,240);
        touch(preview,10,MotionEvent.ACTION_POINTER_DOWN|(1<<8),240,240,400,240);
        touch(preview,30,MotionEvent.ACTION_MOVE,200,240,440,240);
        touch(preview,50,MotionEvent.ACTION_MOVE,120,240,520,240);
        float saved=SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK);assertTrue(saved>1f);
        touch(preview,60,MotionEvent.ACTION_POINTER_UP|(1<<8),120,240,520,240);
        List<Intent> actions=drainServices();assertEquals(1,actions.size());
        assertEquals(Constants.INTENT_ACTION_SET_ZOOM_RATIO,actions.get(0).getAction());assertEquals(saved,actions.get(0).getFloatExtra(Constants.EXTRA_ZOOM_RATIO,0),.0001f);
        touch(preview,80,MotionEvent.ACTION_MOVE,150,240);touch(preview,90,MotionEvent.ACTION_UP,150,240);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertTrue(drainServices().isEmpty());
        assertEquals(View.GONE,fragment.requireView().findViewById(R.id.personal_focus_ring).getVisibility());
    }

    @Test public void stationaryTwoFingerTapDoesNotRequestZoomOrAccidentalFocus() {
        TextureView preview=readyControls();
        touch(preview,0,MotionEvent.ACTION_DOWN,240,240);
        touch(preview,10,MotionEvent.ACTION_POINTER_DOWN|(1<<8),240,240,400,240);
        touch(preview,20,MotionEvent.ACTION_POINTER_UP|(1<<8),240,240,400,240);
        touch(preview,30,MotionEvent.ACTION_UP,240,240);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertTrue(drainServices().isEmpty());
        assertEquals(1f,SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK),0f);
    }

    @Test public void pinchPointerReplacementWhilePausedPreservesRatioAndNeverBecomesTapFocus() {
        TextureView preview=readyControls();samsungState(RecordingState.PAUSED,1L,100L,0L);drainServices();
        touchIds(preview,0,MotionEvent.ACTION_DOWN,new int[]{3},240,240);
        touchIds(preview,10,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{3,8},240,240,400,240);
        touchIds(preview,30,MotionEvent.ACTION_MOVE,new int[]{3,8},200,240,440,240);
        touchIds(preview,50,MotionEvent.ACTION_MOVE,new int[]{3,8},160,240,480,240);
        touchIds(preview,60,MotionEvent.ACTION_POINTER_UP,new int[]{3,8},160,240,480,240);
        float first=SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK);assertTrue(first>1);
        touchIds(preview,70,MotionEvent.ACTION_MOVE,new int[]{8},470,240);
        assertEquals(first,SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK),0f);
        touchIds(preview,80,MotionEvent.ACTION_POINTER_DOWN|(1<<8),new int[]{8,12},470,240,270,240);
        touchIds(preview,100,MotionEvent.ACTION_MOVE,new int[]{8,12},500,240,240,240);
        touchIds(preview,120,MotionEvent.ACTION_MOVE,new int[]{8,12},550,240,190,240);
        touchIds(preview,130,MotionEvent.ACTION_POINTER_UP|(1<<8),new int[]{8,12},550,240,190,240);
        touchIds(preview,140,MotionEvent.ACTION_UP,new int[]{8},550,240);
        float last=SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK);assertTrue(last>first);assertTrue(last<=4f);
        List<Intent> actions=drainServices();assertFalse(actions.isEmpty());
        assertTrue(actions.stream().allMatch(i->Constants.INTENT_ACTION_SET_ZOOM_RATIO.equals(i.getAction())));
        assertEquals(last,actions.get(actions.size()-1).getFloatExtra(Constants.EXTRA_ZOOM_RATIO,0),.0001f);
        assertEquals(RecordingState.PAUSED,RecordingService.getSessionState());assertTrue(preview.getTransform(null).isIdentity());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertTrue(drainServices().isEmpty());
    }

    @Test public void sameLensReflowCommitsAcceptedPinchRatioBeforeCancellingGesture() {
        TextureView preview=readyControls();samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);drainServices();
        touch(preview,0,MotionEvent.ACTION_DOWN,240,240);
        touch(preview,10,MotionEvent.ACTION_POINTER_DOWN|(1<<8),240,240,400,240);
        touch(preview,30,MotionEvent.ACTION_MOVE,200,240,440,240);
        touch(preview,50,MotionEvent.ACTION_MOVE,120,240,520,240);
        float saved=SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK);assertTrue(saved>1);
        PersonalCameraControls controls=ReflectionHelpers.getField(fragment,"cameraControls");controls.cancelGesture();
        List<Intent> actions=drainServices();assertEquals(1,actions.size());assertEquals(Constants.INTENT_ACTION_SET_ZOOM_RATIO,actions.get(0).getAction());
        assertEquals(saved,actions.get(0).getFloatExtra(Constants.EXTRA_ZOOM_RATIO,0),.0001f);
        controls.reflow(true);assertEquals(saved,SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK),0f);
        assertEquals(RecordingState.IN_PROGRESS,RecordingService.getSessionState());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertTrue(drainServices().isEmpty());
    }

    @Test public void tapFocusUsesPreviewCoordinatesButCancelledAndDraggedTouchesDoNotFocus() {
        TextureView preview = readyControls();
        preview.layout(0, 0, 480, 854);
        touch(preview, 0, MotionEvent.ACTION_DOWN, 120, 640.5f);
        touch(preview, 20, MotionEvent.ACTION_UP, 120, 640.5f);
        List<Intent> intents = drainServices();
        assertEquals(1, intents.size());
        assertEquals(Constants.INTENT_ACTION_TAP_TO_FOCUS, intents.get(0).getAction());
        assertEquals(.25f, intents.get(0).getFloatExtra(Constants.EXTRA_FOCUS_X, 0), .001f);
        assertEquals(.75f, intents.get(0).getFloatExtra(Constants.EXTRA_FOCUS_Y, 0), .001f);
        assertEquals(View.VISIBLE, fragment.requireView().findViewById(R.id.personal_focus_ring).getVisibility());
        touch(preview, 100, MotionEvent.ACTION_DOWN, 160, 360);
        touch(preview, 110, MotionEvent.ACTION_CANCEL, 160, 360);
        touch(preview, 200, MotionEvent.ACTION_DOWN, 160, 360);
        touch(preview, 220, MotionEvent.ACTION_MOVE, 300, 360);
        touch(preview, 240, MotionEvent.ACTION_UP, 300, 360);
        assertTrue(drainServices().isEmpty());
    }

    @Test public void tapFocusMapsAspectFitContentAndIgnoresPreviewBorders() {
        TextureView preview=readyControls(); preview.layout(0,0,640,480);
        touch(preview,0,MotionEvent.ACTION_DOWN,80,240);
        touch(preview,20,MotionEvent.ACTION_UP,80,240);
        assertTrue("Pillarbox border is not camera content",drainServices().isEmpty());
        touch(preview,100,MotionEvent.ACTION_DOWN,320,240);
        touch(preview,120,MotionEvent.ACTION_UP,320,240);
        List<Intent> intents=drainServices(); assertEquals(1,intents.size());
        assertEquals(.5f,intents.get(0).getFloatExtra(Constants.EXTRA_FOCUS_X,0),.001f);
        assertEquals(.5f,intents.get(0).getFloatExtra(Constants.EXTRA_FOCUS_Y,0),.001f);
    }

    @Test public void idleLandscapeTapUsesDisplayedAspectRatherThanPortraitRecordingPreset() {
        TextureView preview=readyControls();preview.layout(0,0,854,480);
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        prefs.setVideoOrientation(SharedPreferencesManager.ORIENTATION_PORTRAIT);
        Shadows.shadowOf(controller.get().getWindowManager().getDefaultDisplay()).setRotation(Surface.ROTATION_90);
        touch(preview,0,MotionEvent.ACTION_DOWN,80,240);touch(preview,20,MotionEvent.ACTION_UP,80,240);
        List<Intent> intents=drainServices();assertEquals("Visible landscape content must accept focus taps",1,intents.size());
        assertEquals(80f/854,intents.get(0).getFloatExtra(Constants.EXTRA_FOCUS_X,0),.002f);
        assertEquals(.5f,intents.get(0).getFloatExtra(Constants.EXTRA_FOCUS_Y,0),.002f);
        assertEquals(SharedPreferencesManager.ORIENTATION_PORTRAIT,prefs.getVideoOrientation());
    }

    private void addS24Lenses() throws Exception {
        CameraManager manager=(CameraManager)app.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics back=manager.getCameraCharacteristics("0");
        when(back.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{6.3f});
        when(back.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)).thenReturn(new android.util.SizeF(9.792f,7.344f));
        String[] ids={"2","5","6","7"}; float[] focals={2.2f,6.3f,7.9f,18.6f}, widths={5.6f,9.792f,4.48f,5.712f};
        for(int i=0;i<4;i++) {
            CameraCharacteristics c=mock(CameraCharacteristics.class);
            when(c.get(CameraCharacteristics.LENS_FACING)).thenReturn(CameraCharacteristics.LENS_FACING_BACK);
            when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)).thenReturn(new float[]{focals[i]});
            when(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)).thenReturn(new android.util.SizeF(widths[i],widths[i]*.75f));
            when(c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)).thenReturn(new Range<>(1f,8f));
            android.hardware.camera2.params.StreamConfigurationMap map=mock(android.hardware.camera2.params.StreamConfigurationMap.class);
            when(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).thenReturn(map);
            android.util.Size current=SharedPreferencesManager.getInstance(app).getCameraResolution();
            when(map.getOutputSizes(android.graphics.SurfaceTexture.class)).thenReturn(new android.util.Size[]{new android.util.Size(Math.max(current.getWidth(),current.getHeight()),Math.min(current.getWidth(),current.getHeight()))});
            Shadows.shadowOf(manager).addCamera(ids[i],c);
        }
        when(back.getPhysicalCameraIds()).thenReturn(new java.util.LinkedHashSet<>(java.util.Arrays.asList(ids)));
    }

    @Test public void lensButtonsUseActualS24FovsAndRestoreIndependentLocalZoomWithoutStartingRecording() throws Exception {
        addS24Lenses(); readyControls();
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        prefs.setSpecificZoomRatio(CameraType.FRONT,1.25f);
        LinearLayout row=fragment.requireView().findViewById(R.id.personal_zoom_presets);
        assertEquals(4,row.getChildCount());
        assertEquals("UW",((android.widget.TextView)row.getChildAt(0)).getText().toString());
        assertEquals("ST",((android.widget.TextView)row.getChildAt(3)).getText().toString());
        assertTrue(row instanceof PersonalLensSelector);
        for(int n=0;n<row.getChildCount();n++)assertNull("Only the shared rail draws the lens highlight",row.getChildAt(n).getBackground());
        assertEquals(app.getString(R.string.personal_lens_choice,"ST","5.0×"),row.getChildAt(3).getContentDescription().toString());
        row.getChildAt(3).performClick();
        assertEquals("7",prefs.getSelectedBackCameraId());
        int selectedCount=0;for(int n=0;n<row.getChildCount();n++)if(row.getChildAt(n).isSelected())selectedCount++;
        assertEquals(1,selectedCount);assertTrue(row.getChildAt(3).isSelected());
        assertEquals(1f,prefs.getSpecificZoomRatio(CameraType.BACK),0f);
        assertEquals("5.0×",((android.widget.TextView)fragment.requireView().findViewById(R.id.personal_zoom_value)).getText().toString());
        assertEquals(1.25f,prefs.getSpecificZoomRatio(CameraType.FRONT),0f);
        List<Intent> intents=drainServices();
        assertTrue(intents.stream().anyMatch(i -> Constants.INTENT_ACTION_STOP_PREVIEW_ONLY.equals(i.getAction())));
        assertFalse(intents.stream().anyMatch(i -> Constants.INTENT_ACTION_START_RECORDING.equals(i.getAction())));
        controller.pause(); drainServices(); row.getChildAt(0).performClick();
        assertEquals("7",prefs.getSelectedBackCameraId());
        assertTrue(drainServices().isEmpty());
    }

    @Test public void physicalLensChoicesDisableUnsupportedHighSpeedWithoutChangingPreset() throws Exception {
        addS24Lenses(); readyControls();
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        prefs.setSpecificVideoFrameRate(CameraType.BACK,120);
        ReflectionHelpers.callInstanceMethod(fragment,"render"); drainServices();
        LinearLayout row=fragment.requireView().findViewById(R.id.personal_zoom_presets);
        for(int i=0;i<row.getChildCount();i++) { assertFalse(row.getChildAt(i).isEnabled()); row.getChildAt(i).performClick(); }
        assertEquals("0",prefs.getSelectedBackCameraId());
        assertEquals(120,prefs.getSpecificVideoFrameRate(CameraType.BACK));
        assertTrue(drainServices().isEmpty());
    }

    @Test public void defaultZoomUsesSelectedLensChipAndKeepsRulerAccessibleDuringRecording() throws Exception {
        addS24Lenses();readyControls();
        View root=fragment.requireView();LinearLayout lenses=root.findViewById(R.id.personal_zoom_presets);
        assertEquals(View.GONE,root.findViewById(R.id.personal_zoom_value).getVisibility());
        View selected=null;for(int i=0;i<lenses.getChildCount();i++)if(lenses.getChildAt(i).isSelected())selected=lenses.getChildAt(i);
        assertNotNull(selected);selected.performClick();
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_zoom_slider).getVisibility());
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_zoom_value).getVisibility());
        root.findViewById(R.id.personal_zoom_value).performClick();
        assertEquals(View.GONE,root.findViewById(R.id.personal_zoom_value).getVisibility());
        samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);
        selected=null;for(int i=0;i<lenses.getChildCount();i++)if(lenses.getChildAt(i).isSelected())selected=lenses.getChildAt(i);
        assertNotNull(selected);assertTrue(selected.isEnabled());selected.performClick();
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_zoom_slider).getVisibility());
        assertEquals(RecordingState.IN_PROGRESS,RecordingService.getSessionState());
    }

    private android.widget.TextView zoomAnchor(View root,int index) {
        LinearLayout anchors=root.findViewById(R.id.personal_zoom_anchors);
        return (android.widget.TextView)((android.widget.FrameLayout)anchors.getChildAt(index)).getChildAt(0);
    }

    @Test public void expandedZoomExposesSupportedStopsAndCloseRestoresExistingControls() {
        readyControls();View root=fragment.requireView();
        root.findViewById(R.id.personal_zoom_value).performClick();
        LinearLayout anchors=root.findViewById(R.id.personal_zoom_anchors);assertEquals(3,anchors.getChildCount());
        assertEquals("1",zoomAnchor(root,0).getText().toString());assertEquals("2",zoomAnchor(root,1).getText().toString());assertEquals("4",zoomAnchor(root,2).getText().toString());
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_zoom_background).getVisibility());
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_zoom_close).getVisibility());
        assertEquals(View.INVISIBLE,((View)root.findViewById(R.id.personal_pro_items).getParent()).getVisibility());
        assertEquals(View.INVISIBLE,root.findViewById(R.id.personal_camera_more).getVisibility());
        zoomAnchor(root,2).performClick();List<Intent> actions=drainServices();assertEquals(1,actions.size());
        assertEquals(Constants.INTENT_ACTION_SET_ZOOM_RATIO,actions.get(0).getAction());assertEquals(4f,actions.get(0).getFloatExtra(Constants.EXTRA_ZOOM_RATIO,0),0f);
        assertTrue(zoomAnchor(root,2).isSelected());
        root.findViewById(R.id.personal_zoom_close).performClick();
        assertEquals(View.GONE,root.findViewById(R.id.personal_zoom_slider).getVisibility());
        assertEquals(View.GONE,anchors.getVisibility());assertEquals(View.VISIBLE,root.findViewById(R.id.personal_zoom_presets_container).getVisibility());
        assertEquals(View.VISIBLE,((View)root.findViewById(R.id.personal_pro_items).getParent()).getVisibility());
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_camera_more).getVisibility());assertTrue(drainServices().isEmpty());
    }

    @Test public void expandedTeleZoomUsesLocalLimitsAndCaptureRatiosDuringRecording() throws Exception {
        addS24Lenses();readyControls();View root=fragment.requireView();
        LinearLayout lenses=root.findViewById(R.id.personal_zoom_presets);lenses.getChildAt(3).performClick();drainServices();
        assertFalse("Lens switch must wait for the actual preview lifecycle",root.findViewById(R.id.personal_zoom_value).isEnabled());
        ReflectionHelpers.setField(service,"previewOnlyActive",false);event(Constants.BROADCAST_ON_PREVIEW_ONLY_STOPPED);
        ReflectionHelpers.setField(service,"previewOnlyActive",true);event(Constants.BROADCAST_ON_PREVIEW_ONLY_STARTED);
        samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);drainServices();root.findViewById(R.id.personal_zoom_value).performClick();
        assertEquals("5",zoomAnchor(root,0).getText().toString());assertEquals("10",zoomAnchor(root,1).getText().toString());assertEquals("25",zoomAnchor(root,2).getText().toString());
        zoomAnchor(root,1).performClick();List<Intent> actions=drainServices();assertEquals(1,actions.size());
        assertEquals(Constants.INTENT_ACTION_SET_ZOOM_RATIO,actions.get(0).getAction());assertEquals(2f,actions.get(0).getFloatExtra(Constants.EXTRA_ZOOM_RATIO,0),0f);
        assertEquals("10.0×",((android.widget.TextView)root.findViewById(R.id.personal_zoom_value)).getText().toString());
        assertEquals("7",SharedPreferencesManager.getInstance(app).getSelectedBackCameraId());assertEquals(RecordingState.IN_PROGRESS,RecordingService.getSessionState());
    }

    @Test public void expandedZoomReflowsReferenceAxesAndKeepsManualPanelExclusive() {
        readyControls();View root=fragment.requireView();PersonalCameraControls controls=ReflectionHelpers.getField(fragment,"cameraControls");
        controls.reflow(true);root.findViewById(R.id.personal_zoom_value).performClick();PersonalCameraLayout g=new PersonalCameraLayout(controller.get());
        android.widget.FrameLayout.LayoutParams ticks=(android.widget.FrameLayout.LayoutParams)root.findViewById(R.id.personal_zoom_slider).getLayoutParams();
        android.widget.FrameLayout.LayoutParams value=(android.widget.FrameLayout.LayoutParams)root.findViewById(R.id.personal_zoom_value).getLayoutParams();
        android.widget.FrameLayout.LayoutParams presets=(android.widget.FrameLayout.LayoutParams)root.findViewById(R.id.personal_zoom_anchors).getLayoutParams();
        assertEquals(g.px(168),ticks.width);assertEquals(g.px(752),ticks.getMarginEnd());
        assertEquals(g.px(920),value.getMarginEnd());assertEquals(g.px(596),presets.getMarginEnd());
        assertEquals(android.view.Gravity.CENTER_VERTICAL,value.gravity&android.view.Gravity.VERTICAL_GRAVITY_MASK);
        assertEquals("4",zoomAnchor(root,0).getText().toString());
        root.findViewById(R.id.personal_zoom_close).performClick();root.findViewById(R.id.personal_pro_focus).performClick();
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_pro_panel).getVisibility());
        assertEquals(View.GONE,root.findViewById(R.id.personal_zoom_slider).getVisibility());assertEquals(View.GONE,root.findViewById(R.id.personal_zoom_close).getVisibility());
        root.findViewById(R.id.personal_pro_close).performClick();
        controls.reflow(false);root.findViewById(R.id.personal_zoom_value).performClick();
        ticks=(android.widget.FrameLayout.LayoutParams)root.findViewById(R.id.personal_zoom_slider).getLayoutParams();assertEquals(g.px(168),ticks.height);
        assertEquals(LinearLayout.HORIZONTAL,((LinearLayout)root.findViewById(R.id.personal_zoom_anchors)).getOrientation());assertEquals("1",zoomAnchor(root,0).getText().toString());
        assertTrue(drainServices().isEmpty());
    }

    @Test public void controlsDuringRecordingSendOnlyRuntimeZoomAndKeepPhysicalLensLocked() throws Exception {
        addS24Lenses(); readyControls();
        ReflectionHelpers.setField(service,"previewOnlyActive",false);
        ReflectionHelpers.setField(service,"recordingState",RecordingState.IN_PROGRESS);
        ReflectionHelpers.setField(fragment,"state",RecordingState.IN_PROGRESS);
        ReflectionHelpers.callInstanceMethod(fragment,"render"); drainServices();
        LinearLayout row=fragment.requireView().findViewById(R.id.personal_zoom_presets);
        assertFalse(row.getChildAt(2).isEnabled());
        row.getChildAt(2).performClick(); assertTrue(drainServices().isEmpty());
        android.widget.SeekBar zoom=fragment.requireView().findViewById(R.id.personal_zoom_slider);
        zoom.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60));
        List<Intent> intents=drainServices();
        assertEquals(1,intents.size()); assertEquals(Constants.INTENT_ACTION_SET_ZOOM_RATIO,intents.get(0).getAction());
        assertTrue(SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK)<=4f);
        assertEquals(RecordingState.IN_PROGRESS,RecordingService.getSessionState());
    }

    @Test public void exposureAndLockPanelDispatchExistingControlsAndSaveQuickCaptureSettings() {
        readyControls();
        fragment.requireView().findViewById(R.id.personal_camera_adjust).performClick();
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        android.widget.SeekBar slider = dialog.findViewById(R.id.personal_exposure_slider);
        assertNotNull(slider);
        slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(app);
        assertEquals(1, prefs.getSavedExposureCompensation());
        dialog.findViewById(R.id.personal_ae_lock).performClick();
        assertTrue(prefs.isAeLockedSaved());
        assertFalse(slider.isEnabled());
        dialog.findViewById(R.id.personal_af_picker).performClick();
        androidx.appcompat.app.AlertDialog focus = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
        int autoRow=-1;
        for(int row=0;row<focus.getListView().getAdapter().getCount();row++)
            if(app.getString(R.string.personal_af_auto).equals(focus.getListView().getAdapter().getItem(row).toString()))autoRow=row;
        assertTrue("Select AF by its visible label, independent of removed raw OFF row",autoRow>=0);
        focus.getListView().performItemClick(null, autoRow, autoRow);
        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO, prefs.getSavedAfMode());
        List<Intent> intents = drainServices();
        assertEquals(3, intents.size());
        assertEquals(Constants.INTENT_ACTION_SET_AF_MODE, intents.get(2).getAction());
        assertEquals(Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION, intents.get(0).getAction());
        assertEquals(1, intents.get(0).getIntExtra(Constants.EXTRA_EXPOSURE_COMPENSATION, 0));
        assertEquals(Constants.INTENT_ACTION_TOGGLE_AE_LOCK, intents.get(1).getAction());
        controller.pause();
        assertFalse(dialog.isShowing());
    }

    @Test public void upstreamZoomActuallyChangesCameraRequestForIdlePreviewAndKeepsSession() throws Exception {
        readyControls();
        CameraCharacteristics characteristics = ((CameraManager) app.getSystemService(Context.CAMERA_SERVICE))
                .getCameraCharacteristics("0");
        when(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))
                .thenReturn(new android.graphics.Rect(0, 0, 4000, 3000));
        Class metadataClass = Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        Object metadata = ReflectionHelpers.callConstructor(metadataClass);
        CaptureRequest.Builder builder = ReflectionHelpers.callConstructor(CaptureRequest.Builder.class,
                ReflectionHelpers.ClassParameter.from(metadataClass, metadata),
                ReflectionHelpers.ClassParameter.from(boolean.class, false),
                ReflectionHelpers.ClassParameter.from(int.class, -1),
                ReflectionHelpers.ClassParameter.from(String.class, "0"),
                ReflectionHelpers.ClassParameter.from(java.util.Set.class, null));
        android.hardware.camera2.CameraCaptureSession session = mock(android.hardware.camera2.CameraCaptureSession.class);
        ReflectionHelpers.setField(service, "sharedPreferencesManager", SharedPreferencesManager.getInstance(app));
        ReflectionHelpers.setField(service, "currentCameraCharacteristics", characteristics);
        ReflectionHelpers.setField(service, "captureRequestBuilder", builder);
        ReflectionHelpers.setField(service, "captureSession", session);
        ReflectionHelpers.callInstanceMethod(service, "applyZoomAndPan",
                ReflectionHelpers.ClassParameter.from(float.class, 2f),
                ReflectionHelpers.ClassParameter.from(float.class, 0f),
                ReflectionHelpers.ClassParameter.from(float.class, 0f));
        assertEquals(new android.graphics.Rect(1000, 750, 3000, 2250), builder.get(CaptureRequest.SCALER_CROP_REGION));
        assertEquals(1f, builder.get(CaptureRequest.CONTROL_ZOOM_RATIO), 0f);
        verify(session).setRepeatingRequest(any(CaptureRequest.class), isNull(), isNull());
        assertTrue(RecordingService.hasPreviewOnlySession());
        assertFalse(RecordingService.hasActiveSession());
        assertEquals(2f, SharedPreferencesManager.getInstance(app).getSpecificZoomRatio(CameraType.BACK), 0f);
        when(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)).thenReturn(0);
        ReflectionHelpers.setField(service, "cameraDevice", mock(android.hardware.camera2.CameraDevice.class));
        ReflectionHelpers.callInstanceMethod(service, "performTapToFocus",
                ReflectionHelpers.ClassParameter.from(float.class, .25f),
                ReflectionHelpers.ClassParameter.from(float.class, .75f));
        android.hardware.camera2.params.MeteringRectangle[] regions = builder.get(CaptureRequest.CONTROL_AF_REGIONS);
        assertNotNull(regions);
        assertEquals(1500, regions[0].getRect().centerX());
        assertEquals(1875, regions[0].getRect().centerY());
        assertTrue(new android.graphics.Rect(1000, 750, 3000, 2250).contains(regions[0].getRect()));
    }

    @Test public void cameraUsesSensorEvenWithSystemPortraitLockAndRestoresActivityPolicyWhenLeaving() {
        android.provider.Settings.System.putInt(app.getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 0);
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                controller.get().getRequestedOrientation());
        readyControls();
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        ReflectionHelpers.setField(fragment, "state", RecordingState.IN_PROGRESS);
        ReflectionHelpers.callInstanceMethod(fragment, "render");
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED,
                controller.get().getRequestedOrientation());
        ReflectionHelpers.setField(service, "recordingState", RecordingState.NONE);
        ReflectionHelpers.setField(fragment, "state", RecordingState.NONE);
        ReflectionHelpers.callInstanceMethod(fragment, "render");
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
                controller.get().getRequestedOrientation());
        controller.pause();
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                controller.get().getRequestedOrientation());
        assertEquals(0, android.provider.Settings.System.getInt(app.getContentResolver(),
                android.provider.Settings.System.ACCELEROMETER_ROTATION, -1));
    }

    @Test public void landscapeReflowsExistingViewAndPreservesIdlePreviewWithLandscapePreset() {
        TextureView preview = readyControls();
        ReflectionHelpers.setField(fragment, "previewOnlyOwned", true);
        android.content.res.Configuration landscape = new android.content.res.Configuration(
                app.getResources().getConfiguration());
        landscape.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        fragment.onConfigurationChanged(landscape);
        assertSame(preview, fragment.requireView().findViewById(R.id.personal_home_preview));
        assertEquals(SharedPreferencesManager.ORIENTATION_LANDSCAPE,
                SharedPreferencesManager.getInstance(app).getVideoOrientation());
        android.widget.FrameLayout.LayoutParams controls = (android.widget.FrameLayout.LayoutParams)
                fragment.requireView().findViewById(R.id.personal_home_controls).getLayoutParams();
        assertEquals(android.view.Gravity.BOTTOM|android.view.Gravity.START, controls.gravity);
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, controls.height);
        assertEquals(0, ((android.widget.FrameLayout.LayoutParams) preview.getLayoutParams()).getMarginEnd());
        List<Intent> actions = drainServices();
        assertFalse(actions.stream().anyMatch(i -> Constants.INTENT_ACTION_STOP_PREVIEW_ONLY.equals(i.getAction())
                || Constants.INTENT_ACTION_START_PREVIEW_ONLY.equals(i.getAction())));
        assertFalse(actions.stream().anyMatch(i -> Constants.INTENT_ACTION_STOP_RECORDING.equals(i.getAction())));
        assertTrue(RecordingService.hasPreviewOnlySession());
        assertTrue((boolean)ReflectionHelpers.getField(fragment,"previewOnlyOwned"));
        assertFalse((boolean)ReflectionHelpers.getField(fragment,"previewStopPending"));
    }

    @Test public void fullSensorHostStillUsesAcceptedRecordingDirectionAndRestoresGalleryPolicy() {
        readyControls();
        // The real MainActivity now starts with this manifest policy. Keep the
        // existing host fixture, but exercise its actual Fragment policy owner.
        ReflectionHelpers.setField(fragment,"previousRequestedOrientation",Integer.valueOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR));
        ReflectionHelpers.setField(service,"sessionDisplayOrientation",android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,controller.get().getRequestedOrientation());
        samsungState(RecordingState.PAUSED,1L,10L,0L);
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,controller.get().getRequestedOrientation());
        controller.pause();
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,controller.get().getRequestedOrientation());
        controller.resume();ReflectionHelpers.setField(fragment,"state",RecordingState.PAUSED);ReflectionHelpers.callInstanceMethod(fragment,"render");
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,controller.get().getRequestedOrientation());
        samsungState(RecordingState.NONE,0L,0L,0L);
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,controller.get().getRequestedOrientation());
    }

    @Test public void sessionLandscapeDirectionSurvivesBackgroundReentryAndNewFragmentWhilePaused() {
        readyControls();
        ReflectionHelpers.setField(service,"sessionDisplayOrientation",android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        ReflectionHelpers.setField(service,"recordingState",RecordingState.IN_PROGRESS);
        ReflectionHelpers.setField(fragment,"state",RecordingState.IN_PROGRESS);
        ReflectionHelpers.callInstanceMethod(fragment,"render");
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,controller.get().getRequestedOrientation());
        controller.pause().stop();
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,controller.get().getRequestedOrientation());
        android.view.Display display=((android.view.WindowManager)app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Shadows.shadowOf(display).setRotation(Surface.ROTATION_0);
        ReflectionHelpers.setField(service,"recordingState",RecordingState.PAUSED);
        controller.start().resume();
        ReflectionHelpers.setField(fragment,"state",RecordingState.PAUSED);
        ReflectionHelpers.callInstanceMethod(fragment,"render");
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,controller.get().getRequestedOrientation());
        PersonalHomeFragment replacement=new PersonalHomeFragment();
        controller.get().getSupportFragmentManager().beginTransaction().replace(android.R.id.content,replacement).commitNow();
        fragment=replacement;
        ReflectionHelpers.setField(fragment,"state",RecordingState.PAUSED);
        ReflectionHelpers.callInstanceMethod(fragment,"render");
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,controller.get().getRequestedOrientation());
        ReflectionHelpers.setField(service,"recordingState",RecordingState.NONE);
        ReflectionHelpers.setField(fragment,"state",RecordingState.NONE);
        ReflectionHelpers.callInstanceMethod(fragment,"render");
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,controller.get().getRequestedOrientation());
    }

    @Test public void foregroundStartSnapshotsDirectionBeforeServiceHasAcceptedItsIntent() {
        readyControls();
        int snapshot=RecordingService.snapshotDisplayOrientation(fragment.requireContext());
        assertNotEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,snapshot);
        drainServices();
        fragment.requireView().findViewById(R.id.personal_home_record).performClick();
        List<Intent> starts=drainServices();
        Intent start=starts.stream().filter(i->Constants.INTENT_ACTION_START_RECORDING.equals(i.getAction())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(snapshot,start.getIntExtra(RecordingService.EXTRA_SESSION_DISPLAY_ORIENTATION,99));
        assertEquals("START",ReflectionHelpers.getField(fragment,"pendingAction"));
        assertEquals(RecordingState.NONE,RecordingService.getSessionState());
        assertEquals(snapshot,controller.get().getRequestedOrientation());
    }

    @Test public void orientationChangeDuringCapturePreservesItsSavedOrientationAndRecordingSession() {
        TextureView preview = readyControls();
        ReflectionHelpers.setField(service, "previewOnlyActive", false);
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        ReflectionHelpers.setField(fragment, "state", RecordingState.IN_PROGRESS);
        String orientation = SharedPreferencesManager.getInstance(app).getVideoOrientation();
        android.content.res.Configuration landscape = new android.content.res.Configuration(
                app.getResources().getConfiguration());
        landscape.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        fragment.onConfigurationChanged(landscape);
        assertSame(preview, fragment.requireView().findViewById(R.id.personal_home_preview));
        assertEquals(orientation, SharedPreferencesManager.getInstance(app).getVideoOrientation());
        assertEquals(RecordingState.IN_PROGRESS, RecordingService.getSessionState());
        assertTrue(drainServices().isEmpty());
    }

    @Test public void focusRotationUsesRealWindowDisplayForLandscapeAndReverseLandscape() throws Exception {
        CameraCharacteristics characteristics = ((CameraManager) app.getSystemService(Context.CAMERA_SERVICE))
                .getCameraCharacteristics("0");
        when(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)).thenReturn(90);
        ReflectionHelpers.setField(service, "currentCameraCharacteristics", characteristics);
        android.view.Display display = ((android.view.WindowManager) app.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        Shadows.shadowOf(display).setRotation(android.view.Surface.ROTATION_90);
        assertEquals(0, (int) ReflectionHelpers.callInstanceMethod(service, "getDisplayToSensorRotation"));
        Shadows.shadowOf(display).setRotation(android.view.Surface.ROTATION_270);
        assertEquals(180, (int) ReflectionHelpers.callInstanceMethod(service, "getDisplayToSensorRotation"));
        Shadows.shadowOf(display).setRotation(android.view.Surface.ROTATION_0);
        assertEquals(90, (int) ReflectionHelpers.callInstanceMethod(service, "getDisplayToSensorRotation"));
    }

    @Test public void proStripSelectsOneInlineControlAndSavesRealManualPresets() {
        TextureView preview=readyControls();
        View root=fragment.requireView();
        root.findViewById(R.id.personal_pro_iso).performClick();
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_pro_panel).getVisibility());
        android.widget.SeekBar slider=root.findViewById(R.id.personal_pro_slider);
        slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        assertTrue(com.fadcam.utils.PersonalProSettings.manualExposure(prefs,CameraType.BACK));
        assertFalse(root.findViewById(R.id.personal_pro_ev).isEnabled());
        root.findViewById(R.id.personal_pro_focus).performClick();
        slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(com.fadcam.utils.PersonalProSettings.focus(prefs,CameraType.BACK)>0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        assertTrue(drainServices().stream().anyMatch(i -> com.fadcam.utils.PersonalProSettings.ACTION_APPLY.equals(i.getAction())));
        touch(preview,0,MotionEvent.ACTION_DOWN,160,360);touch(preview,20,MotionEvent.ACTION_UP,160,360);
        assertTrue("Tapping must not override manual focus",drainServices().isEmpty());
        root.findViewById(R.id.personal_pro_auto).performClick();
        assertEquals(-1f,com.fadcam.utils.PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        root.findViewById(R.id.personal_pro_iso).performClick();root.findViewById(R.id.personal_pro_auto).performClick();
        assertFalse(com.fadcam.utils.PersonalProSettings.manualExposure(prefs,CameraType.BACK));
        assertTrue(root.findViewById(R.id.personal_pro_ev).isEnabled());
        root.findViewById(R.id.personal_pro_wb).performClick();
        slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,com.fadcam.utils.PersonalProSettings.wb(prefs,CameraType.BACK));
    }

    @Test public void changingIsoStartsFromMeasuredAutoValueAndPreservesMeasuredShutter() {
        readyControls();View root=fragment.requireView();
        android.hardware.camera2.TotalCaptureResult result=mock(android.hardware.camera2.TotalCaptureResult.class);
        when(result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)).thenReturn(800);
        when(result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(25_000_000L);
        com.fadcam.utils.PersonalCameraTelemetry.callback(CameraType.BACK,"0").onCaptureCompleted(null,null,result);
        root.findViewById(R.id.personal_pro_iso).performClick();
        android.widget.SeekBar ruler=root.findViewById(R.id.personal_pro_slider);
        ruler.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        assertEquals(1000,com.fadcam.utils.PersonalProSettings.iso(prefs,CameraType.BACK));
        assertEquals(25_000_000L,com.fadcam.utils.PersonalProSettings.exposure(prefs,CameraType.BACK));
        root.findViewById(R.id.personal_pro_auto).performClick();
        assertFalse(com.fadcam.utils.PersonalProSettings.manualExposure(prefs,CameraType.BACK));
    }

    @Test public void landscapeIsoRulerReplacesLensPickerAndAdjustsRealStandardIsoStops() {
        readyControls();View root=fragment.requireView();
        android.content.res.Configuration config=new android.content.res.Configuration(app.getResources().getConfiguration());config.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        fragment.onConfigurationChanged(config);root.measure(View.MeasureSpec.makeMeasureSpec(1040,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,1040,480);
        root.findViewById(R.id.personal_home_preview).setVisibility(View.VISIBLE);
        PersonalCameraControls controls=ReflectionHelpers.getField(fragment,"cameraControls");controls.updateEnabled();
        root.findViewById(R.id.personal_pro_iso).performClick();root.measure(View.MeasureSpec.makeMeasureSpec(1040,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));root.layout(0,0,1040,480);
        assertEquals(View.INVISIBLE,root.findViewById(R.id.personal_zoom_presets_container).getVisibility());
        PersonalCameraRuler ruler=root.findViewById(R.id.personal_pro_slider);assertTrue(ruler.getHeight()>ruler.getWidth());
        long t=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,20,180,0);ruler.onTouchEvent(down);down.recycle();
        for(int step=1;step<=30;step++) {
            android.view.MotionEvent move=android.view.MotionEvent.obtain(t,t+step,MotionEvent.ACTION_MOVE,20,180-step*2,0);ruler.onTouchEvent(move);move.recycle();
        }
        android.view.MotionEvent up=android.view.MotionEvent.obtain(t,t+60,MotionEvent.ACTION_UP,20,120,0);ruler.onTouchEvent(up);up.recycle();
        int iso=com.fadcam.utils.PersonalProSettings.iso(SharedPreferencesManager.getInstance(app),CameraType.BACK);
        assertTrue(java.util.Arrays.asList(50,64,80,100,125,160,200,250,320,400,500,640,800,1000,1250,1600,2000,2500,3200).contains(iso));
        assertTrue(iso>50);
        root.findViewById(R.id.personal_pro_close).performClick();assertEquals(View.VISIBLE,root.findViewById(R.id.personal_zoom_presets_container).getVisibility());
    }

    private void samsungState(RecordingState value, long start, long pause, long accumulated) {
        ReflectionHelpers.setField(service, "recordingState", value);
        Intent intent = new Intent(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK)
                .putExtra(Constants.INTENT_EXTRA_RECORDING_STATE, value)
                .putExtra(Constants.INTENT_EXTRA_RECORDING_START_TIME, start)
                .putExtra(Constants.INTENT_EXTRA_RECORDING_PAUSE_STARTED_AT, pause)
                .putExtra(Constants.INTENT_EXTRA_RECORDING_ACCUMULATED_PAUSED_DURATION, accumulated);
        BroadcastReceiver receiver = ReflectionHelpers.getField(fragment, "recordingReceiver");
        receiver.onReceive(fragment.requireContext(), intent);
    }

    @Test public void samsungPauseResumeWaitsForActualServiceAcknowledgementAndSuppressesRapidTaps() {
        readyControls();
        samsungState(RecordingState.IN_PROGRESS, 1L, 0L, 0L);
        drainServices();
        View pause = fragment.requireView().findViewById(R.id.personal_home_pause);
        View stop = fragment.requireView().findViewById(R.id.personal_home_record);
        assertEquals(View.VISIBLE, pause.getVisibility());
        pause.performClick(); pause.performClick(); stop.performClick();
        List<Intent> commands = drainServices();
        assertEquals(1, commands.size());
        assertEquals(Constants.INTENT_ACTION_PAUSE_RECORDING, commands.get(0).getAction());
        assertEquals(RecordingState.IN_PROGRESS, ReflectionHelpers.getField(fragment, "state"));
        assertFalse(pause.isEnabled()); assertFalse(stop.isEnabled());
        samsungState(RecordingState.PAUSED, 1L, 100L, 0L);
        assertTrue(pause.isEnabled());
        assertEquals(fragment.getString(R.string.personal_home_resume), pause.getContentDescription());
        pause.performClick(); pause.performClick();
        assertEquals(Constants.INTENT_ACTION_RESUME_RECORDING, drainServices().get(0).getAction());
        assertEquals(RecordingState.PAUSED, ReflectionHelpers.getField(fragment, "state"));
        samsungState(RecordingState.IN_PROGRESS, 1L, 0L, 50L);
        assertTrue(stop.isEnabled());
        assertEquals(fragment.getString(R.string.personal_home_pause), pause.getContentDescription());
    }

    @Test public void samsungStopWorksFromRecordingAndPausedAndBlocksStartDuringMuxerCleanup() {
        readyControls();
        for (RecordingState value : new RecordingState[]{RecordingState.IN_PROGRESS, RecordingState.PAUSED}) {
            samsungState(value, 1L, 100L, 0L); drainServices();
            View stop = fragment.requireView().findViewById(R.id.personal_home_record);
            stop.performClick(); stop.performClick();
            List<Intent> commands = drainServices(); assertEquals(1, commands.size());
            assertEquals(Constants.INTENT_ACTION_STOP_RECORDING, commands.get(0).getAction());
            ReflectionHelpers.setField(service, "isStopping", true);
            samsungState(RecordingState.NONE, 0L, 0L, 0L);
            assertEquals(fragment.getString(R.string.personal_home_saving), ((android.widget.TextView) fragment.requireView().findViewById(R.id.personal_home_status)).getText().toString());
            assertFalse(stop.isEnabled()); stop.performClick();
            assertFalse(drainServices().stream().anyMatch(i -> Constants.INTENT_ACTION_START_RECORDING.equals(i.getAction())));
            ReflectionHelpers.setField(service, "isStopping", false);
            ReflectionHelpers.callInstanceMethod(fragment, "render"); assertTrue(stop.isEnabled());
        }
    }

    @Test public void samsungStartFailureAndReentryRemainTruthfulWithoutDuplicateStart() {
        readyControls(); samsungState(RecordingState.NONE, 0L, 0L, 0L); drainServices();
        View start = fragment.requireView().findViewById(R.id.personal_home_record);
        start.performClick(); start.performClick();
        assertEquals(1L, drainServices().stream().filter(i -> Constants.INTENT_ACTION_START_RECORDING.equals(i.getAction())).count());
        assertFalse(start.isEnabled());
        event(Constants.ACTION_RECORDING_FAILED);
        assertNull(ReflectionHelpers.getField(fragment, "state"));
        samsungState(RecordingState.NONE, 0L, 0L, 0L); assertTrue(start.isEnabled());
        samsungState(RecordingState.PAUSED, 1L, 100L, 0L);
        assertEquals(View.VISIBLE, fragment.requireView().findViewById(R.id.personal_home_pause).getVisibility());
        assertEquals(fragment.getString(R.string.personal_home_resume), fragment.requireView().findViewById(R.id.personal_home_pause).getContentDescription());
    }

    @Test public void samsungPauseFreezesElapsedTimeAndResumeSubtractsPausedDuration() {
        readyControls();
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        long now = android.os.SystemClock.elapsedRealtime();
        samsungState(RecordingState.PAUSED, now-20_000L, now-5_000L, 3_000L);
        android.widget.TextView clock = fragment.requireView().findViewById(R.id.personal_home_timer);
        assertEquals("00:00:12", clock.getText().toString());
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
        ReflectionHelpers.callInstanceMethod(fragment, "renderTimer"); assertEquals("00:00:12", clock.getText().toString());
        samsungState(RecordingState.IN_PROGRESS, now-20_000L, 0L, 13_000L);
        assertEquals("00:00:12", clock.getText().toString());
    }

    @Test public void samsungRecordingControlsReflowToVerticalPillAndKeepStopAndPauseSeparate() {
        readyControls(); samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);
        android.content.res.Configuration config = new android.content.res.Configuration(app.getResources().getConfiguration());
        config.orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        fragment.onConfigurationChanged(config);
        LinearLayout actions = fragment.requireView().findViewById(R.id.personal_recording_actions);
        assertEquals(LinearLayout.VERTICAL, actions.getOrientation());
        assertEquals(2, actions.getChildCount());
        assertEquals(R.id.personal_home_record, actions.getChildAt(0).getId());
        assertEquals(R.id.personal_home_pause, actions.getChildAt(1).getId());
        config.orientation = android.content.res.Configuration.ORIENTATION_PORTRAIT;
        fragment.onConfigurationChanged(config); assertEquals(LinearLayout.HORIZONTAL, actions.getOrientation());
        assertEquals(View.VISIBLE, fragment.requireView().findViewById(R.id.personal_home_shortcuts).getVisibility());
    }

    @Test public void samsungCameraGeometryUsesMeasuredReferenceAndCentersClockWithoutDoubleMarginOffset() {
        readyControls();
        android.content.res.Configuration config=new android.content.res.Configuration(app.getResources().getConfiguration());
        config.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;fragment.onConfigurationChanged(config);
        View root=fragment.requireView();PersonalCameraLayout g=new PersonalCameraLayout(app);
        com.google.android.material.button.MaterialButton record=root.findViewById(R.id.personal_home_record);
        assertEquals(g.px(256),record.getLayoutParams().width);assertEquals(g.px(256),record.getLayoutParams().height);
        assertEquals(g.px(240),root.findViewById(R.id.personal_camera_left_rail).getLayoutParams().width);
        assertEquals(g.px(315),root.findViewById(R.id.personal_camera_mode_rail).getLayoutParams().width);
        android.widget.FrameLayout.LayoutParams row=(android.widget.FrameLayout.LayoutParams)root.findViewById(R.id.personal_shutter_row).getLayoutParams();
        assertEquals(g.px(360),row.width);assertEquals(g.px(352),row.getMarginEnd());
        assertEquals(g.px(95),root.findViewById(R.id.personal_home_timer).getLayoutParams().height);
        assertEquals(g.px(128),root.findViewById(R.id.personal_pro_iso).getLayoutParams().height);
        View clock=root.findViewById(R.id.personal_home_clock);android.widget.FrameLayout.LayoutParams clockPosition=(android.widget.FrameLayout.LayoutParams)clock.getLayoutParams();
        assertEquals(android.view.Gravity.TOP|android.view.Gravity.START,clockPosition.gravity);
        assertEquals(0,clockPosition.getMarginStart());assertEquals(0,clockPosition.getMarginEnd());assertEquals(-g.px(234),clock.getTranslationX(),0f);
        assertEquals(g.px(61),clockPosition.topMargin);
        int width=g.px(3120),height=g.px(1440);root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
        View timer=root.findViewById(R.id.personal_home_timer);samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
        float center=timer.getLeft()+timer.getWidth()/2f+clock.getX()+((View)clock.getParent()).getX();
        assertEquals(g.px(1326),center,2f);
        assertEquals(g.px(72),record.getIconSize());assertEquals(g.px(232),record.getLayoutParams().width);
        assertEquals(View.GONE,root.findViewById(R.id.personal_home_status).getVisibility());
        assertEquals(fragment.getString(R.string.personal_home_recording),androidx.core.view.ViewCompat.getStateDescription(record));
        samsungState(RecordingState.PAUSED,1L,100L,0L);
        assertEquals(View.GONE,root.findViewById(R.id.personal_home_status).getVisibility());
        assertEquals(fragment.getString(R.string.personal_home_paused),androidx.core.view.ViewCompat.getStateDescription(record));
        samsungState(RecordingState.NONE,0L,0L,0L);assertEquals(g.px(104),record.getIconSize());
        assertEquals(View.GONE,root.findViewById(R.id.personal_home_pause).getVisibility());
    }

    @Test public void quickToolsShareAnIconAxisAndUseReferenceRecordingRows() {
        readyControls();android.content.res.Configuration config=new android.content.res.Configuration(app.getResources().getConfiguration());
        config.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;fragment.onConfigurationChanged(config);
        View root=fragment.requireView();PersonalCameraLayout g=new PersonalCameraLayout(app);
        View tools=root.findViewById(R.id.personal_quick_tools);
        android.widget.FrameLayout.LayoutParams position=(android.widget.FrameLayout.LayoutParams)tools.getLayoutParams();
        assertEquals(g.px(108),position.getMarginStart());assertEquals(g.px(234),position.topMargin);
        for(int id:new int[]{R.id.personal_stabilization_control,R.id.personal_mic_control,R.id.personal_torch_control}) {
            com.google.android.material.button.MaterialButton button=root.findViewById(id);
            assertEquals(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START,button.getIconGravity());assertEquals(0,button.getIconPadding());assertEquals(g.px(127),button.getLayoutParams().width);
            assertEquals(g.px(127),button.getLayoutParams().height);assertEquals(View.TEXT_ALIGNMENT_CENTER,button.getTextAlignment());
        }
        assertEquals(View.GONE,root.findViewById(R.id.personal_grid_control).getVisibility());
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_camera_more).getVisibility());
        samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);
        position=(android.widget.FrameLayout.LayoutParams)tools.getLayoutParams();assertEquals(g.px(52),position.topMargin);
        assertEquals(View.GONE,root.findViewById(R.id.personal_quality_control).getVisibility());
        assertEquals(View.GONE,root.findViewById(R.id.personal_home_camera).getVisibility());
        samsungState(RecordingState.NONE,0L,0L,0L);
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_quality_control).getVisibility());assertEquals(View.VISIBLE,root.findViewById(R.id.personal_home_camera).getVisibility());
        config.orientation=android.content.res.Configuration.ORIENTATION_PORTRAIT;fragment.onConfigurationChanged(config);
        position=(android.widget.FrameLayout.LayoutParams)tools.getLayoutParams();
        assertEquals(android.view.Gravity.TOP|android.view.Gravity.START,position.gravity);
        assertEquals(g.px(234),position.getMarginStart());assertEquals(g.px(108),position.topMargin);
        assertEquals(LinearLayout.HORIZONTAL,((LinearLayout)tools).getOrientation());
        samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);
        position=(android.widget.FrameLayout.LayoutParams)tools.getLayoutParams();assertEquals(g.px(52),position.getMarginStart());
        assertEquals(R.id.personal_stabilization_control,((LinearLayout)tools).getChildAt(1).getId());

    }

    @Test public void cameraModeStripHasExactlyPhotoVideoAndProVideoAndShowsOnlySelectedProTools() {
        readyControls();
        LinearLayout modes=fragment.requireView().findViewById(R.id.personal_camera_mode);
        assertEquals(3,modes.getChildCount());
        assertEquals(R.id.personal_camera_mode_photo,modes.getChildAt(0).getId());
        assertEquals(R.id.personal_camera_mode_video,modes.getChildAt(1).getId());
        assertEquals(R.id.personal_camera_mode_pro_video,modes.getChildAt(2).getId());
        assertTrue(modes.getChildAt(2).isSelected());
        modes.getChildAt(1).performClick();
        assertTrue(modes.getChildAt(1).isSelected());assertFalse(modes.getChildAt(2).isSelected());
        assertEquals(View.GONE,((View)fragment.requireView().findViewById(R.id.personal_pro_items).getParent()).getVisibility());
        // Later zoom/live control updates must not revive Pro tools in ordinary modes.
        PersonalCameraControls.updateZoomValueVisibility(fragment.requireView());
        assertEquals(View.GONE,((View)fragment.requireView().findViewById(R.id.personal_pro_items).getParent()).getVisibility());
        modes.getChildAt(2).performClick();
        assertEquals(View.VISIBLE,((View)fragment.requireView().findViewById(R.id.personal_pro_items).getParent()).getVisibility());
    }

    @Test public void photoModeUsesPreviewPhotoActionWithoutStartingVideoAndNeedsNoAudioPermission() {
        readyControls();
        fragment.requireView().findViewById(R.id.personal_camera_mode_photo).performClick();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        ReflectionHelpers.callInstanceMethod(fragment,"render");drainServices();
        View shutter=fragment.requireView().findViewById(R.id.personal_home_record);
        assertEquals(fragment.getString(R.string.shortcut_take_photo),shutter.getContentDescription());
        shutter.performClick();
        List<Intent> actions=drainServices();assertEquals(1,actions.size());
        assertEquals(Constants.INTENT_ACTION_CAPTURE_PHOTO,actions.get(0).getAction());
        assertEquals(RecordingState.NONE,RecordingService.getSessionState());
        assertNull(ReflectionHelpers.getField(fragment,"pendingAction"));
    }

    @Test public void cameraModeCannotSwitchDuringRecordingPauseOrPendingStartAndSurvivesReflow() {
        readyControls();View photo=fragment.requireView().findViewById(R.id.personal_camera_mode_photo);
        for(RecordingState state:new RecordingState[]{RecordingState.STARTING,RecordingState.IN_PROGRESS,RecordingState.PAUSED}) {
            samsungState(state,1L,0L,0L);photo.performClick();
            assertFalse(photo.isSelected());assertTrue(fragment.requireView().findViewById(R.id.personal_camera_mode_pro_video).isSelected());
        }
        samsungState(RecordingState.NONE,0L,0L,0L);photo.performClick();assertTrue(photo.isSelected());
        android.content.res.Configuration configuration=new android.content.res.Configuration(app.getResources().getConfiguration());
        configuration.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;fragment.onConfigurationChanged(configuration);
        assertTrue(photo.isSelected());assertEquals(270f,fragment.requireView().findViewById(R.id.personal_camera_mode).getRotation(),0f);
        Bundle saved=new Bundle();fragment.onSaveInstanceState(saved);assertEquals(0,saved.getInt("personal.camera.mode",-1));
    }

    @Test public void ordinaryVideoModeStillStartsExistingVideoPipeline() {
        readyControls();fragment.requireView().findViewById(R.id.personal_camera_mode_video).performClick();drainServices();
        fragment.requireView().findViewById(R.id.personal_home_record).performClick();
        assertTrue(drainServices().stream().anyMatch(action -> Constants.INTENT_ACTION_START_RECORDING.equals(action.getAction())));
        assertEquals(RecordingState.STARTING,ReflectionHelpers.getField(fragment,"state"));
    }

    @Test public void ordinaryModeSwitchChangesEffectiveRequestPolicyAndKeepsProPreset() {
        readyControls();SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        com.fadcam.utils.PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);
        com.fadcam.utils.PersonalProSettings.saveExposure(prefs,CameraType.BACK,400,10_000_000L);
        for(int mode:new int[]{R.id.personal_camera_mode_photo,R.id.personal_camera_mode_video}) {
            drainServices();fragment.requireView().findViewById(mode).performClick();
            assertFalse(com.fadcam.utils.PersonalProSettings.isEnabled(prefs));
            assertTrue(drainServices().stream().anyMatch(i->com.fadcam.utils.PersonalProSettings.ACTION_APPLY.equals(i.getAction())));
            assertEquals(2f,com.fadcam.utils.PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        }
        fragment.requireView().findViewById(R.id.personal_camera_mode_pro_video).performClick();
        assertTrue(com.fadcam.utils.PersonalProSettings.isEnabled(prefs));
        assertEquals(400,com.fadcam.utils.PersonalProSettings.iso(prefs,CameraType.BACK));
    }

    @Test public void legacyAfPickerOffersOnlyAutomaticModesWhileDedicatedMfStillWorks() {
        readyControls();View root=fragment.requireView();PersonalCameraControls controls=ReflectionHelpers.getField(fragment,"cameraControls");
        root.findViewById(R.id.personal_pro_focus).performLongClick();
        androidx.appcompat.app.AlertDialog adjustment=ReflectionHelpers.getField(controls,"dialog");
        adjustment.findViewById(R.id.personal_af_picker).performClick();
        androidx.appcompat.app.AlertDialog picker=ReflectionHelpers.getField(controls,"focusDialog");
        android.widget.ListAdapter options=picker.getListView().getAdapter();assertEquals(2,options.getCount());
        for(int n=0;n<options.getCount();n++)assertNotEquals(fragment.getString(R.string.personal_af_off),options.getItem(n).toString());
        picker.dismiss();adjustment.dismiss();root.findViewById(R.id.personal_pro_focus).performClick();
        android.widget.SeekBar slider=root.findViewById(R.id.personal_pro_slider);
        slider.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        assertTrue(com.fadcam.utils.PersonalProSettings.focus(SharedPreferencesManager.getInstance(app),CameraType.BACK)>0);
    }

    @Test public void wbLabelUsesCurrentLensCapabilityWithoutDiscardingSavedKelvin() throws Exception {
        readyControls();SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        CameraCharacteristics c=((CameraManager)app.getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics("0");
        PersonalCameraControls controls=ReflectionHelpers.getField(fragment,"cameraControls");
        com.fadcam.utils.PersonalProSettings.saveKelvin(prefs,CameraType.BACK,4100);
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING});
        when(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AWB_MODE_AUTO,CaptureRequest.CONTROL_AWB_MODE_OFF});
        controls.refresh();android.widget.TextView label=fragment.requireView().findViewById(R.id.personal_pro_wb);assertEquals("WB 4100K",label.getText().toString());
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[0]);controls.refresh();assertEquals("WB A",label.getText().toString());
        assertEquals(4100,com.fadcam.utils.PersonalProSettings.kelvin(prefs,CameraType.BACK));
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING});
        controls.refresh();assertEquals("WB 4100K",label.getText().toString());
    }

    @Test public void silentVideoStartsWithoutMicrophonePermissionWhileAudioVideoStillRequestsPermission() {
        readyControls();SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
        prefs.setRecordAudioEnabled(false);
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO);
        drainServices();fragment.requireView().findViewById(R.id.personal_home_record).performClick();
        assertTrue(drainServices().stream().anyMatch(i->Constants.INTENT_ACTION_START_RECORDING.equals(i.getAction())));
        assertEquals(RecordingState.STARTING,ReflectionHelpers.getField(fragment,"state"));
        ReflectionHelpers.setField(fragment,"pendingAction",null);ReflectionHelpers.setField(fragment,"state",RecordingState.NONE);
        prefs.setRecordAudioEnabled(true);drainServices();
        fragment.requireView().findViewById(R.id.personal_home_record).performClick();
        assertFalse(drainServices().stream().anyMatch(i->Constants.INTENT_ACTION_START_RECORDING.equals(i.getAction())));
        assertEquals(RecordingState.NONE,ReflectionHelpers.getField(fragment,"state"));
    }

    @Test public void recentCameraThumbnailFillsMeasuredCircleAndPreservesSnapshotGeometry() {
        readyControls();
        View root=fragment.requireView();PersonalCameraLayout geometry=new PersonalCameraLayout(app);
        com.google.android.material.button.MaterialButton recent=root.findViewById(R.id.personal_home_videos);
        assertEquals(geometry.px(192),recent.getLayoutParams().width);
        assertEquals(geometry.px(192),recent.getLayoutParams().height);
        assertEquals(geometry.px(96),recent.getCornerRadius());
        assertEquals(geometry.px(1),recent.getStrokeWidth());assertEquals(0,recent.getIconPadding());
        RecentMediaLauncher launcher=ReflectionHelpers.getField(fragment,"recentMedia");
        VideoItem item=new VideoItem(android.net.Uri.parse("file:///synthetic-camera-thumbnail.mp4"),"fixture",32,1,
                VideoItem.Category.CAMERA,VideoItem.MediaType.VIDEO);
        ReflectionHelpers.callInstanceMethod(launcher,"showThumbnail",ReflectionHelpers.ClassParameter.from(VideoItem.class,item));
        com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable> target=ReflectionHelpers.getField(launcher,"target");
        target.onResourceReady(new android.graphics.drawable.ColorDrawable(android.graphics.Color.RED),null);
        assertEquals("Decoded image must fill the same measured circle, without a fixed dp ring",geometry.px(192),recent.getIconSize());
        assertNull(recent.getIconTint());
        android.widget.FrameLayout.LayoutParams position=(android.widget.FrameLayout.LayoutParams)recent.getLayoutParams();
        assertEquals(geometry.px(136),position.getMarginStart());
        samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);
        assertEquals(geometry.px(106),recent.getIconSize());assertEquals(0,recent.getStrokeWidth());
        assertEquals(fragment.getString(R.string.shortcut_take_photo),recent.getContentDescription());
    }

    @Test public void activeRecentButtonDispatchesExistingPhotoActionWithoutStoppingOrOpeningViewer() {
        readyControls();samsungState(RecordingState.IN_PROGRESS,1L,0L,0L);drainServices();
        View button=fragment.requireView().findViewById(R.id.personal_home_videos);
        assertEquals(fragment.getString(R.string.shortcut_take_photo),button.getContentDescription());button.performClick();
        List<Intent> actions=drainServices();assertEquals(1,actions.size());assertEquals(Constants.INTENT_ACTION_CAPTURE_PHOTO,actions.get(0).getAction());
        assertEquals(RecordingState.IN_PROGRESS,RecordingService.getSessionState());
        samsungState(RecordingState.PAUSED,1L,100L,0L);drainServices();button.performClick();
        assertFalse(button.isEnabled());
        assertEquals(fragment.getString(R.string.photo_capture_paused), button.getContentDescription());
        assertTrue(drainServices().isEmpty());
        assertEquals(RecordingState.PAUSED,RecordingService.getSessionState());
    }

    @Test public void samsungFinalizingTimelineSurvivesUnknownCallbackAndNewUiInstance() {
        readyControls();
        ReflectionHelpers.setField(service,"isStopping",true);
        ReflectionHelpers.setField(service,"finalizingTimelineMs",12_500L);
        ReflectionHelpers.setField(fragment,"state",null);
        samsungState(RecordingState.NONE,0L,0L,0L);
        android.widget.TextView clock=fragment.requireView().findViewById(R.id.personal_home_timer);
        assertEquals("00:00:12",clock.getText().toString());
        ReflectionHelpers.setField(fragment,"frozenStopElapsed",0L);
        ReflectionHelpers.callInstanceMethod(fragment,"render");
        assertEquals("00:00:12",clock.getText().toString());
    }

    @Test @Config(sdk=35,application=Application.class,qualifiers="ko-rKR-w480dp-h1040dp-mdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void fullPreviewBoundsAndPcRenderedLayoutsInBothOrientations() throws Exception {
        readyControls();
        addS24Lenses(); readyControls();
        View root=fragment.requireView();
        java.io.File output=new java.io.File("build/outputs/pc-layouts");assertTrue(output.mkdirs() || output.isDirectory());
        for(int stage=0;stage<3;stage++) {
            boolean landscape=stage>0;
            android.content.res.Configuration config=new android.content.res.Configuration(app.getResources().getConfiguration());
            config.orientation=landscape?android.content.res.Configuration.ORIENTATION_LANDSCAPE:android.content.res.Configuration.ORIENTATION_PORTRAIT;
            fragment.onConfigurationChanged(config);
            int width=landscape?1040:480,height=landscape?480:1040;
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            root.layout(0,0,width,height);
            TextureView camera=root.findViewById(R.id.personal_home_preview);
            assertEquals(width,camera.getWidth());assertEquals(height,camera.getHeight());
            assertEquals(0,camera.getLeft());assertEquals(0,camera.getTop());
            assertTrue(root.findViewById(R.id.personal_home_controls).getHeight()<height/2);
            assertTrue(root.findViewById(R.id.personal_home_record).getWidth()>0);
            if(stage==2) {
                camera.setVisibility(View.VISIBLE);
                PersonalCameraControls controls=ReflectionHelpers.getField(fragment,"cameraControls");controls.updateEnabled();
                com.fadcam.utils.PersonalProSettings.saveExposure(SharedPreferencesManager.getInstance(app),CameraType.BACK,800,33_333_333L);
                root.findViewById(R.id.personal_pro_iso).performClick();
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
                View panel=root.findViewById(R.id.personal_pro_panel);assertEquals(View.VISIBLE,panel.getVisibility());assertTrue(panel.getWidth()<=100);
            }
            root.getViewTreeObserver().dispatchOnPreDraw();
            java.util.List<String> geometry=new java.util.ArrayList<>();
            LinearLayout ratios=root.findViewById(R.id.personal_zoom_presets);
            for(int i=0;i<ratios.getChildCount();i++) {
                android.widget.TextView ratio=(android.widget.TextView)ratios.getChildAt(i);
                geometry.add(ratio.getText()+" bounds="+ratio.getLeft()+","+ratio.getTop()+","+ratio.getRight()+","+ratio.getBottom()
                        +" color="+ratio.getCurrentTextColor()+" alpha="+ratio.getPaint().getAlpha()+" layout="+ratio.getLayout()
                        +" textSize="+ratio.getTextSize()+" baseline="+ratio.getBaseline()+" layoutWidth="+ratio.getLayout().getWidth()+" scrollX="+ratio.getScrollX());
            }
            java.nio.file.Files.write(new java.io.File(output,landscape?"landscape-geometry.txt":"portrait-geometry.txt").toPath(),geometry);
            camera.setVisibility(View.INVISIBLE); // PC layout image uses the explicit generated grid placeholder.
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
            android.graphics.Paint paint=new android.graphics.Paint();
            paint.setShader(new android.graphics.LinearGradient(0,0,width,height,0xFF45616B,0xFF152B35,android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0,0,width,height,paint);
            paint.setShader(null);paint.setColor(0x405A8798);paint.setStrokeWidth(1);
            for(int x=0;x<width;x+=80)canvas.drawLine(x,0,x,height,paint);
            for(int y=0;y<height;y+=80)canvas.drawLine(0,y,width,y,paint);
            root.setBackground(null);root.draw(canvas);
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(output,stage==2?"landscape-iso.png":landscape?"landscape.png":"portrait.png"))) {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream));
            }
            bitmap.recycle();
        }
    }

}
