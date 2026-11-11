package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.net.Uri;
import android.view.View;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.fadcam.R;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class GallerySeekBoundaryTest {
    private static final long LONG_VIDEO=35*60*1000L;
    private GalleryMediaStrip strip(int width,int shortEdge) {
        FragmentActivity activity=Robolectric.buildActivity(FragmentActivity.class).setup().get();
        activity.setTheme(R.style.Base_Theme_FadCam);
        GalleryMediaStrip strip=new GalleryMediaStrip(activity,null);
        strip.setReferenceShortEdge(shortEdge);
        Uri uri=Uri.parse("content://test/long-video");
        strip.bindMedia(Collections.singletonList(new VideoItem(uri,"",1,1)),uri,mock(GalleryMediaStrip.Listener.class));
        strip.setExpanded(true,LONG_VIDEO,0);
        layout(strip,width,Math.round(184*shortEdge/1440f));return strip;
    }
    private void layout(GalleryMediaStrip strip,int width,int height) {
        for(int pass=0;pass<3;pass++) {
            strip.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            strip.layout(0,0,width,height);
        }
    }
    private long cursorPosition(GalleryMediaStrip strip) {
        View selected=strip.getLayoutManager().findViewByPosition(0);assertNotNull(selected);
        return GalleryMediaStrip.seekPosition(LONG_VIDEO,strip.getWidth()/2,selected.getLeft()+selected.getPaddingLeft(),selected.getWidth()-selected.getPaddingLeft()-selected.getPaddingRight());
    }
    @Test public void firstVideoCanReachExactlyZeroInLandscapeEvenFor35MinuteVideo() {
        GalleryMediaStrip strip=strip(3120,1440);
        strip.scrollBy(-100000,0);layout(strip,3120,184);
        assertEquals("Start must not clamp about81seconds into this video",0L,cursorPosition(strip));strip.close();
    }
    @Test public void lastVideoCanReachItsFullDurationAndRewindFromThere() {
        GalleryMediaStrip strip=strip(3120,1440);
        strip.scrollBy(100000,0);layout(strip,3120,184);
        assertEquals(LONG_VIDEO,cursorPosition(strip));
        strip.scrollBy(-100000,0);layout(strip,3120,184);
        assertEquals(0L,cursorPosition(strip));strip.close();
    }
    @Test public void portraitEndpointsAlsoReachZeroAndDurationWithoutDoubleTap() {
        GalleryMediaStrip strip=strip(1440,1440);
        strip.scrollBy(-100000,0);layout(strip,1440,184);assertEquals(0L,cursorPosition(strip));
        strip.scrollBy(100000,0);layout(strip,1440,184);assertEquals(LONG_VIDEO,cursorPosition(strip));strip.close();
    }
}
