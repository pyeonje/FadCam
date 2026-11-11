package com.fadcam.ui;
import static org.junit.Assert.*;
import android.view.View;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
/** Explicit screenshot proof task; run only this method, with synthetic HAL. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=android.app.Application.class)
public class PersonalLensRasterTest {
    private final PersonalHomePreviewTest fixture=new PersonalHomePreviewTest();
    @Before public void setup() throws Exception {fixture.setup();}
    @After public void cleanup() {fixture.cleanup();}
    @Test @Config(qualifiers="w384dp-h832dp-600dpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void syntheticHalLensRailRendersOneMovingDiscInActualCameraRoot() throws Exception {
        ReflectionHelpers.callInstanceMethod(fixture,"addS24Lenses");ReflectionHelpers.callInstanceMethod(fixture,"readyControls");
        PersonalHomeFragment fragment=ReflectionHelpers.getField(fixture,"fragment");View root=fragment.requireView();
        int width=1440,height=3120;
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
        PersonalLensSelector row=root.findViewById(R.id.personal_zoom_presets);
        java.io.File project=new java.io.File(System.getProperty("user.dir")).getCanonicalFile();
        while(project!=null && !new java.io.File(project,"settings.gradle").isFile() && !new java.io.File(project,"settings.gradle.kts").isFile())project=project.getParentFile();
        String label=System.getenv("FADCAM_LENS_RASTER_LABEL");
        if(label!=null)assertTrue(label.matches("[a-z0-9-]+"));
        assertNotNull(project);java.io.File folder=label==null?java.nio.file.Files.createTempDirectory("fadcam-lens-raster-").toFile()
                :new java.io.File(project,"tasks/samsung-parity-20261022/control-parity-20261102/"+label);assertTrue(folder.isDirectory()||folder.mkdirs());
        saveLensRaster(root,folder,"camera-wide.png",width,height);
        saveLensRaster(row,folder,"rail-wide.png",row.getWidth(),row.getHeight());
        View tele=null;for(int n=0;n<row.getChildCount();n++)if("T".contentEquals(((android.widget.TextView)row.getChildAt(n)).getText()))tele=row.getChildAt(n);
        assertNotNull(tele);assertTrue(tele.isEnabled());tele.performClick();
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
        android.animation.ValueAnimator animation=ReflectionHelpers.getField(row,"motion");assertNotNull(animation);
        animation.setCurrentFraction(.3f);saveLensRaster(root,folder,"camera-mid-motion.png",width,height);
        saveLensRaster(row,folder,"rail-mid-motion.png",row.getWidth(),row.getHeight());
        animation.end();saveLensRaster(root,folder,"camera-tele.png",width,height);
        saveLensRaster(row,folder,"rail-tele.png",row.getWidth(),row.getHeight());
        assertEquals("6",SharedPreferencesManager.getInstance(org.robolectric.RuntimeEnvironment.getApplication()).getSelectedBackCameraId());
        java.nio.file.Files.write(new java.io.File(folder,"scope.json").toPath(),"{\"scope\":\"Actual product view drawn by Robolectric native raster with synthetic advertised camera capabilities and no camera pixels; not phone or emulator screenshot\",\"width\":1440,\"height\":3120,\"midAnimationFractionForced\":0.3,\"wirelessDebugging\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void saveLensRaster(View root,java.io.File folder,String name,int width,int height) throws Exception {
        assertFalse("Preserve earlier visual evidence",new java.io.File(folder,name).exists());
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(folder,name))) {
            root.draw(new android.graphics.Canvas(image));assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream));
        } finally {image.recycle();}
    }

}
