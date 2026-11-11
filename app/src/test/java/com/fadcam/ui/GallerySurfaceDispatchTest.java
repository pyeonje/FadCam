package com.fadcam.ui;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import android.app.Application;
import android.view.*;
import android.net.Uri;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.fadcam.R;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,application=Application.class)
public class GallerySurfaceDispatchTest {
 @Test public void actualInflatedSurfaceRoutesBothDirectionsToMediaRatherThanSeek() {
  VideoPlayerActivity a=Robolectric.buildActivity(VideoPlayerActivity.class).get();a.setTheme(R.style.Base_Theme_FadCam);
  View root=LayoutInflater.from(a).inflate(R.layout.activity_gallery_video_viewer,null,false);a.setContentView(root);
  root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,2400);
  PlayerView pv=root.findViewById(R.id.player_view);pv.showController();
  GalleryMediaStrip strip=root.findViewById(R.id.gallery_media_strip);AtomicReference<VideoItem> chosen=new AtomicReference<>();
  VideoItem first=new VideoItem(Uri.parse("file:///first.mp4"),"first",1,1),middle=new VideoItem(Uri.parse("file:///middle.mp4"),"middle",1,1),last=new VideoItem(Uri.parse("file:///last.mp4"),"last",1,1);
  strip.bindMedia(Arrays.asList(first,middle,last),middle.uri,new GalleryMediaStrip.Listener(){public void onCurrentVideoTap(){} public void onMediaSelected(VideoItem v){chosen.set(v);} public void onFrameSeekStarted(){fail("surface must not scrub");}public void onFrameSeek(long x,boolean done){fail("surface must not seek");}});
  ExoPlayer player=mock(ExoPlayer.class);
  ReflectionHelpers.setField(a,"galleryPreview",true);ReflectionHelpers.setField(a,"playerView",pv);ReflectionHelpers.setField(a,"galleryMediaStrip",strip);ReflectionHelpers.setField(a,"player",player);
  ReflectionHelpers.setField(a,"scaleGestureDetector",new ScaleGestureDetector(a,new ScaleGestureDetector.SimpleOnScaleGestureListener()));ReflectionHelpers.setField(a,"gestureDetector",new GestureDetector(a,new GestureDetector.SimpleOnGestureListener()));
  ReflectionHelpers.callInstanceMethod(a,"setupPressAndHoldFor2x");
  drag(root,300,750);assertNotNull("Real view dispatch must navigate right",chosen.get());assertEquals(last.uri,chosen.get().uri);
  chosen.set(null);drag(root,750,300);assertNotNull("Real view dispatch must navigate left",chosen.get());assertEquals(first.uri,chosen.get().uri);verify(player,never()).seekTo(anyLong());strip.close();
 }
 @Test public void actualPhotoSurfacePagesBothWaysButZoomedDragsKeepPanning() {
  ImageViewerActivity a=Robolectric.buildActivity(ImageViewerActivity.class).get();a.setTheme(R.style.Base_Theme_FadCam);
  VideoItem left=new VideoItem(Uri.parse("file:///left.mp4"),"left",1,1),middle=new VideoItem(Uri.parse("file:///middle.jpg"),"middle",1,1),right=new VideoItem(Uri.parse("file:///right.mp4"),"right",1,1);
  a.setIntent(ImageViewerActivity.createGalleryIntent(a,middle.uri));
  View root=LayoutInflater.from(a).inflate(R.layout.activity_image_viewer,null,false);a.setContentView(root);
  ZoomableImageView image=root.findViewById(R.id.imageViewerImage);
  image.setImageDrawable(new android.graphics.drawable.BitmapDrawable(a.getResources(),android.graphics.Bitmap.createBitmap(640,360,android.graphics.Bitmap.Config.ARGB_8888)));
  ReflectionHelpers.setField(a,"imageView",image);
  ReflectionHelpers.callInstanceMethod(a,"setupGalleryMediaNavigation",ReflectionHelpers.ClassParameter.from(Uri.class,middle.uri));
  GalleryMediaStrip strip=root.findViewById(R.id.gallery_media_strip);GalleryMediaStrip.Listener listener=ReflectionHelpers.getField(strip,"listener");
  strip.bindMedia(Arrays.asList(left,middle,right),middle.uri,listener);
  root.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));root.layout(0,0,1080,2400);
  drag(root,300,750);android.content.Intent chosen=Shadows.shadowOf(a).getNextStartedActivity();assertNotNull(chosen);assertEquals(right.uri,chosen.getData());assertTrue(chosen.getBooleanExtra(VideoPlayerActivity.EXTRA_GALLERY_PREVIEW,false));
  drag(root,750,300);chosen=Shadows.shadowOf(a).getNextStartedActivity();assertNotNull(chosen);assertEquals(left.uri,chosen.getData());
  image.restoreViewport(new float[]{2f,.5f,.5f});drag(root,300,750);assertNull("Zoomed drag must pan, not open a neighbour",Shadows.shadowOf(a).getNextStartedActivity());
  image.restoreViewport(new float[]{1f,.5f,.5f});cancelledDrag(root);assertNull("Cancelled gesture must not navigate",Shadows.shadowOf(a).getNextStartedActivity());strip.close();
 }
 private void cancelledDrag(View root){long now=android.os.SystemClock.uptimeMillis();for(int n=0;n<3;n++){MotionEvent e=MotionEvent.obtain(now,now+n*40,n==0?0:n==1?2:3,300+n*225,1000,0);root.dispatchTouchEvent(e);e.recycle();}}
 private void drag(View root,float from,float to){long now=android.os.SystemClock.uptimeMillis();for(int n=0;n<3;n++){MotionEvent e=MotionEvent.obtain(now,now+n*40,n==0?0:n==1?2:1,from+(to-from)*n/2,1000,0);root.dispatchTouchEvent(e);e.recycle();}}
}
