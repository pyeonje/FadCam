package com.fadcam.ui;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.Manifest;
import android.app.Application;
import android.content.*;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;
import com.fadcam.*;
import com.fadcam.services.PlaybackService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class,shadows=GalleryPreviewLifecycleTest.FrameworkPause.class)
public class GalleryPreviewLifecycleTest {
 // Isolate the product pause policy from an unattached FragmentManager; not a lifecycle integration claim.
 @org.robolectric.annotation.Implements(androidx.fragment.app.FragmentActivity.class)
 public static class FrameworkPause extends org.robolectric.shadows.ShadowActivity {
  @org.robolectric.annotation.Implementation protected void onPause() {}
  @org.robolectric.annotation.Implementation protected void onDestroy() {}
 }
 @Before public void setup(){ReflectionHelpers.setStaticField(SharedPreferencesManager.class,"instance",null);RuntimeEnvironment.getApplication().getSharedPreferences(Constants.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit();Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);}
 private void pause(boolean gallery) { pause(gallery,true); }
 private void pause(boolean gallery,boolean currentlyPlaying) {
  VideoPlayerActivity a=Robolectric.buildActivity(VideoPlayerActivity.class).get();a.setTheme(R.style.Base_Theme_FadCam);
  a.setIntent(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("content://qa/video")));
  SharedPreferencesManager.getInstance(a).setBackgroundPlaybackEnabled(true);
  ExoPlayer p=mock(ExoPlayer.class);when(p.isPlaying()).thenReturn(currentlyPlaying);when(p.getPlayWhenReady()).thenReturn(true);when(p.getPlaybackParameters()).thenReturn(new PlaybackParameters(1f));
  ReflectionHelpers.setField(a,"player",p);ReflectionHelpers.setField(a,"galleryPreview",gallery);
  androidx.media3.ui.PlayerView view=mock(androidx.media3.ui.PlayerView.class);when(view.getPlayer()).thenReturn(p);
  ReflectionHelpers.setField(a,"playerView",view);ReflectionHelpers.callInstanceMethod(a,"attachSharedPlayerView",ReflectionHelpers.ClassParameter.from(ExoPlayer.class,p));
  ReflectionHelpers.callInstanceMethod(a,"onPause");
  Intent service=Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
  if(gallery)assertNull("Gallery must not asynchronously stop/reset the shared service player",service);
  else {assertNotNull(service);assertEquals(PlaybackService.ACTION_START,service.getAction());}
  if(gallery)verify(p).pause();else verify(p,never()).pause();
  ReflectionHelpers.callInstanceMethod(a,"onDestroy");
  if(gallery)verify(p,times(1)).pause();else verify(p,never()).pause();
  assertNull("Late destruction must not send STOP after a handoff",Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
  assertTrue("Standalone background preference remains saved",SharedPreferencesManager.getInstance(a).isBackgroundPlaybackEnabled());
 }
 @Test public void galleryPreviewStopsWhenPagingToPhotoEvenIfStandaloneBackgroundIsEnabled(){pause(true);}
 @Test public void bufferingGalleryPreviewAlsoClearsPlayIntent(){pause(true,false);}
 @Test public void standalonePlayerRetainsExplicitBackgroundPlayback(){pause(false);}
}
