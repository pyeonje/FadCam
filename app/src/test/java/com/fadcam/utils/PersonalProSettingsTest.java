package com.fadcam.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.RecordingControlIntents;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.services.RecordingService;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalProSettingsTest {
    private SharedPreferencesManager prefs;
    private CameraCharacteristics c;
    private CaptureRequest.Builder builder;
    @Before public void setup() throws Exception {
        Context app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        prefs=SharedPreferencesManager.getInstance(app);
        c=mock(CameraCharacteristics.class);
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR});
        when(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AE_MODE_OFF,CaptureRequest.CONTROL_AE_MODE_ON});
        when(c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)).thenReturn(new Range<>(50,3200));
        when(c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)).thenReturn(new Range<>(100_000L,1_000_000_000L));
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(5f);
        when(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AF_MODE_OFF,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO});
        when(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AWB_MODE_AUTO,CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT});
        Class metadataClass=Class.forName("android.hardware.camera2.impl.CameraMetadataNative");
        Object metadata=ReflectionHelpers.callConstructor(metadataClass);
        builder=ReflectionHelpers.callConstructor(CaptureRequest.Builder.class,
                ReflectionHelpers.ClassParameter.from(metadataClass,metadata),
                ReflectionHelpers.ClassParameter.from(boolean.class,false),ReflectionHelpers.ClassParameter.from(int.class,-1),
                ReflectionHelpers.ClassParameter.from(String.class,"0"),ReflectionHelpers.ClassParameter.from(java.util.Set.class,null));
    }
    @After public void cleanup(){ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",null);}
    @Test public void ordinaryModesApplyAutomaticRequestAndPreserveHiddenProPresetForReturn() {
        PersonalProSettings.saveExposure(prefs,CameraType.BACK,400,10_000_000L);
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);
        PersonalProSettings.saveWb(prefs,CameraType.BACK,CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
        prefs.setSavedAeLock(true);prefs.setSavedAfMode(CaptureRequest.CONTROL_AF_MODE_OFF);
        assertTrue(PersonalProSettings.apply(builder,c,prefs,30));
        PersonalProSettings.setEnabled(prefs,false);
        assertFalse(PersonalProSettings.usesManualExposure(prefs,c,30));
        assertFalse(PersonalProSettings.usesManualFocus(prefs,CameraType.BACK,c));
        assertFalse(PersonalProSettings.apply(builder,c,prefs,30));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON),builder.get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(Boolean.FALSE,builder.get(CaptureRequest.CONTROL_AE_LOCK));
        assertNull(builder.get(CaptureRequest.SENSOR_SENSITIVITY));assertNull(builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        assertNull(builder.get(CaptureRequest.LENS_FOCUS_DISTANCE));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),builder.get(CaptureRequest.CONTROL_AF_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),builder.get(CaptureRequest.CONTROL_AWB_MODE));
        assertEquals(400,PersonalProSettings.iso(prefs,CameraType.BACK));assertEquals(2f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        assertEquals(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,PersonalProSettings.wb(prefs,CameraType.BACK));
        PersonalProSettings.setEnabled(prefs,true);assertTrue(PersonalProSettings.apply(builder,c,prefs,30));
        assertEquals(Integer.valueOf(400),builder.get(CaptureRequest.SENSOR_SENSITIVITY));
        assertEquals(2f,builder.get(CaptureRequest.LENS_FOCUS_DISTANCE),0f);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),builder.get(CaptureRequest.CONTROL_AWB_MODE));
    }
    @Test public void unsupportedKelvinLensUsesAutoAndClearsManualColorButRestoresSavedKelvinOnReturn() {
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING});
        when(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AWB_MODE_AUTO,CaptureRequest.CONTROL_AWB_MODE_OFF});
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_FAST);
        PersonalProSettings.saveKelvin(prefs,CameraType.BACK,4100);PersonalProSettings.apply(builder,c,prefs,30);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_OFF),builder.get(CaptureRequest.CONTROL_AWB_MODE));
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[0]);
        PersonalProSettings.apply(builder,c,prefs,30);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),builder.get(CaptureRequest.CONTROL_AWB_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.COLOR_CORRECTION_MODE_FAST),builder.get(CaptureRequest.COLOR_CORRECTION_MODE));
        assertNull(builder.get(CaptureRequest.COLOR_CORRECTION_GAINS));
        assertEquals(4100,PersonalProSettings.kelvin(prefs,CameraType.BACK));
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING});
        PersonalProSettings.apply(builder,c,prefs,30);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_OFF),builder.get(CaptureRequest.CONTROL_AWB_MODE));
        assertNotNull(builder.get(CaptureRequest.COLOR_CORRECTION_GAINS));assertEquals(4100,PersonalProSettings.kelvin(prefs,CameraType.BACK));
    }
    @Test public void manualPresetsReachNativeRequestAndClampToHardwareAndFramePeriod() {
        PersonalProSettings.saveExposure(prefs,CameraType.BACK,9000,500_000_000L);
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,8f);
        PersonalProSettings.saveWb(prefs,CameraType.BACK,CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
        assertTrue(PersonalProSettings.apply(builder,c,prefs,30));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_OFF),builder.get(CaptureRequest.CONTROL_AE_MODE));
        assertEquals(Integer.valueOf(3200),builder.get(CaptureRequest.SENSOR_SENSITIVITY));
        assertEquals(Long.valueOf(33_333_333L),builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        assertEquals(Long.valueOf(33_333_333L),builder.get(CaptureRequest.SENSOR_FRAME_DURATION));
        assertEquals(5f,builder.get(CaptureRequest.LENS_FOCUS_DISTANCE),0f);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),builder.get(CaptureRequest.CONTROL_AWB_MODE));
        assertEquals(3200,PersonalProSettings.iso(prefs,CameraType.BACK));
        assertEquals(33_333_333L,PersonalProSettings.exposure(prefs,CameraType.BACK));
    }
    @Test public void autoRestoresAeAfAwbAndClearsManualSensorValuesWithoutChangingOtherCamera() {
        PersonalProSettings.saveExposure(prefs,CameraType.FRONT,500,20_000_000L);
        PersonalProSettings.saveExposure(prefs,CameraType.BACK,400,10_000_000L);
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);
        PersonalProSettings.apply(builder,c,prefs,30);
        PersonalProSettings.saveExposure(prefs,CameraType.BACK,0,0);
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,-1);
        assertFalse(PersonalProSettings.apply(builder,c,prefs,30));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON),builder.get(CaptureRequest.CONTROL_AE_MODE));
        assertNull(builder.get(CaptureRequest.SENSOR_SENSITIVITY));assertNull(builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        assertNull(builder.get(CaptureRequest.LENS_FOCUS_DISTANCE));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),builder.get(CaptureRequest.CONTROL_AF_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),builder.get(CaptureRequest.CONTROL_AWB_MODE));
        assertEquals(500,PersonalProSettings.iso(prefs,CameraType.FRONT));
    }
    @Test public void unsupportedSensorAndHighSpeedDoNotSendManualExposure() {
        PersonalProSettings.saveExposure(prefs,CameraType.BACK,400,10_000_000L);
        assertFalse(PersonalProSettings.apply(builder,c,prefs,120));
        assertNull(builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[0]);
        assertFalse(PersonalProSettings.apply(builder,c,prefs,30));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AE_MODE_ON),builder.get(CaptureRequest.CONTROL_AE_MODE));
    }
    @Test public void runtimeAndStartupUseSameSavedRequestWithoutRestartingRecording() throws Exception {
        RecordingService service=Robolectric.buildService(RecordingService.class).get();
        android.hardware.camera2.CameraCaptureSession session=mock(android.hardware.camera2.CameraCaptureSession.class);
        ReflectionHelpers.setField(service,"sharedPreferencesManager",prefs);
        ReflectionHelpers.setField(service,"currentCameraCharacteristics",c);
        ReflectionHelpers.setField(service,"captureRequestBuilder",builder);
        ReflectionHelpers.setField(service,"captureSession",session);
        ReflectionHelpers.setField(service,"recordingState",RecordingState.IN_PROGRESS);
        ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",service);
        PersonalProSettings.saveExposure(prefs,CameraType.BACK,200,12_500_000L);
        service.onStartCommand(RecordingControlIntents.applyProSettings(RuntimeEnvironment.getApplication()),0,1);
        verify(session).setRepeatingRequest(any(CaptureRequest.class),isNull(),isNull());
        assertEquals(RecordingState.IN_PROGRESS,RecordingService.getSessionState());
        assertEquals(Integer.valueOf(200),builder.get(CaptureRequest.SENSOR_SENSITIVITY));
        assertEquals(Long.valueOf(12_500_000L),builder.get(CaptureRequest.SENSOR_EXPOSURE_TIME));
        ReflectionHelpers.callInstanceMethod(service,"applySavedCameraPrefsToBuilder",ReflectionHelpers.ClassParameter.from(CaptureRequest.Builder.class,builder));
        assertEquals(Integer.valueOf(200),builder.get(CaptureRequest.SENSOR_SENSITIVITY));
    }    @Test public void stabilizationNeverEnablesOpticalAndDigitalAtTheSameTime() {
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)).thenReturn(new int[]{0,1});
        when(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)).thenReturn(new int[]{0,1});
        PersonalStabilization.apply(builder,c,true);
        assertEquals(Integer.valueOf(1),builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE));
        assertEquals(Integer.valueOf(0),builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
        when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)).thenReturn(new int[]{0});
        PersonalStabilization.apply(builder,c,true);
        assertEquals(Integer.valueOf(0),builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE));
        assertEquals(Integer.valueOf(1),builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
        PersonalStabilization.apply(builder,c,false);
        assertEquals(Integer.valueOf(0),builder.get(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE));
        assertEquals(Integer.valueOf(0),builder.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE));
    }
    @Test public void autoReadingsComeFromRealResultsAndNeverAnotherLens() {
        android.hardware.camera2.TotalCaptureResult result=mock(android.hardware.camera2.TotalCaptureResult.class);
        when(result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)).thenReturn(800);
        when(result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(33_333_333L);
        when(result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)).thenReturn(.75f);
        android.hardware.camera2.CameraCaptureSession.CaptureCallback callback=PersonalCameraTelemetry.callback(CameraType.BACK,"7");
        assertNull(PersonalCameraTelemetry.current(CameraType.BACK,"7"));
        callback.onCaptureCompleted(null,null,result);
        assertEquals(Integer.valueOf(800),PersonalCameraTelemetry.current(CameraType.BACK,"7").iso);
        assertEquals(Float.valueOf(.75f),PersonalCameraTelemetry.current(CameraType.BACK,"7").focus);
        assertNull(PersonalCameraTelemetry.current(CameraType.BACK,"5"));
    }
    @Test public void normalizedManualFocusEndpointsAndMidpointReachAfOffOnEachReappliedRequest() {
        for(float scale:new float[]{0f,.5f,1f}) {
            PersonalProSettings.saveFocus(prefs,CameraType.BACK,PersonalProSettings.focusDiopters(scale,c));
            PersonalProSettings.apply(builder,c,prefs,30);
            assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_OFF),builder.get(CaptureRequest.CONTROL_AF_MODE));
            assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_TRIGGER_IDLE),builder.get(CaptureRequest.CONTROL_AF_TRIGGER));
            assertEquals(scale*5f,builder.get(CaptureRequest.LENS_FOCUS_DISTANCE),0f);
            assertEquals(scale,PersonalProSettings.normalizedFocus(builder.get(CaptureRequest.LENS_FOCUS_DISTANCE),c),0f);
            // The same saved state is applied after a session rebuild/resume.
            builder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE,null);
            PersonalProSettings.apply(builder,c,prefs,30);
            assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_OFF),builder.get(CaptureRequest.CONTROL_AF_MODE));
            assertEquals(scale*5f,builder.get(CaptureRequest.LENS_FOCUS_DISTANCE),0f);
        }
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(20f);
        assertEquals(10f,PersonalProSettings.focusDiopters(.5f,c),0f);
        assertEquals(20f,PersonalProSettings.focusDiopters(1f,c),0f);
    }
    @Test public void rearLensesKeepIndependentFocusAndLegacyPreferenceMigratesOnlyOnce() {
        prefs.setSelectedBackCameraId("wide");
        prefs.sharedPreferences.edit().putFloat("personal_pro_back_focus",4f).commit();
        assertEquals(4f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        prefs.setSelectedBackCameraId("tele");
        assertEquals(-1f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);
        prefs.setSelectedBackCameraId("wide");
        assertEquals(4f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,-1f);
        prefs.setSelectedBackCameraId("tele");
        assertEquals(2f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(1f);
        PersonalProSettings.apply(builder,c,prefs,30);
        assertEquals(1f,builder.get(CaptureRequest.LENS_FOCUS_DISTANCE),0f);
        assertEquals(2f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        prefs.setSelectedBackCameraId("wide");
        assertEquals(-1f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
    }
    @Test public void afReturnUsesSupportedAutoModeAndClearsManualDistanceAndTrigger() {
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,3f);
        PersonalProSettings.apply(builder,c,prefs,30);
        when(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AF_MODE_OFF,CaptureRequest.CONTROL_AF_MODE_AUTO});
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,-1f);
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
        PersonalProSettings.apply(builder,c,prefs,30);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_MODE_AUTO),builder.get(CaptureRequest.CONTROL_AF_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AF_TRIGGER_IDLE),builder.get(CaptureRequest.CONTROL_AF_TRIGGER));
        assertNull(builder.get(CaptureRequest.LENS_FOCUS_DISTANCE));
    }
    @Test public void fixedFocusMissingOffAndInvalidCapabilitiesNeverPretendManualFocus() {
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(0f);
        assertFalse(PersonalProSettings.usesManualFocus(prefs,CameraType.BACK,c));
        PersonalProSettings.apply(builder,c,prefs,30);
        assertNull(builder.get(CaptureRequest.LENS_FOCUS_DISTANCE));
        assertEquals(2f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(Float.NaN);
        assertFalse(PersonalProSettings.supportsFocus(c));
        when(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(5f);
        when(c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO});
        assertFalse(PersonalProSettings.supportsFocus(c));
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,Float.NaN);
        assertEquals(-1f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
    }
    @Test public void kelvinUsesCamera2ManualColorAndAutoRestoresTheOriginalRequestDefaults() {
        when(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)).thenReturn(new int[]{CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING});
        when(c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AWB_MODE_AUTO,CaptureRequest.CONTROL_AWB_MODE_OFF});
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE,CaptureRequest.COLOR_CORRECTION_MODE_FAST);
        PersonalProSettings.saveKelvin(prefs,CameraType.BACK,4100);PersonalProSettings.apply(builder,c,prefs,30);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_OFF),builder.get(CaptureRequest.CONTROL_AWB_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX),builder.get(CaptureRequest.COLOR_CORRECTION_MODE));
        android.hardware.camera2.params.RggbChannelVector gains=builder.get(CaptureRequest.COLOR_CORRECTION_GAINS);
        assertNotNull(gains);assertTrue(gains.getRed()>0 && gains.getBlue()>0 && Float.isFinite(gains.getBlue()));
        PersonalProSettings.saveWb(prefs,CameraType.BACK,CaptureRequest.CONTROL_AWB_MODE_AUTO);PersonalProSettings.apply(builder,c,prefs,30);
        assertEquals(Integer.valueOf(CaptureRequest.CONTROL_AWB_MODE_AUTO),builder.get(CaptureRequest.CONTROL_AWB_MODE));
        assertEquals(Integer.valueOf(CaptureRequest.COLOR_CORRECTION_MODE_FAST),builder.get(CaptureRequest.COLOR_CORRECTION_MODE));
        assertNull(builder.get(CaptureRequest.COLOR_CORRECTION_GAINS));assertEquals(0,PersonalProSettings.kelvin(prefs,CameraType.BACK));
    }

    @Test public void centerAndMultiAfReachDifferentBoundedHardwareRegionsAndPreserveAe() {
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(2);
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new android.graphics.Rect(0,0,4000,3000));
        builder.set(CaptureRequest.SCALER_CROP_REGION,new android.graphics.Rect(1000,750,3000,2250));
        PersonalProSettings.saveFocusArea(prefs,CameraType.BACK,PersonalProSettings.FOCUS_AREA_CENTER);
        PersonalProSettings.applyFocusArea(builder,c,prefs);
        android.hardware.camera2.params.MeteringRectangle[] center=builder.get(CaptureRequest.CONTROL_AF_REGIONS);
        assertEquals(1,center.length);assertEquals(new android.graphics.Rect(1800,1350,2200,1650),center[0].getRect());assertTrue(center[0].getMeteringWeight()>0);
        assertNull(builder.get(CaptureRequest.CONTROL_AE_REGIONS));
        PersonalProSettings.saveFocusArea(prefs,CameraType.BACK,PersonalProSettings.FOCUS_AREA_MULTI);
        PersonalProSettings.applyFocusArea(builder,c,prefs);
        android.hardware.camera2.params.MeteringRectangle[] multi=builder.get(CaptureRequest.CONTROL_AF_REGIONS);
        assertEquals(2,multi.length);assertEquals(new android.graphics.Rect(1000,750,2000,2250),multi[0].getRect());assertEquals(new android.graphics.Rect(2000,750,3000,2250),multi[1].getRect());assertTrue(multi[0].getMeteringWeight()>0);assertTrue(multi[1].getMeteringWeight()>0);
    }
    @Test public void unavailableAfAreasAreNotSentAndLensPreferencesStayIndependent() {
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new android.graphics.Rect(0,0,4000,3000));
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(0);
        assertFalse(PersonalProSettings.supportsFocusArea(c));PersonalProSettings.applyFocusArea(builder,c,prefs);assertNull(builder.get(CaptureRequest.CONTROL_AF_REGIONS));
        prefs.setSelectedBackCameraId("wide");PersonalProSettings.saveFocusArea(prefs,CameraType.BACK,PersonalProSettings.FOCUS_AREA_MULTI);
        prefs.setSelectedBackCameraId("tele");assertEquals(PersonalProSettings.FOCUS_AREA_CENTER,PersonalProSettings.focusArea(prefs,CameraType.BACK));
        prefs.setSelectedBackCameraId("wide");assertEquals(PersonalProSettings.FOCUS_AREA_MULTI,PersonalProSettings.focusArea(prefs,CameraType.BACK));
    }
    @Test public void manualFocusClearsAreaAndStandardModeUsesDeviceSelectedAreaWithoutErasingCenter() {
        when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(1);
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new android.graphics.Rect(0,0,4000,3000));
        PersonalProSettings.saveFocusArea(prefs,CameraType.BACK,PersonalProSettings.FOCUS_AREA_CENTER);PersonalProSettings.applyFocusArea(builder,c,prefs);
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2f);PersonalProSettings.applyFocusArea(builder,c,prefs);assertNull(builder.get(CaptureRequest.CONTROL_AF_REGIONS));
        PersonalProSettings.setEnabled(prefs,false);PersonalProSettings.applyFocusArea(builder,c,prefs);
        assertEquals(0,builder.get(CaptureRequest.CONTROL_AF_REGIONS)[0].getMeteringWeight());assertEquals(PersonalProSettings.FOCUS_AREA_CENTER,PersonalProSettings.focusArea(prefs,CameraType.BACK));
    }

    @Test public void oneRegionCannotPretendMultiAndHardwareRegionCountIsNeverExceeded() {
        when(c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new android.graphics.Rect(0,0,4000,3000));
        PersonalProSettings.saveFocusArea(prefs,CameraType.BACK,PersonalProSettings.FOCUS_AREA_MULTI);
        for(int max:new int[]{1,2,3,5,9,16}) {
            when(c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(max);
            assertEquals(max>1,PersonalProSettings.supportsMultiFocusArea(c));PersonalProSettings.applyFocusArea(builder,c,prefs);
            android.hardware.camera2.params.MeteringRectangle[] areas=builder.get(CaptureRequest.CONTROL_AF_REGIONS);
            assertEquals(Math.min(9,max),areas.length);for(android.hardware.camera2.params.MeteringRectangle a:areas){assertTrue(a.getMeteringWeight()>0);assertTrue(new android.graphics.Rect(0,0,4000,3000).contains(a.getRect()));}
        }
        assertEquals(PersonalProSettings.FOCUS_AREA_MULTI,PersonalProSettings.focusArea(prefs,CameraType.BACK));
    }

}
