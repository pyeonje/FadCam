package com.fadcam.utils;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.view.Surface;
import android.view.WindowManager;
import com.fadcam.Constants;
import com.fadcam.RecordingState;
import com.fadcam.SharedPreferencesManager;
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

/** Session policy/control-flow qualification; no camera/codec/device execution. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w480dp-h1040dp-port")
public class PersonalRecordingOrientationTest {
    private Context app;
    private SharedPreferencesManager prefs;
    private RecordingService service;
    @Before public void setup() {
        app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        prefs=SharedPreferencesManager.getInstance(app);
        service=Robolectric.buildService(RecordingService.class).get();
        ReflectionHelpers.setField(service,"sharedPreferencesManager",prefs);
        ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",service);
    }
    @After public void cleanup(){ReflectionHelpers.setStaticField(RecordingService.class,"liveInstance",null);}
    private int mapped(int rotation,int configuration,boolean reverse) {
        return ReflectionHelpers.callStaticMethod(RecordingService.class,"displayOrientation",
                ReflectionHelpers.ClassParameter.from(int.class,rotation),
                ReflectionHelpers.ClassParameter.from(int.class,configuration),
                ReflectionHelpers.ClassParameter.from(boolean.class,reverse));
    }
    private void capture(Intent intent) {
        ReflectionHelpers.callInstanceMethod(service,"captureSessionDisplayOrientation",
                ReflectionHelpers.ClassParameter.from(Intent.class,intent));
    }
    @Test public void portraitAndLandscapeNaturalDisplaysMapAllFourDirectionsIncludingReversePolicy() {
        int[] portrait={ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE};
        int[] landscape={ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};
        for(int rotation=0;rotation<4;rotation++) {
            int phoneConfig=rotation%2==0?Configuration.ORIENTATION_PORTRAIT:Configuration.ORIENTATION_LANDSCAPE;
            int tabletConfig=rotation%2==0?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;
            assertEquals(portrait[rotation],mapped(rotation,phoneConfig,false));
            assertEquals(landscape[rotation],mapped(rotation,tabletConfig,false));
            assertEquals(portrait[rotation%2==0?rotation:4-rotation],mapped(rotation,phoneConfig,true));
            assertEquals(landscape[rotation%2==0?rotation:4-rotation],mapped(rotation,tabletConfig,true));
        }
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,mapped(-1,Configuration.ORIENTATION_PORTRAIT,false));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,mapped(0,Configuration.ORIENTATION_UNDEFINED,false));
    }
    @Test public void acceptedForegroundSnapshotSurvivesStartingPausedAndDisplayChangeUntilSessionEnds() {
        capture(new Intent().putExtra(RecordingService.EXTRA_SESSION_DISPLAY_ORIENTATION,ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,RecordingService.getSessionDisplayOrientation());
        for(RecordingState state:new RecordingState[]{RecordingState.STARTING,RecordingState.IN_PROGRESS,RecordingState.PAUSED}) {
            ReflectionHelpers.setField(service,"recordingState",state);
            Shadows.shadowOf(((WindowManager)app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay()).setRotation(Surface.ROTATION_0);
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,RecordingService.getSessionDisplayOrientation());
        }
        ReflectionHelpers.setField(service,"recordingState",RecordingState.NONE);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,RecordingService.getSessionDisplayOrientation());
        capture(new Intent().putExtra(RecordingService.EXTRA_SESSION_DISPLAY_ORIENTATION,ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        ReflectionHelpers.setField(service,"recordingState",RecordingState.STARTING);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,RecordingService.getSessionDisplayOrientation());
    }
    @Test public void backgroundQuickStartHonorsSavedAspectWithoutInheritingOldSessionOrInvalidExtra() {
        Shadows.shadowOf(((WindowManager)app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay()).setRotation(Surface.ROTATION_0);
        prefs.setVideoOrientation(SharedPreferencesManager.ORIENTATION_LANDSCAPE);
        capture(new Intent());ReflectionHelpers.setField(service,"recordingState",RecordingState.STARTING);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,RecordingService.getSessionDisplayOrientation());
        prefs.setVideoOrientation(SharedPreferencesManager.ORIENTATION_PORTRAIT);
        capture(new Intent().putExtra(RecordingService.EXTRA_SESSION_DISPLAY_ORIENTATION,ActivityInfo.SCREEN_ORIENTATION_LOCKED));
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,RecordingService.getSessionDisplayOrientation());
    }
    @Test public void unknownStartingSessionHasNoFabricatedDirection() {
        ReflectionHelpers.setField(service,"recordingState",RecordingState.STARTING);
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,RecordingService.getSessionDisplayOrientation());
    }
}
