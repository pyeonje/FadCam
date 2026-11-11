package com.fadcam;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import com.fadcam.services.RecordingService;
import org.junit.Before;
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
public class RecordingSavedSettingsTest {
    @Before public void setup() {
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class, "instance", null);
        RuntimeEnvironment.getApplication().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO);
    }

    @Test public void currentModeQuickStartKeepsSavedCameraQualityFpsAndMicrophone() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(app);
        prefs.sharedPreferences.edit()
                .putBoolean(Constants.COMPLETED_ONBOARDING_KEY, true)
                .putString(Constants.PREF_CAMERA_SELECTION, CameraType.FRONT.name())
                .putInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, 3840)
                .putInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, 2160).commit();
        prefs.setSpecificVideoFrameRate(CameraType.FRONT, 24);
        prefs.setSpecificVideoFrameRate(CameraType.BACK, 60);
        prefs.setRecordAudioEnabled(true);
        prefs.setAudioInputSource(SharedPreferencesManager.AUDIO_INPUT_SOURCE_PHONE);
        prefs.setSpecificZoomRatio(CameraType.FRONT, 2.75f);
        prefs.setSavedExposureCompensation(2);
        prefs.setSavedAfMode(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_AUTO);
        com.fadcam.utils.PersonalProSettings.saveExposure(prefs, CameraType.FRONT, 400, 12_500_000L);
        com.fadcam.utils.PersonalProSettings.saveFocus(prefs, CameraType.FRONT, 2.5f);
        com.fadcam.utils.PersonalProSettings.saveWb(prefs, CameraType.FRONT, android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
        // Compare the entire saved map: a quick launch must not overwrite any preset.
        java.util.Map<String, ?> before = new java.util.HashMap<>(prefs.sharedPreferences.getAll());
        Intent quick = new Intent(app, RecordingStartActivity.class)
                .putExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE, RecordingStartActivity.CAMERA_MODE_CURRENT);
        Robolectric.buildActivity(RecordingStartActivity.class, quick).create();
        Intent dispatched = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull(dispatched);
        assertEquals(RecordingService.class.getName(), dispatched.getComponent().getClassName());
        assertEquals(Constants.INTENT_ACTION_START_RECORDING, dispatched.getAction());
        assertFalse(dispatched.hasExtra("SURFACE")); // quick entry never opens an on-screen preview
        assertEquals(before, prefs.sharedPreferences.getAll());
        assertEquals(3840, prefs.getCameraResolution().getWidth());
        assertEquals(24, prefs.getSpecificVideoFrameRate(CameraType.FRONT));
    }
}
