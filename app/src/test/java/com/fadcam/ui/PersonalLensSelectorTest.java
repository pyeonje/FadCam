package com.fadcam.ui;

import static org.junit.Assert.*;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.graphics.RectF;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class PersonalLensSelectorTest {
    private ActivityController<Activity> host;
    private PersonalLensSelector rail;
    @Before public void setup() {
        host=Robolectric.buildActivity(Activity.class).setup();rail=new PersonalLensSelector(host.get(),null);
        rail.setOrientation(LinearLayout.HORIZONTAL);host.get().setContentView(rail);
        for(String label:new String[]{"UW","W","T","ST"}) {
            TextView button=new TextView(host.get());button.setText(label);rail.addView(button,new LinearLayout.LayoutParams(136,120));
        }
        rail.selectIndex(1);layout(544,120);
    }
    private void layout(int width,int height) {
        rail.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY));
        rail.layout(0,0,width,height);
    }
    private RectF rect(String field) {return ReflectionHelpers.getField(rail,field);}
    @After public void cleanup(){host.pause().stop().destroy();}
    @Test public void firstSelectionIsOneDiscAndChildrenNeverOwnIndependentBackgrounds() {
        assertEquals(new RectF(144,0,264,120),rect("displayed"));assertNull(ReflectionHelpers.getField(rail,"motion"));
        for(int n=0;n<4;n++)assertNull(rail.getChildAt(n).getBackground());
    }
    @Test public void selectionMovesSharedHighlightContinuouslyAndRapidRetargetStartsAtCurrentPosition() {
        rail.selectIndex(2);layout(544,120);
        ValueAnimator motion=ReflectionHelpers.getField(rail,"motion");assertNotNull(motion);
        assertEquals(200,motion.getDuration());assertEquals(144,rect("displayed").left,.01f);
        motion.setCurrentFraction(.3f);float intermediate=rect("displayed").left;
        assertTrue(intermediate>144 && intermediate<280);
        assertTrue(rail.getChildAt(1).getScaleX()>1f && rail.getChildAt(1).getScaleX()<1.25f);
        assertTrue(rail.getChildAt(2).getScaleX()>1f && rail.getChildAt(2).getScaleX()<1.25f);
        rail.selectIndex(3);layout(544,120);
        assertEquals(intermediate,rect("displayed").left,.01f);
        motion=ReflectionHelpers.getField(rail,"motion");motion.end();assertEquals(416,rect("displayed").left,.01f);
    }
    @Test public void orientationAndDetachEndMotionWithoutStaleCoordinatesOrTasks() {
        rail.selectIndex(2);layout(544,120);ValueAnimator motion=ReflectionHelpers.getField(rail,"motion");assertNotNull(motion);
        rail.setOrientation(LinearLayout.VERTICAL);layout(136,480);
        assertFalse(motion.isRunning());assertNull(ReflectionHelpers.getField(rail,"motion"));
        assertEquals(new RectF(8,240,128,360),rect("displayed"));
        rail.selectIndex(3);layout(136,480);motion=ReflectionHelpers.getField(rail,"motion");assertNotNull(motion);
        host.get().setContentView(new android.view.View(host.get()));
        assertFalse(motion.isRunning());assertNull(ReflectionHelpers.getField(rail,"motion"));
    }
    @Test public void invalidSelectionRemovesHighlightRatherThanBorrowingAnOldLens() {
        rail.selectIndex(-1);layout(544,120);assertTrue(rect("displayed").isEmpty());
    }
}
