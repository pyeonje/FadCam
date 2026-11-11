package com.fadcam.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.*;
import android.os.Handler;
import android.os.Looper;
import com.fadcam.*;
import com.fadcam.services.RecordingService;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Actual tap/request callbacks, not screen or physical focus quality verification. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class RecordingFocusRequestTest {
    RecordingService service;
    SharedPreferencesManager prefs;
    CameraCharacteristics c;
    CaptureRequest.Builder builder;
    CameraCaptureSession session;
    @Before public void setup() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        prefs=SharedPreferencesManager.getInstance(app);
        c=mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new Rect(0,0,4000,3000));
        when(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[]{0,1,3});
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(1);
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)).thenReturn(1);
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(5f);
        builder=requestBuilder();builder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        session=mock(CameraCaptureSession.class);
        service=Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service,"sharedPreferencesManager",prefs);
        ReflectionHelpers.setField(service,"currentCameraCharacteristics",c);
        ReflectionHelpers.setField(service,"captureRequestBuilder",builder);
        ReflectionHelpers.setField(service,"captureSession",session);
        ReflectionHelpers.setField(service,"cameraDevice",mock(CameraDevice.class));
        ReflectionHelpers.setField(service,"backgroundHandler",new Handler(Looper.getMainLooper()));
    }
    @After public void cleanup(){ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",null);}
    private CaptureRequest.Builder requestBuilder() throws Exception {
        Class metadata=Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        return ReflectionHelpers.callConstructor(CaptureRequest.Builder.class,
                ReflectionHelpers.ClassParameter.from(metadata,ReflectionHelpers.callConstructor(metadata)),
                ReflectionHelpers.ClassParameter.from(boolean.class,false),ReflectionHelpers.ClassParameter.from(int.class,-1),
                ReflectionHelpers.ClassParameter.from(String.class,"0"),ReflectionHelpers.ClassParameter.from(java.util.Set.class,null));
    }
    private void tap() {
        ReflectionHelpers.callInstanceMethod(service,"performTapToFocus",ReflectionHelpers.ClassParameter.from(float.class,.5f),ReflectionHelpers.ClassParameter.from(float.class,.5f));
    }
    private void tick(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    @Test public void startTriggerIsOneShotAndNormalAfReturns() throws Exception {
        // Robolectric's reflected native Builder retains values, but build()'s
        // copied request metadata is empty. Observe the real builder at each
        // session boundary rather than claiming the shadow copied payload passed.
        Integer[] captureState=new Integer[2],repeatingState=new Integer[2];
        doAnswer(call->{
            assertNotNull(call.getArgument(0));
            captureState[0]=builder.get(CaptureRequest.CONTROL_AF_MODE);
            captureState[1]=builder.get(CaptureRequest.CONTROL_AF_TRIGGER);
            CaptureRequest copied=call.getArgument(0);
            System.out.println("Camera2 test copy metadata AF="+copied.get(CaptureRequest.CONTROL_AF_MODE)+" trigger="+copied.get(CaptureRequest.CONTROL_AF_TRIGGER)+"; invocation builder AF="+captureState[0]+" trigger="+captureState[1]);
            return 1;
        }).when(session).capture(any(CaptureRequest.class),isNull(),any(Handler.class));
        doAnswer(call->{
            assertNotNull(call.getArgument(0));
            repeatingState[0]=builder.get(CaptureRequest.CONTROL_AF_MODE);
            repeatingState[1]=builder.get(CaptureRequest.CONTROL_AF_TRIGGER);
            return 2;
        }).when(session).setRepeatingRequest(any(CaptureRequest.class),isNull(),any(Handler.class));
        tap();
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_AUTO),captureState[0]);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_TRIGGER_START),captureState[1]);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_AUTO),repeatingState[0]);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_TRIGGER_IDLE),repeatingState[1]);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_TRIGGER_IDLE),builder.get(CaptureRequest.CONTROL_AF_TRIGGER));
        tick(1000);assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),builder.get(CaptureRequest.CONTROL_AF_MODE));
    }
    @Test public void oldTapCannotRestoreIntoReplacementLensSession() throws Exception {
        tap();CaptureRequest.Builder replacement=requestBuilder();replacement.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
        CameraCaptureSession successor=mock(CameraCaptureSession.class);
        ReflectionHelpers.setField(service,"captureRequestBuilder",replacement);ReflectionHelpers.setField(service,"captureSession",successor);
        tick(1000);verifyNoInteractions(successor);assertEquals(Integer.valueOf(0),replacement.get(CaptureRequest.CONTROL_AF_MODE));
    }
    @Test public void latestTapOwnsRestoreDeadlineAndDoesNotRestoreTemporaryAutoMode() throws Exception {
        tap();tick(500);tap();tick(500);verify(session,times(2)).setRepeatingRequest(any(),any(),any());
        tick(500);verify(session,times(3)).setRepeatingRequest(any(),any(),any());
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),builder.get(CaptureRequest.CONTROL_AF_MODE));
    }
    @Test public void explicitAfSelectionInvalidatesOldTapAndRemainsSaved() throws Exception {
        tap();ReflectionHelpers.callInstanceMethod(service,"applyAfMode",ReflectionHelpers.ClassParameter.from(int.class,CaptureRequest.CONTROL_AF_MODE_AUTO));
        assertEquals(CaptureRequest.CONTROL_AF_MODE_AUTO,prefs.getSavedAfMode());
        tick(1000);verify(session,times(2)).setRepeatingRequest(any(),any(),any());
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_AUTO),builder.get(CaptureRequest.CONTROL_AF_MODE));
    }
    @Test public void unsupportedAutoDoesNotSendUnsupportedAfAndOnlyUsesAvailableAeRegion() throws Exception {
        when(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[]{0});
        builder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(0);
        tap();verify(session,never()).capture(any(),any(),any());verify(session).setRepeatingRequest(any(),any(),any());
        assertNull(builder.get(CaptureRequest.CONTROL_AF_REGIONS));assertNotNull(builder.get(CaptureRequest.CONTROL_AE_REGIONS));
        assertEquals(Integer.valueOf(0),builder.get(CaptureRequest.CONTROL_AF_MODE));
    }
    @Test public void absentMeteringRegionsAndNoAutoLeaveRequestAlone() {
        when(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[]{0});
        builder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(0);
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)).thenReturn(0);
        tap();verifyNoInteractions(session);assertNull(builder.get(CaptureRequest.CONTROL_AF_REGIONS));assertNull(builder.get(CaptureRequest.CONTROL_AE_REGIONS));
    }
    @Test public void manualFocusSuppressesTapUntilAfIsSelected() {
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);tap();verifyNoInteractions(session);
    }
    @Test public void standardModeTapDoesNotRestoreHiddenManualAfIntent() {
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);
        prefs.setSavedAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
        ReflectionHelpers.setField(service,"runtimeAfMode",Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_OFF));
        PersonalProSettings.setEnabled(prefs,false);
        tap();tick(1000);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),builder.get(CaptureRequest.CONTROL_AF_MODE));
        assertEquals(2f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        assertEquals(CaptureRequest.CONTROL_AF_MODE_OFF,prefs.getSavedAfMode());
    }
    @Test public void modeApplyClearsRuntimeLocksAndInvalidatesPendingTapWithoutErasingProPreset() throws Exception {
        tap();tick(500);
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);prefs.setSavedAeLock(true);
        ReflectionHelpers.setField(service,"runtimeAfMode",Integer.valueOf(0));
        ReflectionHelpers.setField(service,"runtimeAeLock",Boolean.TRUE);
        PersonalProSettings.setEnabled(prefs,false);
        service.onStartCommand(RecordingControlIntents.applyProSettings(RuntimeEnvironment.getApplication()),0,1);
        assertNull(ReflectionHelpers.getField(service,"runtimeAfMode"));assertNull(ReflectionHelpers.getField(service,"runtimeAeLock"));
        assertEquals(Boolean.FALSE,builder.get(CaptureRequest.CONTROL_AE_LOCK));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),builder.get(CaptureRequest.CONTROL_AF_MODE));
        assertNull(builder.get(CaptureRequest.LENS_FOCUS_DISTANCE));assertEquals(2f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        clearInvocations(session);tick(500);verifyNoInteractions(session);
    }

}
