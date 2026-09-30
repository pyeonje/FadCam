package com.fadcam;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import com.fadcam.dualcam.DualCameraState;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import org.junit.After;
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
@Config(sdk = 28, application = Application.class)
public class RecordingToggleActivityTest {
    @Before public void resetLiveServicesAndToggleGuard() {
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class, "instance", null);
        RuntimeEnvironment.getApplication().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
        ReflectionHelpers.setStaticField(RecordingService.class, "liveInstance", null);
        ReflectionHelpers.setStaticField(DualCameraRecordingService.class, "liveInstance", null);
        Object guard = ReflectionHelpers.getStaticField(RecordingToggleActivity.class, "TOGGLE_GUARD");
        ReflectionHelpers.setField(guard, "lastAcceptedAt", Long.MIN_VALUE);
    }

    @After public void clearLiveServices() {
        ReflectionHelpers.setStaticField(RecordingService.class, "liveInstance", null);
        ReflectionHelpers.setStaticField(DualCameraRecordingService.class, "liveInstance", null);
    }

    @Test public void stalePreferencesAfterProcessDeathRouteToStartNotStop() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
        prefs.setRecordingInProgress(true);
        prefs.sharedPreferences.edit().putLong(Constants.PREF_RECORDING_START_TIME, 1000L).commit();
        RecordingToggleActivity activity = Robolectric.buildActivity(RecordingToggleActivity.class).create().get();
        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(RecordingStartActivity.class.getName(), launched.getComponent().getClassName());
        assertEquals(RecordingStartActivity.CAMERA_MODE_CURRENT,
                launched.getStringExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE));
        assertFalse(prefs.isRecordingInProgress());
        assertEquals(0L, prefs.sharedPreferences.getLong(Constants.PREF_RECORDING_START_TIME, 0L));
    }

    @Test public void activeMainSessionRoutesQuickToggleToTheSameSingleCameraService() {
        RecordingService service = Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service, "recordingState", RecordingState.IN_PROGRESS);
        ReflectionHelpers.setStaticField(RecordingService.class, "liveInstance", service);
        SharedPreferencesManager.getInstance(RuntimeEnvironment.getApplication()).setRecordingInProgress(false);
        RecordingToggleActivity activity = Robolectric.buildActivity(RecordingToggleActivity.class).create().get();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        Intent stopped = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull(stopped);
        assertEquals(RecordingService.class.getName(), stopped.getComponent().getClassName());
        assertEquals(Constants.INTENT_ACTION_STOP_RECORDING, stopped.getAction());
    }

    @Test public void pausedDualSessionRoutesToItsOwnServiceEvenWithDifferentSavedCamera() {
        DualCameraRecordingService service = Robolectric.buildService(DualCameraRecordingService.class).get();
        ReflectionHelpers.setField(service, "state", DualCameraState.PAUSED);
        ReflectionHelpers.setStaticField(DualCameraRecordingService.class, "liveInstance", service);
        SharedPreferencesManager.getInstance(RuntimeEnvironment.getApplication()).sharedPreferences.edit()
                .putString(Constants.PREF_CAMERA_SELECTION, CameraType.BACK.name()).commit();
        Robolectric.buildActivity(RecordingToggleActivity.class).create();
        Intent stopped = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull(stopped);
        assertEquals(DualCameraRecordingService.class.getName(), stopped.getComponent().getClassName());
        assertEquals(Constants.INTENT_ACTION_STOP_DUAL_RECORDING, stopped.getAction());
    }

    @Test public void serviceGetterIncludesStartingButExcludesIdleQueriesAndCleanup() {
        RecordingService service = Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setStaticField(RecordingService.class, "liveInstance", service);
        assertFalse(RecordingService.hasActiveSession()); // Merely querying creates an idle service.
        ReflectionHelpers.setField(service, "recordingState", RecordingState.STARTING);
        assertTrue(RecordingService.hasActiveSession());
        ReflectionHelpers.setField(service, "recordingState", RecordingState.PAUSED);
        assertEquals(RecordingState.PAUSED, RecordingService.getSessionState());
        ReflectionHelpers.setField(service, "isStopping", true);
        assertFalse(RecordingService.hasActiveSession());
        ReflectionHelpers.setStaticField(RecordingService.class, "liveInstance", null); // Fresh process.
        assertEquals(RecordingState.NONE, RecordingService.getSessionState());
    }
}
