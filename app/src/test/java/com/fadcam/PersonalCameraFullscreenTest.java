package com.fadcam;

import static org.junit.Assert.*;
import android.app.Application;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalCameraFullscreenTest {
    @Test public void galleryWindowUsesItsLightSurfaceAndRestoresHostChromeOnLeaving() {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.Base_Theme_FadCam);activity.setContentView(R.layout.activity_main);
        ReflectionHelpers.setField(activity,"currentFragmentPosition",1);
        ReflectionHelpers.callInstanceMethod(activity,"restoreBarColorsForCurrentTab");
        assertEquals(0xFFFCFCFF,((android.graphics.drawable.ColorDrawable)activity.findViewById(R.id.status_bar_scrim).getBackground()).getColor());
        assertEquals(0xFFFCFCFF,((android.graphics.drawable.ColorDrawable)activity.findViewById(R.id.main_root_layout).getBackground()).getColor());
        ReflectionHelpers.setField(activity,"currentFragmentPosition",4);
        ReflectionHelpers.callInstanceMethod(activity,"restoreBarColorsForCurrentTab");
        assertEquals(0xFF111315,((android.graphics.drawable.ColorDrawable)activity.findViewById(R.id.status_bar_scrim).getBackground()).getColor());
        assertEquals(0xFF171A1C,((android.graphics.drawable.ColorDrawable)activity.findViewById(R.id.main_root_layout).getBackground()).getColor());
        activity.setPersonalCameraFullscreen(true);
        ReflectionHelpers.callInstanceMethod(activity,"restoreBarColorsForCurrentTab");
        assertEquals(View.GONE,activity.findViewById(R.id.status_bar_scrim).getVisibility());
    }
    @Test public void hiddenSystemBarsCannotReviveAnOpaqueFullscreenStatusScrim() {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(R.style.Base_Theme_FadCam);
        activity.setContentView(R.layout.activity_main);
        ReflectionHelpers.callInstanceMethod(activity,"enableEdgeToEdge");
        View content=activity.findViewById(android.R.id.content);
        View scrim=activity.findViewById(R.id.status_bar_scrim);
        activity.setPersonalCameraFullscreen(true);
        ViewCompat.dispatchApplyWindowInsets(content,new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(),Insets.NONE).build());
        assertEquals(View.GONE,scrim.getVisibility());
        assertEquals(View.GONE,activity.findViewById(R.id.nav_container).getVisibility());
        activity.setPersonalCameraFullscreen(false);
        ViewCompat.dispatchApplyWindowInsets(content,new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,0,16)).build());
        assertEquals(View.VISIBLE,scrim.getVisibility());
        assertEquals(24,scrim.getLayoutParams().height);
        assertEquals(View.VISIBLE,activity.findViewById(R.id.nav_container).getVisibility());
    }
}
