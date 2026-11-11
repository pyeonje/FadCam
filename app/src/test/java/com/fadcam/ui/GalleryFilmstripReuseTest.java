package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.ImageView;
import androidx.fragment.app.FragmentActivity;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.signature.ObjectKey;
import com.fadcam.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class GalleryFilmstripReuseTest {
    private final Uri first=Uri.parse("content://test/first-video");
    private final Uri second=Uri.parse("content://test/second-video");
    private RequestManager requests;
    private RequestBuilder<Bitmap> builder;
    @SuppressWarnings("unchecked")
    private GalleryVideoFilmstrip strip() {
        FragmentActivity activity=Robolectric.buildActivity(FragmentActivity.class).setup().get();
        activity.setTheme(R.style.Base_Theme_FadCam);
        GalleryVideoFilmstrip strip=new GalleryVideoFilmstrip(activity,null);
        requests=mock(RequestManager.class);
        builder=(RequestBuilder<Bitmap>)mock(RequestBuilder.class,RETURNS_SELF);
        when(requests.asBitmap()).thenReturn(builder);
        ReflectionHelpers.setField(strip,"requests",requests);
        return strip;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private RequestListener<Bitmap> latestListener() {
        ArgumentCaptor<RequestListener<Bitmap>> captured=(ArgumentCaptor)ArgumentCaptor.forClass(RequestListener.class);
        verify(builder,atLeastOnce()).listener(captured.capture());
        return captured.getValue();
    }
    private void ready(RequestListener<Bitmap> listener) {
        assertFalse(listener.onResourceReady(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888),
                first,null,DataSource.MEMORY_CACHE,true));
    }
    @Test public void collapseExpansionAndTemporaryDetachReusePreparedEightRequests() {
        GalleryVideoFilmstrip strip=strip();
        strip.showVideo(first,60_000);
        ready(latestListener()); assertTrue(strip.hasAnyFrame());
        ReflectionHelpers.callInstanceMethod(strip,"onDetachedFromWindow");
        strip.showVideo(first,60_000);
        assertTrue(strip.hasAnyFrame());
        verify(requests,times(8)).asBitmap();
        verify(builder,times(8)).skipMemoryCache(false);
        verify(builder,times(8)).diskCacheStrategy(DiskCacheStrategy.NONE);
        verify(builder,times(8)).dontAnimate();
        strip.clearFrames(); assertFalse(strip.hasAnyFrame());
    }
    @Test public void uriAndDurationChangesInvalidateOldCallbacksAndCacheIdentity() {
        GalleryVideoFilmstrip strip=strip(); Runnable callback=mock(Runnable.class);
        strip.setOnFramesReadyListener(callback); strip.showVideo(first,60_000);
        RequestListener<Bitmap> old=latestListener();
        strip.showVideo(second,60_000);
        assertTrue(old.onResourceReady(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888),
                first,null,DataSource.MEMORY_CACHE,true));
        assertFalse(strip.hasAnyFrame()); verifyNoInteractions(callback);
        RequestListener<Bitmap> current=latestListener(); ready(current); ready(current);
        assertTrue(strip.hasAnyFrame()); verify(callback,times(1)).run();
        strip.showVideo(second,120_000); assertFalse(strip.hasAnyFrame());
        verify(builder,times(16)).signature(new ObjectKey(60_000L));
        verify(builder,times(8)).signature(new ObjectKey(120_000L));
        strip.clearFrames();
        assertTrue(current.onResourceReady(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888),
                second,null,DataSource.MEMORY_CACHE,true));
    }
    @Test public void unavailableNewMediaCannotRetainPreviousVideoFramesAndClearIsRepeatable() {
        GalleryVideoFilmstrip strip=strip(); strip.showVideo(first,60_000); ready(latestListener());
        strip.showVideo(first,0); assertTrue(strip.hasAnyFrame());
        strip.showVideo(second,0); assertFalse(strip.hasAnyFrame());
        for(int i=0;i<strip.getChildCount();i++) assertNull(((ImageView)strip.getChildAt(i)).getDrawable());
        strip.showVideo(first,60_000); ready(latestListener());
        strip.showVideo(Uri.parse("https://invalid/remote"),60_000);
        assertFalse(strip.hasAnyFrame()); strip.clearFrames(); strip.clearFrames();
    }
}
