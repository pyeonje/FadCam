package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.net.Uri;
import android.view.View;
import androidx.fragment.app.FragmentActivity;
import com.fadcam.R;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class)
public class GallerySelectedTimelineBoundsTest {
    private GalleryMediaStrip strip() {
        FragmentActivity activity=Robolectric.buildActivity(FragmentActivity.class).setup().get();activity.setTheme(R.style.Base_Theme_FadCam);
        GalleryMediaStrip strip=new GalleryMediaStrip(activity,null);strip.setReferenceShortEdge(1440);
        VideoItem first=new VideoItem(Uri.parse("content://test/first"),"",1,1);
        VideoItem current=new VideoItem(Uri.parse("content://test/current"),"",1,1);
        VideoItem last=new VideoItem(Uri.parse("content://test/last"),"",1,1);
        strip.bindMedia(Arrays.asList(first,current,last),current.uri,mock(GalleryMediaStrip.Listener.class));
        strip.setExpanded(true,53000,26000);layout(strip);
        ReflectionHelpers.callInstanceMethod(strip,"centerSelectedNow");layout(strip);return strip;
    }
    private void layout(GalleryMediaStrip strip) {
        for(int n=0;n<3;n++){strip.measure(View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(184,View.MeasureSpec.EXACTLY));strip.layout(0,0,1440,184);}
    }
    private View current(GalleryMediaStrip strip) { View selected=strip.getLayoutManager().findViewByPosition(1);assertNotNull("Selected timeline cannot disappear into adjacent media",selected);return selected; }
    @Test public void hugeLeftwardScrollStopsAtSelectedVideosVisibleEndNotOtherItems() {
        GalleryMediaStrip strip=strip();strip.scrollBy(100000,0);layout(strip);View selected=current(strip);
        assertEquals(strip.getWidth()/2,selected.getRight()-selected.getPaddingRight());
        assertEquals(53000L,(long)ReflectionHelpers.callInstanceMethod(strip,"positionAtCursor"));strip.close();
    }
    @Test public void hugeRightwardScrollStopsAtSelectedVideosVisibleStartNotOtherItems() {
        GalleryMediaStrip strip=strip();strip.scrollBy(-100000,0);layout(strip);View selected=current(strip);
        assertEquals(strip.getWidth()/2,selected.getLeft()+selected.getPaddingLeft());
        assertEquals(0L,(long)ReflectionHelpers.callInstanceMethod(strip,"positionAtCursor"));strip.close();
    }
    @Test public void repeatedOverscrollCannotChangePositionBeyondEitherEndpoint() {
        GalleryMediaStrip strip=strip();
        for(int n=0;n<6;n++)strip.scrollBy(10000,0);layout(strip);View selected=current(strip);
        assertEquals(strip.getWidth()/2,selected.getRight()-selected.getPaddingRight());
        for(int n=0;n<6;n++)strip.scrollBy(-10000,0);layout(strip);selected=current(strip);
        assertEquals(strip.getWidth()/2,selected.getLeft()+selected.getPaddingLeft());strip.close();
    }
}
