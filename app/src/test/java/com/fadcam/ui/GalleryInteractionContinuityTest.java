package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.net.Uri;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import com.fadcam.R;
import com.google.android.material.slider.Slider;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class GalleryInteractionContinuityTest {
    private FragmentActivity host() {
        FragmentActivity host=Robolectric.buildActivity(FragmentActivity.class).setup().get();
        host.setTheme(R.style.Base_Theme_FadCam); return host;
    }
    private VideoItem item(String name) { return new VideoItem(Uri.parse("file:///"+name+".mp4"),name,1,1); }
    private GalleryMediaStrip strip(FragmentActivity host, AtomicReference<VideoItem> selected) {
        GalleryMediaStrip strip=new GalleryMediaStrip(host,null);
        strip.bindMedia(Arrays.asList(item("first"),item("second")), item("first").uri,new GalleryMediaStrip.Listener() {
            public void onCurrentVideoTap() {}
            public void onMediaSelected(VideoItem item) { selected.set(item); }
            public void onFrameSeekStarted() { fail("Media navigation must not scrub"); }
            public void onFrameSeek(long position,boolean finished) { fail("Media navigation must not seek"); }
        }); return strip;
    }
    @Test public void swipeNavigatesMediaWithoutSeekingAndDoesNotWrapAtEdges() {
        FragmentActivity host=host(); AtomicReference<VideoItem> chosen=new AtomicReference<>();
        GalleryMediaStrip strip=strip(host,chosen);
        ExoPlayer player=mock(ExoPlayer.class);
        VideoPlayerActivity activity=Robolectric.buildActivity(VideoPlayerActivity.class).get();
        ReflectionHelpers.setField(activity,"galleryMediaStrip",strip);
        ReflectionHelpers.setField(activity,"player",player);
        ReflectionHelpers.setField(activity,"scaleGestureDetector",new ScaleGestureDetector(host,new ScaleGestureDetector.SimpleOnScaleGestureListener()));
        ReflectionHelpers.setField(activity,"gestureDetector",new GestureDetector(host,new GestureDetector.SimpleOnGestureListener()));
        long now=android.os.SystemClock.uptimeMillis();
        for (int i=0;i<3;i++) {
            MotionEvent event=MotionEvent.obtain(now,now+i*100,i==0?MotionEvent.ACTION_DOWN:i==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,100+i*200,100,0);
            assertTrue(activity.handleGalleryTouch(event)); event.recycle();
        }
        assertEquals(item("second").uri,chosen.get().uri);
        verify(player,never()).seekTo(anyLong());
        assertTrue(strip.selectMedia(item("second").uri));
        assertFalse(strip.navigateMedia(1));
        assertTrue(strip.navigateMedia(-1));
        assertEquals(item("first").uri,chosen.get().uri); strip.close();
    }
    @Test public void selectingAnotherMediaClearsExpandedDurationAndPosition() {
        GalleryMediaStrip strip=strip(host(),new AtomicReference<>());
        strip.setExpanded(true,2100000,80000);
        assertTrue(strip.selectMedia(item("second").uri));
        assertFalse(strip.isExpanded());
        assertEquals(0L,(long)ReflectionHelpers.getField(strip,"positionMs"));
        assertEquals(0L,(long)ReflectionHelpers.getField(strip,"durationMs"));
        assertFalse(strip.selectMedia(item("second").uri)); strip.close();
    }
    @Test public void dragUsesSyncFramesThenCommitsExactAndRestoresPlaybackIntent() {
        FragmentActivity host=host(); Slider slider=new Slider(host); slider.setValueTo(10000); slider.setValue(4000);
        VideoPlayerActivity activity=Robolectric.buildActivity(VideoPlayerActivity.class).get();
        ExoPlayer player=mock(ExoPlayer.class); when(player.getDuration()).thenReturn(10000L); when(player.getPlayWhenReady()).thenReturn(true);
        ReflectionHelpers.setField(activity,"player",player); ReflectionHelpers.setField(activity,"galleryPreview",true);
        Slider.OnSliderTouchListener touch=ReflectionHelpers.getField(activity,"timelineTouchListener");
        touch.onStartTrackingTouch(slider); verify(player).pause(); verify(player).setSeekParameters(SeekParameters.CLOSEST_SYNC);
        touch.onStopTrackingTouch(slider);
        org.mockito.InOrder order=inOrder(player);
        order.verify(player).setSeekParameters(SeekParameters.CLOSEST_SYNC);
        order.verify(player).setSeekParameters(SeekParameters.EXACT); order.verify(player).seekTo(4000L);
        order.verify(player).setPlayWhenReady(true);
    }
}
