package com.fadcam.ui;
import static org.junit.Assert.*;
import android.app.Application;
import android.content.*;
import com.fadcam.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class UsbCameraEntryTest {
 private final PersonalHomePreviewTest fixture=new PersonalHomePreviewTest();
 @Before public void setup(){fixture.setup();}
 @After public void cleanup(){fixture.cleanup();}
 @Test public void usbChoiceOpensInsideFadCamWithoutChangingBuiltInPreset(){
  PersonalHomeFragment home=ReflectionHelpers.getField(fixture,"fragment");ReflectionHelpers.setField(home,"state",RecordingState.NONE);
  SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(RuntimeEnvironment.getApplication());Map<String,?> saved=new HashMap<>(prefs.sharedPreferences.getAll());
  home.requireView().findViewById(R.id.personal_home_camera).performClick();
  androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();assertNotNull(dialog);assertEquals(3,dialog.getListView().getCount());dialog.getListView().performItemClick(null,2,2);
  Intent intent=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();assertNotNull(intent);assertEquals(UsbCameraActivity.class.getName(),intent.getComponent().getClassName());assertEquals(saved,prefs.sharedPreferences.getAll());
 }
}
