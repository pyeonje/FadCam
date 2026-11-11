package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.graphics.*;
import android.hardware.camera2.*;
import android.view.*;
import android.widget.TextView;
import com.fadcam.*;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.PersonalProSettings;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

/** Reuses the real Home test fixture, with native rasterization and actual root dispatch.
 * No camera pixels or physical device are inspected. */
@Config(sdk=35,application=android.app.Application.class,qualifiers="w1040dp-h480dp-land-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PersonalFocusVisualTest extends PersonalHomePreviewTest {
    private void measure(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(3120,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));root.layout(0,0,3120,1440);
    }
    private void capture(View root,File folder,String name) throws Exception {
        measure(root);Bitmap bitmap=Bitmap.createBitmap(3120,1440,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);root.draw(canvas);
        Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);label.setColor(Color.WHITE);label.setTextSize(25);
        canvas.drawText("Robolectric / simulated Camera2 capabilities / no phone or camera pixels",40,1415,label);
        File file=new File(folder,name+".png");assertFalse(file.exists());try(FileOutputStream output=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();
    }
    private void dispatch(View root,int action,float x,float y) {
        long now=android.os.SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);assertTrue(root.dispatchTouchEvent(event));event.recycle();
    }
    @Test public void realInflatedHomeDispatchSwitchesAfToMfAndCapturesCenterMulti() throws Exception {
        File folder=Files.createTempDirectory("fadcam-focus-visual-").toFile();
        System.out.println("Focus visual evidence: "+folder.getAbsolutePath());
        PersonalHomeFragment fragment=ReflectionHelpers.getField(this,"fragment");RecordingService service=ReflectionHelpers.getField(this,"service");
        ReflectionHelpers.setField(service,"previewOnlyActive",true);ReflectionHelpers.callInstanceMethod(fragment,"render");
        View root=fragment.requireView();
        CameraManager manager=(CameraManager)fragment.requireContext().getSystemService(android.content.Context.CAMERA_SERVICE);
        CameraCharacteristics lens=manager.getCameraCharacteristics("0");when(lens.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)).thenReturn(3);when(lens.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)).thenReturn(new Rect(0,0,4000,3000));
        PersonalCameraControls camera=ReflectionHelpers.getField(fragment,"cameraControls");camera.refresh();camera.reflow(true);measure(root);
        PersonalProControls pro=ReflectionHelpers.getField(camera,"pro");root.findViewById(R.id.personal_pro_focus).performClick();measure(root);
        TextView value=root.findViewById(R.id.personal_pro_selected_value);assertEquals(fragment.getString(R.string.personal_focus_center),value.getText().toString());capture(root,folder,"center-af");
        View ruler=root.findViewById(R.id.personal_pro_slider);int[] location=new int[2];ruler.getLocationOnScreen(location);int[] origin=new int[2];root.getLocationOnScreen(origin);
        float x=location[0]-origin[0]+ruler.getWidth()*160f/222f,y=location[1]-origin[1]+ruler.getHeight()/2f;
        dispatch(root,MotionEvent.ACTION_DOWN,x,y+80);dispatch(root,MotionEvent.ACTION_MOVE,x,y-200);dispatch(root,MotionEvent.ACTION_UP,x,y-200);
        SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(fragment.requireContext());assertTrue(PersonalProSettings.usesManualFocus(prefs,CameraType.BACK,lens));
        assertEquals("MF",((TextView)root.findViewById(R.id.personal_pro_auto)).getText().toString());assertNotEquals("AF",value.getText().toString());capture(root,folder,"dragged-mf");
        root.findViewById(R.id.personal_pro_auto).performClick();value.performClick();
        androidx.appcompat.widget.PopupMenu menu=ReflectionHelpers.getField(pro,"focusAreaMenu");assertNotNull(menu);assertTrue(menu.getMenu().performIdentifierAction(PersonalProSettings.FOCUS_AREA_MULTI,0));menu.dismiss();
        assertEquals(fragment.getString(R.string.personal_focus_multi),value.getText().toString());capture(root,folder,"multi-af");
        File note=new File(folder,"scope.txt");
        try(FileOutputStream output=new FileOutputStream(note)){output.write("Real inflated product Home; native graphics; actual root MotionEvent dispatch; mocked Camera2 capability; no physical camera image or autofocus quality verification.".getBytes(StandardCharsets.UTF_8));}
    }
}
