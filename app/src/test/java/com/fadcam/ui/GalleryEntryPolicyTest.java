package com.fadcam.ui;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import androidx.fragment.app.FragmentActivity;
import com.fadcam.MainActivity;
import com.fadcam.SharedPreferencesManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Exercises the actual grid/album click callback and packaged orientation policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class GalleryEntryPolicyTest {
    private Intent openGridItem(VideoItem item) {
        FragmentActivity host=Robolectric.buildActivity(FragmentActivity.class).get();
        RecordsFragment grid=spy(new RecordsFragment());
        doReturn(host).when(grid).getActivity();doReturn(host).when(grid).getContext();
        doNothing().when(grid).startActivity(any(Intent.class));
        SharedPreferencesManager settings=mock(SharedPreferencesManager.class);
        ReflectionHelpers.setField(grid,"sharedPreferencesManager",settings);
        grid.onVideoClick(item);
        verify(settings).addOpenedVideoUri(item.uri.toString());
        ArgumentCaptor<Intent> target=ArgumentCaptor.forClass(Intent.class);
        verify(grid).startActivity(target.capture());return target.getValue();
    }

    @Test public void galleryGridAndAlbumVideosShareCameraRecentInlineEntryContract() {
        for(VideoItem.Category category:new VideoItem.Category[]{VideoItem.Category.CAMERA,VideoItem.Category.SCREEN,VideoItem.Category.FADITOR}) {
            VideoItem item=new VideoItem(Uri.parse("content://fixture/"+category+"/video"),"selected.mp4",32,1,category,VideoItem.MediaType.VIDEO);
            Intent actual=openGridItem(item);
            Intent recent=RecentMediaLauncher.createIntent(RuntimeEnvironment.getApplication(),item);
            assertEquals(recent.getComponent(),actual.getComponent());assertEquals(item.uri,actual.getData());
            assertEquals("video/*",actual.getType());assertTrue(actual.getBooleanExtra(VideoPlayerActivity.EXTRA_GALLERY_PREVIEW,false));
            assertTrue((actual.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
            assertEquals(category.name(),actual.getStringExtra("item_category"));assertEquals(item.displayName,actual.getStringExtra("item_display_name"));
            assertTrue(actual.getBooleanExtra("gallery_return_to_grid",false));
        }
    }

    @Test public void galleryImagesKeepExactItemAndImageViewerReadGrant() {
        VideoItem item=new VideoItem(Uri.parse("content://fixture/image"),"selected.jpg",32,1,VideoItem.Category.SHOT,VideoItem.MediaType.IMAGE);
        Intent target=openGridItem(item);
        assertEquals(ImageViewerActivity.class.getName(),target.getComponent().getClassName());
        assertEquals(item.uri,target.getData());assertEquals("image/*",target.getType());
        assertTrue((target.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
        assertEquals("SHOT",target.getStringExtra("item_category"));assertEquals(item.displayName,target.getStringExtra("item_display_name"));
    }

    @Test public void nullItemDoesNotLaunchAnyViewer() {
        RecordsFragment grid=spy(new RecordsFragment());grid.onVideoClick(null);
        verify(grid,never()).startActivity(any(Intent.class));
    }

    @Test public void cameraGalleryAndBothViewersDeclareFullSensorWithSystemRotationOff() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication();
        String rotation=android.provider.Settings.System.getString(context.getContentResolver(),android.provider.Settings.System.ACCELEROMETER_ROTATION);
        try {
            android.provider.Settings.System.putInt(context.getContentResolver(),android.provider.Settings.System.ACCELEROMETER_ROTATION,0);
            for(Class<?> activity:new Class<?>[]{MainActivity.class,VideoPlayerActivity.class,ImageViewerActivity.class}) {
                ActivityInfo info=context.getPackageManager().getActivityInfo(new ComponentName(context,activity),0);
                assertEquals(activity.getSimpleName(),ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,info.screenOrientation);
                assertTrue((info.configChanges&ActivityInfo.CONFIG_ORIENTATION)!=0);
            }
            assertEquals(0,android.provider.Settings.System.getInt(context.getContentResolver(),android.provider.Settings.System.ACCELEROMETER_ROTATION,-1));
        } finally {
            android.provider.Settings.System.putString(context.getContentResolver(),android.provider.Settings.System.ACCELEROMETER_ROTATION,rotation);
        }
    }
}
