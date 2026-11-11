package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.utils.PersonalCameraTelemetry;
import com.fadcam.utils.PersonalProSettings;
import com.google.android.material.button.MaterialButton;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Executes the real controls and ruler; no fabricated camera or screen pixels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalManualFocusControlsTest {
    public static class Host extends Activity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(R.style.Base_Theme_FadCam);super.onCreate(state);
        }
    }
    private ActivityController<Host> host;
    private FrameLayout root;
    private PersonalProControls controls;
    private PersonalCameraRuler ruler;
    private SharedPreferencesManager prefs;
    private CameraCharacteristics lens;
    private final AtomicBoolean ready=new AtomicBoolean(true);

    @Before public void setup() {
        Context app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        prefs=SharedPreferencesManager.getInstance(app);prefs.setSelectedBackCameraId("0");
        PersonalCameraTelemetry.callback(CameraType.BACK,"0");
        host=Robolectric.buildActivity(Host.class).setup();
        root=new FrameLayout(host.get());host.get().setContentView(root);
        FrameLayout panel=new FrameLayout(host.get());panel.setId(R.id.personal_pro_panel);
        panel.setVisibility(View.GONE);root.addView(panel);
        ruler=new PersonalCameraRuler(host.get(),null);ruler.setId(R.id.personal_pro_slider);
        panel.addView(ruler,new FrameLayout.LayoutParams(1000,52));
        TextView text=new TextView(host.get());text.setId(R.id.personal_pro_selected_value);panel.addView(text);
        for(int id:new int[]{R.id.personal_pro_auto,R.id.personal_pro_close}) {
            MaterialButton button=new MaterialButton(host.get());button.setId(id);panel.addView(button);
        }
        for(int id:new int[]{R.id.personal_pro_iso,R.id.personal_pro_speed,R.id.personal_pro_ev,R.id.personal_pro_focus,R.id.personal_pro_wb,R.id.personal_pro_reset}) {
            MaterialButton button=new MaterialButton(host.get());button.setId(id);root.addView(button);
        }
        for(int id:new int[]{R.id.personal_zoom_presets_container,R.id.personal_zoom_value,R.id.personal_zoom_slider,R.id.personal_camera_more}) {
            View view=new View(host.get());view.setId(id);
            if(id==R.id.personal_zoom_slider)view.setVisibility(View.GONE); // Match the actual closed-zoom XML default.
            root.addView(view);
        }
        lens=mock(CameraCharacteristics.class);
        when(lens.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(5f);
        when(lens.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)).thenReturn(new int[]{CaptureRequest.CONTROL_AF_MODE_OFF,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO});
        controls=new PersonalProControls(host.get(),root,prefs,ready::get);controls.refresh(lens);
        drainServices();
    }
    @After public void cleanup() {if(controls!=null)controls.release();if(host!=null)host.pause().stop().destroy();}
    private int drainServices() {
        int count=0;Intent next;
        while((next=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService())!=null)
            if(PersonalProSettings.ACTION_APPLY.equals(next.getAction()))count++;
        return count;
    }
    private void focusPanel() {root.findViewById(R.id.personal_pro_focus).performClick();ruler.layout(0,0,1000,52);}
    private void touch(int action,float x) {
        long now=android.os.SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,action,x,26,0);ruler.onTouchEvent(event);event.recycle();
    }
    private String selectedValue(){return ((TextView)root.findViewById(R.id.personal_pro_selected_value)).getText().toString();}

    @Test public void adjustmentHidesMoreAcrossRefreshAndRestoresItForEachClosePath() {
        View more=root.findViewById(R.id.personal_camera_more);
        assertEquals(View.VISIBLE,more.getVisibility());focusPanel();
        assertEquals(View.INVISIBLE,more.getVisibility());
        controls.refresh(lens);controls.reflow(true);
        assertEquals(View.VISIBLE,root.findViewById(R.id.personal_pro_panel).getVisibility());
        assertEquals(View.INVISIBLE,more.getVisibility());
        root.findViewById(R.id.personal_pro_close).performClick();assertEquals(View.VISIBLE,more.getVisibility());
        focusPanel();assertEquals(View.INVISIBLE,more.getVisibility());
        root.findViewById(R.id.personal_pro_focus).performClick();assertEquals(View.VISIBLE,more.getVisibility());
        focusPanel();assertEquals(View.INVISIBLE,more.getVisibility());
        controls.refresh(null);assertEquals(View.GONE,root.findViewById(R.id.personal_pro_panel).getVisibility());
        assertEquals(View.VISIBLE,more.getVisibility());assertEquals(0,drainServices());
    }

    @Test public void mfCanStartBeforeTelemetryAndContinuousDragShowsHundredthsAndCoalescesRequests() {
        focusPanel();assertEquals("AF",selectedValue());
        root.findViewById(R.id.personal_pro_auto).performClick();
        assertEquals(0f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        assertEquals("0.00",selectedValue());assertEquals("MF",((TextView)root.findViewById(R.id.personal_pro_auto)).getText().toString());
        drainServices();touch(MotionEvent.ACTION_DOWN,0);
        for(int i=1;i<=50;i++)touch(MotionEvent.ACTION_MOVE,6.5f*i);
        assertEquals(2.5f,PersonalProSettings.focus(prefs,CameraType.BACK),.0001f);
        assertEquals("0.50",selectedValue());assertEquals(0,drainServices());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));assertEquals(1,drainServices());
        touch(MotionEvent.ACTION_MOVE,650);touch(MotionEvent.ACTION_UP,650);
        assertEquals(5f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);assertEquals("1.00",selectedValue());
        assertEquals(1,drainServices());
        root.findViewById(R.id.personal_pro_auto).performClick();
        assertEquals(-1f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);assertEquals("AF",selectedValue());
    }
    @Test public void reopeningAndPausedReadinessPreserveMfWhileOtherLensStartsAfAndFixedLensIsDisabled() {
        PersonalProSettings.saveFocus(prefs,CameraType.BACK,2.5f);focusPanel();assertEquals("0.50",selectedValue());
        controls.hide();focusPanel();assertEquals("0.50",selectedValue());
        ready.set(false);controls.updateEnabled();ready.set(true);controls.updateEnabled();focusPanel();
        assertEquals("0.50",selectedValue());
        prefs.setSelectedBackCameraId("tele");controls.refresh(lens);
        assertEquals("AF",((TextView)root.findViewById(R.id.personal_pro_focus)).getText().toString());
        assertEquals(View.GONE,root.findViewById(R.id.personal_pro_panel).getVisibility());
        prefs.setSelectedBackCameraId("0");controls.refresh(lens);focusPanel();assertEquals("0.50",selectedValue());
        when(lens.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(0f);controls.refresh(lens);
        assertFalse(root.findViewById(R.id.personal_pro_focus).isEnabled());
        assertEquals("AF",((TextView)root.findViewById(R.id.personal_pro_focus)).getText().toString());
        assertEquals(View.GONE,root.findViewById(R.id.personal_pro_panel).getVisibility());
    }
    @Test public void lensChangeCancelsUnsentOldLensApplyAndKeepsSavedOldLensValue() {
        focusPanel();touch(MotionEvent.ACTION_DOWN,0);touch(MotionEvent.ACTION_MOVE,325);
        assertEquals(2.5f,PersonalProSettings.focus(prefs,CameraType.BACK),.0001f);
        prefs.setSelectedBackCameraId("tele");controls.refresh(lens);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertEquals(0,drainServices());
        assertEquals(-1f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
        prefs.setSelectedBackCameraId("0");assertEquals(2.5f,PersonalProSettings.focus(prefs,CameraType.BACK),.0001f);
    }
    @Test public void rulerFirstTouchEntersMfEvenAtZeroAndCloseCancelsOldGesture() {
        focusPanel();assertEquals("AF",selectedValue());
        touch(MotionEvent.ACTION_DOWN,500);assertEquals("0.00",selectedValue());
        assertEquals("MF",((TextView)root.findViewById(R.id.personal_pro_auto)).getText().toString());
        root.findViewById(R.id.personal_pro_close).performClick();drainServices();
        touch(MotionEvent.ACTION_MOVE,900);touch(MotionEvent.ACTION_UP,900);
        assertEquals(0f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);assertEquals(0,drainServices());
        focusPanel();root.findViewById(R.id.personal_pro_auto).performClick();assertEquals("AF",selectedValue());
    }
    @Test public void actualFocusAreaMenuChangesPolicyAndIsDismissedOnLensReplacement() {
        when(lens.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(3);
        when(lens.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new android.graphics.Rect(0,0,4000,3000));
        controls.refresh(lens);focusPanel();assertEquals(host.get().getString(R.string.personal_focus_center),selectedValue());
        root.findViewById(R.id.personal_pro_selected_value).performClick();
        androidx.appcompat.widget.PopupMenu menu=ReflectionHelpers.getField(controls,"focusAreaMenu");assertNotNull(menu);
        assertTrue(menu.getMenu().performIdentifierAction(PersonalProSettings.FOCUS_AREA_MULTI,0));
        assertEquals(PersonalProSettings.FOCUS_AREA_MULTI,PersonalProSettings.focusArea(prefs,CameraType.BACK));
        assertEquals(host.get().getString(R.string.personal_focus_multi),selectedValue());assertEquals(1,drainServices());
        prefs.setSelectedBackCameraId("tele");controls.refresh(lens);assertNull(ReflectionHelpers.getField(controls,"focusAreaMenu"));
        assertEquals(View.GONE,root.findViewById(R.id.personal_pro_panel).getVisibility());
    }

    @Test public void areaOnlyLensCanSelectCenterMultiWithoutPretendingManualDistance() {
        when(lens.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(1);
        when(lens.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new android.graphics.Rect(0,0,4000,3000));
        when(lens.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)).thenReturn(0f);
        controls.refresh(lens);assertTrue(root.findViewById(R.id.personal_pro_focus).isEnabled());focusPanel();
        assertFalse(ruler.isEnabled());assertFalse(root.findViewById(R.id.personal_pro_auto).isEnabled());
        assertTrue(root.findViewById(R.id.personal_pro_selected_value).isClickable());
        touch(MotionEvent.ACTION_DOWN,0);touch(MotionEvent.ACTION_MOVE,650);touch(MotionEvent.ACTION_UP,650);
        assertEquals(-1f,PersonalProSettings.focus(prefs,CameraType.BACK),0f);
    }

    @Test public void singleRegionLensShowsMultiChoiceDisabledAndDoesNotWriteFalsePolicy() {
        when(lens.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(1);
        when(lens.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new android.graphics.Rect(0,0,4000,3000));
        controls.refresh(lens);focusPanel();root.findViewById(R.id.personal_pro_selected_value).performClick();
        androidx.appcompat.widget.PopupMenu menu=ReflectionHelpers.getField(controls,"focusAreaMenu");
        assertTrue(menu.getMenu().findItem(PersonalProSettings.FOCUS_AREA_CENTER).isEnabled());assertFalse(menu.getMenu().findItem(PersonalProSettings.FOCUS_AREA_MULTI).isEnabled());
        assertFalse(menu.getMenu().performIdentifierAction(PersonalProSettings.FOCUS_AREA_MULTI,0));assertEquals(PersonalProSettings.FOCUS_AREA_CENTER,PersonalProSettings.focusArea(prefs,CameraType.BACK));
    }

}
