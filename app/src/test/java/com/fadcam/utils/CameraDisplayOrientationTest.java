package com.fadcam.utils;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import com.fadcam.SharedPreferencesManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class CameraDisplayOrientationTest {
    @Test public void foregroundUsesBothSensorSidesAndBackgroundHonorsSavedAspect() {
        Context context = RuntimeEnvironment.getApplication();
        Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        for (int rotation : new int[] {Surface.ROTATION_0, Surface.ROTATION_90,
                Surface.ROTATION_180, Surface.ROTATION_270}) {
            Shadows.shadowOf(display).setRotation(rotation);
            boolean landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
            assertEquals(rotation, CameraDisplayOrientation.resolve(context, landscape
                    ? SharedPreferencesManager.ORIENTATION_LANDSCAPE : SharedPreferencesManager.ORIENTATION_PORTRAIT));
        }
        Shadows.shadowOf(display).setRotation(Surface.ROTATION_0);
        assertEquals(Surface.ROTATION_90, CameraDisplayOrientation.resolve(context,
                SharedPreferencesManager.ORIENTATION_LANDSCAPE));
        Shadows.shadowOf(display).setRotation(Surface.ROTATION_90);
        assertEquals(Surface.ROTATION_0, CameraDisplayOrientation.resolve(context,
                SharedPreferencesManager.ORIENTATION_PORTRAIT));
    }
}
