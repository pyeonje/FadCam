package com.fadcam.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.Manifest;
import android.app.Application;
import android.content.Context;
import com.fadcam.*;
import com.fadcam.services.RecordingService;
import com.fadcam.streaming.RemoteStreamManager;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Exercises the actual service start gate, stopping before any camera/encoder setup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class SilentRecordingPermissionTest {
    SharedPreferencesManager prefs;
    RecordingService service;
    @Before public void setup() {
        Application app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        prefs=SharedPreferencesManager.getInstance(app);
        service=Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service,"sharedPreferencesManager",prefs);
        ReflectionHelpers.setField(service,"recordingState",RecordingState.STARTING);
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
    }
    private void startToConfigurationBoundary(boolean expected) {
        RemoteStreamManager streaming=mock(RemoteStreamManager.class);
        // A deliberate sentinel after the permission gate prevents native capture in a JVM test.
        when(streaming.isStreamingEnabled()).thenThrow(new IllegalStateException("QA configuration boundary"));
        try(MockedStatic<RemoteStreamManager> access=mockStatic(RemoteStreamManager.class)) {
            access.when(RemoteStreamManager::getInstance).thenReturn(streaming);
            ReflectionHelpers.callInstanceMethod(service,"startRecording");
            verify(streaming,expected?times(1):never()).isStreamingEnabled();
        }
        assertFalse(prefs.isRecordingInProgress());
    }
    @Test public void silentVideoPassesWithoutMicrophonePermission(){prefs.setRecordAudioEnabled(false);startToConfigurationBoundary(true);}
    @Test public void audibleVideoStillRequiresMicrophonePermission(){prefs.setRecordAudioEnabled(true);startToConfigurationBoundary(false);}
    @Test public void silentVideoStillRequiresCameraPermission(){prefs.setRecordAudioEnabled(false);Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);startToConfigurationBoundary(false);}
}
