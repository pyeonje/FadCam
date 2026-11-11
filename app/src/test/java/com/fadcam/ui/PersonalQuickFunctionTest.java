package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.view.View;
import com.fadcam.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalQuickFunctionTest {
    public static class Host extends Activity {
        @Override public void onCreate(android.os.Bundle state) {setTheme(R.style.Base_Theme_FadCam);super.onCreate(state);}
    }
    @Test public void unavailableStabilizationCannotChangePresetOrDispatchWhileOpticalAndDigitalCan() {
        Context app=RuntimeEnvironment.getApplication();ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);prefs.setVideoStabilizationEnabled(false);
        ActivityController<Host> host=Robolectric.buildActivity(Host.class).setup();
        try {
            View root=host.get().getLayoutInflater().inflate(R.layout.fragment_personal_home,null);host.get().setContentView(root);
            PersonalQuickControls controls=new PersonalQuickControls(host.get(),root,prefs,()->true,()->true,()->{},()->{});
            CameraCharacteristics c=mock(CameraCharacteristics.class);
            controls.refresh(c);View button=root.findViewById(R.id.personal_stabilization_control);
            assertFalse(button.isEnabled());button.performClick();assertFalse(prefs.isVideoStabilizationEnabled());
            assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
            when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)).thenReturn(new int[]{CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON});
            controls.refresh(c);assertTrue(button.isEnabled());button.performClick();assertTrue(prefs.isVideoStabilizationEnabled());
            android.content.Intent action=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertEquals(Constants.INTENT_ACTION_SET_VIDEO_STABILIZATION,action.getAction());assertTrue(action.getBooleanExtra(Constants.EXTRA_VIDEO_STABILIZATION_ENABLED,false));
            when(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)).thenReturn(new int[]{CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF});
            when(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON});
            controls.refresh(c);assertTrue(button.isEnabled());button.performClick();assertFalse(prefs.isVideoStabilizationEnabled());controls.dismiss();
        } finally {host.pause().stop().destroy();ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);}
    }
}
