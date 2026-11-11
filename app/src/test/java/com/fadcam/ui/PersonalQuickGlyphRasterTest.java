package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.camera2.CameraCharacteristics;
import android.view.View;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.google.android.material.button.MaterialButton;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

/** Real button/drawable raster checks, independent of emulated camera content. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,qualifiers="w832dp-h384dp-600dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PersonalQuickGlyphRasterTest {
    public static class Host extends Activity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(R.style.Base_Theme_FadCam);super.onCreate(state);
        }
    }

    @Test public void renderedCommonGlyphsMatchReferenceAxisSizeAndOpticalBalance() {
        Context app=RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        app.getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();
        ActivityController<Host> host=Robolectric.buildActivity(Host.class).setup();
        try {
            View root=host.get().getLayoutInflater().inflate(R.layout.fragment_personal_home,null);
            host.get().setContentView(root);
            SharedPreferencesManager prefs=SharedPreferencesManager.getInstance(app);
            prefs.sharedPreferences.edit().putBoolean(Constants.PREF_TORCH_STATE,false).commit();
            PersonalQuickControls controls=new PersonalQuickControls(host.get(),root,prefs,()->true,()->true,()->{},()->{});
            CameraCharacteristics camera=mock(CameraCharacteristics.class);
            when(camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)).thenReturn(true);
            controls.refresh(camera);controls.reflow(true);controls.setRecordingUi(true);
            PersonalCameraLayout geometry=new PersonalCameraLayout(app);
            // Match the independently captured 3120x1440/600dpi reference one-to-one.
            // A 1536px fixture rounds every127px tile separately before rasterization.
            assertEquals("Reference short edge must not introduce cumulative tile rounding",1440,geometry.px(1440));
            assertEquals(3.75f,app.getResources().getDisplayMetrics().density,0f);
            int width=geometry.px(3120),height=geometry.px(1440);
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            root.layout(0,0,width,height);
            float scale=geometry.px(1440)/1440f;
            int[] ids={R.id.personal_stabilization_control,R.id.personal_mic_control,R.id.personal_torch_control};
            float[] widths={83,65,53},heights={49,65,53},centersY={115.5f,242.5f,369.5f};
            for(int i=0;i<ids.length;i++) {
                MaterialButton button=root.findViewById(ids[i]);
                Bitmap image=Bitmap.createBitmap(button.getWidth(),button.getHeight(),Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas=new Canvas(image);canvas.drawColor(Color.BLACK);button.draw(canvas);
                    int left=image.getWidth(),top=image.getHeight(),right=-1,bottom=-1,count=0;double sumX=0;
                    for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
                        int pixel=image.getPixel(x,y),r=Color.red(pixel),g=Color.green(pixel),b=Color.blue(pixel);
                        if(r<200 || g<180 || !(b>180 || b<80))continue;
                        left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);sumX+=x+.5;count++;
                    }
                    assertTrue("An actual visible glyph must be drawn",count>0);
                    assertEquals("Visible glyph width",widths[i],(right-left+1)/scale,2.5f);
                    assertEquals("Visible glyph height",heights[i],(bottom-top+1)/scale,2.5f);
                    View column=(View)button.getParent();
                    assertEquals("Visible glyph horizontal axis",171.5f,(column.getX()+button.getX()+(left+right+1)/2f)/scale,1.5f);
                    assertEquals("Visible glyph row",centersY[i],(column.getY()+button.getY()+(top+bottom+1)/2f)/scale,1.5f);
                    if(i==0)assertEquals("Overlap glyph optical balance",170.47f,(float)((column.getX()+button.getX()+sumX/count)/scale),2f);
                } finally {image.recycle();}
            }
            controls.dismiss();
        } finally {
            host.pause().stop().destroy();
            ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);
        }
    }
}
