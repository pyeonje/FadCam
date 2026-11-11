package com.fadcam.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.animation.ValueAnimator;
import com.fadcam.ui.utils.AnimatedTextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AnimatedSelectionTitleTest {
    @Test public void rapidSelectionCountSurvivesPreviousAnimationCompletion() {
        AnimatedTextView title = new AnimatedTextView(RuntimeEnvironment.getApplication());
        title.setLayoutParams(new android.view.ViewGroup.LayoutParams(800, 90));
        title.setText("Recordings");
        title.measure(android.view.View.MeasureSpec.makeMeasureSpec(800, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(90, android.view.View.MeasureSpec.EXACTLY));
        title.layout(0, 0, 800, 90);
        ReflectionHelpers.callStaticMethod(android.animation.ValueAnimator.class, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(float.class, 1f));
        ReflectionHelpers.setField(title, "lastUpdateTime", System.currentTimeMillis() - 1000L);
        title.animateSlot("Select items", 300);
        assertTrue("Previous title transition must still be running",
                ReflectionHelpers.getField(title, "isSlotAnimating"));
        ValueAnimator previous = ReflectionHelpers.getField(title, "slotAnimator");
        assertNotNull(previous);
        // Isolate the real debounce branch at a95ms age from Robolectric's frame clock.
        ReflectionHelpers.setField(title, "lastUpdateTime", System.currentTimeMillis() - 95L);
        title.animateSlot("1 selected • 93.0 KB", 300);
        assertEquals("1 selected • 93.0 KB", title.getText().toString());
        // Complete the original real animator. Its300ms completion callback must
        // no longer commit its older pending title over the newest selection count.
        previous.end();
        assertEquals("1 selected • 93.0 KB", title.getText().toString());
    }
}
