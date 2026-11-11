package com.fadcam.ui;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.exoplayer.ExoPlayer;
import com.fadcam.R;
import com.fadcam.Utils;
import com.google.android.material.slider.Slider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,application=Application.class)
public class GalleryExternalAppsTest {
    private final Uri uri=Uri.parse("content://owned.fixture/clip");
    private Context context(PackageManager pm) {Context context=mock(Context.class);when(context.getPackageManager()).thenReturn(pm);return context;}
    private ResolveInfo handler(boolean exported) {ResolveInfo info=new ResolveInfo();info.activityInfo=new ActivityInfo();info.activityInfo.exported=exported;info.activityInfo.enabled=true;return info;}
    @Test public void preferredSamsungEditorUsesExportedStandardContractAndScopedReadWriteGrant() {
        PackageManager pm=mock(PackageManager.class);
        when(pm.resolveActivity(argThat(i->i!=null&&"com.sec.android.app.vepreload".equals(i.getPackage())),eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(handler(true));
        Intent edit=GalleryExternalApps.intent(context(pm),uri,true,true);
        assertEquals("com.sec.android.app.vepreload",edit.getPackage());assertNull(edit.getComponent());
        assertEquals(Intent.ACTION_EDIT,edit.getAction());assertEquals(uri,edit.getData());assertEquals(uri,edit.getClipData().getItemAt(0).getUri());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION,edit.getFlags());
    }
    @Test public void playerIsReadOnlyAndAbsentSamsungDoesNotOpenInternalFaditor() {
        PackageManager pm=mock(PackageManager.class);Context context=context(pm);
        Intent player=GalleryExternalApps.intent(context,uri,false,true);
        assertEquals(Intent.ACTION_VIEW,player.getAction());assertEquals("video/*",player.getType());assertNull(player.getPackage());assertNull(player.getComponent());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,player.getFlags());
        Intent edit=GalleryExternalApps.intent(context,uri,true,true);assertEquals(Intent.ACTION_EDIT,edit.getAction());assertNull(edit.getComponent());
    }
    @Test public void targetPhoneSamsungStudioMainFilterWorksWithoutPrivateComponent() {
        PackageManager pm=mock(PackageManager.class);
        when(pm.resolveActivity(argThat(i->i!=null&&Intent.ACTION_MAIN.equals(i.getAction())&&"com.sec.android.app.vepreload".equals(i.getPackage())),eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(handler(true));
        Intent intent=GalleryExternalApps.intent(context(pm),uri,true,true);
        assertEquals(Intent.ACTION_MAIN,intent.getAction());assertEquals("com.sec.android.app.vepreload",intent.getPackage());
        assertEquals("video/*",intent.getType());assertEquals(uri,intent.getData());assertNull(intent.getComponent());
    }
    @Test public void privateOrSignatureProtectedSamsungActivitiesAreNeverTargeted() {
        PackageManager pm=mock(PackageManager.class);Context context=context(pm);
        ResolveInfo info=handler(false);when(pm.resolveActivity(any(),eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(info);
        assertNull(GalleryExternalApps.intent(context,uri,true,true).getPackage());
        info.activityInfo.exported=true;info.activityInfo.permission="signature.only";
        when(context.checkSelfPermission("signature.only")).thenReturn(PackageManager.PERMISSION_DENIED);
        assertNull(GalleryExternalApps.intent(context,uri,true,true).getPackage());
    }
    @Test public void galleryTimelineHasNoEndpointVibrationWhileStandaloneKeepsExistingPolicy() {
        FragmentActivity host=Robolectric.buildActivity(FragmentActivity.class).setup().get();host.setTheme(R.style.Base_Theme_FadCam);
        Slider slider=new Slider(host);slider.setValueTo(53000);
        VideoPlayerActivity activity=Robolectric.buildActivity(VideoPlayerActivity.class).get();ExoPlayer player=mock(ExoPlayer.class);when(player.getDuration()).thenReturn(53000L);
        ReflectionHelpers.setField(activity,"player",player);ReflectionHelpers.setField(activity,"galleryPreview",true);
        Slider.OnChangeListener change=ReflectionHelpers.getField(activity,"timelineChangeListener");
        try(MockedStatic<Utils> utils=mockStatic(Utils.class)) {
            change.onValueChange(slider,0,true);change.onValueChange(slider,53000,true);utils.verifyNoInteractions();
            ReflectionHelpers.setField(activity,"galleryPreview",false);change.onValueChange(slider,0,true);utils.verify(()->Utils.vibrateSliderTick(activity));
        }
    }
}
